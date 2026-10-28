package tools;

import noi.netex.calls.Calls;
import noi.netex.model.Call_VersionedChildStructure;
import noi.netex.model.JourneyPattern_VersionStructure;
import noi.netex.model.JourneyPatternRefStructure;
import noi.netex.model.LocationStructure;
import noi.netex.model.PassengerStopAssignment;
import noi.netex.model.PointInLinkSequence_VersionedChildStructure;
import noi.netex.model.ServiceJourney;
import noi.netex.model.ServiceJourneyInterchange;
import noi.netex.model.SimplePoint_VersionStructure;
import noi.netex.model.StopPlace;
import noi.netex.model.StopPointInJourneyPattern_VersionedChildStructure;
import noi.netex.text.Mls;
import toolkit.store.Store;
import toolkit.store.Stores;
import toolkit.store.Txn;
import toolkit.util.Log;
import transformers.xb.XbIds;
import tools.VerifyCrossborder.Handover;
import tools.VerifyCrossborder.Stop;

import jakarta.xml.bind.JAXBElement;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// The cross-border handover check of `VerifyCrossborder` passes B1 and E, run against a coupled
/// store instead of an exported zip. Reads only.
///
/// Every classification — the distance buckets, the one-country test, `classify`, `fullyCovered`,
/// `backwards` — is [VerifyCrossborder]'s own public helper, called from here; only the reader
/// differs.
///
/// One structural difference from the report, before comparing numbers: the store holds the
/// coupling's output, the export holds it after the EPIP transform, which backfills names and
/// coordinates and re-anchors some interchange point refs of its own
/// (`epip repair_interchange_points`). The store's counts are the coupling's own work; the
/// export's include that repair.
///
/// Usage: `tools/XbAnchorProbe.java <coupled-or-epip.lmdb>`
public final class XbAnchorProbe {

    private XbAnchorProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            System.err.println("usage: XbAnchorProbe <store.lmdb> [interchange-id-substring]");
            System.exit(2);
        }
        String explain = args.length == 2 ? args[1] : null;
        try (Store db = Stores.open(Path.of(args[0]), /*readonly=*/true); Txn txn = db.roTxn()) {
            Map<String, Stop> stops = readStops(db, txn);
            Log.info("[xb-probe] %,d StopPlaces", stops.size());
            Map<String, String[]> assigns = readAssignments(db, txn);   // ssp -> {stopPlace, quay}
            Log.info("[xb-probe] %,d PassengerStopAssignments", assigns.size());

            List<ServiceJourneyInterchange> links = new ArrayList<>();
            int total = 0;
            for (Object o : db.iterOnlyObjects(txn, ServiceJourneyInterchange.class)) {
                total++;
                ServiceJourneyInterchange x = (ServiceJourneyInterchange) o;
                if (Boolean.TRUE.equals(x.isCrossBorder())) links.add(x);
            }
            Log.info("[xb-probe] %,d interchanges, %,d cross-border", total, links.size());

            if (explain != null) {
                explain(db, txn, stops, assigns, links, explain);
                return;
            }
            report(db, txn, stops, assigns, links);
        }
    }

    // ------------------------------------------------------------------------------ readers

    static Map<String, Stop> readStops(Store db, Txn txn) {
        Map<String, Stop> out = new HashMap<>();
        for (Object o : db.iterOnlyObjects(txn, StopPlace.class)) {
            StopPlace sp = (StopPlace) o;
            Double lat = null;
            Double lon = null;
            SimplePoint_VersionStructure c = sp.getCentroid();
            LocationStructure loc = c != null ? c.getLocation() : null;
            if (loc != null && loc.getLatitude() != null && loc.getLongitude() != null) {
                lat = loc.getLatitude().doubleValue();
                lon = loc.getLongitude().doubleValue();
            } else if (loc != null && loc.getPos() != null && loc.getPos().getValue() != null
                    && loc.getPos().getValue().size() >= 2) {
                lat = loc.getPos().getValue().get(0);
                lon = loc.getPos().getValue().get(1);
            }
            out.put(sp.getId(), new Stop(sp.getId(), Mls.text(sp.getName()), Mls.text(sp.getShortName()),
                    lat, lon));
        }
        return out;
    }

    /// ScheduledStopPoint ref -> `{StopPlace ref, Quay ref}`, lowest assignment id winning — the
    /// rule the report's own reader uses.
    static Map<String, String[]> readAssignments(Store db, Txn txn) {
        Map<String, String[]> out = new HashMap<>();
        Map<String, String> winner = new HashMap<>();
        for (Object o : db.iterOnlyObjects(txn, PassengerStopAssignment.class)) {
            PassengerStopAssignment psa = (PassengerStopAssignment) o;
            JAXBElement<?> sspEl = psa.getScheduledStopPointRef();
            if (sspEl == null) continue;
            String ssp = ((noi.netex.model.ScheduledStopPointRefStructure) sspEl.getValue()).getRef();
            if (ssp == null) continue;
            String id = psa.getId() == null ? "" : psa.getId();
            String prev = winner.get(ssp);
            if (prev != null && prev.compareTo(id) <= 0) continue;
            JAXBElement<? extends noi.netex.model.StopPlaceRefStructure> spEl = psa.getStopPlaceRef();
            JAXBElement<? extends noi.netex.model.QuayRefStructure> qEl = psa.getQuayRef();
            String quay = qEl != null ? qEl.getValue().getRef()
                    : psa.getQuay() != null ? psa.getQuay().getId() : null;
            out.put(ssp, new String[] {spEl != null ? spEl.getValue().getRef() : null, quay});
            winner.put(ssp, id);
        }
        return out;
    }

    /// The consolidated StopPlaces a journey calls at, in order — from its Calls when it has them,
    /// otherwise from its JourneyPattern's points. Null when the journey or its pattern is gone,
    /// which [VerifyCrossborder#classify] reads as UNRESOLVED rather than as a shape.
    static List<String> legStops(Store db, Txn txn, String journeyId, Map<String, String[]> assigns) {
        if (journeyId == null) return null;
        long fk = db.lookupFullKey(txn, journeyId, null, ServiceJourney.class);
        if (fk == -1) return null;
        ServiceJourney sj = (ServiceJourney) db.loadObjectByFullKey(txn, fk);
        if (sj == null) return null;
        List<String> refs = journeyRefs(db, txn, sj);
        if (refs == null) return null;
        List<String> out = new ArrayList<>(refs.size());
        for (String r : refs) {
            String[] a = r == null ? null : assigns.get(r);
            out.add(a == null ? null : a[0]);
        }
        return out;
    }

    /// A journey's ScheduledStopPoint refs in order, from its Calls or its pattern's points; null
    /// when neither resolves.
    static List<String> journeyRefs(Store db, Txn txn, ServiceJourney sj) {
        List<String> refs = new ArrayList<>();
        if (sj.getCalls() != null) {
            for (Call_VersionedChildStructure c : Calls.of(sj.getCalls())) {
                JAXBElement<? extends noi.netex.model.ScheduledStopPointRefStructure> el =
                        c.getScheduledStopPointRef();
                refs.add(el == null ? null : el.getValue().getRef());
            }
        } else {
            JAXBElement<? extends JourneyPatternRefStructure> pref = sj.getJourneyPatternRef();
            Object pat = pref == null ? null : db.loadObjectByReference(txn, pref.getValue(), true);
            if (!(pat instanceof JourneyPattern_VersionStructure jp) || jp.getPointsInSequence() == null) {
                return null;
            }
            for (PointInLinkSequence_VersionedChildStructure p
                    : jp.getPointsInSequence()
                        .getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern()) {
                refs.add(p instanceof StopPointInJourneyPattern_VersionedChildStructure spp
                        && spp.getScheduledStopPointRef() != null
                        ? spp.getScheduledStopPointRef().getValue().getRef() : null);
            }
        }
        return refs;
    }

    // ------------------------------------------------------------------------------ report

    static void report(Store db, Txn txn, Map<String, Stop> stops, Map<String, String[]> assigns,
            List<ServiceJourneyInterchange> links) {
        int n = links.size();
        int unresolved = 0, same = 0, diff = 0, sameQuay = 0;
        int oneCountry = 0, twoCountries = 0, unknownCountry = 0;
        int covered = 0, back = 0;
        int joinedSide = 0, leftSide = 0, bothWays = 0, stubShaped = 0;
        int coveredHome = 0, coveredCounterpart = 0, coveredEqual = 0, coveredStub = 0;
        String joinedExample = null, leftExample = null;
        Map<String, Integer> byBucket = new LinkedHashMap<>();
        for (String b : VerifyCrossborder.DISTANCE_BUCKETS) byBucket.put(b, 0);
        Map<Handover, Integer> byClass = new LinkedHashMap<>();
        for (Handover h : Handover.values()) byClass.put(h, 0);
        Map<Handover, String> examples = new LinkedHashMap<>();

        for (ServiceJourneyInterchange x : links) {
            String fromPt = x.getFromPointRef() != null ? x.getFromPointRef().getRef() : null;
            String toPt = x.getToPointRef() != null ? x.getToPointRef().getRef() : null;
            String[] a = fromPt == null ? null : assigns.get(fromPt);
            String[] b = toPt == null ? null : assigns.get(toPt);
            String fromStop = a == null ? null : a[0];
            String toStop = b == null ? null : b[0];
            if (a != null && b != null && a[1] != null && a[1].equals(b[1])) sameQuay++;
            if (fromStop == null || toStop == null) {
                unresolved++;
                continue;
            }
            if (fromStop.equals(toStop)) {
                same++;
                continue;
            }
            diff++;
            Double m = VerifyCrossborder.distM(stops.get(fromStop), stops.get(toStop));
            byBucket.merge(VerifyCrossborder.distanceBucket(m), 1, Integer::sum);
            Boolean one = VerifyCrossborder.sameCountryHandover(fromStop, toStop);
            if (one == null) unknownCountry++;
            else if (one) oneCountry++;
            else twoCountries++;

            String fromJy = x.getFromJourneyRef() != null ? x.getFromJourneyRef().getRef() : null;
            String toJy = x.getToJourneyRef() != null ? x.getToJourneyRef().getRef() : null;
            List<String> fromSeq = legStops(db, txn, fromJy, assigns);
            List<String> toSeq = legStops(db, txn, toJy, assigns);
            Handover h = VerifyCrossborder.classify(fromSeq, toSeq, fromStop, toStop);
            byClass.merge(h, 1, Integer::sum);
            // Which side could have moved. `classify` says a shared anchor exists, not whose end is
            // wrong; the interchange is built from the home leg's boundary stop and the
            // counterpart's ref at that station, so only the continuing leg can be re-anchored.
            // Splitting the two says whether the residue is a missing arm or a missing station.
            if (h == Handover.SHARED_AVAILABLE) {
                if (stubShaped(db, txn, fromJy)) stubShaped++;
                boolean joinedServesLeft = toSeq.contains(fromStop);
                boolean leftServesJoined = fromSeq.contains(toStop);
                if (joinedServesLeft && leftServesJoined) bothWays++;
                else if (joinedServesLeft) joinedSide++;
                else leftSide++;
                if (joinedServesLeft && !leftServesJoined && joinedExample == null) {
                    joinedExample = x.getId();
                } else if (leftServesJoined && !joinedServesLeft && leftExample == null) {
                    leftExample = x.getId();
                }
            }
            if (VerifyCrossborder.fullyCovered(fromSeq, toSeq)) {
                covered++;
                // Which leg is the redundant one, and is it the one the coupling could drop? The
                // drop rule only ever removes the home leg -- the journey this pipeline truncated
                // -- and never a counterpart, which belongs to another feed. The home leg is the
                // one whose slot the interchange id carries first.
                boolean fromIsHome = homeIsFrom(x.getId(), fromJy);
                java.util.Set<String> leftSet = new java.util.HashSet<>(fromSeq);
                java.util.Set<String> rightSet = new java.util.HashSet<>(toSeq);
                leftSet.remove(null);
                rightSet.remove(null);
                boolean fromRedundant = rightSet.containsAll(leftSet);
                boolean toRedundant = leftSet.containsAll(rightSet);
                if (fromRedundant && toRedundant) coveredEqual++;
                else if (fromRedundant == fromIsHome) coveredHome++;
                else coveredCounterpart++;
                if (stubShaped(db, txn, fromIsHome ? fromJy : toJy)) coveredStub++;
            }
            if (VerifyCrossborder.backwards(toSeq, fromStop, toStop)) back++;
            examples.putIfAbsent(h, String.format("%s -> %s%s, %s",
                    label(stops, fromStop), label(stops, toStop),
                    m == null ? "" : String.format(" (%,.0f m)", m), x.getId()));
        }

        Log.info("[xb-probe] %,d cross-border links: %,d on ONE StopPlace, %,d on different ones, "
                + "%,d unresolved; %,d share a Quay", n, same, diff, unresolved, sameQuay);
        for (Map.Entry<String, Integer> e : byBucket.entrySet()) {
            Log.info("[xb-probe]   %-52s %,6d", e.getKey(), e.getValue());
        }
        Log.info("[xb-probe]   %-52s %,6d", "handover stops in ONE country", oneCountry);
        Log.info("[xb-probe]   %-52s %,6d", "handover stops in two countries", twoCountries);
        Log.info("[xb-probe]   %-52s %,6d", "country of an end unknown", unknownCountry);
        for (Map.Entry<Handover, Integer> e : byClass.entrySet()) {
            Log.info("[xb-probe]   %-52s %,6d", e.getKey().name(), e.getValue());
        }
        Log.info("[xb-probe]   %-52s %,6d", "one leg calls at EVERY stop of the other", covered);
        Log.info("[xb-probe]   %-52s %,6d", "runs BACKWARDS along the continuing leg", back);
        Log.info("[xb-probe]   %-52s %,6d", "  ..of SHARED_AVAILABLE: joined leg serves left stop", joinedSide);
        Log.info("[xb-probe]   %-52s %,6d", "  ..of SHARED_AVAILABLE: left leg serves joined stop", leftSide);
        Log.info("[xb-probe]   %-52s %,6d", "  ..of SHARED_AVAILABLE: both", bothWays);
        Log.info("[xb-probe]   %-52s %,6d", "  ..of SHARED_AVAILABLE: left leg is a foreign-leg STUB", stubShaped);
        Log.info("[xb-probe]   %-52s %,6d", "  ..of REDUNDANT: the HOME leg is the covered one", coveredHome);
        Log.info("[xb-probe]   %-52s %,6d", "  ..of REDUNDANT: the COUNTERPART is the covered one", coveredCounterpart);
        Log.info("[xb-probe]   %-52s %,6d", "  ..of REDUNDANT: the two call at the same set", coveredEqual);
        Log.info("[xb-probe]   %-52s %,6d", "  ..of REDUNDANT: the home leg is a foreign-leg STUB", coveredStub);
        Log.info("[xb-probe]   e.g. joined-side: %s", joinedExample);
        Log.info("[xb-probe]   e.g. left-side:   %s", leftExample);
        for (Map.Entry<Handover, String> e : examples.entrySet()) {
            Log.info("[xb-probe]   e.g. %s: %s", e.getKey().name(), e.getValue());
        }
    }

    /// Dump one link's two legs stop by stop: the ScheduledStopPoint ref as published, and the
    /// consolidated StopPlace its assignment resolves to. It answers one question -- the coupling
    /// decided there was no station in common; is that true of the data, or only of what the
    /// coupling was looking at?
    static void explain(Store db, Txn txn, Map<String, Stop> stops, Map<String, String[]> assigns,
            List<ServiceJourneyInterchange> links, String needle) {
        for (ServiceJourneyInterchange x : links) {
            if (x.getId() == null || !x.getId().contains(needle)) continue;
            String fromJy = x.getFromJourneyRef() != null ? x.getFromJourneyRef().getRef() : null;
            String toJy = x.getToJourneyRef() != null ? x.getToJourneyRef().getRef() : null;
            String fromPt = x.getFromPointRef() != null ? x.getFromPointRef().getRef() : null;
            String toPt = x.getToPointRef() != null ? x.getToPointRef().getRef() : null;
            Log.info("[xb-probe] %s", x.getId());
            Log.info("[xb-probe]   leaves %s at %s", fromJy, fromPt);
            Log.info("[xb-probe]   joins  %s at %s", toJy, toPt);
            for (String jy : new String[] {fromJy, toJy}) {
                Log.info("[xb-probe]   -- %s", jy);
                long fk = jy == null ? -1 : db.lookupFullKey(txn, jy, null, ServiceJourney.class);
                ServiceJourney sj = fk == -1 ? null : (ServiceJourney) db.loadObjectByFullKey(txn, fk);
                if (sj == null) {
                    Log.info("[xb-probe]      (not in this store)");
                    continue;
                }
                List<String> refs = journeyRefs(db, txn, sj);
                if (refs == null) {
                    Log.info("[xb-probe]      (no calls and no resolvable pattern)");
                    continue;
                }
                for (String r : refs) {
                    String[] a = r == null ? null : assigns.get(r);
                    String sp = a == null ? null : a[0];
                    Log.info("[xb-probe]      %-52s -> %-46s %s", r, sp, label(stops, sp));
                }
            }
            return;
        }
        Log.warn("[xb-probe] no cross-border link id contains %s", needle);
    }

    /// Is this journey a foreign-leg stub in `scanOneSource`'s sense -- every stop in one country
    /// that is not its feed's? Those are linked by `buildStubInterchanges`, which anchors the
    /// stub's first stop against the counterpart's first stop and never asks whether the two are
    /// the same station.
    static boolean stubShaped(Store db, Txn txn, String journeyId) {
        if (journeyId == null) return false;
        String feed = XbIds.feedCountry(journeyId);
        if (feed == null) return false;
        long fk = db.lookupFullKey(txn, journeyId, null, ServiceJourney.class);
        ServiceJourney sj = fk == -1 ? null : (ServiceJourney) db.loadObjectByFullKey(txn, fk);
        if (sj == null) return false;
        List<String> refs = journeyRefs(db, txn, sj);
        if (refs == null) return false;
        String only = null;
        for (String r : refs) {
            String c = r == null ? null : XbIds.stopCountry(r);
            if (c == null || c.isEmpty()) continue;
            if (only == null) only = c;
            else if (!only.equals(c)) return false;
        }
        return only != null && !only.equals(feed);
    }

    /// Is the from journey the home leg -- the one this pipeline truncated or stubbed? Both
    /// interchange builders compose the id as
    /// `<cc>-<cc>:ServiceJourneyInterchange:<num>:<slot(home)>:<slot(counterpart)>`, and `slot`
    /// only replaces colons, so the fourth colon-separated field is the home journey whichever
    /// direction the link runs in.
    static boolean homeIsFrom(String linkId, String fromJy) {
        if (linkId == null || fromJy == null) return false;
        String[] f = linkId.split(":");
        return f.length >= 4 && f[3].equals(fromJy.replace(':', '_'));
    }

    private static String label(Map<String, Stop> stops, String id) {
        Stop s = stops.get(id);
        return s == null ? id : s.label();
    }
}
