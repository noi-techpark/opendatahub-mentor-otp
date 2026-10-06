package tests;

// Italo's lexically ordered pointsInSequence and passingTimes, and the shapes that must be left as
// the feed shipped them.
//
// Every "left alone" assertion is on the STORED BYTES, not on the bound object: the pass is a clone
// plus an overlay of only what it changed, so an untouched object must come out of the target with
// the bytes the clone copied. A pass that re-emitted everything would still satisfy an assertion on
// the bean and fail these.
//
// Each refusal fixture is deliberately OUT of document order, so unchanged bytes can only mean the
// refusal fired -- never that the object was already in sequence.

import fix.SortItaloSequences;
import noi.netex.model.EntityStructure;
import noi.netex.model.PointInLinkSequence_VersionedChildStructure;
import noi.netex.model.ServiceJourney;
import noi.netex.model.ServiceJourneyPattern;
import noi.netex.model.TimetabledPassingTime;
import toolkit.store.ObjectRow;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.Runner;
import toolkit.test.TestStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TestSortItaloSequences {

    public static void main(String[] args) {
        Runner.run(TestSortItaloSequences.class);
    }

    // Pat:long      -- the defect: 11 points emitted `:1, :10, :11, :2 … :9`, `order` 1..11 correct.
    // Pat:short     -- 3 points already in sequence.
    // Pat:noorder   -- out of order, and one point carries no `order`.
    // Pat:duporder  -- out of order, and two points share `order` 2.
    // Pat:links     -- out of order, and carries a linksInSequence.
    //
    // Sj:long       -- 11 passing times in the pattern's own lexical shuffle, times 06:00..16:00.
    // Sj:short      -- already in sequence.
    // Sj:stray      -- on Pat:long, but one passing time names a point the pattern does not hold.
    // Sj:nopattern  -- names a pattern that is not in the feed.
    // Sj:noorder    -- on Pat:noorder, which refused, so this refuses with it.
    static final String NETEX = """
<ServiceFrame id="sf" version="1">
  <journeyPatterns>
    <ServiceJourneyPattern id="Pat:long" version="1">
      <pointsInSequence>
      <StopPointInJourneyPattern id="lp1" version="1" order="1"><ScheduledStopPointRef ref="ssp1" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="lp10" version="1" order="10"><ScheduledStopPointRef ref="ssp2" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="lp11" version="1" order="11"><ScheduledStopPointRef ref="ssp3" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="lp2" version="1" order="2"><ScheduledStopPointRef ref="ssp1" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="lp3" version="1" order="3"><ScheduledStopPointRef ref="ssp2" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="lp4" version="1" order="4"><ScheduledStopPointRef ref="ssp3" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="lp5" version="1" order="5"><ScheduledStopPointRef ref="ssp1" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="lp6" version="1" order="6"><ScheduledStopPointRef ref="ssp2" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="lp7" version="1" order="7"><ScheduledStopPointRef ref="ssp3" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="lp8" version="1" order="8"><ScheduledStopPointRef ref="ssp1" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="lp9" version="1" order="9"><ScheduledStopPointRef ref="ssp2" version="1"/></StopPointInJourneyPattern>
      </pointsInSequence>
    </ServiceJourneyPattern>
    <ServiceJourneyPattern id="Pat:short" version="1">
      <pointsInSequence>
      <StopPointInJourneyPattern id="sp1" version="1" order="1"><ScheduledStopPointRef ref="ssp1" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="sp2" version="1" order="2"><ScheduledStopPointRef ref="ssp2" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="sp3" version="1" order="3"><ScheduledStopPointRef ref="ssp3" version="1"/></StopPointInJourneyPattern>
      </pointsInSequence>
    </ServiceJourneyPattern>
    <ServiceJourneyPattern id="Pat:noorder" version="1">
      <pointsInSequence>
      <StopPointInJourneyPattern id="np2" version="1" order="2"><ScheduledStopPointRef ref="ssp2" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="np1" version="1" order="1"><ScheduledStopPointRef ref="ssp1" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="np3" version="1"><ScheduledStopPointRef ref="ssp3" version="1"/></StopPointInJourneyPattern>
      </pointsInSequence>
    </ServiceJourneyPattern>
    <ServiceJourneyPattern id="Pat:duporder" version="1">
      <pointsInSequence>
      <StopPointInJourneyPattern id="dp2" version="1" order="2"><ScheduledStopPointRef ref="ssp2" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="dp1" version="1" order="1"><ScheduledStopPointRef ref="ssp1" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="dp3" version="1" order="2"><ScheduledStopPointRef ref="ssp3" version="1"/></StopPointInJourneyPattern>
      </pointsInSequence>
    </ServiceJourneyPattern>
    <ServiceJourneyPattern id="Pat:links" version="1">
      <pointsInSequence>
      <StopPointInJourneyPattern id="kp2" version="1" order="2"><ScheduledStopPointRef ref="ssp2" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="kp1" version="1" order="1"><ScheduledStopPointRef ref="ssp1" version="1"/></StopPointInJourneyPattern>
      </pointsInSequence>
      <linksInSequence>
      <ServiceLinkInJourneyPattern id="kl1" version="1" order="1"><ServiceLinkRef ref="sl1" version="1"/></ServiceLinkInJourneyPattern>
      </linksInSequence>
    </ServiceJourneyPattern>
  </journeyPatterns>
  <serviceLinks>
    <ServiceLink id="sl1" version="1"><FromPointRef ref="ssp1" version="1"/><ToPointRef ref="ssp2" version="1"/></ServiceLink>
  </serviceLinks>
</ServiceFrame>
<TimetableFrame id="tf" version="1">
  <vehicleJourneys>
    <ServiceJourney id="Sj:long" version="1">
      <ServiceJourneyPatternRef ref="Pat:long" version="1"/>
      <passingTimes>
      <TimetabledPassingTime id="lt1" version="1"><StopPointInJourneyPatternRef ref="lp1" version="1"/><DepartureTime>06:00:00</DepartureTime></TimetabledPassingTime>
      <TimetabledPassingTime id="lt10" version="1"><StopPointInJourneyPatternRef ref="lp10" version="1"/><DepartureTime>15:00:00</DepartureTime></TimetabledPassingTime>
      <TimetabledPassingTime id="lt11" version="1"><StopPointInJourneyPatternRef ref="lp11" version="1"/><ArrivalTime>16:00:00</ArrivalTime></TimetabledPassingTime>
      <TimetabledPassingTime id="lt2" version="1"><StopPointInJourneyPatternRef ref="lp2" version="1"/><DepartureTime>07:00:00</DepartureTime></TimetabledPassingTime>
      <TimetabledPassingTime id="lt3" version="1"><StopPointInJourneyPatternRef ref="lp3" version="1"/><DepartureTime>08:00:00</DepartureTime></TimetabledPassingTime>
      <TimetabledPassingTime id="lt4" version="1"><StopPointInJourneyPatternRef ref="lp4" version="1"/><DepartureTime>09:00:00</DepartureTime></TimetabledPassingTime>
      <TimetabledPassingTime id="lt5" version="1"><StopPointInJourneyPatternRef ref="lp5" version="1"/><DepartureTime>10:00:00</DepartureTime></TimetabledPassingTime>
      <TimetabledPassingTime id="lt6" version="1"><StopPointInJourneyPatternRef ref="lp6" version="1"/><DepartureTime>11:00:00</DepartureTime></TimetabledPassingTime>
      <TimetabledPassingTime id="lt7" version="1"><StopPointInJourneyPatternRef ref="lp7" version="1"/><DepartureTime>12:00:00</DepartureTime></TimetabledPassingTime>
      <TimetabledPassingTime id="lt8" version="1"><StopPointInJourneyPatternRef ref="lp8" version="1"/><DepartureTime>13:00:00</DepartureTime></TimetabledPassingTime>
      <TimetabledPassingTime id="lt9" version="1"><StopPointInJourneyPatternRef ref="lp9" version="1"/><DepartureTime>14:00:00</DepartureTime></TimetabledPassingTime>
      </passingTimes>
    </ServiceJourney>
    <ServiceJourney id="Sj:short" version="1">
      <ServiceJourneyPatternRef ref="Pat:short" version="1"/>
      <passingTimes>
      <TimetabledPassingTime id="st1" version="1"><StopPointInJourneyPatternRef ref="sp1" version="1"/><DepartureTime>06:00:00</DepartureTime></TimetabledPassingTime>
      <TimetabledPassingTime id="st2" version="1"><StopPointInJourneyPatternRef ref="sp2" version="1"/><DepartureTime>07:00:00</DepartureTime></TimetabledPassingTime>
      <TimetabledPassingTime id="st3" version="1"><StopPointInJourneyPatternRef ref="sp3" version="1"/><ArrivalTime>08:00:00</ArrivalTime></TimetabledPassingTime>
      </passingTimes>
    </ServiceJourney>
    <ServiceJourney id="Sj:stray" version="1">
      <ServiceJourneyPatternRef ref="Pat:long" version="1"/>
      <passingTimes>
      <TimetabledPassingTime id="xt2" version="1"><StopPointInJourneyPatternRef ref="lp2" version="1"/><DepartureTime>07:00:00</DepartureTime></TimetabledPassingTime>
      <TimetabledPassingTime id="xt1" version="1"><StopPointInJourneyPatternRef ref="lp1" version="1"/><DepartureTime>06:00:00</DepartureTime></TimetabledPassingTime>
      <TimetabledPassingTime id="xt9" version="1"><StopPointInJourneyPatternRef ref="lpX" version="1"/><ArrivalTime>09:00:00</ArrivalTime></TimetabledPassingTime>
      </passingTimes>
    </ServiceJourney>
    <ServiceJourney id="Sj:nopattern" version="1">
      <ServiceJourneyPatternRef ref="Pat:missing" version="1"/>
      <passingTimes>
      <TimetabledPassingTime id="mt2" version="1"><StopPointInJourneyPatternRef ref="lp2" version="1"/><DepartureTime>07:00:00</DepartureTime></TimetabledPassingTime>
      <TimetabledPassingTime id="mt1" version="1"><StopPointInJourneyPatternRef ref="lp1" version="1"/><DepartureTime>06:00:00</DepartureTime></TimetabledPassingTime>
      </passingTimes>
    </ServiceJourney>
    <ServiceJourney id="Sj:noorder" version="1">
      <ServiceJourneyPatternRef ref="Pat:noorder" version="1"/>
      <passingTimes>
      <TimetabledPassingTime id="nt2" version="1"><StopPointInJourneyPatternRef ref="np2" version="1"/><DepartureTime>07:00:00</DepartureTime></TimetabledPassingTime>
      <TimetabledPassingTime id="nt1" version="1"><StopPointInJourneyPatternRef ref="np1" version="1"/><DepartureTime>06:00:00</DepartureTime></TimetabledPassingTime>
      </passingTimes>
    </ServiceJourney>
  </vehicleJourneys>
</TimetableFrame>
""";

    /// The repair on the pattern side, asserted on the surviving point ids and their orders.
    public static void testPatternPointsGoIntoOrderSequence(TestStore db) throws Exception {
        Fixed f = fix(db);

        Check.equals(List.of("lp1", "lp2", "lp3", "lp4", "lp5", "lp6", "lp7", "lp8", "lp9",
                        "lp10", "lp11"),
                pointIds(f.pattern("Pat:long")), "eleven points, in `order` sequence");
        Check.equals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
                pointOrders(f.pattern("Pat:long")), "and their orders ascend");
    }

    /// The repair on the journey side, and the property the whole pass buys: a consumer walking the
    /// list positionally now walks the journey forwards, so the LAST element is the terminus.
    public static void testPassingTimesFollowTheirPatternPoints(TestStore db) throws Exception {
        Fixed f = fix(db);

        Check.equals(List.of("lp1", "lp2", "lp3", "lp4", "lp5", "lp6", "lp7", "lp8", "lp9",
                        "lp10", "lp11"),
                passingPointRefs(f.journey("Sj:long")), "passing times follow the pattern's points");
        List<String> times = passingTimes(f.journey("Sj:long"));
        for (int i = 1; i < times.size(); i++) {
            Check.that(times.get(i - 1).compareTo(times.get(i)) <= 0,
                    "time " + i + " does not go backwards: " + times);
        }
        Check.equals("16:00:00", times.get(times.size() - 1), "the terminus is last");
    }

    /// The choice this pass is built on: an object already in sequence keeps the bytes the clone
    /// copied. Swap the clone for a re-emit-everything pass and this goes red.
    public static void testAlreadySequencedRowsKeepTheirClonedBytes(TestStore db) throws Exception {
        Fixed f = fix(db);

        f.assertVerbatim(ServiceJourneyPattern.class, "Pat:short");
        f.assertVerbatim(ServiceJourney.class, "Sj:short");
    }

    /// `order` is the only signal that carries the sequence, so a pattern missing one cannot be
    /// sequenced -- and neither can any journey on it.
    public static void testPatternWithoutOrderIsLeftAsShipped(TestStore db) throws Exception {
        Fixed f = fix(db);

        f.assertVerbatim(ServiceJourneyPattern.class, "Pat:noorder");
        f.assertVerbatim(ServiceJourney.class, "Sj:noorder");
    }

    /// Two points at one `order` do not say what the sequence is.
    public static void testDuplicateOrderIsLeftAsShipped(TestStore db) throws Exception {
        fix(db).assertVerbatim(ServiceJourneyPattern.class, "Pat:duporder");
    }

    /// A link joins two adjacent points, so permuting the points alone would leave the pattern's two
    /// views of itself disagreeing. Measured empty on Italo; here so it stays that way.
    public static void testLinksInSequenceDeclinesThePattern(TestStore db) throws Exception {
        fix(db).assertVerbatim(ServiceJourneyPattern.class, "Pat:links");
    }

    /// The two decisions are independent: one unjoinable journey does not cost its pattern the sort.
    public static void testStrayPassingTimeDeclinesOnlyItsJourney(TestStore db) throws Exception {
        Fixed f = fix(db);

        f.assertVerbatim(ServiceJourney.class, "Sj:stray");
        Check.equals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
                pointOrders(f.pattern("Pat:long")), "its pattern was still sequenced");
    }

    /// A dangling ServiceJourneyPatternRef has no orders to join against.
    public static void testJourneyWithNoPatternIsLeftAsShipped(TestStore db) throws Exception {
        fix(db).assertVerbatim(ServiceJourney.class, "Sj:nopattern");
    }

    // ------------------------------------------------------------------------------- //

    /// The source and the transformed target, both open, so a test can compare stored bytes.
    record Fixed(TestStore src, TestStore dst) {

        ServiceJourneyPattern pattern(String id) {
            return (ServiceJourneyPattern) object(ServiceJourneyPattern.class, id);
        }

        ServiceJourney journey(String id) {
            return (ServiceJourney) object(ServiceJourney.class, id);
        }

        Object object(Class<?> clazz, String id) {
            try (Txn txn = dst.store.roTxn()) {
                for (Object o : dst.store.iterOnlyObjects(txn, clazz)) {
                    if (o instanceof EntityStructure e && id.equals(e.getId())) return o;
                }
            }
            Check.that(false, "no " + clazz.getSimpleName() + " " + id + " in the target");
            return null;
        }

        /// The target's stored blob for `id` is the source's, byte for byte.
        void assertVerbatim(Class<?> clazz, String id) {
            byte[] before = raw(src.store, clazz, id);
            byte[] after = raw(dst.store, clazz, id);
            Check.that(before != null && after != null,
                    clazz.getSimpleName() + " " + id + " is in both stores");
            Check.that(Arrays.equals(before, after),
                    clazz.getSimpleName() + " " + id + " was re-marshalled, not cloned");
        }

        private static byte[] raw(Store store, Class<?> clazz, String id) {
            try (Txn txn = store.roTxn()) {
                for (ObjectRow r : store.iterObjects(txn, clazz)) {
                    if (r.object() instanceof EntityStructure e && id.equals(e.getId())) {
                        return store.loadRawObject(txn, clazz, r.localKey());
                    }
                }
            }
            return null;
        }
    }

    static Fixed fix(TestStore db) throws Exception {
        db.loadNetex(NETEX);
        return new Fixed(db, db.runDbToDb(SortItaloSequences::apply));
    }

    static List<PointInLinkSequence_VersionedChildStructure> points(ServiceJourneyPattern p) {
        var seq = p.getPointsInSequence();
        return seq == null ? new ArrayList<>()
                : seq.getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern();
    }

    static List<String> pointIds(ServiceJourneyPattern p) {
        List<String> out = new ArrayList<>();
        for (var pt : points(p)) out.add(pt.getId());
        return out;
    }

    static List<Integer> pointOrders(ServiceJourneyPattern p) {
        List<Integer> out = new ArrayList<>();
        for (var pt : points(p)) out.add(pt.getOrder() == null ? null : pt.getOrder().intValue());
        return out;
    }

    static List<TimetabledPassingTime> passings(ServiceJourney sj) {
        return sj.getPassingTimes() == null ? new ArrayList<>()
                : sj.getPassingTimes().getTimetabledPassingTime();
    }

    static List<String> passingPointRefs(ServiceJourney sj) {
        List<String> out = new ArrayList<>();
        for (TimetabledPassingTime t : passings(sj)) {
            var ref = t.getPointInJourneyPatternRef();
            out.add(ref == null || ref.getValue() == null ? null : ref.getValue().getRef());
        }
        return out;
    }

    /// Departure where the feed gave one, arrival at the terminus — the two shapes the fixture uses.
    static List<String> passingTimes(ServiceJourney sj) {
        List<String> out = new ArrayList<>();
        for (TimetabledPassingTime t : passings(sj)) {
            out.add(String.valueOf(t.getDepartureTime() != null
                    ? t.getDepartureTime() : t.getArrivalTime()));
        }
        return out;
    }
}
