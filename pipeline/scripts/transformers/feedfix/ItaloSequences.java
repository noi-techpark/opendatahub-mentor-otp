package transformers.feedfix;

// Italo emits its repeated children in lexical order of the child id rather than in `order`: a
// pattern's pointsInSequence arrives `:1, :10, :11, :12, :2 … :9`, and the journey's passingTimes in
// the same shuffle. The `order` attributes themselves are correct, so the sequence the feed means is
// recoverable -- and `order` is the only signal that carries it, which is why nothing here parses an
// id. The trailing number happens to encode the position in this asset; a later one is free to
// change the id grammar while keeping `order` right.
//
// Measured on asset 1814124, export of 2026-09-25: 1,364 patterns over 10,987 points, of which 397
// have ten points or more and are therefore mis-sequenced. A nine-point pattern's `:1`..`:9` sort
// the same either way. The highest `order` is 26.
//
// Not cosmetic. XbScan.stopRefs returns a journey's stop refs in passing-time LIST order, and
// XbScan.node reads `i == tt.size() - 1` as the terminus and takes that element's ARRIVAL time;
// under the feed's order the last element of a twelve-stop journey is `:9`, six stops short of the
// end. XbTruncate, SplitJourneys and DedupDefinitionDays read the same lists positionally. OTP
// re-sorts both sides for itself, so what this repairs is the corridor and the export, not the graph.
//
// TimetabledPassingTime has no `order` of its own -- it names a point through its
// PointInJourneyPatternRef -- so the journeys cannot be sequenced without the patterns. Phase one
// builds that index once per pattern; phase two joins against it. A pattern whose `order` attributes
// cannot sequence it refuses, and every journey on it refuses with it, because a partial sort would
// invent a sequence rather than recover one.
//
// Sorting is intra-object and touches no id, version or reference, so a re-emitted object overwrites
// its own cloned row and the two id spaces stay exactly as the feed issued them.

import noi.netex.model.DatedServiceJourney;
import noi.netex.model.PointInLinkSequence_VersionedChildStructure;
import noi.netex.model.ServiceJourney;
import noi.netex.model.ServiceJourneyPattern;
import noi.netex.model.TemplateServiceJourney;
import noi.netex.model.TimetabledPassingTime;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.util.Log;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

public final class ItaloSequences {

    private ItaloSequences() {}

    /// Every ServiceJourneyPattern and ServiceJourney whose repeated children the feed shipped out
    /// of `order` sequence, re-sequenced, over `dbRead` at `txn` -- patterns first, then the
    /// journeys that reference them. An object already in sequence is not yielded, so it keeps the
    /// bytes it was cloned with.
    public static Iterator<Object> inOrderSequence(Store dbRead, Txn txn) {
        return new Pass(dbRead, txn);
    }

    /// Phase one streams the patterns, recording each one's point orders as it goes; phase two
    /// streams the journeys and joins against that index. Draining the patterns first is what makes
    /// the index complete before a journey needs it, so the only state held across the two is the
    /// index itself -- a re-sequenced pattern is handed to the caller and forgotten.
    private static final class Pass implements Iterator<Object> {

        private final Store db;
        private final Txn txn;

        /// Per pattern id, its points' `order` by point id. A pattern absent from here cannot
        /// sequence a journey: it refused, or it published two versions of itself.
        private final Map<String, Map<String, BigInteger>> orders = new HashMap<>();
        private final Set<String> twoVersions = new HashSet<>();

        private final Iterator<Object> patterns;
        private Iterator<Object> journeys;
        private Object next;
        private boolean summarised;

        private long patternsSeen, patternsSorted, patternsInSequence;
        private long patternNoPoints, patternNoOrder, patternDuplicateOrder,
                patternDuplicatePointId, patternWithLinks, patternTwoVersions;

        private long journeysSeen, journeysSorted, journeysInSequence;
        private long journeyNoPassingTimes, journeyNoPatternRef, journeyPatternUnusable,
                journeyNoPointRef, journeyUnknownPoint, journeyDuplicatePoint;

        Pass(Store db, Txn txn) {
            this.db = db;
            this.txn = txn;
            warnOnJourneyKindsThisPassDoesNotCover();
            this.patterns = db.iterOnlyObjects(txn, ServiceJourneyPattern.class).iterator();
        }

        @Override
        public boolean hasNext() {
            if (next != null) return true;
            while (patterns.hasNext()) {
                if (patterns.next() instanceof ServiceJourneyPattern pat && resequence(pat)) {
                    next = pat;
                    return true;
                }
            }
            if (journeys == null) {
                journeys = db.iterOnlyObjects(txn, ServiceJourney.class).iterator();
            }
            while (journeys.hasNext()) {
                if (journeys.next() instanceof ServiceJourney sj && resequence(sj)) {
                    next = sj;
                    return true;
                }
            }
            summarise();
            return false;
        }

        @Override
        public Object next() {
            if (!hasNext()) throw new NoSuchElementException();
            Object o = next;
            next = null;
            return o;
        }

        /// True when this pattern's points were out of sequence and have been re-sequenced. Indexes
        /// the pattern's orders either way, because a journey on an already-ordered pattern may
        /// still need sorting.
        private boolean resequence(ServiceJourneyPattern pat) {
            patternsSeen++;
            if (pat.getPointsInSequence() == null) {
                patternNoPoints++;
                return false;
            }
            List<PointInLinkSequence_VersionedChildStructure> pts = pat
                    .getPointsInSequence()
                    .getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern();
            if (pts.size() < 2) return false;
            // A link joins two ADJACENT points and carries its own `order`, so permuting the points
            // without permuting the links would leave the pattern's two views of itself disagreeing.
            // Italo publishes no linksInSequence and no ServiceLink at all, so this arm is measured
            // empty; it is here so a feed that grows them refuses instead of being half-sorted.
            if (pat.getLinksInSequence() != null) {
                patternWithLinks++;
                return false;
            }

            Map<String, BigInteger> byPoint = new HashMap<>();
            Set<BigInteger> seenOrder = new HashSet<>();
            BigInteger[] ord = new BigInteger[pts.size()];
            for (int i = 0; i < pts.size(); i++) {
                PointInLinkSequence_VersionedChildStructure p = pts.get(i);
                BigInteger order = p.getOrder();
                ord[i] = order;
                if (order == null) {
                    patternNoOrder++;
                    return refusePattern(pat, "a point with no `order`");
                }
                if (!seenOrder.add(order)) {
                    patternDuplicateOrder++;
                    return refusePattern(pat, "two points at `order` " + order);
                }
                // A point with no id cannot be named by a passing time, but it still sorts, so it
                // is left out of the index rather than refusing the pattern.
                if (p.getId() != null && byPoint.put(p.getId(), order) != null) {
                    patternDuplicatePointId++;
                    return refusePattern(pat, "two points with id " + p.getId());
                }
            }

            index(pat.getId(), byPoint);

            if (inSequence(ord)) {
                patternsInSequence++;
                return false;
            }
            List<PointInLinkSequence_VersionedChildStructure> sorted = new ArrayList<>(pts);
            sorted.sort(Comparator.comparing(PointInLinkSequence_VersionedChildStructure::getOrder));
            pts.clear();
            pts.addAll(sorted);
            patternsSorted++;
            return true;
        }

        /// True when this journey's passing times were out of sequence and have been re-sequenced.
        private boolean resequence(ServiceJourney sj) {
            journeysSeen++;
            if (sj.getPassingTimes() == null) {
                journeyNoPassingTimes++;
                return false;
            }
            List<TimetabledPassingTime> tt = sj.getPassingTimes().getTimetabledPassingTime();
            if (tt.size() < 2) return false;
            if (sj.getJourneyPatternRef() == null || sj.getJourneyPatternRef().getValue() == null) {
                journeyNoPatternRef++;
                return false;
            }
            // Italo publishes ServiceJourneyPatternRef, which substitutes into this same slot.
            Map<String, BigInteger> byPoint =
                    orders.get(sj.getJourneyPatternRef().getValue().getRef());
            if (byPoint == null) {
                journeyPatternUnusable++;
                return false;
            }

            // Positional rather than a map keyed on the passing time: the generated beans do not
            // define equality, and a pass that relied on their identity would change meaning the day
            // the model gained an equals().
            BigInteger[] order = new BigInteger[tt.size()];
            Set<String> seenPoint = new HashSet<>();
            for (int i = 0; i < tt.size(); i++) {
                var ref = tt.get(i).getPointInJourneyPatternRef();
                String pointId = ref == null || ref.getValue() == null ? null : ref.getValue().getRef();
                if (pointId == null) {
                    journeyNoPointRef++;
                    return refuseJourney(sj, "a passing time naming no point in the journey pattern");
                }
                BigInteger o = byPoint.get(pointId);
                if (o == null) {
                    journeyUnknownPoint++;
                    return refuseJourney(sj, "a passing time at point " + pointId
                            + ", which its pattern does not hold");
                }
                if (!seenPoint.add(pointId)) {
                    journeyDuplicatePoint++;
                    return refuseJourney(sj, "two passing times at point " + pointId);
                }
                order[i] = o;
            }

            if (inSequence(order)) {
                journeysInSequence++;
                return false;
            }
            Integer[] perm = new Integer[tt.size()];
            for (int i = 0; i < perm.length; i++) perm[i] = i;
            java.util.Arrays.sort(perm, Comparator.comparing(i -> order[i]));
            List<TimetabledPassingTime> sorted = new ArrayList<>(tt.size());
            for (int i : perm) sorted.add(tt.get(i));
            tt.clear();
            tt.addAll(sorted);
            journeysSorted++;
            return true;
        }

        /// Record a pattern's point orders, unless the pattern published a second version of itself:
        /// two versions may hold different points, so neither can answer for the id and the journeys
        /// refuse rather than picking one. The pattern itself still sorts -- that is intra-object.
        private void index(String id, Map<String, BigInteger> byPoint) {
            if (id == null) return;
            if (twoVersions.contains(id)) return;
            if (orders.put(id, byPoint) != null) {
                orders.remove(id);
                twoVersions.add(id);
                patternTwoVersions++;
            }
        }

        private boolean refusePattern(ServiceJourneyPattern pat, String why) {
            Log.error("italo-sequences: leaving ServiceJourneyPattern %s as the feed shipped it -- %s",
                    pat.getId(), why);
            return false;
        }

        private boolean refuseJourney(ServiceJourney sj, String why) {
            Log.error("italo-sequences: leaving ServiceJourney %s as the feed shipped it -- %s",
                    sj.getId(), why);
            return false;
        }

        /// A journey class this pass does not walk, published by the store it was pointed at. Italo
        /// has neither; a feed that does would come out half-sequenced in silence.
        private void warnOnJourneyKindsThisPassDoesNotCover() {
            for (Class<?> clazz : db.dbNames(txn)) {
                if (clazz == TemplateServiceJourney.class || clazz == DatedServiceJourney.class) {
                    Log.warn("italo-sequences: the store holds %s, which this pass does not "
                            + "re-sequence", clazz.getSimpleName());
                }
            }
        }

        private void summarise() {
            if (summarised) return;
            summarised = true;
            Log.info("italo-sequences: re-sequenced %d of %d ServiceJourneyPatterns and %d of %d "
                            + "ServiceJourneys by `order`; %d patterns and %d journeys already were",
                    patternsSorted, patternsSeen, journeysSorted, journeysSeen,
                    patternsInSequence, journeysInSequence);
            long refused = patternNoPoints + patternNoOrder + patternDuplicateOrder
                    + patternDuplicatePointId + patternWithLinks + patternTwoVersions
                    + journeyNoPassingTimes + journeyNoPatternRef + journeyPatternUnusable
                    + journeyNoPointRef + journeyUnknownPoint + journeyDuplicatePoint;
            if (refused == 0) return;
            Log.info("italo-sequences: left as shipped -- patterns: no points %d, no `order` %d, "
                            + "duplicate `order` %d, duplicate point id %d, linksInSequence %d, "
                            + "two versions %d; journeys: no passing times %d, no pattern ref %d, "
                            + "pattern refused or absent %d, no point ref %d, unknown point %d, "
                            + "duplicate point %d",
                    patternNoPoints, patternNoOrder, patternDuplicateOrder, patternDuplicatePointId,
                    patternWithLinks, patternTwoVersions,
                    journeyNoPassingTimes, journeyNoPatternRef, journeyPatternUnusable,
                    journeyNoPointRef, journeyUnknownPoint, journeyDuplicatePoint);
        }

        /// Non-decreasing. Duplicates cannot reach here on the pattern side -- a repeated `order`
        /// refuses -- nor on the journey side, where a repeated point refuses.
        private static boolean inSequence(BigInteger[] order) {
            for (int i = 1; i < order.length; i++) {
                if (order[i - 1].compareTo(order[i]) > 0) return false;
            }
            return true;
        }
    }
}
