package transformers.xb;

// Emitting the stay-seated ServiceJourneyInterchanges, sharing a Quay at the handover, and checking
// the result is something the consumer can map.
//
// The handover station is an output of the tiling, which has already established that both legs
// serve it, so there is no search for the far end and no fallback to an endpoint: each end is the
// counterpart's own reference to that station. [#validate] refuses a link whose ends are not both on
// their journeys' patterns, and nothing downstream repairs one.

import jakarta.xml.bind.JAXBElement;
import noi.netex.model.JourneyRefStructure;
import noi.netex.model.ObjectFactory;
import noi.netex.model.PassengerStopAssignment;
import noi.netex.model.QuayRefStructure;
import noi.netex.model.ScheduledStopPointRefStructure;
import noi.netex.model.ServiceJourneyInterchange;
import toolkit.util.Log;
import transformers.xb.XbCalendar.DaySet;
import transformers.xb.XbTypes.Dict;
import transformers.xb.XbTypes.Node;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class XbLinks {

    private static final ObjectFactory FACTORY = new ObjectFactory();

    private XbLinks() {}

    /// One publication after cutting: everything a link needs from one end.
    ///
    /// Always one journey, never a stitched vehicle: a link names a ServiceJourney and a truncation
    /// cuts one, so a leg over a node [XbStitch] made out of several publications yields one End per
    /// part, each carrying that part's own positions, refs and version.
    ///
    /// @param node    the matched publication.
    /// @param from    first kept passing-time position.
    /// @param to      last kept passing-time position.
    /// @param refs    the kept stops' ScheduledStopPoint refs, aligned with the kept passing times.
    /// @param version the journey's version, for the interchange's own.
    /// @param ofStitched this End is one part of a stitched vehicle, so it may legitimately not
    ///                   call at a junction the vehicle as a whole reaches — the other part does.
    ///                   Without the flag [#junction] would charge that to `noRefAtHandover`.
    public record End(Node node, int from, int to, List<String> refs, String version,
            boolean ofStitched) {

        /// One journey that was never stitched.
        public End(Node node, int from, int to, List<String> refs, String version) {
            this(node, from, to, refs, version, false);
        }

        /// This end's own ref at `station`. `arriving` picks between repeated calls by direction: the
        /// leg that arrives hands over on its last call there, the leg that continues picks it up on
        /// its first.
        public String refAt(int station, boolean arriving) {
            String found = null;
            for (int i = 0; i < refs.size(); i++) {
                int at = from + i;
                if (at > to || node.canon[at] != station) continue;
                String ref = refs.get(i);
                if (ref == null || ref.isEmpty()) continue;
                if (!arriving) return ref;
                found = ref;
            }
            return found;
        }

        /// The kept position of this end's call at `station`, or -1.
        public int indexAt(int station, boolean arriving) {
            int found = -1;
            for (int i = from; i <= to; i++) {
                if (node.canon[i] != station) continue;
                if (!arriving) return i;
                found = i;
            }
            return found;
        }
    }

    // ------------------------------------------------------------------------------------- //

    /// Every link across one junction: each feeder publication to each distributor publication that
    /// it actually meets there.
    ///
    /// One logical train is published as many journeys, one per calendar variant, so a junction is a
    /// cross product rather than a pair. Each pair is re-checked: [XbGroups#meetAt] must give the two
    /// a common date at this station, so a feeder that runs only on Sundays is not linked to a
    /// distributor that never does.
    public static List<ServiceJourneyInterchange> junction(List<End> feeders, List<End> distributors,
            int station, Dict numbers, Dict countries, Counters counters) {
        List<ServiceJourneyInterchange> out = new ArrayList<>();
        for (End f : feeders) {
            // A part of a stitched vehicle that does not reach this junction is not a failure: the
            // vehicle reaches it through its other part, which is in this same list. Skipped before
            // the refusal counters so it cannot be read as a missing ref.
            if (f.ofStitched() && f.indexAt(station, /*arriving=*/true) < 0) {
                counters.partNotAtJunction++;
                continue;
            }
            String fromRef = f.refAt(station, /*arriving=*/true);
            for (End d : distributors) {
                if (d.ofStitched() && d.indexAt(station, /*arriving=*/false) < 0) {
                    counters.partNotAtJunction++;
                    continue;
                }
                DaySet meet = XbGroups.meetAt(f.node(), d.node(), station);
                if (meet.isEmpty()) {
                    counters.noMeetingAtJunction++;
                    continue;
                }
                String toRef = d.refAt(station, /*arriving=*/false);
                if (fromRef == null || toRef == null) {
                    counters.noRefAtHandover++;
                    continue;
                }
                // Stay-seated only when the mode does not change, and the corridor test alone cannot
                // tell: SAD publishes the Pustertal as rail line 034 and as bus line 065 over the
                // same endpoints.
                boolean sameMode = f.node().mode == d.node().mode;
                if (!sameMode) counters.modeChangeAtJunction++;
                out.add(interchange(
                        id(countries.value(f.node().home), countries.value(d.node().home),
                                numbers.value(f.node().num), f.node().id, d.node().id),
                        f.version(), fromRef, toRef,
                        f.node().id, f.version(), d.node().id, d.version(), sameMode));
            }
        }
        return out;
    }

    /// The interchange id. Each identity slot is the whole journey id, and anything shorter collides.
    static String id(String fromCc, String toCc, String num, String fromJourney, String toJourney) {
        return String.format("%s-%s:ServiceJourneyInterchange:%s:%s:%s",
                fromCc == null ? "??" : fromCc, toCc == null ? "??" : toCc, num,
                XbIds.slot(fromJourney), XbIds.slot(toJourney));
    }

    /// The interchange itself: a planned, guaranteed, advertised crossing of a border.
    ///
    /// `staySeated` is the one flag that is not unconditional. It asserts the passenger keeps their
    /// seat, which is false the moment the mode changes, so it is passed in rather than assumed.
    static ServiceJourneyInterchange interchange(String id, String version, String fromPt,
            String toPt, String fromJourney, String fromVersion, String toJourney,
            String toVersion, boolean staySeated) {
        ServiceJourneyInterchange x = new ServiceJourneyInterchange();
        x.setId(id);
        x.setVersion(version);
        x.setStaySeated(staySeated);
        x.setCrossBorder(Boolean.TRUE);
        x.setPlanned(Boolean.TRUE);
        x.setGuaranteed(Boolean.TRUE);
        x.setAdvertised(Boolean.TRUE);
        x.setFromPointRef(sspRef(fromPt));
        x.setToPointRef(sspRef(toPt));
        // The binding flattens the from/to journey-ref union into two typed fields, so the element
        // name comes from the field and there is no JAXBElement wrapper.
        x.setFromJourneyRef(journeyRef(fromJourney, fromVersion));
        x.setToJourneyRef(journeyRef(toJourney, toVersion));
        return x;
    }

    /// Omit the ref entirely rather than emit a dangling `ScheduledStopPointRef` with `ref=null`.
    static ScheduledStopPointRefStructure sspRef(String ref) {
        if (ref == null) return null;
        ScheduledStopPointRefStructure r = new ScheduledStopPointRefStructure();
        r.setRef(ref);
        return r;
    }

    /// A journey ref that needs no repair afterwards.
    ///
    /// The declared type at 2.0 is the base JourneyRefStructure, so constructing that keeps JAXB
    /// from stamping an xsi:type no NeTEx version's vocabulary contains; `nameOfRefClass` then has to
    /// be set explicitly or the store files an unresolved row under "Journey", which is neither the
    /// class the id names nor what the resolver writes back. The version is set for the same reason
    /// the other two are: this stage does not run the store's resolve pass, so a ref it emits has to
    /// arrive complete rather than be completed later.
    static JourneyRefStructure journeyRef(String ref, String version) {
        JourneyRefStructure r = new JourneyRefStructure();
        r.setRef(ref);
        r.setVersion(version);
        r.setNameOfRefClass("ServiceJourney");
        return r;
    }

    // ------------------------------------------------------------------------------------- //

    /// For the station two legs hand over at, make both sides' assignments reference one Quay.
    /// Whichever side owns a quay is copied onto the side that lacks one or holds a different one.
    ///
    /// The OTP this pipeline feeds does not need this to create the transfer: it maps each end to a
    /// `TripTransferPoint(trip, stopPositionInPattern)`, which holds no quay, no stop and no station,
    /// and it never compares the two ends. The exposure is at routing time — Raptor matches the two
    /// ends independently, so when they are different StopLocations the boarding costs a transfer
    /// round and the walk it took is hidden from the itinerary, precisely because the constraint is
    /// stay-seated.
    ///
    /// Mutates the shared assignment beans and records them, so the write phase re-emits exactly the
    /// ones that moved. Pairwise and last-writer-wins: one assignment is held per stop point and
    /// rewritten per link, so a stop point whose partners hold different quays keeps only the last
    /// one.
    public static void shareQuay(String fromRef, String toRef,
            Map<String, PassengerStopAssignment> psaBySsp,
            Map<String, PassengerStopAssignment> modified, Counters counters) {
        PassengerStopAssignment a = psaBySsp.get(fromRef);
        PassengerStopAssignment b = psaBySsp.get(toRef);
        if (a == null || b == null) {
            counters.noAssignmentAtHandover++;
            return;
        }
        String qa = quayId(a);
        String qb = quayId(b);
        if (qa != null && !qa.isEmpty() && !qa.equals(qb)) {
            setQuayRef(b, qa);
            modified.put(b.getId(), b);
            counters.quaysShared++;
        } else if (qb != null && !qb.isEmpty() && !qb.equals(qa)) {
            setQuayRef(a, qb);
            modified.put(a.getId(), a);
            counters.quaysShared++;
        }
    }

    /// The Quay an assignment resolves to. The union holds one object but the binding splits it, so
    /// the ref side is preferred exactly when the union would hold a QuayRef.
    static String quayId(PassengerStopAssignment psa) {
        JAXBElement<? extends QuayRefStructure> qr = psa.getQuayRef();
        if (qr != null) return qr.getValue().getRef();
        return psa.getQuay() != null ? psa.getQuay().getId() : null;
    }

    /// Assigning a QuayRef replaces the whole union, so the embedded Quay arm is cleared too.
    private static void setQuayRef(PassengerStopAssignment psa, String quayId) {
        QuayRefStructure qr = new QuayRefStructure();
        qr.setRef(quayId);
        psa.setQuayRef(FACTORY.createQuayRef(qr));
        psa.setQuay(null);
    }

    // ------------------------------------------------------------------------------------- //

    /// The postcondition: every link's two point refs are on the two journeys' final patterns, and no
    /// two links share an `(id, version)`.
    ///
    /// The consumer drops an interchange whose point refs are not on the referenced journeys'
    /// patterns, and the store keeps only the last writer of a colliding key. Neither failure says
    /// anything when it happens, so both are counted here, before the write, against the patterns as
    /// they will be exported — which is why this runs on the cut refs rather than on what the scan
    /// read.
    ///
    /// Returns the links that passed. A link that fails is dropped rather than repaired: it means the
    /// tiling produced an anchor neither leg serves, which is a defect in this stage.
    public static List<ServiceJourneyInterchange> validate(List<ServiceJourneyInterchange> links,
            Map<String, Set<String>> patternStopsByJourney, Counters counters) {
        List<ServiceJourneyInterchange> kept = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ServiceJourneyInterchange x : links) {
            if (!seen.add(x.getId() + ' ' + x.getVersion())) {
                counters.idCollisions++;
                continue;
            }
            if (!onPattern(patternStopsByJourney, x.getFromJourneyRef(), x.getFromPointRef())
                    || !onPattern(patternStopsByJourney, x.getToJourneyRef(), x.getToPointRef())) {
                counters.offPattern++;
                continue;
            }
            kept.add(x);
        }
        return kept;
    }

    private static boolean onPattern(Map<String, Set<String>> byJourney, JourneyRefStructure journey,
            ScheduledStopPointRefStructure point) {
        if (journey == null || point == null) return false;
        Set<String> stops = byJourney.get(journey.getRef());
        // A journey this stage never touched keeps whatever pattern it had; the check applies to the
        // ones it cut, which are exactly the ones in the map.
        return stops == null || stops.contains(point.getRef());
    }

    // ------------------------------------------------------------------------------------- //

    /// The counters, all of them printed even at zero.
    public static final class Counters {
        /// What the interchange graph produced, before any of the counters below.
        public final transformers.xb.XbStitch.Counters stitch =
                new transformers.xb.XbStitch.Counters();
        public long groups;
        public long groupsRefused;
        /// Groups whose tiling was fine but a leg could not be cut, so the whole group was abandoned
        /// and every mutation it had staged thrown away.
        public long groupsAbandoned;
        /// Groups abandoned because another group had already truncated or dropped one of their
        /// journeys. Only a stitched vehicle can be in two groups, so this is zero without
        /// [XbStitch].
        public long groupsAbandonedConflict;
        /// Publications of a stitched vehicle that lie outside the leg's kept range and were left
        /// exactly as published.
        public long partsLeftWhole;
        /// Publications the leg touches at exactly one stop — its handover — and which are
        /// therefore kept whole rather than truncated to a single-stop journey.
        public long partsKeptWhole;
        /// Publications of a stitched vehicle whose truncation failed, taking their group with
        /// them. The population behind `abandoned mid-cut` when it is not zero.
        public long partsUncuttable;
        public long legs;
        public long variants;
        public long dropped;
        /// Links whose two journeys are different transport modes, so the handover is a real change
        /// of vehicle and StaySeated is false.
        public long modeChangeAtJunction;
        /// One part of a stitched vehicle that does not call at a junction, where another part of
        /// the same vehicle does. Not a refusal — see [XbLinks.End#ofStitched] — but a large number
        /// here would mean the stitch is spanning junctions it should not.
        public long partNotAtJunction;
        /// Members left uncoupled: not a leg, not a variant, not covered. See XbGroups.tile step 6.
        public long leftAlone;
        /// Of those, how many still call in another country — the "Italian journey in Switzerland"
        /// population. A non-zero count here is work this stage did not manage to do.
        public long leftAloneCrossing;
        /// Station -> how many parallel cuts it blocked. See XbCouple.reportBlockingStations.
        public final java.util.Map<Integer, Long> blockedBy = new java.util.HashMap<>();
        /// How far each uncoupled publication got. Indexed by XbGroups.PARALLEL_REFUSAL.
        public final long[] parallelRefusals = new long[transformers.xb.XbGroups.PARALLEL_REFUSAL.length];
        /// The same, restricted to publications calling where the station alias is blind.
        public final long[] parallelRefusalsBlind = new long[transformers.xb.XbGroups.PARALLEL_REFUSAL.length];
        /// The same again, restricted to blind spots next to a copy-sized id space.
        public final long[] parallelRefusalsNarrow = new long[transformers.xb.XbGroups.PARALLEL_REFUSAL.length];
        /// A few worked examples of the route misses. See XbCouple.reportRouteMisses.
        public final java.util.List<transformers.xb.XbGroups.RouteMiss> routeMisses = new java.util.ArrayList<>();
        public long truncated;
        public long patternsForked;
        public long patternsMisaligned;
        /// Variants attached whose call list between the leg's endpoints is not the leg's own — a
        /// call skipped, a call added, or both.
        public long variantsInexact;
        /// Variants that could not be cut. They are left as published and their leg still links.
        public long variantsUncuttable;
        /// Variants that were cut and then met no counterpart at the handover, so their truncation
        /// was withdrawn. Not a defect: this stage declines to shorten a publication it could not
        /// give a link to. A leg in that position is not counted here and is not withdrawn — it
        /// keeps its section because the tiling assigned it, not because a link exists.
        public long variantsUnlinked;
        public long links;
        /// Quay refs copied by [#shareQuay], not links that end up sharing a quay: the copies are
        /// last-writer-wins per stop point, so every copy but the last at one is overwritten before
        /// the store is written.
        public long quaysShared;
        public long noMeetingAtJunction;
        public long noRefAtHandover;
        public long noAssignmentAtHandover;
        public long offPattern;
        public long idCollisions;
        public long prunedExisting;

        public void report(String tag) {
            Log.info("%s groups %d (refused %d, abandoned mid-cut %d, abandoned on a journey another "
                    + "group already wrote %d), legs %d (+%d variants), "
                    + "legs dropped as redundant %d, members left uncoupled %d (%d still crossing a border)",
                    tag, groups, groupsRefused, groupsAbandoned, groupsAbandonedConflict, legs,
                    variants, dropped, leftAlone, leftAloneCrossing);
            Log.info("%s of the stitched vehicles' publications: %d lay outside their leg, %d are "
                    + "touched at one stop only -- both kept as published -- and %d could not be "
                    + "cut, which is what abandons a group",
                    tag, partsLeftWhole, partsKeptWhole, partsUncuttable);
            Log.info("%s truncated %d journeys, forked %d shared patterns, %d left misaligned",
                    tag, truncated, patternsForked, patternsMisaligned);
            Log.info("%s of the variants: %d carry a different call list from their leg, %d could "
                    + "not be cut, %d met no counterpart so their cut was withdrawn",
                    tag, variantsInexact, variantsUncuttable, variantsUnlinked);
            Log.info("%s links %d (%d NOT stay-seated: the mode changes at the handover), quay refs "
                    + "COPIED %d (copies made, some overwritten by a later link at the same stop "
                    + "point -- NOT links that end up sharing); refused: no meeting %d, no ref at "
                    + "handover %d, no assignment %d, off pattern %d, id collisions %d; %d stitched "
                    + "parts do not reach a junction their other part does",
                    tag, links, modeChangeAtJunction, quaysShared, noMeetingAtJunction,
                    noRefAtHandover, noAssignmentAtHandover, offPattern, idCollisions,
                    partNotAtJunction);
            Log.info("%s pruned %d existing interchanges referencing a dropped journey",
                    tag, prunedExisting);
        }
    }
}
