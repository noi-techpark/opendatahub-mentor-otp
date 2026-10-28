package transformers.xb;

// Reading stop references out of the model, for the cross-border stage.
//
//   psaSspRef / psaStopRef  a PassengerStopAssignment's two refs
//   callRef                 a Call's stop ref
//   patternSspRefs          ServiceJourneyPattern id -> its ScheduledStopPoint refs
//   servedFromRailSsp       StopPlace ids a rail-served SSP set maps to via the PSAs
//   namespace               the feed-ish id namespace, for the same-namespace guard
//
// Union fields use the binding's split accessors: the stop-place union maps to
// getStopPlaceRef()/getStopPlace(), the quay union to getQuayRef()/getQuay(), and the
// scheduled-stop-point union to getScheduledStopPointRef(), whose JAXBElement covers the Fare
// variant too since it subclasses ScheduledStopPointRefStructure.

import jakarta.xml.bind.JAXBElement;
import noi.netex.model.Call_VersionedChildStructure;
import noi.netex.model.PassengerStopAssignment;
import noi.netex.model.PointInLinkSequence_VersionedChildStructure;
import noi.netex.model.ServiceJourneyPattern;
import noi.netex.model.StopPointInJourneyPattern_VersionedChildStructure;
import toolkit.store.Store;
import toolkit.store.Txn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class XbStopRefs {

    private XbStopRefs() {}

    /// The PSA's ScheduledStopPoint ref, or null.
    static String psaSspRef(PassengerStopAssignment psa) {
        JAXBElement<?> el = psa.getScheduledStopPointRef();
        return el != null ? ((noi.netex.model.ScheduledStopPointRefStructure) el.getValue()).getRef() : null;
    }

    /// The PSA's StopPlaceRef, or null — the union's other members carry no ref.
    static String psaStopRef(PassengerStopAssignment psa) {
        JAXBElement<? extends noi.netex.model.StopPlaceRefStructure> el = psa.getStopPlaceRef();
        return el != null ? el.getValue().getRef() : null;
    }

    /// The Call's stop ref; the view variant carries no ref.
    static String callRef(Call_VersionedChildStructure call) {
        JAXBElement<? extends noi.netex.model.ScheduledStopPointRefStructure> el = call.getScheduledStopPointRef();
        return el != null ? el.getValue().getRef() : null;
    }

    /// ServiceJourneyPattern id -> its ScheduledStopPoint refs. A malformed source pattern can
    /// carry no point sequence at all and contributes no stop refs. List entries may be null (a
    /// point without a stop ref), filtered at use.
    public static Map<String, List<String>> patternSspRefs(Store db, Txn txn) {
        Map<String, List<String>> patSsps = new HashMap<>();
        for (Object o : db.iterOnlyObjects(txn, ServiceJourneyPattern.class)) {
            ServiceJourneyPattern pat = (ServiceJourneyPattern) o;
            List<PointInLinkSequence_VersionedChildStructure> pts = pat.getPointsInSequence() != null
                    ? pat.getPointsInSequence().getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern()
                    : List.of();
            List<String> refs = new ArrayList<>(pts.size());
            for (PointInLinkSequence_VersionedChildStructure p : pts) {
                // Only StopPointInJourneyPattern carries the attribute; the other two use
                // differently-named unions, so anything else contributes a null ref.
                String r = null;
                if (p instanceof StopPointInJourneyPattern_VersionedChildStructure spp
                        && spp.getScheduledStopPointRef() != null) {
                    r = spp.getScheduledStopPointRef().getValue().getRef();
                }
                refs.add(r);
            }
            patSsps.put(pat.getId(), refs);
        }
        return patSsps;
    }

    /// StopPlace ids the rail-served SSP set maps to, via the PSAs.
    public static Set<String> servedFromRailSsp(Set<String> railSsp, List<PassengerStopAssignment> psas) {
        Set<String> served = new HashSet<>();
        for (PassengerStopAssignment psa : psas) {
            String ssp = psaSspRef(psa);
            String sp = psaStopRef(psa);
            if (sp != null && !sp.isEmpty() && ssp != null && railSsp.contains(ssp)) {
                served.add(sp);
            }
        }
        return served;
    }

    /// The feed-ish id namespace, the first two tokens: it separates the Swiss feed's own records
    /// (`ch:2:...`) from an Austrian feed's copy of a Swiss stop (`ch:23016:...`).
    public static String namespace(String spId) {
        String[] parts = spId.split(":", 3); // the limit keeps trailing empties
        return parts.length >= 2 ? parts[0] + ":" + parts[1] : parts[0];
    }
}
