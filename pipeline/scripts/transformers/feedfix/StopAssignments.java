package transformers.feedfix;

// The placeholder-quay strip and the stop-assignment repairs that follow from it, run per feed.
//
// The three repairs, in the order one visit applies them:
//
//  1. an embedded Quay without a coordinate is stripped from its StopPlace — such quays are
//     whole-stop placeholders, never locatable platforms (the Verbund exporters' `…:HoB:`,
//     "Hst. ohne Bereich"; 26,065 of 106,729 embedded Austrian quays carry no centroid). A
//     surviving quay whose ParentQuayRef pointed at a stripped one reparents to the StopPlace,
//     and a stop that loses every quay loses the `<quays>` container with them;
//  2. an assignment carrying only a quay ref gains that quay's parent StopPlaceRef;
//  3. an assignment whose quay ref no StopPlace embeds — after the strip — loses that ref and
//     becomes station-level. This converts placeholder-quay assignments and genuinely dangling
//     refs alike.
//
// 2 runs before 3, on the pre-strip stops, so the assignment 3 demotes keeps the station 2 gave it.
//
// Binding decisions (choice groups):
//  - a StopPlace's quays are a mixed list of embedded quays and refs; the ref entries keep their
//    JAXBElement wrappers so the element names survive re-marshalling;
//  - an assignment's quay union splits into a ref half and an embedded half. A taxi-stand ref
//    extends the quay ref, so "is a real quay ref" is: present and not a taxi-stand ref. Clearing
//    the union nulls both halves. The stop-place union splits the same way, and a TaxiRankRef
//    arrives through the stopPlaceRef slot (TaxiRankRefStructure extends StopPlaceRefStructure),
//    so getStopPlaceRef() + getStopPlace() cover all three of its arms;
//  - a quay is located by latitude/longitude, else by a gml:pos fallback.

import jakarta.xml.bind.JAXBElement;
import noi.netex.model.EntityStructure;
import noi.netex.model.LocationStructure;
import noi.netex.model.ObjectFactory;
import noi.netex.model.PassengerStopAssignment;
import noi.netex.model.Quay;
import noi.netex.model.QuayRefStructure;
import noi.netex.model.SimplePoint_VersionStructure;
import noi.netex.model.StopPlace;
import noi.netex.model.StopPlaceRefStructure;
import noi.netex.model.TaxiStandRefStructure;
import toolkit.model.ModelRegistry;
import toolkit.model.NetexUtils;
import toolkit.model.RecursiveAttributes;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

public final class StopAssignments {

    private static final ObjectFactory FACTORY = new ObjectFactory();

    private StopAssignments() {}

    /// The three repairs above, over `dbRead` at `txn`, yielding every object the caller must write
    /// back.
    ///
    /// Yield order: the changed StopPlaces in class-map order, then the changed assignments, then
    /// the summary line. The assignment cursor opens only after the StopPlace cursor is drained —
    /// one live cursor at a time.
    public static Iterator<Object> sanitizeStopAssignments(Store dbRead, Txn txn) {
        return new Iterator<>() {
            // Embedded quay id -> its StopPlace's ref, from the pre-strip stops. Built during
            // the StopPlace pass, so repair 2 sees the quays repair 1 is removing.
            final Map<String, StopPlaceRefStructure> quayStop = new HashMap<>();
            // The embedded quay ids that survived the strip — repair 3's membership test.
            final Set<String> embedded = new HashSet<>();
            long stripped = 0;
            long parentRefsCleared = 0;
            long stops = 0;
            long emptied = 0;
            long backfilled = 0;
            long cleared = 0;
            Iterator<Object> stopPlaces;
            Iterator<Object> psas;
            Object ready;
            boolean logged;

            private void pump() {
                if (stopPlaces == null) {
                    stopPlaces = dbRead.iterOnlyObjects(txn, StopPlace.class).iterator();
                }
                while (ready == null && stopPlaces.hasNext()) {
                    StopPlace sp = (StopPlace) stopPlaces.next();
                    // One ref per StopPlace: it is re-marshalled per referencing assignment on
                    // insert. Every embedded entity, not only the quays list — the parent of a
                    // quay ref an assignment carries is whatever embeds it.
                    StopPlaceRefStructure spRef = NetexUtils.getRef(sp, StopPlaceRefStructure.class);
                    for (EntityStructure emb : RecursiveAttributes.embeddedEntities(
                            sp, c -> ModelRegistry.isRegistered(c.getSimpleName()))) {
                        quayStop.put(emb.getId(), spRef);
                    }

                    List<JAXBElement<?>> quays = sp.getQuays() != null
                            ? sp.getQuays().getQuayRefOrQuay() : List.of();

                    List<JAXBElement<?>> real = new ArrayList<>();
                    Set<String> removedHere = new HashSet<>();
                    for (JAXBElement<?> el : quays) {
                        if (located(el)) real.add(el);
                        else removedHere.add(((Quay) el.getValue()).getId());
                    }
                    boolean changed = real.size() != quays.size();
                    for (JAXBElement<?> el : real) {
                        if (!(el.getValue() instanceof Quay q)) continue;   // keep refs untouched
                        embedded.add(q.getId());
                        QuayRefStructure pr = q.getParentQuayRef();
                        if (pr != null && removedHere.contains(pr.getRef())) {
                            q.setParentQuayRef(null);
                            parentRefsCleared++;
                            changed = true;
                        }
                    }
                    if (changed) {
                        stripped += quays.size() - real.size();
                        if (real.isEmpty()) {
                            // OTP's StopAndStationMapper.listOfQuays raises StopPlaceWithoutQuays
                            // only on a null container, so a stop left with an empty <quays/> is
                            // degraded to station level with nothing in the issue report.
                            sp.setQuays(null);
                            emptied++;
                        } else {
                            // Getter-only list: clear and refill.
                            List<JAXBElement<?>> live = sp.getQuays().getQuayRefOrQuay();
                            live.clear();
                            live.addAll(real);
                        }
                        stops++;
                        ready = sp;
                    }
                }
                if (ready == null) {
                    if (psas == null) {
                        psas = dbRead.iterOnlyObjects(txn, PassengerStopAssignment.class).iterator();
                    }
                    while (ready == null && psas.hasNext()) {
                        PassengerStopAssignment psa = (PassengerStopAssignment) psas.next();
                        // The ref half of the split quay union, minus the taxi-stand substitute.
                        JAXBElement<? extends QuayRefStructure> qrEl = psa.getQuayRef();
                        QuayRefStructure quayRef = qrEl == null ? null : qrEl.getValue();
                        if (quayRef == null || quayRef instanceof TaxiStandRefStructure) continue;
                        boolean changed = false;
                        // 2: an assignment with no stop-place arm at all gains its quay's parent.
                        // One whose union is already populated is left alone entirely, so it keeps
                        // its source-faithful stored fragment.
                        if (psa.getStopPlaceRef() == null && psa.getStopPlace() == null) {
                            StopPlaceRefStructure spRef = quayStop.get(quayRef.getRef());
                            if (spRef != null) {
                                psa.setStopPlaceRef(FACTORY.createStopPlaceRef(spRef));
                                backfilled++;
                                changed = true;
                            }
                        }
                        // 3: a quay ref no surviving StopPlace embeds -> whole-union clear.
                        if (!embedded.contains(quayRef.getRef())) {
                            psa.setQuayRef(null);
                            psa.setQuay(null);
                            cleared++;
                            changed = true;
                        }
                        if (changed) ready = psa;
                    }
                }
                if (ready == null && !logged) {
                    logged = true;
                    Log.info("[stop-assignments] stripped %d coordinate-less placeholder quays "
                            + "from %d StopPlaces (%d emptied outright, "
                            + "%d ParentQuayRefs reparented to the stop), "
                            + "backfilled %d assignment StopPlaceRefs, "
                            + "cleared %d placeholder/dangling assignment quay refs",
                            stripped, stops, emptied, parentRefsCleared, backfilled, cleared);
                }
            }

            @Override
            public boolean hasNext() {
                pump();
                return ready != null;
            }

            @Override
            public Object next() {
                if (!hasNext()) throw new NoSuchElementException();
                Object out = ready;
                ready = null;
                return out;
            }
        };
    }

    /// Ref entries are kept untouched; a Quay is located iff a coordinate can be read.
    private static boolean located(JAXBElement<?> el) {
        if (!(el.getValue() instanceof Quay q)) return true;
        return quayCoord(q) != null;
    }

    /// The coordinate read, applied to a Quay; [toolkit.transform.common.ScheduledStopPoints] holds
    /// the StopPlace-typed twin, and [transformers.xb.XbStations#coord] the Zone-typed one.
    private static double[] quayCoord(Quay q) {
        SimplePoint_VersionStructure c = q.getCentroid();
        LocationStructure loc = c != null ? c.getLocation() : null;
        if (loc == null) return null;
        if (loc.getLatitude() != null && loc.getLongitude() != null) {
            return new double[] {loc.getLatitude().doubleValue(), loc.getLongitude().doubleValue()};
        }
        if (loc.getPos() != null && loc.getPos().getValue() != null && loc.getPos().getValue().size() >= 2) {
            return new double[] {loc.getPos().getValue().get(0), loc.getPos().getValue().get(1)};
        }
        return null;
    }
}
