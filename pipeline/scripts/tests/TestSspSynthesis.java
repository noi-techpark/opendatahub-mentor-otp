package tests;

// toolkit.transform.common.ScheduledStopPoints: what SSP synthesis mints -- a StopPlace, a Quay, a
// PSA and its QuayRef.
//
// OTP files a PassengerStopAssignment under the quay index or the stop-place index but never both,
// and a StopPlace holding two quays with no per-PSA QuayRef fails its "exactly one child stop"
// test: it falls back to one synthetic stop at the station centroid and the quays carry no service.

import jakarta.xml.bind.JAXBElement;
import noi.netex.model.PassengerStopAssignment;
import noi.netex.model.Quay;
import noi.netex.model.QuayRefStructure;
import noi.netex.model.ScheduledStopPoint;
import noi.netex.model.StopPlace;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.Runner;
import toolkit.test.TestStore;
import toolkit.transform.common.ScheduledStopPoints;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TestSspSynthesis {

    /// lone1 is unassigned and locatable -> full synthesis. bound1 already has a PSA -> nothing.
    /// owned1's StopPlace is published by the feed -> a PSA, but no Quay of ours.
    static final String NETEX = """
        <SiteFrame id="site" version="1"><stopPlaces>
          <StopPlace id="IT:reg:StopPlace:owned1" version="1"><Name>Owned One</Name>
            <Centroid><Location><Latitude>44.1</Latitude><Longitude>9.1</Longitude></Location></Centroid>
            <quays><Quay id="IT:reg:Quay:feedOwned" version="1"/></quays></StopPlace>
        </stopPlaces></SiteFrame>
        <ServiceFrame id="sf" version="1">
          <scheduledStopPoints>
            <ScheduledStopPoint id="IT:reg:ScheduledStopPoint:lone1" version="1"><Name>Lone One</Name>
              <Location><Latitude>43.1</Latitude><Longitude>11.1</Longitude></Location></ScheduledStopPoint>
            <ScheduledStopPoint id="IT:reg:ScheduledStopPoint:bound1" version="1"><Name>Bound One</Name>
              <Location><Latitude>43.2</Latitude><Longitude>11.2</Longitude></Location></ScheduledStopPoint>
            <ScheduledStopPoint id="IT:reg:ScheduledStopPoint:owned1" version="1"><Name>Owned One</Name>
              <Location><Latitude>44.1</Latitude><Longitude>9.1</Longitude></Location></ScheduledStopPoint>
          </scheduledStopPoints>
          <stopAssignments>
            <PassengerStopAssignment id="IT:reg:PassengerStopAssignment:pre" version="1" order="1">
              <ScheduledStopPointRef ref="IT:reg:ScheduledStopPoint:bound1"/>
              <StopPlaceRef ref="IT:reg:StopPlace:owned1"/></PassengerStopAssignment>
          </stopAssignments>
        </ServiceFrame>
        """;

    public static void main(String[] args) {
        Runner.run(TestSspSynthesis.class);
    }

    private static List<Object> synth(TestStore db) throws Exception {
        db.loadNetex(NETEX);
        try (Txn txn = db.store.roTxn()) {
            return ScheduledStopPoints.synthesizeStopPlacesFromSsps(db.store, txn);
        }
    }

    private static Map<String, StopPlace> stopPlaces(List<Object> out) {
        Map<String, StopPlace> m = new LinkedHashMap<>();
        for (Object o : out) if (o instanceof StopPlace sp) m.put(sp.getId(), sp);
        return m;
    }

    private static Map<String, PassengerStopAssignment> psas(List<Object> out) {
        Map<String, PassengerStopAssignment> m = new LinkedHashMap<>();
        for (Object o : out) if (o instanceof PassengerStopAssignment p) m.put(p.getId(), p);
        return m;
    }

    private static List<Quay> quaysOf(StopPlace sp) {
        List<Quay> out = new ArrayList<>();
        if (sp.getQuays() == null) return out;
        for (JAXBElement<?> el : sp.getQuays().getQuayRefOrQuay()) {
            if (el.getValue() instanceof Quay q) out.add(q);
        }
        return out;
    }

    private static String quayRefOf(PassengerStopAssignment psa) {
        JAXBElement<? extends QuayRefStructure> el = psa.getQuayRef();
        return el == null || el.getValue() == null ? null : el.getValue().getRef();
    }

    /// A locatable, unassigned SSP mints a StopPlace carrying exactly one Quay at the SSP's own
    /// position, and a PSA naming BOTH.
    public static void testMintsQuayAndBothRefs(TestStore db) throws Exception {
        List<Object> out = synth(db);
        StopPlace sp = stopPlaces(out).get("IT:reg:StopPlace:lone1");
        Check.that(sp != null, "a StopPlace is synthesised for the unassigned SSP");

        List<Quay> quays = quaysOf(sp);
        Check.equals(1, quays.size(), "exactly one Quay is minted");
        Quay q = quays.get(0);
        Check.equals("IT:reg:Quay:lone1", q.getId(), "quay id is the SSP id with the class swapped");
        Check.equals("1", q.getVersion(), "quay version follows the SSP's");
        Check.that(q.getCentroid() != null && q.getCentroid().getLocation() != null,
                "quay carries a centroid -- OTP DROPS a coordinate-less quay, undoing the change");
        Check.equals(0, q.getCentroid().getLocation().getLatitude()
                .compareTo(new java.math.BigDecimal("43.1")), "quay sits at the SSP's latitude");
        Check.isNull(q.getPublicCode(), "no PublicCode: it drives OTP platformCode and quay-code gating");

        PassengerStopAssignment psa = psas(out).get("IT:reg:PassengerStopAssignment:lone1");
        Check.that(psa != null, "a PSA is synthesised");
        Check.equals("IT:reg:Quay:lone1", quayRefOf(psa), "the PSA names the minted quay");
        Check.that(psa.getStopPlaceRef() != null && "IT:reg:StopPlace:lone1"
                        .equals(psa.getStopPlaceRef().getValue().getRef()),
                "the PSA still names the StopPlace -- both refs, not one");
    }

    /// The StopPlace centroid and the Quay centroid must not share one LocationStructure: the merge
    /// mutates survivors in place, so a shared instance couples two objects that then diverge.
    public static void testQuayLocationIsDetached(TestStore db) throws Exception {
        StopPlace sp = stopPlaces(synth(db)).get("IT:reg:StopPlace:lone1");
        var spLoc = sp.getCentroid().getLocation();
        var qLoc = quaysOf(sp).get(0).getCentroid().getLocation();
        Check.that(spLoc != qLoc, "quay Location is a copy, not the StopPlace's own instance");
        Check.equals(0, spLoc.getLatitude().compareTo(qLoc.getLatitude()), "but the same position");
    }

    /// An SSP the feed already bound mints nothing at all.
    public static void testAlreadyAssignedSspIsSkipped(TestStore db) throws Exception {
        List<Object> out = synth(db);
        Check.that(!stopPlaces(out).containsKey("IT:reg:StopPlace:bound1"),
                "an SSP already named by a PSA is left alone");
        Check.that(!psas(out).containsKey("IT:reg:PassengerStopAssignment:bound1"),
                "and gets no second PSA");
    }

    /// When the feed publishes the StopPlace itself we add a PSA but no Quay and no QuayRef: we
    /// cannot know the feed's own quay set does not already cover this SSP, and a QuayRef naming a
    /// quay that is not there is discarded by OTP along with the stop times.
    public static void testFeedOwnedStopPlaceGetsNoQuay(TestStore db) throws Exception {
        List<Object> out = synth(db);
        Check.that(!stopPlaces(out).containsKey("IT:reg:StopPlace:owned1"),
                "no second StopPlace for an id the feed already publishes");
        PassengerStopAssignment psa = psas(out).get("IT:reg:PassengerStopAssignment:owned1");
        Check.that(psa != null, "but the SSP still gets bound");
        Check.isNull(quayRefOf(psa), "with no QuayRef, so OTP keeps its centroid fallback");
    }

    /// An SSP stored at two versions is two rows of one id, and every derived id is the same, so
    /// synthesis runs once.
    public static void testTwoVersionSspMintsOnce(TestStore db) throws Exception {
        ScheduledStopPoint v1 = new ScheduledStopPoint();
        v1.setId("IT:reg:ScheduledStopPoint:twice");
        v1.setVersion("1");
        v1.setLocation(new noi.netex.model.LocationStructure()
                .withLatitude(new java.math.BigDecimal("45.0"))
                .withLongitude(new java.math.BigDecimal("9.0")));
        ScheduledStopPoint v2 = new ScheduledStopPoint();
        v2.setId("IT:reg:ScheduledStopPoint:twice");
        v2.setVersion("2");
        v2.setLocation(new noi.netex.model.LocationStructure()
                .withLatitude(new java.math.BigDecimal("45.0"))
                .withLongitude(new java.math.BigDecimal("9.0")));

        List<Object> out = ScheduledStopPoints.synthesizeStopPlaces(
                List.of(v1, v2), Set.of(), Set.of(),
                new ScheduledStopPoints.SspSynthesis(s -> s.getId() != null, s -> null, "[t] %d"));

        Check.equals(1, stopPlaces(out).size(), "one StopPlace for the two rows of one id");
        Check.equals(1, psas(out).size(), "one PSA");
        Check.equals(1, quaysOf(stopPlaces(out).values().iterator().next()).size(), "one Quay");
    }

    /// Build a StopPlace with one embedded Quay, both named `name`, at (lat, lon).
    private static StopPlace stopWithQuay(String spId, String quayId, String name,
                                          String lat, String lon) {
        StopPlace sp = new StopPlace();
        sp.setId(spId);
        sp.setVersion("1");
        sp.setName(new noi.netex.model.MultilingualString().withContent(name));
        sp.setCentroid(new noi.netex.model.SimplePoint_VersionStructure()
                .withLocation(new noi.netex.model.LocationStructure()
                        .withLatitude(new java.math.BigDecimal(lat))
                        .withLongitude(new java.math.BigDecimal(lon))));
        Quay q = new Quay();
        q.setId(quayId);
        q.setVersion("1");
        q.setName(new noi.netex.model.MultilingualString().withContent(name));
        q.setCentroid(new noi.netex.model.SimplePoint_VersionStructure()
                .withLocation(new noi.netex.model.LocationStructure()
                        .withLatitude(new java.math.BigDecimal(lat))
                        .withLongitude(new java.math.BigDecimal(lon))));
        noi.netex.model.Quays_RelStructure rel = new noi.netex.model.Quays_RelStructure();
        rel.getQuayRefOrQuay().add(new noi.netex.model.ObjectFactory().createQuay(q));
        sp.setQuays(rel);
        return sp;
    }

    private static PassengerStopAssignment psaFor(String id, String spId, String quayId) {
        var f = new noi.netex.model.ObjectFactory();
        PassengerStopAssignment psa = new PassengerStopAssignment();
        psa.setId(id);
        psa.setVersion("1");
        psa.setStopPlaceRef(f.createStopPlaceRef(
                new noi.netex.model.StopPlaceRefStructure().withRef(spId)));
        psa.setQuayRef(f.createQuayRef(new QuayRefStructure().withRef(quayId)));
        return psa;
    }

    /// Two stations 5.5 m apart with one name: the StopPlaces merge, and because their quays are at
    /// the same point they are one pole published twice, so the quays fold too and the loser's PSA
    /// QuayRef follows.
    public static void testCoincidentQuaysFoldAndQuayRefsFollow(TestStore db) throws Exception {
        List<StopPlace> sps = List.of(
                stopWithQuay("IT:reg:StopPlace:a", "IT:reg:Quay:a", "Piazza Roma", "45.00000", "9.00000"),
                stopWithQuay("IT:reg:StopPlace:b", "IT:reg:Quay:b", "Piazza Roma", "45.00000", "9.00000"));
        List<PassengerStopAssignment> psas = new ArrayList<>(List.of(
                psaFor("IT:reg:PassengerStopAssignment:a", "IT:reg:StopPlace:a", "IT:reg:Quay:a"),
                psaFor("IT:reg:PassengerStopAssignment:b", "IT:reg:StopPlace:b", "IT:reg:Quay:b")));

        var plan = ScheduledStopPoints.planStopplaceMerge(sps, psas);
        Check.equals(1, plan.kept().size(), "the two stations merge to one");
        StopPlace survivor = plan.kept().values().iterator().next();
        Check.equals(1, quaysOf(survivor).size(),
                "and their coincident quays fold to ONE pole, not two at the same point");
        String keptQuay = quaysOf(survivor).get(0).getId();
        Check.equals("IT:reg:Quay:a", keptQuay, "smallest id wins, so the choice is order-independent");

        for (PassengerStopAssignment psa : psas) {
            Check.equals(keptQuay, quayRefOf(psa),
                    "every QuayRef names the surviving quay -- a dangling one is silently DISCARDed");
            Check.equals(survivor.getId(), psa.getStopPlaceRef().getValue().getRef(),
                    "and every StopPlaceRef the survivor");
        }
    }

    /// The two directions of a street stop are ~20 m apart across the carriageway. The station
    /// radius groups them, but they are different boarding positions and the quay fold must leave
    /// them alone.
    public static void testOppositeKerbQuaysAreNotFolded(TestStore db) throws Exception {
        List<StopPlace> sps = List.of(
                stopWithQuay("IT:reg:StopPlace:n", "IT:reg:Quay:n", "Via Roma", "45.00000", "9.00000"),
                stopWithQuay("IT:reg:StopPlace:s", "IT:reg:Quay:s", "Via Roma", "45.00018", "9.00000"));
        List<PassengerStopAssignment> psas = new ArrayList<>(List.of(
                psaFor("IT:reg:PassengerStopAssignment:n", "IT:reg:StopPlace:n", "IT:reg:Quay:n"),
                psaFor("IT:reg:PassengerStopAssignment:s", "IT:reg:StopPlace:s", "IT:reg:Quay:s")));

        var plan = ScheduledStopPoints.planStopplaceMerge(sps, psas);
        Check.equals(1, plan.kept().size(), "~20 m apart with one name: one station");
        StopPlace survivor = plan.kept().values().iterator().next();
        Check.equals(2, quaysOf(survivor).size(), "but TWO quays -- one per direction");
        Set<String> refs = new java.util.TreeSet<>();
        for (PassengerStopAssignment psa : psas) refs.add(quayRefOf(psa));
        Check.equals(Set.of("IT:reg:Quay:n", "IT:reg:Quay:s"), refs,
                "and each PSA still names its own");
    }

    /// Three coincident quays, with the alphabetically smallest not the one the scan anchors on.
    /// The fold must group first and elect second: a chain (c recording b as its survivor while b
    /// folds onto a) would leave c's PSA naming a quay that is no longer there.
    public static void testThreeCoincidentQuaysAllFoldOntoOneSurvivor(TestStore db) throws Exception {
        List<StopPlace> sps = List.of(
                stopWithQuay("IT:reg:StopPlace:p2", "IT:reg:Quay:c", "Duomo", "45.00000", "9.00000"),
                stopWithQuay("IT:reg:StopPlace:p1", "IT:reg:Quay:b", "Duomo", "45.00000", "9.00000"),
                stopWithQuay("IT:reg:StopPlace:p3", "IT:reg:Quay:a", "Duomo", "45.00000", "9.00000"));
        List<PassengerStopAssignment> psas = new ArrayList<>(List.of(
                psaFor("IT:reg:PassengerStopAssignment:c", "IT:reg:StopPlace:p2", "IT:reg:Quay:c"),
                psaFor("IT:reg:PassengerStopAssignment:b", "IT:reg:StopPlace:p1", "IT:reg:Quay:b"),
                psaFor("IT:reg:PassengerStopAssignment:a", "IT:reg:StopPlace:p3", "IT:reg:Quay:a")));

        var plan = ScheduledStopPoints.planStopplaceMerge(sps, psas);
        StopPlace survivor = plan.kept().values().iterator().next();
        List<Quay> left = quaysOf(survivor);
        Check.equals(1, left.size(), "all three coincident quays fold to one");
        Check.equals("IT:reg:Quay:a", left.get(0).getId(), "the smallest id survives");

        Set<String> present = new java.util.TreeSet<>();
        for (Quay q : left) present.add(q.getId());
        for (PassengerStopAssignment psa : psas) {
            Check.that(present.contains(quayRefOf(psa)),
                    "PSA " + psa.getId() + " names a surviving quay, not a folded one ("
                            + quayRefOf(psa) + ")");
        }
    }

    private static ScheduledStopPoint ssp(String id, String lat, String lon) {
        ScheduledStopPoint s = new ScheduledStopPoint();
        s.setId(id);
        s.setVersion("1");
        s.setLocation(new noi.netex.model.LocationStructure()
                .withLatitude(new java.math.BigDecimal(lat))
                .withLongitude(new java.math.BigDecimal(lon)));
        return s;
    }

    private static StopPlace bareStop(String id, String name) {
        StopPlace sp = new StopPlace();
        sp.setId(id);
        sp.setVersion("1");
        sp.setName(new noi.netex.model.MultilingualString().withContent(name));
        return sp;   // no quays at all -- the population this transform exists for
    }

    private static PassengerStopAssignment bareLink(String id, String sspId, String spId) {
        var f = new noi.netex.model.ObjectFactory();
        PassengerStopAssignment p = new PassengerStopAssignment();
        p.setId(id);
        p.setVersion("1");
        p.setScheduledStopPointRef(f.createScheduledStopPointRef(
                new noi.netex.model.ScheduledStopPointRefStructure().withRef(sspId)));
        p.setStopPlaceRef(f.createStopPlaceRef(
                new noi.netex.model.StopPlaceRefStructure().withRef(spId)));
        return p;   // assigned, but names no quay
    }

    /// A feed that publishes its own StopPlace and its own assignment but no quay at all, so
    /// synthesis skips it: the SSP is already assigned. 41,364 Italian StopPlaces are in this
    /// state.
    public static void testAssignedButQuaylessStopGetsAQuay(TestStore db) throws Exception {
        List<StopPlace> stops = new ArrayList<>(List.of(bareStop("IT:reg:StopPlace:X", "Stazione")));
        List<PassengerStopAssignment> psas = new ArrayList<>(List.of(
                bareLink("IT:reg:PassengerStopAssignment:1", "IT:reg:ScheduledStopPoint:X", "IT:reg:StopPlace:X")));

        ScheduledStopPoints.backfillQuaysForAssignedSsps(stops, psas,
                List.of(ssp("IT:reg:ScheduledStopPoint:X", "45.0", "9.0")),
                ScheduledStopPoints::isRapCodespace);

        List<Quay> quays = quaysOf(stops.get(0));
        Check.equals(1, quays.size(), "the quay-less stop gains exactly one Quay");
        Check.equals("IT:reg:Quay:X", quays.get(0).getId(), "id derived from the SSP, as in synthesis");
        Check.that(quays.get(0).getCentroid() != null, "with the SSP's own position");
        Check.equals("IT:reg:Quay:X", quayRefOf(psas.get(0)), "and the feed's PSA now names it");
    }

    /// Two ScheduledStopPoints assigned to ONE quay-less StopPlace are two boarding positions, so
    /// each gets its own Quay and its own QuayRef -- not one shared between them.
    public static void testTwoAssignedSspsOnOneStopGetAQuayEach(TestStore db) throws Exception {
        List<StopPlace> stops = new ArrayList<>(List.of(bareStop("IT:reg:StopPlace:Y", "Piazza")));
        List<PassengerStopAssignment> psas = new ArrayList<>(List.of(
                bareLink("IT:reg:PassengerStopAssignment:n", "IT:reg:ScheduledStopPoint:Yn", "IT:reg:StopPlace:Y"),
                bareLink("IT:reg:PassengerStopAssignment:s", "IT:reg:ScheduledStopPoint:Ys", "IT:reg:StopPlace:Y")));

        ScheduledStopPoints.backfillQuaysForAssignedSsps(stops, psas,
                List.of(ssp("IT:reg:ScheduledStopPoint:Yn", "45.00000", "9.0"),
                        ssp("IT:reg:ScheduledStopPoint:Ys", "45.00018", "9.0")),
                ScheduledStopPoints::isRapCodespace);

        Check.equals(2, quaysOf(stops.get(0)).size(), "one Quay per assignment, not one per StopPlace");
        Check.equals("IT:reg:Quay:Yn", quayRefOf(psas.get(0)), "each assignment names its own");
        Check.equals("IT:reg:Quay:Ys", quayRefOf(psas.get(1)), "and they are distinct");
    }

    /// A StopPlace the feed did give quays keeps them untouched: there is no telling whether the
    /// feed's quay set already covers this SSP.
    public static void testStopThatAlreadyHasQuaysIsLeftAlone(TestStore db) throws Exception {
        List<StopPlace> stops = new ArrayList<>(List.of(
                stopWithQuay("IT:reg:StopPlace:Z", "IT:reg:Quay:feed", "Porto", "45.0", "9.0")));
        List<PassengerStopAssignment> psas = new ArrayList<>(List.of(
                bareLink("IT:reg:PassengerStopAssignment:z", "IT:reg:ScheduledStopPoint:Z", "IT:reg:StopPlace:Z")));

        ScheduledStopPoints.backfillQuaysForAssignedSsps(stops, psas,
                List.of(ssp("IT:reg:ScheduledStopPoint:Z", "45.0", "9.0")),
                ScheduledStopPoints::isRapCodespace);

        Check.equals(1, quaysOf(stops.get(0)).size(), "no second quay is invented");
        Check.equals("IT:reg:Quay:feed", quaysOf(stops.get(0)).get(0).getId(), "the feed's own survives");
        Check.isNull(quayRefOf(psas.get(0)), "and the PSA keeps OTP's station-level fallback");
    }

    /// The codespace gate. It is `IT:`, so a space outside it is skipped whole.
    public static void testNonRapCodespaceIsSkipped(TestStore db) throws Exception {
        List<StopPlace> stops = new ArrayList<>(List.of(bareStop("at:49:StopPlace:W", "Haltestelle")));
        List<PassengerStopAssignment> psas = new ArrayList<>(List.of(
                bareLink("at:49:PassengerStopAssignment:w",
                        "at:49:ScheduledStopPoint:W", "at:49:StopPlace:W")));

        ScheduledStopPoints.backfillQuaysForAssignedSsps(stops, psas,
                List.of(ssp("at:49:ScheduledStopPoint:W", "45.0", "9.0")),
                ScheduledStopPoints::isRapCodespace);

        Check.equals(0, quaysOf(stops.get(0)).size(), "a non-RAP space is left untouched");
        Check.isNull(quayRefOf(psas.get(0)), "and so are its assignments");
    }

    /// Marche's ids arrive as `epd:IT:ITI3:`, an undeclared prefix `isRapCodespace` does not match;
    /// `normalize-it-ids` strips the token at load, so the region reaches this pass as an ordinary
    /// RAP feed. It carries ~9,653 quay-less StopPlaces.
    public static void testMarcheIsBackfilledOnceItsIdsAreCorrected(TestStore db) throws Exception {
        List<StopPlace> stops = new ArrayList<>(List.of(bareStop("IT:ITI3:StopPlace:W", "Fermata")));
        List<PassengerStopAssignment> psas = new ArrayList<>(List.of(
                bareLink("IT:ITI3:PassengerStopAssignment:w",
                        "IT:ITI3:ScheduledStopPoint:W", "IT:ITI3:StopPlace:W")));

        ScheduledStopPoints.backfillQuaysForAssignedSsps(stops, psas,
                List.of(ssp("IT:ITI3:ScheduledStopPoint:W", "45.0", "9.0")),
                ScheduledStopPoints::isRapCodespace);

        Check.equals(1, quaysOf(stops.get(0)).size(), "the quay-less Marche stop gains one");
        Check.equals("IT:ITI3:Quay:W", quaysOf(stops.get(0)).get(0).getId(),
                "minted by substitution on the SSP id");
        Check.equals("IT:ITI3:Quay:W", quayRefOf(psas.get(0)), "and the assignment points at it");
    }

    /// The survivor's quay list is a union deduped by id: the merge runs at more than one tier, so
    /// one quay id can arrive from several members.
    public static void testMergeDedupesQuaysById(TestStore db) throws Exception {
        List<StopPlace> sps = new ArrayList<>();
        for (String v : List.of("1", "2")) {                   // one id, two versions, same quay
            StopPlace sp = new StopPlace();
            sp.setId("IT:reg:StopPlace:same");
            sp.setVersion(v);
            sp.setName(new noi.netex.model.MultilingualString().withContent("Same Place"));
            sp.setCentroid(new noi.netex.model.SimplePoint_VersionStructure()
                    .withLocation(new noi.netex.model.LocationStructure()
                            .withLatitude(new java.math.BigDecimal("45.0"))
                            .withLongitude(new java.math.BigDecimal("9.0"))));
            Quay q = new Quay();
            q.setId("IT:reg:Quay:shared");
            q.setVersion(v);
            noi.netex.model.Quays_RelStructure rel = new noi.netex.model.Quays_RelStructure();
            rel.getQuayRefOrQuay().add(new noi.netex.model.ObjectFactory().createQuay(q));
            sp.setQuays(rel);
            sps.add(sp);
        }
        var plan = ScheduledStopPoints.planStopplaceMerge(sps, List.of());
        StopPlace survivor = plan.kept().get("IT:reg:StopPlace:same");
        Check.that(survivor != null, "the two rows of one id form a cluster with a survivor");
        Check.equals(1, quaysOf(survivor).size(),
                "the shared quay id appears once on the survivor, not once per member");
    }
}
