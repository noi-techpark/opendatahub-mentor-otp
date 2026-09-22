package tests;

// The station consolidation. It runs before the EPIP conversion, and it moves a merged-away copy's
// Levels along with its Quays.
//
// Innsbruck Hbf is the fixture: two StopPlaces 28 m apart, `at:47:1187` and
// `ch:2:StopPlace:8101187`. The Austrian copy carries only a Verbund-local id and the Swiss copy
// carries the station's true Austrian UIC, so the survivor is elected by voting the members' real
// UIC codes rather than by looking at the survivor's own id: a foreign feed references a station by
// its true UIC even when the owning feed's own copy has only a local id.

import noi.netex.model.Level;
import noi.netex.model.PassengerStopAssignment;
import noi.netex.model.Quay;
import noi.netex.model.StopPlace;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.Runner;
import toolkit.test.TestStore;
import noi.netex.model.AllVehicleModesOfTransportEnumeration;
import noi.netex.model.Line;
import transformers.xb.XbLines;
import transformers.xb.XbScan;
import transformers.xb.XbStations;
import transformers.xb.XbStations.Consolidation;

import jakarta.xml.bind.JAXBElement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class TestXbStations {

    public static void main(String[] args) {
        Runner.run(TestXbStations.class);
    }

    // at:47:1187 and ch:2:StopPlace:8101187 are 28 m apart and are one station. at:47:9999 is a bus
    // stop 20 m from them, published by a third feed, and must NOT merge into either: "a bus stop and
    // a train station can sit 20 m apart and share a code; merging them corrupts both".
    //
    // The rail journey reaches `rail` only through its LineRef, which is the arm the eligibility
    // rule depends on: an unresolved mode does not count as rail.
    //
    // The Swiss copy carries two quays: one on a Level it also publishes, and one whose LevelRef
    // names a Level nobody defines. After the merge the first must still resolve against the
    // SURVIVOR, and the second must be gone.
    static final String NETEX = """
<SiteFrame id="site" version="1">
  <stopPlaces>
    <StopPlace id="at:47:1187" version="1"><Name>Innsbruck Hbf</Name>
      <Centroid><Location><Latitude>47.263000</Latitude><Longitude>11.401000</Longitude></Location></Centroid>
      <quays><Quay id="at:47:1187:1" version="1"><Centroid><Location><Latitude>47.263000</Latitude><Longitude>11.401000</Longitude></Location></Centroid></Quay></quays>
    </StopPlace>
    <StopPlace id="ch:2:StopPlace:8101187" version="1"><Name>Innsbruck Hbf</Name>
      <PrivateCode>8101187</PrivateCode>
      <Centroid><Location><Latitude>47.263250</Latitude><Longitude>11.401000</Longitude></Location></Centroid>
      <levels><Level id="L:ch:1" version="1"><Name>Platform level</Name></Level></levels>
      <quays>
        <Quay id="ch:2:Quay:1" version="1"><LevelRef ref="L:ch:1" version="1"/><Centroid><Location><Latitude>47.263250</Latitude><Longitude>11.401000</Longitude></Location></Centroid></Quay>
        <Quay id="ch:2:Quay:2" version="1"><LevelRef ref="L:nobody:1" version="1"/><Centroid><Location><Latitude>47.263260</Latitude><Longitude>11.401010</Longitude></Location></Centroid></Quay>
      </quays>
    </StopPlace>
    <StopPlace id="at:obb:StopPlace:ch-23016-20302" version="1"><Name>Buchs SG</Name>
      <PrivateCode>23016</PrivateCode>
      <Centroid><Location><Latitude>47.165000</Latitude><Longitude>9.475000</Longitude></Location></Centroid>
      <quays><Quay id="at:obb:Quay:1" version="1"><Centroid><Location><Latitude>47.165000</Latitude><Longitude>9.475000</Longitude></Location></Centroid></Quay></quays>
    </StopPlace>
    <StopPlace id="ch:2:StopPlace:8509404" version="1"><Name>Buchs SG</Name>
      <PrivateCode>8509404</PrivateCode>
      <Centroid><Location><Latitude>47.165200</Latitude><Longitude>9.475000</Longitude></Location></Centroid>
      <quays><Quay id="ch:2:Quay:9" version="1"><Centroid><Location><Latitude>47.165200</Latitude><Longitude>9.475000</Longitude></Location></Centroid></Quay></quays>
    </StopPlace>
    <StopPlace id="at:47:9999" version="1"><Name>Bahnhofsvorplatz</Name>
      <Centroid><Location><Latitude>47.263100</Latitude><Longitude>11.401100</Longitude></Location></Centroid>
      <quays><Quay id="at:47:9999:1" version="1"><Centroid><Location><Latitude>47.263100</Latitude><Longitude>11.401100</Longitude></Location></Centroid></Quay></quays>
    </StopPlace>
    <StopPlace id="ch:2:StopPlace:8501360" version="1"><Name>Montreux-Les Planches</Name>
      <Centroid><Location><Latitude>46.430000</Latitude><Longitude>6.920000</Longitude></Location></Centroid>
      <quays><Quay id="ch:2:Quay:60" version="1"><Centroid><Location><Latitude>46.430000</Latitude><Longitude>6.920000</Longitude></Location></Centroid></Quay></quays>
    </StopPlace>
    <StopPlace id="ch:2:StopPlace:8501361" version="1"><Name>Toveyre</Name>
      <Centroid><Location><Latitude>46.433000</Latitude><Longitude>6.920000</Longitude></Location></Centroid>
      <quays><Quay id="ch:2:Quay:61" version="1"><Centroid><Location><Latitude>46.433000</Latitude><Longitude>6.920000</Longitude></Location></Centroid></Quay></quays>
    </StopPlace>
    <StopPlace id="ch:2:StopPlace:8501362" version="1"><Name>Valmont</Name>
      <Centroid><Location><Latitude>46.436000</Latitude><Longitude>6.920000</Longitude></Location></Centroid>
      <quays><Quay id="ch:2:Quay:62" version="1"><Centroid><Location><Latitude>46.436000</Latitude><Longitude>6.920000</Longitude></Location></Centroid></Quay></quays>
    </StopPlace>
    <StopPlace id="ch:2:StopPlace:9000001" version="1"><Name>Chain A</Name>
      <Centroid><Location><Latitude>45.000000</Latitude><Longitude>9.000000</Longitude></Location></Centroid>
    </StopPlace>
    <StopPlace id="at:47:9000002" version="1"><Name>Chain B</Name>
      <Centroid><Location><Latitude>45.003500</Latitude><Longitude>9.000000</Longitude></Location></Centroid>
    </StopPlace>
    <StopPlace id="it:apb:StopPlace:9000003" version="1"><Name>Chain C</Name>
      <Centroid><Location><Latitude>45.007000</Latitude><Longitude>9.000000</Longitude></Location></Centroid>
    </StopPlace>
    <StopPlace id="IT:ITC4:StopPlace:9000004" version="1"><Name>Chain D</Name>
      <Centroid><Location><Latitude>45.010500</Latitude><Longitude>9.000000</Longitude></Location></Centroid>
    </StopPlace>
  </stopPlaces>
</SiteFrame>
<ServiceFrame id="sf" version="1">
  <lines>
    <Line id="L:rail" version="1"><Name>Arlbergbahn</Name><TransportMode>rail</TransportMode></Line>
  </lines>
  <stopAssignments>
    <PassengerStopAssignment id="psa:at" version="1" order="1"><ScheduledStopPointRef ref="ssp:at"/><StopPlaceRef ref="at:47:1187"/><QuayRef ref="at:47:1187:1"/></PassengerStopAssignment>
    <PassengerStopAssignment id="psa:ch" version="1" order="2"><ScheduledStopPointRef ref="ssp:ch"/><StopPlaceRef ref="ch:2:StopPlace:8101187"/><QuayRef ref="ch:2:Quay:1"/></PassengerStopAssignment>
    <PassengerStopAssignment id="psa:buchs:at" version="1" order="4"><ScheduledStopPointRef ref="ssp:buchs:at"/><StopPlaceRef ref="at:obb:StopPlace:ch-23016-20302"/></PassengerStopAssignment>
    <PassengerStopAssignment id="psa:buchs:ch" version="1" order="5"><ScheduledStopPointRef ref="ssp:buchs:ch"/><StopPlaceRef ref="ch:2:StopPlace:8509404"/></PassengerStopAssignment>
    <PassengerStopAssignment id="psa:bus" version="1" order="3"><ScheduledStopPointRef ref="ssp:bus"/><StopPlaceRef ref="at:47:9999"/></PassengerStopAssignment>
    <PassengerStopAssignment id="psa:rack:60" version="1" order="6"><ScheduledStopPointRef ref="ssp:rack:60"/><StopPlaceRef ref="ch:2:StopPlace:8501360"/></PassengerStopAssignment>
    <PassengerStopAssignment id="psa:rack:61" version="1" order="7"><ScheduledStopPointRef ref="ssp:rack:61"/><StopPlaceRef ref="ch:2:StopPlace:8501361"/></PassengerStopAssignment>
    <PassengerStopAssignment id="psa:rack:62" version="1" order="8"><ScheduledStopPointRef ref="ssp:rack:62"/><StopPlaceRef ref="ch:2:StopPlace:8501362"/></PassengerStopAssignment>
    <PassengerStopAssignment id="psa:chain:a" version="1" order="9"><ScheduledStopPointRef ref="ssp:chain:a"/><StopPlaceRef ref="ch:2:StopPlace:9000001"/></PassengerStopAssignment>
    <PassengerStopAssignment id="psa:chain:b" version="1" order="10"><ScheduledStopPointRef ref="ssp:chain:b"/><StopPlaceRef ref="at:47:9000002"/></PassengerStopAssignment>
    <PassengerStopAssignment id="psa:chain:c" version="1" order="11"><ScheduledStopPointRef ref="ssp:chain:c"/><StopPlaceRef ref="it:apb:StopPlace:9000003"/></PassengerStopAssignment>
    <PassengerStopAssignment id="psa:chain:d" version="1" order="12"><ScheduledStopPointRef ref="ssp:chain:d"/><StopPlaceRef ref="IT:ITC4:StopPlace:9000004"/></PassengerStopAssignment>
  </stopAssignments>
  <journeyPatterns>
    <ServiceJourneyPattern id="Pat:rail" version="1"><pointsInSequence>
      <StopPointInJourneyPattern id="rp1" version="1" order="1"><ScheduledStopPointRef ref="ssp:at" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="rp2" version="1" order="2"><ScheduledStopPointRef ref="ssp:ch" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="rp3" version="1" order="3"><ScheduledStopPointRef ref="ssp:buchs:at" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="rp4" version="1" order="4"><ScheduledStopPointRef ref="ssp:buchs:ch" version="1"/></StopPointInJourneyPattern>
    </pointsInSequence></ServiceJourneyPattern>
    <ServiceJourneyPattern id="Pat:bus" version="1"><pointsInSequence>
      <StopPointInJourneyPattern id="bp1" version="1" order="1"><ScheduledStopPointRef ref="ssp:bus" version="1"/></StopPointInJourneyPattern>
    </pointsInSequence></ServiceJourneyPattern>
    <ServiceJourneyPattern id="Pat:rack" version="1"><pointsInSequence>
      <StopPointInJourneyPattern id="kp1" version="1" order="1"><ScheduledStopPointRef ref="ssp:rack:60" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="kp2" version="1" order="2"><ScheduledStopPointRef ref="ssp:rack:61" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="kp3" version="1" order="3"><ScheduledStopPointRef ref="ssp:rack:62" version="1"/></StopPointInJourneyPattern>
    </pointsInSequence></ServiceJourneyPattern>
    <ServiceJourneyPattern id="Pat:chain" version="1"><pointsInSequence>
      <StopPointInJourneyPattern id="np1" version="1" order="1"><ScheduledStopPointRef ref="ssp:chain:a" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="np2" version="1" order="2"><ScheduledStopPointRef ref="ssp:chain:b" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="np3" version="1" order="3"><ScheduledStopPointRef ref="ssp:chain:c" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="np4" version="1" order="4"><ScheduledStopPointRef ref="ssp:chain:d" version="1"/></StopPointInJourneyPattern>
    </pointsInSequence></ServiceJourneyPattern>
  </journeyPatterns>
</ServiceFrame>
<TimetableFrame id="tf" version="1">
  <vehicleJourneys>
    <ServiceJourney id="at:obb:Sj:1" version="1"><LineRef ref="L:rail" version="1"/><ServiceJourneyPatternRef ref="Pat:rail" version="1"/>
      <passingTimes><TimetabledPassingTime id="t1" version="1"><StopPointInJourneyPatternRef ref="rp1" version="1" order="1"/><DepartureTime>08:00:00</DepartureTime></TimetabledPassingTime></passingTimes>
    </ServiceJourney>
    <ServiceJourney id="at:47:Sj:bus" version="1"><TransportMode>bus</TransportMode><ServiceJourneyPatternRef ref="Pat:bus" version="1"/>
      <passingTimes><TimetabledPassingTime id="t2" version="1"><StopPointInJourneyPatternRef ref="bp1" version="1" order="1"/><DepartureTime>08:00:00</DepartureTime></TimetabledPassingTime></passingTimes>
    </ServiceJourney>
    <ServiceJourney id="ch:1:Sj:rack" version="1"><TransportMode>rail</TransportMode><ServiceJourneyPatternRef ref="Pat:rack" version="1"/>
      <passingTimes><TimetabledPassingTime id="t3" version="1"><StopPointInJourneyPatternRef ref="kp1" version="1" order="1"/><DepartureTime>08:00:00</DepartureTime></TimetabledPassingTime></passingTimes>
    </ServiceJourney>
    <ServiceJourney id="ch:1:Sj:chain" version="1"><TransportMode>rail</TransportMode><ServiceJourneyPatternRef ref="Pat:chain" version="1"/>
      <passingTimes><TimetabledPassingTime id="t4" version="1"><StopPointInJourneyPatternRef ref="np1" version="1" order="1"/><DepartureTime>08:00:00</DepartureTime></TimetabledPassingTime></passingTimes>
    </ServiceJourney>
  </vehicleJourneys>
</TimetableFrame>
""";

    /// The country the station physically sits in decides which copy survives, and it is voted from
    /// the members' REAL UIC codes. Here only the Swiss-published copy carries one — 8101187,
    /// Austria — so the vote says AT and the AUSTRIAN copy survives, on a code it does not itself
    /// carry.
    public static void testTheOwningCountrysCopySurvives(TestStore db) throws Exception {
        Consolidation c = consolidate(db);

        Check.equals(2L, c.counters().clusters, "two physical stations, each published twice");
        Check.equals(2L, c.counters().mergedAway, "one copy of each is merged away");
        Check.that(c.mergedAway().contains("ch:2:StopPlace:8101187"),
                "and it is the Swiss copy, because the vote its own code cast says Austria");
        Check.that(ids(c.survivors()).contains("at:47:1187"), "the Austrian copy survives");
    }

    /// At Buchs SG the owning feed's copy (`ch:2:...`) sorts after the foreign one (`at:obb:...`),
    /// so the election cannot go by id order. Only the Swiss copy carries a code the UIC table
    /// recognises; the ÖBB copy's PrivateCode 23016 canonicalises to a non-country.
    public static void testTheVoteBeatsIdOrder(TestStore db) throws Exception {
        Consolidation c = consolidate(db);

        Check.that(ids(c.survivors()).contains("ch:2:StopPlace:8509404"),
                "the Swiss copy survives, though its id sorts second");
        Check.that(c.mergedAway().contains("at:obb:StopPlace:ch-23016-20302"),
                "and the OeBB copy is merged away");
    }

    /// Only rail-served stops take part. The bus stop is 20 m away and must be untouched.
    public static void testABusStopTwentyMetresAwayIsNotMerged(TestStore db) throws Exception {
        Consolidation c = consolidate(db);
        Check.that(ids(c.survivors()).contains("at:47:9999"),
                "a bus stop inside the radius still survives -- merging it would corrupt both");
        Check.that(!c.mergedAway().contains("at:47:9999"), "and it is not merged away");
    }

    /// The quays move onto the survivor, and every assignment follows.
    public static void testQuaysAndAssignmentsFollowTheSurvivor(TestStore db) throws Exception {
        Consolidation c = consolidate(db);

        StopPlace survivor = byId(c.survivors(), "at:47:1187");
        Check.equals(3, quays(survivor).size(), "its own quay plus the two it inherited");
        Check.equals(3L, c.counters().quaysMoved, "two onto Innsbruck, one onto Buchs");
        Check.equals(2L, c.counters().assignmentsRepointed,
                "and the assignment on each merged-away copy was re-pointed");
        for (PassengerStopAssignment psa : c.psas()) {
            String ref = XbStationsRefs.stopPlaceRef(psa);
            Check.that(!"ch:2:StopPlace:8101187".equals(ref),
                    "no assignment still names the merged-away stop: " + psa.getId());
        }
    }

    /// EPIP phase C2 clears every LevelRef whose target is not a Level the quay's own Site defines.
    /// A quay moved onto a survivor that does not define its Level is exactly that orphan, which is
    /// why this stage runs before the EPIP conversion and moves the Levels with the Quays: the
    /// survivor ends up defining the Level its new quay names.
    public static void testLevelsMoveWithTheirQuays(TestStore db) throws Exception {
        Consolidation c = consolidate(db);

        StopPlace survivor = byId(c.survivors(), "at:47:1187");
        Check.equals(1L, c.counters().levelsMoved, "the Swiss copy's Level moved with its quays");
        List<String> levelIds = new ArrayList<>();
        if (survivor.getLevels() != null) {
            for (Object o : survivor.getLevels().getLevelRefOrLevel()) {
                Object v = o instanceof JAXBElement<?> el ? el.getValue() : o;
                if (v instanceof Level level) levelIds.add(level.getId());
            }
        }
        Check.equals(List.of("L:ch:1"), levelIds, "the survivor now defines it");
        Quay onLevel = quayById(survivor, "ch:2:Quay:1");
        Check.that(onLevel.getLevelRef() != null,
                "so the moved quay's LevelRef still resolves and is KEPT");
        Check.equals("L:ch:1", onLevel.getLevelRef().getRef(), "against the same Level");
    }

    /// A LevelRef that resolved against nothing before the merge still resolves against nothing
    /// after it, and EPIP's conformance rule wants it gone. LevelRef is minOccurs="0", so absence is
    /// conformant.
    public static void testAnUnresolvableLevelRefIsCleared(TestStore db) throws Exception {
        Consolidation c = consolidate(db);

        StopPlace survivor = byId(c.survivors(), "at:47:1187");
        Check.equals(1L, c.counters().levelRefsCleared, "one orphan cleared");
        Check.isNull(quayById(survivor, "ch:2:Quay:2").getLevelRef(),
                "the quay whose Level nobody defines loses its ref");
    }

    /// Two ids in the same namespace never merge, however close they sit.
    ///
    /// Montreux-Les Planches, Toveyre and Valmont are consecutive halts of the Rochers-de-Naye rack
    /// railway, 332 m apart and so inside the 442 m radius. Their ends are 663 m apart, which is
    /// inside the diameter cap, so the namespace rule is the only one that separates them.
    public static void testAFeedsOwnAdjacentHaltsAreNotFused(TestStore db) throws Exception {
        Consolidation c = consolidate(db);

        for (String id : List.of("ch:2:StopPlace:8501360", "ch:2:StopPlace:8501361",
                "ch:2:StopPlace:8501362")) {
            Check.that(!c.mergedAway().contains(id), id + " survives as its own station");
            Check.that(ids(c.survivors()).contains(id), id + " is still in the export");
        }
        Check.that(c.counters().unionsRefusedSameNamespace >= 4,
                "and the guard counted the refusals: " + c.counters().unionsRefusedSameNamespace);
    }

    /// A chain of four stops from four different publishers, each link 387 m, so every link unions
    /// and the namespace rule has nothing to say. End to end they are 1,161 m apart, and the
    /// cluster is dropped whole.
    public static void testAChainWiderThanTheCapIsDroppedWhole(TestStore db) throws Exception {
        Consolidation c = consolidate(db);

        for (String id : List.of("ch:2:StopPlace:9000001", "at:47:9000002",
                "it:apb:StopPlace:9000003", "IT:ITC4:StopPlace:9000004")) {
            Check.that(!c.mergedAway().contains(id), id + " is not merged away");
        }
        Check.equals(1L, c.counters().clustersRefusedDiameter, "exactly one cluster was too wide");
        Check.equals(4L, c.counters().refusedDiameterMembers, "and it had four members");
    }

    /// The rail scan folded into pass 1 gives the SAME answer as the standalone one.
    ///
    /// There are two implementations on purpose: the consolidation stage runs before any pass 1 and
    /// must walk the journeys itself, while the coupling has already read every one of them and
    /// hands in what it saw ([XbScan.RailSeen]) — worth 194.3 s of a 468.1 s couple phase. Two
    /// implementations of one question drift, so this is the pin that says they have not.
    public static void testTheFoldedRailScanAgreesWithTheStandaloneOne(TestStore db)
            throws Exception {
        db.loadNetex(NETEX);
        try (Txn txn = db.store.roTxn()) {
            List<PassengerStopAssignment> psas = new ArrayList<>();
            for (Object o : db.store.iterOnlyObjects(txn, PassengerStopAssignment.class)) {
                psas.add((PassengerStopAssignment) o);
            }
            Set<String> standalone = XbStations.railServed(db.store, txn, psas);

            XbLines.LineMaps<AllVehicleModesOfTransportEnumeration> modes =
                    XbLines.lineValueMaps(db.store, txn, Line::getTransportMode);
            XbScan.Pass1 p1 = XbScan.pass1(db.store, txn, Map.of(), modes);
            Set<String> folded = XbStations.railServed(db.store, txn, psas, p1.rail());

            Check.equals(new TreeSet<>(standalone), new TreeSet<>(folded),
                    "the folded rail scan and the standalone one name the same StopPlaces");
            Check.that(!standalone.isEmpty(), "and the fixture actually has rail-served stops, "
                    + "or this would pass on two empty sets");
        }
    }

    // ------------------------------------------------------------------------------- //

    static Consolidation consolidate(TestStore db) throws Exception {
        db.loadNetex(NETEX);
        try (Txn txn = db.store.roTxn()) {
            List<StopPlace> stopplaces = new ArrayList<>();
            for (Object o : db.store.iterOnlyObjects(txn, StopPlace.class)) {
                stopplaces.add((StopPlace) o);
            }
            List<PassengerStopAssignment> psas = new ArrayList<>();
            for (Object o : db.store.iterOnlyObjects(txn, PassengerStopAssignment.class)) {
                psas.add((PassengerStopAssignment) o);
            }
            stopplaces.sort(Comparator.comparing(StopPlace::getId));
            psas.sort(Comparator.comparing(PassengerStopAssignment::getId));
            Set<String> rail = XbStations.railServed(db.store, txn, psas);
            Check.that(rail.contains("at:47:1187") && rail.contains("ch:2:StopPlace:8101187")
                            && rail.contains("at:obb:StopPlace:ch-23016-20302")
                            && rail.contains("ch:2:StopPlace:8509404"),
                    "all four rail copies are rail-served: " + rail);
            Check.that(!rail.contains("at:47:9999"), "and the bus stop is not");
            return XbStations.consolidate(stopplaces, psas, rail);
        }
    }

    static List<String> ids(List<StopPlace> sps) {
        List<String> out = new ArrayList<>();
        for (StopPlace sp : sps) out.add(sp.getId());
        return out;
    }

    static StopPlace byId(List<StopPlace> sps, String id) {
        for (StopPlace sp : sps) {
            if (sp.getId().equals(id)) return sp;
        }
        throw new AssertionError("no StopPlace " + id + " in " + ids(sps));
    }

    static List<Quay> quays(StopPlace sp) {
        List<Quay> out = new ArrayList<>();
        if (sp.getQuays() != null) {
            for (JAXBElement<?> el : sp.getQuays().getQuayRefOrQuay()) {
                if (el.getValue() instanceof Quay q) out.add(q);
            }
        }
        return out;
    }

    static Quay quayById(StopPlace sp, String id) {
        for (Quay q : quays(sp)) {
            if (id.equals(q.getId())) return q;
        }
        throw new AssertionError("no Quay " + id + " on " + sp.getId());
    }

    /// The assignment accessor, reached through a tiny local shim because the one in the transformer
    /// package is package-private and this test is not in it.
    static final class XbStationsRefs {
        static String stopPlaceRef(PassengerStopAssignment psa) {
            JAXBElement<? extends noi.netex.model.StopPlaceRefStructure> el = psa.getStopPlaceRef();
            return el != null ? el.getValue().getRef() : null;
        }
    }
}
