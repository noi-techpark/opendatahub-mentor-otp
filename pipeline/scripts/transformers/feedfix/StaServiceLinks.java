package transformers.feedfix;

// The STA export publishes its ServiceLink geometry and then names none of it. Every link carries a
// gml:LineString and both point refs, and no journey pattern points at one: the file holds no
// OnwardServiceLinkRef, no linksInSequence and no ServiceLinkRef at all. A link is reachable only
// through the pattern that names it, so the geometry is unreadable and a consumer falls back to a
// straight line between each pair of stops. The same exporter's Austrian Verbund feeds publish
// linksInSequence normally, so this is one publisher's export settings rather than the vendor's
// shape.
//
// The link's own FromPointRef/ToPointRef name the two ScheduledStopPoints of the hop it spans,
// which is the pattern's own key for that hop, so the wiring is a join and needs nothing inferred.
//
// Two constraints hold this pass where the Makefile puts it:
//
//  - it must run after the Mentz line-version resolve. That pass peels orphans and lists
//    ServiceLink among them, sparing the feed's own orphans because it only removes an object that
//    HAD a referrer. A link wired before it acquires one, and is then peeled away with the first
//    pattern whose journeys that pass drops;
//  - the emitted ref must carry the link's version, which is why it is built through
//    NetexUtils.getRef. Every STA link is version="any"; a versionless ref resolves to no edge, and
//    the export selects a shard's links by walking pattern -> link edges, so the pattern would ship
//    with no geometry and no warning.
//
// Coordinates are left as published. These posLists are lon,lat where NeTEx wants lat,lon, and
// toolkit.transform.feedfix.AxisOrder settles that in the EPIP conversion, per link, against the
// stop the link starts at.

import jakarta.xml.bind.JAXBElement;
import noi.netex.model.PointInLinkSequence_VersionedChildStructure;
import noi.netex.model.ScheduledStopPointRefStructure;
import noi.netex.model.ServiceJourneyPattern;
import noi.netex.model.ServiceLink;
import noi.netex.model.ServiceLinkRefStructure;
import noi.netex.model.StopPointInJourneyPattern_VersionedChildStructure;
import toolkit.model.NetexUtils;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.util.Log;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

public final class StaServiceLinks {

    private StaServiceLinks() {}

    /// Give every hop of every pattern the OnwardServiceLinkRef naming the ServiceLink that spans
    /// it, over `dbRead` at `txn`, yielding each pattern the caller must write back.
    ///
    /// The link index is built on the first pump and the pattern cursor opens only once it is
    /// complete, so the two never read the store at the same time.
    public static Iterator<Object> wireOnwardLinks(Store dbRead, Txn txn) {
        return new Iterator<>() {
            Map<String, ServiceLink> byHop;
            Iterator<Object> patterns;
            long named = 0;
            long hops = 0;
            Object next;
            boolean summarised = false;

            @Override
            public boolean hasNext() {
                if (next != null) return true;
                if (byHop == null) {
                    byHop = index(dbRead, txn);
                    patterns = dbRead.iterOnlyObjects(txn, ServiceJourneyPattern.class).iterator();
                }
                while (patterns.hasNext()) {
                    Object o = patterns.next();
                    if (!(o instanceof ServiceJourneyPattern pat)) continue;
                    int n = wire(pat, byHop);
                    if (n > 0) {
                        named++;
                        hops += n;
                        next = pat;
                        return true;
                    }
                }
                if (!summarised) {
                    summarised = true;
                    Log.info("sta-service-links: named a ServiceLink on %d hops of %d journey "
                            + "patterns, from %d distinct stop-point pairs", hops, named,
                            byHop.size());
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

    /// Every ServiceLink by the stop-point pair it spans. Two links can claim one pair, so the
    /// smaller id wins and the index does not depend on cursor order.
    static Map<String, ServiceLink> index(Store dbRead, Txn txn) {
        Map<String, ServiceLink> byHop = new HashMap<>();
        for (Object o : dbRead.iterOnlyObjects(txn, ServiceLink.class)) {
            if (!(o instanceof ServiceLink sl) || sl.getId() == null) continue;
            ScheduledStopPointRefStructure from = sl.getFromPointRef();
            ScheduledStopPointRefStructure to = sl.getToPointRef();
            if (from == null || to == null || from.getRef() == null || to.getRef() == null) continue;
            byHop.merge(key(from.getRef(), to.getRef()), sl,
                    (a, b) -> a.getId().compareTo(b.getId()) <= 0 ? a : b);
        }
        return byHop;
    }

    /// The hops of `pat` this pass named. A point that already carries an onward ref keeps it: the
    /// repair supplies what the feed omitted and does not restate what it published.
    static int wire(ServiceJourneyPattern pat, Map<String, ServiceLink> byHop) {
        if (pat.getPointsInSequence() == null) return 0;
        List<PointInLinkSequence_VersionedChildStructure> pts = pat
                .getPointsInSequence()
                .getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern();
        int wired = 0;
        // The last point leaves no hop, so the refs come out one short of the points -- which is
        // the count a consumer checks the container against.
        for (int i = 0; i < pts.size() - 1; i++) {
            if (!(pts.get(i) instanceof StopPointInJourneyPattern_VersionedChildStructure from)
                    || !(pts.get(i + 1)
                            instanceof StopPointInJourneyPattern_VersionedChildStructure to)) {
                continue;
            }
            if (from.getOnwardServiceLinkRef() != null) continue;
            String a = sspRef(from);
            String b = sspRef(to);
            if (a == null || b == null) continue;
            ServiceLink sl = byHop.get(key(a, b));
            if (sl == null) continue;
            from.setOnwardServiceLinkRef(NetexUtils.getRef(sl, ServiceLinkRefStructure.class));
            wired++;
        }
        return wired;
    }

    private static String key(String from, String to) {
        return from + "\n" + to;
    }

    private static String sspRef(StopPointInJourneyPattern_VersionedChildStructure sp) {
        JAXBElement<? extends ScheduledStopPointRefStructure> el = sp.getScheduledStopPointRef();
        return el == null || el.getValue() == null ? null : el.getValue().getRef();
    }
}
