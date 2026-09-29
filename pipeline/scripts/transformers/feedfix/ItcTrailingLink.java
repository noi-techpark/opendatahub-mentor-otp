package transformers.feedfix;

// GTT's exporter emits one ServiceLink per stop point including the terminus, so a pattern carries
// `points` links where a link joins two points and there are only `points - 1` gaps. The surplus is
// always the same shape: the last ServiceLinkInJourneyPattern repeats the previous one's
// ServiceLinkRef, and the last StopPointInJourneyPattern repeats it again in its
// OnwardServiceLinkRef, so the defect is published on both sides of the pattern.
//
// Measured on the raw Piemonte GTT feed, before anything in this pipeline touches it: 1,345 of
// 1,345 patterns have links == points with a trailing duplicate, and none have the correct
// points - 1. In the merged export the same shape holds -- 980 of 981 IT:ITC1 patterns in
// g00-0001.xml, 933 of 936 in g01-0001.xml -- and OTP names 7,476 of them in
// WrongNumberOfServiceLinks, always with "should have exactly points - 1".
//
// The defect is recognised by shape, not by count: the test is `links == points` and the last two
// refs being equal. A count-only rule would "repair" a pattern that has the right number of the
// wrong links into a different wrong pattern.
//
// The repair is entirely intra-object -- a pattern's own linksInSequence and its own last point --
// and reads nothing else, so it needs no merged corpus. It runs before the coupling:
// XbTruncate.sliceLinks refuses a pattern whose link count is already wrong and drops the container
// outright, so a truncated IT:ITC1 pattern would lose its geometry on top of the defect itself.
// Repaired first, those patterns are `points - 1` and slice correctly.
//
// EpipJourneys filters pointsInSequence down to its StopPointInJourneyPattern subset in phase B, so
// a pattern carrying TimingPointInJourneyPattern entries would count differently before and after
// EPIP. The GTT feed carries none -- 255,406 StopPointInJourneyPattern and no other kind -- so the
// pre-EPIP count this pass sees is the count OTP will see.

import noi.netex.model.LinkInLinkSequence_VersionedChildStructure;
import noi.netex.model.LinksInJourneyPattern_RelStructure;
import noi.netex.model.PointInLinkSequence_VersionedChildStructure;
import noi.netex.model.ServiceJourneyPattern;
import noi.netex.model.ServiceLinkInJourneyPattern_VersionedChildStructure;
import noi.netex.model.StopPointInJourneyPattern_VersionedChildStructure;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.util.Log;

import java.math.BigInteger;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

public final class ItcTrailingLink {

    private ItcTrailingLink() {}

    /// Drop the duplicated trailing link from every pattern that has one, over `dbRead` at `txn`,
    /// yielding each pattern the caller must write back.
    public static Iterator<Object> dropTrailingDuplicates(Store dbRead, Txn txn) {
        return new Iterator<>() {
            final Iterator<Object> patterns = dbRead
                    .iterOnlyObjects(txn, ServiceJourneyPattern.class)
                    .iterator();
            long repaired = 0;
            Object next;
            boolean summarised = false;

            @Override
            public boolean hasNext() {
                if (next != null) return true;
                while (patterns.hasNext()) {
                    Object o = patterns.next();
                    if (o instanceof ServiceJourneyPattern pat && repair(pat)) {
                        repaired++;
                        next = pat;
                        return true;
                    }
                }
                if (!summarised) {
                    summarised = true;
                    Log.info("itc-trailing-link: dropped a duplicated trailing ServiceLink from %d "
                            + "journey patterns", repaired);
                }
                return false;
            }

            @Override
            public Object next() {
                if (!hasNext()) throw new NoSuchElementException();
                Object o = next;
                next = null;
                return o;
            }
        };
    }

    /// True when this pattern carried the defect and was repaired.
    static boolean repair(ServiceJourneyPattern pat) {
        if (pat.getPointsInSequence() == null || pat.getLinksInSequence() == null) {
            return false;
        }
        List<PointInLinkSequence_VersionedChildStructure> pts = pat
                .getPointsInSequence()
                .getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern();
        LinksInJourneyPattern_RelStructure seq = pat.getLinksInSequence();
        List<LinkInLinkSequence_VersionedChildStructure> links =
                seq.getServiceLinkInJourneyPatternOrTimingLinkInJourneyPattern();
        if (links.size() != pts.size() || links.size() < 2 || pts.isEmpty()) {
            return false;
        }
        String last = serviceLinkRef(links.get(links.size() - 1));
        if (last == null || !last.equals(serviceLinkRef(links.get(links.size() - 2)))) {
            return false;
        }

        links.remove(links.size() - 1);
        // The terminus repeats the same ref in its own onward link. Clearing it keeps the pattern's
        // two views of itself saying the same thing -- and a consumer that reads the onward refs
        // instead of the container would otherwise still see the surplus.
        if (pts.get(pts.size() - 1)
                instanceof StopPointInJourneyPattern_VersionedChildStructure sp) {
            sp.setOnwardServiceLinkRef(null);
        }
        // `order` is a position and the drop moved nothing before it, but renumbering keeps the
        // container self-consistent for anything that sorts by it rather than by list order.
        for (int i = 0; i < links.size(); i++) {
            links.get(i).setOrder(BigInteger.valueOf(i + 1L));
        }
        return true;
    }

    private static String serviceLinkRef(LinkInLinkSequence_VersionedChildStructure link) {
        return link instanceof ServiceLinkInJourneyPattern_VersionedChildStructure sl
                && sl.getServiceLinkRef() != null ? sl.getServiceLinkRef().getRef() : null;
    }
}
