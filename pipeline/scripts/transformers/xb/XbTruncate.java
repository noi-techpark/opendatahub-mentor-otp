package transformers.xb;

// Cutting a publication back to the run it owns, and leaving it CONSISTENT with a pattern of its
// own.
//
// The consumer's first check is that a journey's passing-time count equals its pattern's stop-point
// count, and its action on failure is to SKIP THE JOURNEY ENTIRELY, taking every interchange that
// references it with it. Slicing the passing times without the pattern therefore does not produce a
// shorter trip; it produces no trip.

import jakarta.xml.bind.JAXBElement;
import noi.netex.model.JourneyPatternRefStructure;
import noi.netex.model.JourneyPattern_VersionStructure;
import noi.netex.model.LinkInLinkSequence_VersionedChildStructure;
import noi.netex.model.LinksInJourneyPattern_RelStructure;
import noi.netex.model.PointInJourneyPatternRefStructure;
import noi.netex.model.PointInLinkSequence_VersionedChildStructure;
import noi.netex.model.ScheduledStopPointRefStructure;
import noi.netex.model.ServiceJourney;
import noi.netex.model.StopPointInJourneyPattern_VersionedChildStructure;
import noi.netex.model.TimetabledPassingTime;
import toolkit.store.Store;
import toolkit.store.Txn;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class XbTruncate {

    private XbTruncate() {}

    /// One publication, cut.
    ///
    /// @param sj         the journey bean, passing times already sliced. Re-emit it.
    /// @param pattern    the pattern bean when this cut changed it — sliced in place under its own
    ///                   id, or forked under a new one. Null when the pattern was left alone.
    /// @param oldPattern the pattern id the journey referenced BEFORE the cut, so the caller can
    ///                   keep the usage count honest.
    /// @param refs       the surviving stops' ScheduledStopPoint refs, aligned 1:1 with the kept
    ///                   passing times. This is what anchors a link, and it is read here because
    ///                   here is where the pattern is already decoded.
    /// @param forked     the pattern was shared and this journey was given a re-idded copy.
    /// @param misaligned journey and pattern could not be reconciled at all; the pattern is
    ///                   untouched and the journey is left as published.
    public record Cut(ServiceJourney sj, JourneyPattern_VersionStructure pattern, String oldPattern,
            List<String> refs, boolean forked, boolean misaligned) {}

    /// Cut `sj` to passing-time positions `[from..to]`.
    ///
    /// `shared` says whether any other journey uses this pattern; the caller knows from the pass-1
    /// usage count. A fresh pattern bean is loaded per call, so the one this returns is private to
    /// this journey and forking it is a re-id rather than a copy.
    ///
    /// Returns null when the journey cannot be read at all. Positions are NOT clamped: an
    /// out-of-range one is a broken boundary and `subList` surfaces it rather than mis-truncating.
    public static Cut cut(Store db, Txn txn, ServiceJourney sj, int from, int to, boolean shared) {
        if (sj.getPassingTimes() == null) return null;
        JAXBElement<? extends JourneyPatternRefStructure> pref = sj.getJourneyPatternRef();
        if (pref == null) return null;
        String oldPattern = pref.getValue().getRef();
        Object patObj = db.loadObjectByReference(txn, pref.getValue(), /*missingOk=*/true);
        if (!(patObj instanceof JourneyPattern_VersionStructure pat)) return null;

        List<TimetabledPassingTime> tt = sj.getPassingTimes().getTimetabledPassingTime();
        List<TimetabledPassingTime> keep = new ArrayList<>(tt.subList(from, to + 1));

        // RECONCILE BEFORE MUTATING. Applying the slice and only then discovering that the pattern
        // cannot be reconciled would leave the journey with fewer passing times than its pattern
        // has points, which is precisely the state the consumer skips the journey for. An
        // irreconcilable pair is left ALONE, and "alone" has to include the passing times.
        var seq = pat.getPointsInSequence();
        List<PointInLinkSequence_VersionedChildStructure> pts = seq == null ? null
                : seq.getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern();
        List<Integer> kept = pts == null || pts.isEmpty() ? null : keptPointIndexes(keep, pts);
        if (kept == null) {
            return new Cut(sj, null, oldPattern, List.of(), false, true);
        }

        // JAXB list fields have no setter, so the slice is applied in place.
        tt.clear();
        tt.addAll(keep);
        // Before the points move: sliceLinks reads their ORIGINAL positions.
        sliceLinks(pat, pts.size(), kept);
        List<PointInLinkSequence_VersionedChildStructure> keepPts = new ArrayList<>(kept.size());
        for (int i : kept) keepPts.add(pts.get(i));
        List<String> refs = refsFromPoints(keepPts);
        pts.clear();
        pts.addAll(keepPts);
        boolean forked = false;
        if (shared) {
            forkPattern(sj, pat, keepPts);
            forked = true;
        }
        renumber(pat, keepPts, tt);
        clearOnwardLinks(keepPts.get(keepPts.size() - 1));
        return new Cut(sj, pat, oldPattern, refs, forked, false);
    }

    /// The positions in `pts` of the points the truncated journey still calls at, in journey order —
    /// or null when journey and pattern cannot be reconciled.
    ///
    /// The points are chosen BY ID, never by slicing the point list at the passing times' indices. A
    /// pattern is often a SUPERSET of its journey — the journey calls at some of its points, not all
    /// — and there index arithmetic addresses the wrong stops. The StopPointInJourneyPatternRef each
    /// passing time carries is the only correspondence that exists, and selecting by it makes the
    /// aligned case fall out as the special case where the answer is a contiguous run.
    ///
    /// Null on a ref that resolves into no point of this pattern, and null when two passing times
    /// name the SAME point: a journey looping over one point cannot be given two distinct ids by a
    /// re-id, so it is refused rather than silently collapsed.
    static List<Integer> keptPointIndexes(List<TimetabledPassingTime> tt,
            List<PointInLinkSequence_VersionedChildStructure> pts) {
        Map<String, Integer> byId = new HashMap<>();
        for (int i = 0; i < pts.size(); i++) {
            String id = pts.get(i).getId();
            if (id != null) byId.put(id, i);
        }
        List<Integer> kept = new ArrayList<>(tt.size());
        Set<Integer> seen = new HashSet<>();
        for (TimetabledPassingTime t : tt) {
            PointInJourneyPatternRefStructure ref = pointRef(t);
            Integer i = ref == null ? null : byId.get(ref.getRef());
            if (i == null || !seen.add(i)) return null;
            kept.add(i);
        }
        return kept.isEmpty() ? null : kept;
    }

    /// The stop refs of the kept points, in order — aligned 1:1 with the kept passing times, because
    /// the points were selected by walking those passing times. Nulls where a point carries none, so
    /// positions stay meaningful.
    private static List<String> refsFromPoints(List<PointInLinkSequence_VersionedChildStructure> pts) {
        List<String> out = new ArrayList<>(pts.size());
        for (PointInLinkSequence_VersionedChildStructure p : pts) {
            String ref = null;
            if (p instanceof StopPointInJourneyPattern_VersionedChildStructure sp
                    && sp.getScheduledStopPointRef() != null) {
                ScheduledStopPointRefStructure r = sp.getScheduledStopPointRef().getValue();
                ref = r == null ? null : r.getRef();
            }
            out.add(ref);
        }
        return out;
    }

    /// Slice the pattern's `linksInSequence` to the kept points, or drop the container.
    ///
    /// That container is the one the consumer counts, against points - 1; the per-point
    /// OnwardServiceLinkRef is a different field and it does not read it. Link j joins point j to
    /// point j+1, so k contiguous points keep k-1 links. When the kept points are NOT contiguous no
    /// original link spans the gap and none can be synthesised, so the container is dropped whole: a
    /// null `linksInSequence` reads as "no geometry", a wrong-length one is a logged defect and
    /// still no geometry.
    static void sliceLinks(JourneyPattern_VersionStructure pat, int nPoints, List<Integer> kept) {
        LinksInJourneyPattern_RelStructure seq = pat.getLinksInSequence();
        if (seq == null) return;
        List<LinkInLinkSequence_VersionedChildStructure> links =
                seq.getServiceLinkInJourneyPatternOrTimingLinkInJourneyPattern();
        boolean contiguous = true;
        for (int i = 1; i < kept.size(); i++) {
            if (kept.get(i) != kept.get(i - 1) + 1) {
                contiguous = false;
                break;
            }
        }
        // A pattern whose link count was already wrong cannot be sliced into a right one.
        if (!contiguous || links.size() != nPoints - 1) {
            pat.setLinksInSequence(null);
            return;
        }
        List<LinkInLinkSequence_VersionedChildStructure> keepLinks =
                new ArrayList<>(links.subList(kept.get(0), kept.get(kept.size() - 1)));
        links.clear();
        links.addAll(keepLinks);
    }

    /// Give this journey its own copy of a SHARED pattern; slicing a shared one would truncate every
    /// other journey on it. No deep copy happens and none is needed: the bean was loaded fresh for
    /// this journey, so all that is shared is the ID — which is exactly what makes writing it back
    /// destructive. So the fork is a re-id, of the pattern and of every embedded child that survived
    /// the slice, with the journey's passing times re-pointed onto the new point ids in the same
    /// pass. The children have to be re-idded too: the store's embedding index is keyed by the
    /// embedded object's id, so two points under one id would shadow each other.
    static void forkPattern(ServiceJourney sj, JourneyPattern_VersionStructure pat,
            List<PointInLinkSequence_VersionedChildStructure> pts) {
        String newId = pat.getId() + ":xb:" + XbIds.slot(sj.getId());
        pat.setId(newId);
        Map<String, String> renamed = new HashMap<>();
        for (int i = 0; i < pts.size(); i++) {
            PointInLinkSequence_VersionedChildStructure p = pts.get(i);
            String pointId = newId + "-" + (i + 1);
            if (p.getId() != null) renamed.put(p.getId(), pointId);
            p.setId(pointId);
        }
        LinksInJourneyPattern_RelStructure lseq = pat.getLinksInSequence();
        if (lseq != null) {
            List<LinkInLinkSequence_VersionedChildStructure> links =
                    lseq.getServiceLinkInJourneyPatternOrTimingLinkInJourneyPattern();
            for (int i = 0; i < links.size(); i++) {
                links.get(i).setId(newId + "-link-" + (i + 1));
            }
        }
        for (TimetabledPassingTime t : sj.getPassingTimes().getTimetabledPassingTime()) {
            PointInJourneyPatternRefStructure ref = pointRef(t);
            if (ref == null) continue;
            String to = renamed.get(ref.getRef());
            if (to != null) ref.setRef(to);
        }
        // The ref is mutated in place rather than rebuilt: the JAXBElement's name carries the
        // concrete ref type (ServiceJourneyPatternRef vs JourneyPatternRef) and a new wrapper would
        // have to reconstruct it.
        JAXBElement<? extends JourneyPatternRefStructure> pref = sj.getJourneyPatternRef();
        if (pref != null && pref.getValue() != null) pref.getValue().setRef(newId);
    }

    /// Renumber a sliced pattern and its journey 1..n — points, links and the journey's refs.
    /// `order` is a position and the slice moved every position.
    static void renumber(JourneyPattern_VersionStructure pat,
            List<PointInLinkSequence_VersionedChildStructure> pts, List<TimetabledPassingTime> tt) {
        for (int i = 0; i < pts.size(); i++) {
            pts.get(i).setOrder(BigInteger.valueOf(i + 1L));
        }
        LinksInJourneyPattern_RelStructure lseq = pat.getLinksInSequence();
        if (lseq != null) {
            List<LinkInLinkSequence_VersionedChildStructure> links =
                    lseq.getServiceLinkInJourneyPatternOrTimingLinkInJourneyPattern();
            for (int i = 0; i < links.size(); i++) {
                links.get(i).setOrder(BigInteger.valueOf(i + 1L));
            }
        }
        for (int i = 0; i < tt.size(); i++) {
            PointInJourneyPatternRefStructure ref = pointRef(tt.get(i));
            if (ref != null) ref.setOrder(BigInteger.valueOf(i + 1L));
        }
    }

    /// The last point of a sliced pattern has no next point, so an onward link left on it runs off
    /// the end — n links for n points where the consumer counts on n-1.
    private static void clearOnwardLinks(PointInLinkSequence_VersionedChildStructure last) {
        if (last instanceof StopPointInJourneyPattern_VersionedChildStructure sp) {
            sp.setOnwardServiceLinkRef(null);
            sp.setOnwardTimingLinkRef(null);
        }
    }

    /// A passing time's point-in-pattern ref, unwrapped from the JAXBElement the union binds it
    /// behind; null when the passing time carries none.
    static PointInJourneyPatternRefStructure pointRef(TimetabledPassingTime t) {
        JAXBElement<? extends PointInJourneyPatternRefStructure> e = t.getPointInJourneyPatternRef();
        return e == null ? null : e.getValue();
    }

    /// The stop refs a journey's pattern gives its passing times, without cutting anything — what a
    /// leg that needs no truncation is anchored on.
    public static List<String> refs(Store db, Txn txn, ServiceJourney sj) {
        return XbScan.stopRefs(sj, sj.getPassingTimes().getTimetabledPassingTime(),
                new XbScan.PatternCache(db, txn));
    }
}
