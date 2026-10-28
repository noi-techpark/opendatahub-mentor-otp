package tests;

// The per-feed stop-assignment repair: coordinate-less placeholder quays leave their StopPlace, a
// quay orphaned by that strip reparents to the stop, an assignment with only a quay ref gains that
// quay's StopPlaceRef, and an assignment whose quay ref nothing embeds any more goes station-level.
//
// The fixture: a Verbund `…:HoB:` whole-stop placeholder, an anonymous coordinate-less quay, one
// located platform, and four assignments covering every arm of the decision. A second fixture
// covers the strip taking every quay: the container has to go with them.
//
// Union reads (the choice groups, per StopAssignments' header):
//   sp.quays.taxi_stand_ref_or_quay_ref_or_quay -> getQuays().getQuayRefOrQuay()
//     (JAXBElement entries, all embedded Quays here);
//   an absent quay union -> BOTH halves null (getQuayRef() and getQuay());
//   psa.taxi_stand_ref_or_quay_ref_or_quay.ref -> getQuayRef().getValue().getRef();
//   psa.taxi_rank_ref_or_stop_place_ref_or_stop_place.ref -> getStopPlaceRef().getValue().getRef().

import fix.SanitizeStopAssignments;
import jakarta.xml.bind.JAXBElement;
import noi.netex.model.PassengerStopAssignment;
import noi.netex.model.Quay;
import noi.netex.model.StopPlace;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.Runner;
import toolkit.test.TestStore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class TestSanitizeStopAssignments {

    private static final String FRAMES = """
        <SiteFrame id="site" version="1"><stopPlaces>
          <StopPlace id="at:49:975" version="1"><Name>Wien Testplatz</Name>
            <Centroid><Location><Latitude>48.20000</Latitude><Longitude>16.37000</Longitude></Location></Centroid>
            <quays>
              <Quay id="at:49:975:1" version="1"><Centroid><Location><Latitude>48.20010</Latitude><Longitude>16.37010</Longitude></Location></Centroid><ParentQuayRef ref="at:vor:Quay:at:49:975:HoB:" version="1"/></Quay>
              <Quay id="at:49:975:2" version="1"/>
              <Quay id="at:vor:Quay:at:49:975:HoB:" version="1"><Name>Hst. ohne Bereich</Name><PublicCode>HoB</PublicCode></Quay>
            </quays></StopPlace>
        </stopPlaces></SiteFrame>
        <ServiceFrame id="sf" version="1"><stopAssignments>
          <PassengerStopAssignment id="pa-ok" version="1" order="1"><ScheduledStopPointRef ref="s1"/><StopPlaceRef ref="at:49:975"/><QuayRef ref="at:49:975:1"/></PassengerStopAssignment>
          <PassengerStopAssignment id="pa-dangling" version="1" order="1"><ScheduledStopPointRef ref="s2"/><StopPlaceRef ref="at:49:975"/><QuayRef ref="at:49:975:0:7"/></PassengerStopAssignment>
          <PassengerStopAssignment id="pa-hob" version="1" order="1"><ScheduledStopPointRef ref="s3"/><StopPlaceRef ref="at:49:975"/><QuayRef ref="at:vor:Quay:at:49:975:HoB:"/></PassengerStopAssignment>
          <PassengerStopAssignment id="pa-quay-only" version="1" order="1"><ScheduledStopPointRef ref="s4"/><QuayRef ref="at:vor:Quay:at:49:975:HoB:"/></PassengerStopAssignment>
        </stopAssignments></ServiceFrame>
        """;

    // A stop whose every quay is a placeholder, so the strip leaves nothing behind. Its own
    // fixture: the test above reads the single StopPlace of the one it loads.
    private static final String ALL_PLACEHOLDERS = """
        <SiteFrame id="site" version="1"><stopPlaces>
          <StopPlace id="at:43:12" version="1"><Name>Nur Platzhalter</Name>
            <Centroid><Location><Latitude>47.07000</Latitude><Longitude>15.44000</Longitude></Location></Centroid>
            <quays>
              <Quay id="at:vor:Quay:at:43:12:HoB:" version="1"><Name>Hst. ohne Bereich</Name><PublicCode>HoB</PublicCode></Quay>
              <Quay id="at:43:12:2" version="1"/>
            </quays></StopPlace>
        </stopPlaces></SiteFrame>
        <ServiceFrame id="sf" version="1"><stopAssignments>
          <PassengerStopAssignment id="pa-hob" version="1" order="1"><ScheduledStopPointRef ref="s1"/><StopPlaceRef ref="at:43:12"/><QuayRef ref="at:vor:Quay:at:43:12:HoB:"/></PassengerStopAssignment>
        </stopAssignments></ServiceFrame>
        """;

    public static void main(String[] args) {
        Runner.run(TestSanitizeStopAssignments.class);
    }

    public static void testPlaceholderQuaysStrippedAndAssignmentsRepaired(TestStore db) throws Exception {
        db.loadNetex(FRAMES);
        TestStore target = db.runDbToDb(SanitizeStopAssignments::apply);

        StopPlace sp;
        Map<String, PassengerStopAssignment> psas = new LinkedHashMap<>();
        try (Txn txn = target.store.roTxn()) {
            sp = (StopPlace) target.store.iterOnlyObjects(txn, StopPlace.class).iterator().next();
            for (Object o : target.store.iterOnlyObjects(txn, PassengerStopAssignment.class)) {
                psas.put(((PassengerStopAssignment) o).getId(), (PassengerStopAssignment) o);
            }
        }

        // 1. Only the located platform survives: the anonymous coordinate-less quay and the HoB
        //    whole-stop placeholder are both gone.
        Map<String, Quay> quays = new LinkedHashMap<>();
        for (JAXBElement<?> el : sp.getQuays().getQuayRefOrQuay()) {
            Quay q = (Quay) el.getValue();
            quays.put(q.getId(), q);
        }
        Check.equals(Set.of("at:49:975:1"), new TreeSet<>(quays.keySet()), "surviving quays");

        // 1b. That platform's ParentQuayRef pointed at the stripped HoB quay, so it reparents to
        //     the StopPlace by losing the ref.
        Check.isNull(quays.get("at:49:975:1").getParentQuayRef(),
                "ParentQuayRef at a stripped quay is cleared");

        // The assignment at a surviving quay is untouched — both union arms intact.
        Check.equals("at:49:975:1", psas.get("pa-ok").getQuayRef().getValue().getRef(),
                "pa-ok keeps its quay ref");
        Check.equals("at:49:975", psas.get("pa-ok").getStopPlaceRef().getValue().getRef(),
                "pa-ok keeps its stop place ref");

        // 3. A quay ref no StopPlace embeds any more loses the WHOLE union — the genuinely
        //    dangling one and the one at the stripped placeholder alike — and stays station-level.
        for (String id : new String[] {"pa-dangling", "pa-hob"}) {
            PassengerStopAssignment psa = psas.get(id);
            Check.isNull(psa.getQuayRef(), id + " quay ref cleared");
            Check.isNull(psa.getQuay(), id + " embedded quay half cleared");
            Check.equals("at:49:975", psa.getStopPlaceRef().getValue().getRef(),
                    id + " stays station-level on its own StopPlaceRef");
        }

        // 2 THEN 3, the ordering that makes the demotion safe: pa-quay-only arrived with no stop
        // place arm at all, gained the HoB quay's parent StopPlaceRef from the PRE-strip stop, and
        // only then lost the quay ref. Run the other way round it would be left with nothing.
        PassengerStopAssignment quayOnly = psas.get("pa-quay-only");
        Check.equals("at:49:975", quayOnly.getStopPlaceRef().getValue().getRef(),
                "pa-quay-only backfilled from the stripped quay's parent stop");
        Check.isNull(quayOnly.getQuayRef(), "pa-quay-only quay ref cleared");
        Check.isNull(quayOnly.getQuay(), "pa-quay-only embedded quay half cleared");
    }

    /// A stop whose every quay is stripped comes back with no `<quays>` container at all, not with
    /// an empty one.
    public static void testStopPlaceEmptiedOfQuaysLosesTheContainer(TestStore db) throws Exception {
        db.loadNetex(ALL_PLACEHOLDERS);
        TestStore target = db.runDbToDb(SanitizeStopAssignments::apply);

        try (Txn txn = target.store.roTxn()) {
            StopPlace sp = (StopPlace) target.store.iterOnlyObjects(txn, StopPlace.class).iterator().next();
            Check.isNull(sp.getQuays(), "the quays container is gone, not merely emptied");

            // The assignment survives the emptying: it goes station-level on its own StopPlaceRef.
            PassengerStopAssignment psa = (PassengerStopAssignment)
                    target.store.iterOnlyObjects(txn, PassengerStopAssignment.class).iterator().next();
            Check.isNull(psa.getQuayRef(), "pa-hob quay ref cleared");
            Check.isNull(psa.getQuay(), "pa-hob embedded quay half cleared");
            Check.equals("at:43:12", psa.getStopPlaceRef().getValue().getRef(),
                    "pa-hob stays station-level on its own StopPlaceRef");
        }
    }
}
