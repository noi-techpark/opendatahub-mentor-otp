package transformers.xb;

// Collapses the ServiceLinks that span a passthrough point into one link per hop, rebuilding
// linksInSequence to one link per surviving hop. The pattern is rewritten in place, and its points
// are left as published, so every other consumer still sees the feed's own view.
//
// OTP builds its StopPattern from the passing times, so on a passthrough pattern it sees
// points-passthrough stops and wants points-passthrough-1 links, while the file correctly supplies
// points-1; the set OTP flags with WrongNumberOfServiceLinks is exactly the passthrough set.
//
// Runs as phase 5 of XbDbToDb, the earliest point at which XbTruncate is already done. The rebuilt
// container is points-passthrough-1 long, and XbTruncate.sliceLinks nulls any container whose
// length is not points-1 -- run before the coupling, every truncated passthrough pattern loses the
// geometry this supplies.

import noi.netex.gml.DirectPositionListType;
import noi.netex.gml.LineStringType;
import noi.netex.model.LinkInLinkSequence_VersionedChildStructure;
import noi.netex.model.LinksInJourneyPattern_RelStructure;
import noi.netex.model.ObjectFactory;
import noi.netex.model.PointInLinkSequence_VersionedChildStructure;
import noi.netex.model.ScheduledStopPointRefStructure;
import noi.netex.model.ServiceJourney;
import noi.netex.model.ServiceJourneyPattern;
import noi.netex.model.ServiceLink;
import noi.netex.model.ServiceLinkInJourneyPattern_VersionedChildStructure;
import noi.netex.model.ServiceLinkRefStructure;
import noi.netex.model.StopPointInJourneyPattern_VersionedChildStructure;
import noi.netex.model.StopUseEnumeration;
import noi.netex.model.TimetabledPassingTime;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.util.Log;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

public final class Passthrough {

    private static final ObjectFactory FACTORY = new ObjectFactory();

    /// The same tolerance OTP applies between a stop and the end of a shape
    /// (`maxStopToShapeSnapDistance`, whose build-config default is 150 m), so a geometry this pass
    /// accepts is one the consumer will also accept.
    private static final double SNAP_METRES = 150.0;

    /// A degree of latitude is ~111.3 km everywhere; a degree of longitude is that times cos(lat).
    private static final double METRES_PER_DEGREE = 111_320.0;

    private Passthrough() {}

    /// Merge the spanning links of every mergeable passthrough pattern, over `dbRead` at `txn`,
    /// yielding the rewritten patterns and the links minted for them. Reads only.
    ///
    /// Yield order: for each changed pattern, the links it created and then the pattern. One live
    /// cursor at a time.
    ///
    /// The order is a requirement. `insertAnyObjects` inserts in yield order; `XbDbToDb` runs this
    /// phase with no resolve after it, so a reference becomes an edge only when its target row is
    /// already in the store; and `EpipDbToZip` builds a pattern's link list from the edge index
    /// alone. A pattern inserted before its own minted link exports without that hop.
    public static Iterator<Object> mergeLinks(Store dbRead, Txn txn) {
        return new Iterator<>() {
            final Set<String> mergeable = new HashSet<>();
            final Deque<Object> pending = new ArrayDeque<>();
            Iterator<Object> patterns;
            long merged = 0;
            long refused = 0;
            long timed = 0;
            boolean gateLoaded = false;
            Object next;
            boolean summarised = false;

            @Override
            public boolean hasNext() {
                if (next != null) return true;
                if (!gateLoaded) {
                    loadGate();
                    gateLoaded = true;
                    patterns = dbRead.iterOnlyObjects(txn, ServiceJourneyPattern.class).iterator();
                }
                while (true) {
                    if (!pending.isEmpty()) {
                        next = pending.poll();
                        return true;
                    }
                    if (!patterns.hasNext()) break;
                    Object o = patterns.next();
                    // merge() queues the minted links and then the pattern, so draining `pending`
                    // is the whole emit path.
                    if (o instanceof ServiceJourneyPattern pat && merge(pat)) merged++;
                }
                if (!summarised) {
                    summarised = true;
                    Log.info("passthrough: merged the spanning ServiceLinks of %d patterns; %d "
                            + "refused (links did not join), %d left alone because their journeys "
                            + "time the passthrough points", merged, refused, timed);
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

            /// Two passes: collect the passthrough points of every pattern that has any, then walk
            /// the journeys and strike out every pattern with a journey that times one of them. A
            /// pattern with no journey at all is not struck out -- no evidence against it, and no
            /// trips to break.
            ///
            /// StopUse=passthrough is a statement about the vehicle, not about the timetable, and
            /// does not imply the journey omits a passing time for that point: the Swiss OPENOV
            /// export times every passthrough point, the Austrian and Italian ones skip them, and
            /// one at:stv pattern family disagrees internally. A journey that times them gives OTP
            /// a StopPattern of all `points` stops, so the file's points-1 links are already right
            /// and merging would hand it too few.
            private void loadGate() {
                Map<String, Set<String>> passthroughPoints = new HashMap<>();
                for (Object o : dbRead.iterOnlyObjects(txn, ServiceJourneyPattern.class)) {
                    if (!(o instanceof ServiceJourneyPattern pat) || pat.getId() == null
                            || pat.getPointsInSequence() == null) {
                        continue;
                    }
                    Set<String> ids = new HashSet<>();
                    for (PointInLinkSequence_VersionedChildStructure p : points(pat)) {
                        if (isPassthrough(p) && p.getId() != null) ids.add(p.getId());
                    }
                    if (!ids.isEmpty()) {
                        passthroughPoints.put(pat.getId(), ids);
                        mergeable.add(pat.getId());
                    }
                }
                if (mergeable.isEmpty()) return;
                for (Object o : dbRead.iterOnlyObjects(txn, ServiceJourney.class)) {
                    if (!(o instanceof ServiceJourney sj) || sj.getJourneyPatternRef() == null
                            || sj.getJourneyPatternRef().getValue() == null
                            || sj.getPassingTimes() == null) {
                        continue;
                    }
                    String patternId = sj.getJourneyPatternRef().getValue().getRef();
                    Set<String> ids = passthroughPoints.get(patternId);
                    if (ids == null || !mergeable.contains(patternId)) continue;
                    for (TimetabledPassingTime t : sj.getPassingTimes().getTimetabledPassingTime()) {
                        var ref = t.getPointInJourneyPatternRef();
                        if (ref != null && ref.getValue() != null
                                && ids.contains(ref.getValue().getRef())) {
                            mergeable.remove(patternId);
                            timed++;
                            break;
                        }
                    }
                }
            }

            private boolean merge(ServiceJourneyPattern pat) {
                if (!mergeable.contains(pat.getId())) {
                    return false;
                }
                List<PointInLinkSequence_VersionedChildStructure> pts = points(pat);
                List<String> links = linkRefs(pat, pts);
                if (links.size() != pts.size() - 1) {
                    return false;
                }
                List<Integer> kept = new ArrayList<>(pts.size());
                for (int i = 0; i < pts.size(); i++) {
                    if (!isPassthrough(pts.get(i))) kept.add(i);
                }
                if (kept.size() == pts.size() || kept.size() < 2) {
                    return false;
                }
                // The link objects: a rebuilt ref carries each link's version as well as its id.
                List<ServiceLink> rebuilt = new ArrayList<>(kept.size() - 1);
                List<ServiceLink> minted = new ArrayList<>();
                for (int k = 0; k < kept.size() - 1; k++) {
                    // Link j joins point j to point j+1, so the hop from kept[k] to kept[k+1] spans
                    // links [kept[k], kept[k+1]).
                    List<ServiceLink> span = new ArrayList<>();
                    for (int j = kept.get(k); j < kept.get(k + 1); j++) {
                        ServiceLink sl = resolve(links.get(j));
                        if (sl == null) {
                            refused++;
                            return false;
                        }
                        span.add(sl);
                    }
                    if (span.size() == 1) {
                        rebuilt.add(span.get(0));
                        continue;
                    }
                    ServiceLink joined = join(span);
                    if (joined == null) {
                        refused++;
                        return false;
                    }
                    minted.add(joined);
                    rebuilt.add(joined);
                }
                pat.setLinksInSequence(linksInSequence(pat.getId(), rebuilt));
                pending.addAll(minted);
                pending.add(pat);
                return true;
            }

            /// The pattern's links in order, one per hop, from whichever of the two containers
            /// NeTEx allows the producer to have used.
            ///
            /// The Austrian passthrough patterns carry linksInSequence; the Italian ones (IT:ITH1,
            /// it:apb) carry only the per-point OnwardServiceLinkRef. The rebuilt container is
            /// written as linksInSequence either way, which is what OTP reads first; a pattern's
            /// onward refs are left as published, so a consumer reading those still sees the
            /// unmerged view the feed actually states.
            private List<String> linkRefs(ServiceJourneyPattern pat,
                    List<PointInLinkSequence_VersionedChildStructure> pts) {
                List<String> refs = new ArrayList<>();
                if (pat.getLinksInSequence() != null) {
                    for (LinkInLinkSequence_VersionedChildStructure l : pat.getLinksInSequence()
                            .getServiceLinkInJourneyPatternOrTimingLinkInJourneyPattern()) {
                        refs.add(l instanceof ServiceLinkInJourneyPattern_VersionedChildStructure sl
                                && sl.getServiceLinkRef() != null
                                        ? sl.getServiceLinkRef().getRef() : null);
                    }
                    return refs;
                }
                // The onward ref belongs to the point it leaves, so the last point never has one
                // and the list is one shorter than the points -- exactly one per hop.
                for (int i = 0; i < pts.size() - 1; i++) {
                    refs.add(pts.get(i)
                            instanceof StopPointInJourneyPattern_VersionedChildStructure sp
                            && sp.getOnwardServiceLinkRef() != null
                                    ? sp.getOnwardServiceLinkRef().getRef() : null);
                }
                return refs.stream().anyMatch(java.util.Objects::nonNull) ? refs : List.of();
            }

            private ServiceLink resolve(String ref) {
                if (ref == null) {
                    return null;
                }
                Object o = dbRead.loadObjectByReference(txn,
                        new ServiceLinkRefStructure().withRef(ref), true);
                return o instanceof ServiceLink resolved ? resolved : null;
            }
        };
    }

    /// Concatenate a run of ServiceLinks into one. Null when they do not actually join, so the
    /// caller leaves the pattern alone rather than emitting a link that teleports.
    static ServiceLink join(List<ServiceLink> span) {
        List<Double> out = new ArrayList<>();
        BigDecimal distance = BigDecimal.ZERO;
        for (ServiceLink sl : span) {
            List<Double> pos = posList(sl.getLineString());
            if (pos == null || pos.size() < 4 || pos.size() % 2 != 0) {
                return null;
            }
            if (!out.isEmpty()) {
                // Both lists are lat,lon, so the trailing pair is (lat, lon) and metres() wants
                // (lon, lat) -- do not "simplify" these indices.
                double gap = metres(out.get(out.size() - 1), out.get(out.size() - 2),
                        pos.get(1), pos.get(0));
                if (gap > SNAP_METRES) {
                    return null;
                }
                // Drop the shared seam coordinate.
                out.remove(out.size() - 1);
                out.remove(out.size() - 1);
            }
            out.addAll(pos);
            if (sl.getDistance() != null) distance = distance.add(sl.getDistance());
        }
        ServiceLink first = span.get(0);
        ServiceLink last = span.get(span.size() - 1);
        if (first.getFromPointRef() == null || last.getToPointRef() == null) {
            return null;
        }
        // Content-hashed, so identical merges across patterns collapse to one object and a re-run
        // mints the same ids.
        String hash = sha256Head8(
                first.getFromPointRef().getRef() + "\n" + last.getToPointRef().getRef() + "\n" + out);
        LineStringType ls = new LineStringType()
                .withSrsName(first.getLineString().getSrsName())
                .withPosList(new DirectPositionListType()
                        .withSrsDimension(BigInteger.valueOf(2))
                        .withValue(out));
        ls.setId("LineString_" + hash);
        ServiceLink sl = new ServiceLink()
                .withId(idNamespace(first.getId()) + hash)
                .withFromPointRef(copyRef(first.getFromPointRef()))
                .withToPointRef(copyRef(last.getToPointRef()))
                .withLineString(ls);
        sl.setVersion(first.getVersion());
        if (distance.signum() > 0) sl.setDistance(distance);
        return sl;
    }

    static List<PointInLinkSequence_VersionedChildStructure> points(ServiceJourneyPattern pat) {
        return pat.getPointsInSequence()
                .getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern();
    }

    static boolean isPassthrough(PointInLinkSequence_VersionedChildStructure p) {
        return p instanceof StopPointInJourneyPattern_VersionedChildStructure sp
                && sp.getStopUse() == StopUseEnumeration.PASSTHROUGH;
    }

    /// The rebuilt `linksInSequence`, one entry per surviving hop.
    ///
    /// Each ref carries the link's version as well as its id. The store keys a row on
    /// `id NUL version NUL class` and wires a reference into an edge only on an exact key hit, so a
    /// versionless ref — the version encodes as empty — misses a link stored under a version and
    /// lands as an unresolved row. The pattern then exports with no links at all, its published
    /// hops along with its minted ones, and nothing resolves afterwards.
    static LinksInJourneyPattern_RelStructure linksInSequence(String patternId,
            List<ServiceLink> refs) {
        var seq = FACTORY.createLinksInJourneyPattern_RelStructure();
        List<LinkInLinkSequence_VersionedChildStructure> out =
                seq.getServiceLinkInJourneyPatternOrTimingLinkInJourneyPattern();
        for (int i = 0; i < refs.size(); i++) {
            // The CONCRETE element class, not its _VersionedChildStructure base: JAXB marshals the
            // base under a different element name, whose content model has no ServiceLinkRef -- the
            // ref then vanishes on the next read and the pattern silently loses its links.
            var link = FACTORY.createServiceLinkInJourneyPattern()
                    .withServiceLinkRef(new ServiceLinkRefStructure()
                            .withRef(refs.get(i).getId())
                            .withVersion(refs.get(i).getVersion()));
            // The id convention XbTruncate.forkPattern already uses.
            link.setId(patternId + "-link-" + (i + 1));
            link.setOrder(BigInteger.valueOf(i + 1L));
            out.add(link);
        }
        return seq;
    }

    private static List<Double> posList(LineStringType ls) {
        return ls == null || ls.getPosList() == null ? null : ls.getPosList().getValue();
    }

    private static double metres(double lonA, double latA, double lonB, double latB) {
        double dLat = (latA - latB) * METRES_PER_DEGREE;
        double dLon = (lonA - lonB) * METRES_PER_DEGREE * Math.cos(Math.toRadians((latA + latB) / 2));
        return Math.hypot(dLat, dLon);
    }

    private static String idNamespace(String serviceLinkId) {
        int i = serviceLinkId == null ? -1 : serviceLinkId.indexOf(":ServiceLink:");
        return i < 0 ? "merged:ServiceLink:" : serviceLinkId.substring(0, i + ":ServiceLink:".length());
    }

    private static ScheduledStopPointRefStructure copyRef(ScheduledStopPointRefStructure ref) {
        var out = new ScheduledStopPointRefStructure().withRef(ref.getRef());
        out.setVersion(ref.getVersion());
        return out;
    }

    private static String sha256Head8(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", digest[i]));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
