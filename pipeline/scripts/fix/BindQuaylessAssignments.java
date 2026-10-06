package fix;

// Give a PassengerStopAssignment that names no Quay the one its StopPlace holds.
//
// The rail merge folds a publisher that models quays into one that does not. Italo publishes 70
// StopPlaces with no Quay at all and 70 assignments with no QuayRef; Trenitalia publishes one Quay
// per station and names it from every assignment. planStopplaceMerge re-points a StopPlaceRef whose
// StopPlace was merged away, and re-points a QuayRef whose Quay was folded away -- an assignment
// holding no QuayRef is given neither, and comes out of the merge pointing at the survivor with the
// field still empty.
//
// Nothing downstream repairs it. backfillQuaysForAssignedSsps mints a Quay only for a StopPlace
// that has none, on the argument that a publisher modelling its own quays may model ones we cannot
// see; the survivor here has one, so it is skipped. The station ends with one Quay and two
// assignments, of which one names it.
//
// OTP files an assignment under the quay index or the stop-place index and never both, so the two
// publishers' journeys would arrive at two different stop objects standing on one platform -- the
// thing the merge was run to prevent, and invisible in its own counters, which report the
// StopPlaces folded and say nothing about the assignments left behind.
//
// Exactly one Quay, or nothing happens. Two is a station whose platforms are modelled, and which
// one a journey leaves from is a question the feed has to answer.

import noi.netex.model.PassengerStopAssignment;
import noi.netex.model.Quay;
import noi.netex.model.QuayRefStructure;
import noi.netex.model.StopPlace;
import noi.netex.model.StopPlaceRefStructure;
import toolkit.harness.DbToDb;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.util.Log;

import jakarta.xml.bind.JAXBElement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BindQuaylessAssignments {

    private BindQuaylessAssignments() {}

    private static final noi.netex.model.ObjectFactory FACTORY = new noi.netex.model.ObjectFactory();

    public static void main(String[] args) {
        DbToDb.driverMain("bind-quayless-assignments", args, BindQuaylessAssignments::apply);
    }

    /// Named so tests can run it via TestStore.runDbToDb, without the CLI sandwich.
    public static void apply(Store src, Txn stx, Store dst, Txn dtx) {
        Map<String, String> soleQuay = soleQuayByStopPlace(src, stx);
        for (Class<?> clazz : src.dbNames(stx)) {
            if (clazz == PassengerStopAssignment.class) continue;
            src.copyMap(stx, dst, dtx, clazz);
        }
        dst.insertAnyObjects(dtx, bound(src, stx, soleQuay).iterator());
    }

    /// StopPlace id -> its Quay id, for the StopPlaces holding exactly one.
    private static Map<String, String> soleQuayByStopPlace(Store db, Txn txn) {
        Map<String, String> out = new HashMap<>();
        long several = 0;
        for (Object o : db.iterOnlyObjects(txn, StopPlace.class)) {
            StopPlace sp = (StopPlace) o;
            if (sp.getId() == null || sp.getQuays() == null) continue;
            String only = null;
            int n = 0;
            for (JAXBElement<?> el : sp.getQuays().getQuayRefOrQuay()) {
                if (!(el.getValue() instanceof Quay q) || q.getId() == null) continue;
                n++;
                only = q.getId();
            }
            if (n == 1) out.put(sp.getId(), only);
            else if (n > 1) several++;
        }
        Log.info("[bind_quays] %d StopPlaces hold exactly one Quay, %d hold several", out.size(), several);
        return out;
    }

    /// Every assignment, the quay-less ones carrying their StopPlace's sole Quay.
    private static List<Object> bound(Store db, Txn txn, Map<String, String> soleQuay) {
        List<Object> out = new ArrayList<>();
        long bound = 0, noStopPlace = 0, notSole = 0;
        for (Object o : db.iterOnlyObjects(txn, PassengerStopAssignment.class)) {
            PassengerStopAssignment psa = (PassengerStopAssignment) o;
            out.add(psa);
            if (psa.getQuayRef() != null) continue;
            JAXBElement<? extends StopPlaceRefStructure> srEl = psa.getStopPlaceRef();
            StopPlaceRefStructure sr = srEl == null ? null : srEl.getValue();
            String spId = sr == null ? null : sr.getRef();
            if (spId == null) {
                noStopPlace++;
                continue;
            }
            String quayId = soleQuay.get(spId);
            if (quayId == null) {
                notSole++;
                continue;
            }
            QuayRefStructure qr = new QuayRefStructure();
            qr.setRef(quayId);
            if (sr.getVersion() != null) qr.setVersion(sr.getVersion());
            psa.setQuayRef(FACTORY.createQuayRef(qr));
            bound++;
        }
        Log.info("[bind_quays] bound %d quay-less assignments to their StopPlace's sole Quay; "
                + "left %d naming no StopPlace and %d whose StopPlace holds none or several",
                bound, noStopPlace, notSole);
        return out;
    }
}
