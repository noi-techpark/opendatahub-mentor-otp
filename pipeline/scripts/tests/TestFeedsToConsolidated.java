package tests;

// The zero-gate for conv/FeedsToConsolidatedDbToDb.java, the N-feed consolidate-and-split driver.
// The fixture is split across two feeds the way production runs it:
//   * feed 1 (AT/ÖBB): the cross-border journey (AT block + CH border stops), its pattern,
//     calendar and TrainNumber — plus two Lines ("dup" and "a1");
//   * feed 2 (CH): the authoritative Swiss leg, its availability condition and TrainNumber —
//     plus two Lines ("dup" — same (id, version) as feed 1's — and "b1").
// The tests drive the public phase function FeedsToConsolidatedDbToDb.consolidate(...) directly and
// assert:
//   * each shard carries its own feed's rows and nobody else's;
//   * the journeys are left whole and keep their own TrainNumber — truncation, TrainNumber
//     reconciliation and the orphan sweep are the cross-border stage's, after EPIP;
//   * no ServiceJourneyInterchange is created here;
//   * an empty feed still consumes its target slot, so the source-to-target pairing holds.
//
// The `dup` Line collides across the two feeds on purpose and each shard keeps its own copy: the
// cross-feed keep-first dedup is conv/MergeDbToDb's, once the converted shards come back together.
// Runner runs every store test on both formats.

import conv.FeedsToConsolidatedDbToDb;
import noi.netex.model.Line;
import noi.netex.model.ServiceJourney;
import noi.netex.model.ServiceJourneyInterchange;
import noi.netex.model.TrainNumber;
import toolkit.store.Store;
import toolkit.store.Stores;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.Runner;
import toolkit.test.TestStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import noi.netex.text.Mls;

public class TestFeedsToConsolidated {

    public static void main(String[] args) {
        Runner.run(TestFeedsToConsolidated.class);
    }

    // Feed 1 — the ÖBB feed: at:obb:Sj:1 spans AT (810000020/810000010) + CH (850000300/850000400),
    // train 166, runs 2026-01-05. The stops are UIC-coded, so the shared-stop match resolves
    // directly. The Lines are the merge chain's probes: "dup" collides with feed 2, "a1" is
    // feed-1-only.
    static final String FEED_AT = """
<ServiceFrame id="sf1" version="1">
  <lines>
    <Line id="dup" version="1"><Name>first</Name></Line>
    <Line id="a1" version="1"><Name>A1</Name></Line>
  </lines>
  <journeyPatterns>
    <ServiceJourneyPattern id="at:obb:Pat:1" version="1"><pointsInSequence>
      <StopPointInJourneyPattern id="ip0" version="1" order="1"><ScheduledStopPointRef ref="810000020" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="ip1" version="1" order="2"><ScheduledStopPointRef ref="810000010" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="ip2" version="1" order="2"><ScheduledStopPointRef ref="850000300" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="ip3" version="1" order="3"><ScheduledStopPointRef ref="850000400" version="1"/></StopPointInJourneyPattern>
    </pointsInSequence></ServiceJourneyPattern>
  </journeyPatterns>
</ServiceFrame>
<ResourceFrame id="rf1" version="1">
  <trainNumbers>
    <TrainNumber id="at:Tn:166" version="1"><ForAdvertisement>166</ForAdvertisement></TrainNumber>
  </trainNumbers>
</ResourceFrame>
<ServiceCalendarFrame id="scf1" version="1">
  <dayTypes><DayType id="dt1" version="1"/></dayTypes>
  <operatingPeriods><UicOperatingPeriod id="op1" version="1"><FromDate>2026-01-05T00:00:00</FromDate><ToDate>2026-01-06T00:00:00</ToDate><ValidDayBits>1</ValidDayBits></UicOperatingPeriod></operatingPeriods>
  <dayTypeAssignments><DayTypeAssignment id="dta1" version="1" order="1"><OperatingPeriodRef ref="op1" version="1"/><DayTypeRef ref="dt1" version="1"/></DayTypeAssignment></dayTypeAssignments>
</ServiceCalendarFrame>
<TimetableFrame id="tf1" version="1">
  <vehicleJourneys>
    <ServiceJourney id="at:obb:Sj:1" version="1">
      <dayTypes><DayTypeRef ref="dt1" version="1"/></dayTypes>
      <trainNumbers><TrainNumberRef ref="at:Tn:166"/></trainNumbers>
      <ServiceJourneyPatternRef ref="at:obb:Pat:1" version="1"/>
      <passingTimes>
        <TimetabledPassingTime id="pt0" version="1"><StopPointInJourneyPatternRef ref="ip0" version="1" order="1"/><DepartureTime>20:01:00</DepartureTime></TimetabledPassingTime>
        <TimetabledPassingTime id="pt1" version="1"><StopPointInJourneyPatternRef ref="ip1" version="1" order="2"/><DepartureTime>20:11:00</DepartureTime></TimetabledPassingTime>
        <TimetabledPassingTime id="pt2" version="1"><StopPointInJourneyPatternRef ref="ip2" version="1" order="2"/><DepartureTime>20:23:00</DepartureTime></TimetabledPassingTime>
        <TimetabledPassingTime id="pt3" version="1"><StopPointInJourneyPatternRef ref="ip3" version="1" order="3"/><ArrivalTime>21:20:00</ArrivalTime></TimetabledPassingTime>
      </passingTimes>
    </ServiceJourney>
  </vehicleJourneys>
</TimetableFrame>
""";

    // Feed 2 — the Swiss feed: ch:Sj:1 is the full CH leg of the same train 166, same day;
    // its own availability condition and TrainNumber (no cross-feed references — the coupler
    // routes keyed loads to the owning feed). "dup" duplicates feed 1's (id, version).
    static final String FEED_CH = """
<ServiceFrame id="sf2" version="1">
  <lines>
    <Line id="dup" version="1"><Name>second</Name></Line>
    <Line id="b1" version="1"><Name>B1</Name></Line>
  </lines>
  <validityConditions>
    <AvailabilityCondition id="ac1" version="1"><FromDate>2026-01-05T00:00:00</FromDate><ToDate>2026-01-06T00:00:00</ToDate><ValidDayBits>1</ValidDayBits></AvailabilityCondition>
  </validityConditions>
</ServiceFrame>
<ResourceFrame id="rf2" version="1">
  <trainNumbers>
    <TrainNumber id="ch:Tn:166" version="1"><ForAdvertisement>166</ForAdvertisement></TrainNumber>
  </trainNumbers>
</ResourceFrame>
<TimetableFrame id="tf2" version="1">
  <vehicleJourneys>
    <ServiceJourney id="ch:Sj:1" version="1">
      <validityConditions><AvailabilityConditionRef ref="ac1" version="1"/></validityConditions>
      <trainNumbers><TrainNumberRef ref="ch:Tn:166"/></trainNumbers>
      <calls>
        <Call id="cl1" version="1" order="1"><ScheduledStopPointRef ref="850000300" version="1"/><Departure><Time>20:23:00</Time></Departure></Call>
        <Call id="cl2" version="1" order="2"><ScheduledStopPointRef ref="850000400" version="1"/><Arrival><Time>21:20:00</Time></Arrival></Call>
      </calls>
    </ServiceJourney>
  </vehicleJourneys>
</TimetableFrame>
""";

    /// id -> Name for every Line row, asserting one blob per id.
    private static Map<String, String> lineNames(Store db, Txn txn) {
        Map<String, String> names = new TreeMap<>();
        for (Object o : db.iterOnlyObjects(txn, Line.class)) {
            Line line = (Line) o;
            Check.that(!names.containsKey(line.getId()), "duplicate blob for " + line.getId());
            names.put(line.getId(), Mls.text(line.getName()));
        }
        return names;
    }

    /// The assertion block over one shard: it carries its own feed's rows and nobody else's.
    ///
    /// Each shard is its own key space, so the clone offset is 0. The `dup` Line collides across
    /// the two feeds and each shard keeps its own, which is why the two calls below expect
    /// different Names for one id. `passingTimes` is -1 for a fixture journey that carries
    /// `<calls>` rather than `<passingTimes>` — the CH leg does, and normalising the two shapes
    /// happens after this pass.
    private static void assertShard(Path shard, Map<String, String> lines, String journeyId,
            int passingTimes, String trainNumberId) throws Exception {
        try (Store db = Stores.open(shard, true); Txn txn = db.roTxn()) {
            Check.equals(lines, lineNames(db, txn),
                    "the shard carries its own feed's Lines, one blob each");

            // Truncation to the domestic block happens after EPIP, in transformers.xb, and
            // TestXbTiling asserts it.
            Map<String, ServiceJourney> sjs = new LinkedHashMap<>();
            for (Object o : db.iterOnlyObjects(txn, ServiceJourney.class)) {
                ServiceJourney sj = (ServiceJourney) o;
                sjs.put(sj.getId(), sj);
            }
            Check.equals(Set.of(journeyId), sjs.keySet(), "only this feed's journey is in the shard");
            ServiceJourney sj = sjs.get(journeyId);
            if (passingTimes >= 0) {
                Check.equals(passingTimes, sj.getPassingTimes().getTimetabledPassingTime().size(),
                        "the journey is NOT truncated here — the consolidation leaves it whole for "
                        + "the cross-border stage to cut later");
            }
            Check.equals(1, sj.getTrainNumbers().getTrainNumberRef().size(), "one Tn ref");
            Check.equals(trainNumberId, sj.getTrainNumbers().getTrainNumberRef().get(0).getRef(),
                    "the journey keeps its OWN TrainNumber: reconciling the two is the "
                    + "cross-border stage's job, and it has not run yet");

            Set<String> tns = new TreeSet<>();
            for (Object o : db.iterOnlyObjects(txn, TrainNumber.class)) {
                tns.add(((TrainNumber) o).getId());
            }
            Check.equals(Set.of(trainNumberId), tns,
                    "this feed's TrainNumber survives — the orphan sweep moved to the cross-border "
                    + "stage with the re-pointing that creates orphans in the first place");

            int links = 0;
            for (Object ignored : db.iterOnlyObjects(txn, ServiceJourneyInterchange.class)) links++;
            Check.equals(0, links, "the consolidation links nothing — coupling happens after EPIP");
        }
    }

    private static void assertAtShard(Path shard) throws Exception {
        assertShard(shard, Map.of("dup", "first", "a1", "A1"), "at:obb:Sj:1", 4, "at:Tn:166");
    }

    private static void assertChShard(Path shard) throws Exception {
        assertShard(shard, Map.of("dup", "second", "b1", "B1"), "ch:Sj:1", -1, "ch:Tn:166");
    }

    public static void testTwoFeedSplit(TestStore ts) throws Exception {
        Path f1 = ts.buildDbFile(FEED_AT, "at.lmdb");
        Path f2 = ts.buildDbFile(FEED_CH, "ch.lmdb");
        Path t1 = ts.tempPath("at-cons.lmdb");
        Path t2 = ts.tempPath("ch-cons.lmdb");
        FeedsToConsolidatedDbToDb.consolidate(List.of(f1, f2), List.of(t1, t2), ts.format);
        assertAtShard(t1);
        assertChShard(t2);
    }

    /// An empty feed in the middle must not disturb its neighbours: it gets its own empty shard and
    /// the other two come out exactly as they do without it. A source that contributes nothing must
    /// still consume its target slot, or every feed after it writes into the wrong store.
    public static void testEmptyFeedKeepsShardPairing(TestStore ts) throws Exception {
        Path f1 = ts.buildDbFile(FEED_AT, "at.lmdb");
        Path empty = ts.tempPath("empty.lmdb");
        Stores.create(empty, ts.format).close();
        Path f2 = ts.buildDbFile(FEED_CH, "ch.lmdb");
        Path t1 = ts.tempPath("at-cons.lmdb");
        Path tEmpty = ts.tempPath("empty-cons.lmdb");
        Path t2 = ts.tempPath("ch-cons.lmdb");
        FeedsToConsolidatedDbToDb.consolidate(
                List.of(f1, empty, f2), List.of(t1, tEmpty, t2), ts.format);
        assertAtShard(t1);
        assertChShard(t2);
        try (Store db = Stores.open(tEmpty, true); Txn txn = db.roTxn()) {
            int lines = 0;
            for (Object ignored : db.iterOnlyObjects(txn, Line.class)) lines++;
            Check.equals(0, lines, "the empty feed's shard stays empty");
        }
    }
}
