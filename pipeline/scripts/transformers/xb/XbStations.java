package transformers.xb;

// Making two publishers' copies of one physical station into ONE object.
//
// A precondition of matching rather than part of it: matching identifies journeys by the stations
// they share, and two publishers never use the same stop id. Everything downstream identifies a
// station by the consolidated StopPlace id — the canon — and matching, anchoring and the redundancy
// tests are all set operations over canons.
//
// This runs BEFORE the EPIP conversion. Moving a Quay onto a survivor after EpipToDb phase C2 would
// re-introduce the orphan `LevelRef`s that pass removes; see moveChildren.

import jakarta.xml.bind.JAXBElement;
import noi.netex.calls.Calls;
import noi.netex.model.AllVehicleModesOfTransportEnumeration;
import noi.netex.model.Call_VersionedChildStructure;
import noi.netex.model.Level;
import noi.netex.model.LevelRefStructure;
import noi.netex.model.Line;
import noi.netex.model.LocationStructure;
import noi.netex.model.PassengerStopAssignment;
import noi.netex.model.Quay;
import noi.netex.model.Quays_RelStructure;
import noi.netex.model.ServiceJourney;
import noi.netex.model.SimplePoint_VersionStructure;
import noi.netex.model.StopPlace;
import noi.netex.model.StopPlaceRefStructure;
import noi.netex.model.Zone_VersionStructure;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class XbStations {

    private XbStations() {}

    /// What one consolidation run did, and what it could not reach.
    public static final class Counters {
        public long clusters;
        public long mergedAway;
        public long quaysMoved;
        public long levelsMoved;
        public long levelRefsCleared;
        public long assignmentsRepointed;
        public long railServed;
        // The pole-quay gate excludes most of the corpus from the bus-quay merge BEFORE any of its
        // own skip counters run, so its "merged 58" reads as a shortage of matches when it is
        // mostly a shortage of poles.
        public long noPoleQuays;
        public long onePoleQuay;
        public long twoOrMorePoleQuays;
        /// The pair count is ordered: the scan visits each unordered pair from both ends, so one
        /// refused pair reads as 2.
        public long unionsRefusedSameNamespace;
        public long clustersRefusedDiameter;
        public long refusedDiameterMembers;

        public void report() {
            Log.info("[xb-stations] consolidated %d StopPlaces into %d physical stations "
                    + "(%d quays and %d levels moved, %d orphaned LevelRefs cleared, "
                    + "%d assignments re-pointed)",
                    mergedAway, clusters, quaysMoved, levelsMoved, levelRefsCleared,
                    assignmentsRepointed);
            Log.info("[xb-stations] %d rail-served StopPlaces; pole-quay eligibility: %d with none, "
                    + "%d with one, %d with two or more (only the last can enter the bus-quay merge)",
                    railServed, noPoleQuays, onePoleQuay, twoOrMorePoleQuays);
            Log.info("[xb-stations] guards refused %d in-radius pairs as same-namespace and dropped "
                    + "%d clusters (%d members) as wider than the diameter cap",
                    unionsRefusedSameNamespace, clustersRefusedDiameter, refusedDiameterMembers);
        }
    }

    // ------------------------------------------------------------------------------------- //
    // Rail-served stops
    // ------------------------------------------------------------------------------------- //

    /// The StopPlaces a rail journey calls at — the only ones the consolidation touches.
    ///
    /// "Rail-served" means reachable from a journey whose mode resolves to rail. [#isRail] and
    /// [#transportMode] are that rule.
    ///
    /// Rail-only is a standing constraint: a bus stop and a train station can sit 20 m apart and
    /// share a code, and merging them corrupts both. The cross-border bus that two publishers
    /// duplicate is removed upstream by the foreign-republication drop.
    ///
    /// This overload reads [XbScan.RailSeen] instead of walking the journeys, and pays only for the
    /// pattern expansion. The long form below stays for callers with no pass 1 to draw on — the
    /// consolidation stage, which runs before any of this. The two must give the same set, which
    /// `TestXbStations.testTheFoldedRailScanAgreesWithTheStandaloneOne` pins.
    public static Set<String> railServed(Store db, Txn txn, List<PassengerStopAssignment> psas,
            XbScan.RailSeen seen) {
        Set<String> railStopPoints = new HashSet<>(seen.stopPoints());
        if (!seen.patterns().isEmpty()) {
            for (Map.Entry<String, List<String>> e
                    : XbMergeStops.patternSspRefs(db, txn).entrySet()) {
                if (!seen.patterns().contains(e.getKey())) continue;
                for (String r : e.getValue()) {
                    if (r != null && !r.isEmpty()) railStopPoints.add(r);
                }
            }
        }
        return XbMergeStops.servedFromRailSsp(railStopPoints, psas);
    }

    public static Set<String> railServed(Store db, Txn txn, List<PassengerStopAssignment> psas) {
        Map<String, List<String>> patternStops = XbMergeStops.patternSspRefs(db, txn);
        XbLines.LineMaps<AllVehicleModesOfTransportEnumeration> modes =
                XbLines.lineValueMaps(db, txn, Line::getTransportMode);

        Set<String> railStopPoints = new HashSet<>();
        for (Object o : db.iterOnlyObjects(txn, ServiceJourney.class)) {
            ServiceJourney sj = (ServiceJourney) o;
            if (!isRail(transportMode(sj, modes.lineMap(), modes.sjpLine()))) {
                continue;
            }
            addStops(sj, patternStops, railStopPoints);
        }
        return XbMergeStops.servedFromRailSsp(railStopPoints, psas);
    }

    /// The journey's transport mode: its own, else its Line's, else null. Some exports tag the mode
    /// only on the Line.
    static AllVehicleModesOfTransportEnumeration transportMode(ServiceJourney sj,
            Map<String, AllVehicleModesOfTransportEnumeration> lineMode, Map<String, String> sjpLine) {
        if (sj.getTransportMode() != null) return sj.getTransportMode();
        String lineId = XbLines.journeyLineId(sj, sjpLine);
        return lineId != null ? lineMode.get(lineId) : null;
    }

    /// Rail is the resolved mode RAIL; an unresolved mode is not rail.
    ///
    /// [#transportMode] resolves null for a journey that carries no mode and reaches no Line. The
    /// Piemonte (`IT:ITC1`) and Marche (`IT:ITI3`) feeds put no `LineRef` on any of their 32,155
    /// journeys, so all of them land there, and the 21,741 StopPlaces those journeys serve are bus.
    ///
    /// This and [#transportMode] are the whole rail rule. Nothing else may restate it; [#modeByte]
    /// answers a different question.
    static boolean isRail(AllVehicleModesOfTransportEnumeration mode) {
        return mode == AllVehicleModesOfTransportEnumeration.RAIL;
    }

    /// The mode as the byte [XbTypes.Node#mode] holds, an unresolved mode counting as RAIL — the
    /// opposite of [#isRail]'s convention. [XbStitch] reads this byte to refuse a stitch across a
    /// mode change, where an unknown must not split one vehicle in two; eligibility must not admit
    /// one.
    static byte modeByte(AllVehicleModesOfTransportEnumeration mode) {
        return mode == null ? XbTypes.RAIL_MODE : (byte) mode.ordinal();
    }

    /// The stop points one rail journey serves: its calls where it has them, else its pattern's
    /// points. Post-EPIP no journey carries `<Calls>`, but the consolidation runs on the FEED
    /// stores, where they are the normal form.
    static void addStops(ServiceJourney sj, Map<String, List<String>> patternStops,
            Set<String> into) {
        if (sj.getCalls() != null) {
            for (Call_VersionedChildStructure c : Calls.of(sj.getCalls())) {
                String r = XbMergeStops.callRef(c);
                if (r != null && !r.isEmpty()) into.add(r);
            }
        } else if (sj.getJourneyPatternRef() != null) {
            for (String r : patternStops.getOrDefault(
                    sj.getJourneyPatternRef().getValue().getRef(), List.of())) {
                if (r != null && !r.isEmpty()) into.add(r);
            }
        }
    }

    // ------------------------------------------------------------------------------------- //
    // Consolidation
    // ------------------------------------------------------------------------------------- //

    /// The surviving StopPlaces, and the ids that were merged away.
    public record Consolidation(List<StopPlace> survivors, Set<String> mergedAway,
            List<PassengerStopAssignment> psas, Counters counters) {}

    /// Cluster the rail-served StopPlaces geographically and elect one survivor per physical station.
    ///
    /// Codes are not trusted for the clustering; canonical UICs collide. Canon `80_64` is shared by
    /// an Italian bike-share dock, an Austrian village bus stop, a Sardinian bus stop and Celle
    /// Bahnhof in Germany. They are consulted only to decide WHICH copy survives.
    ///
    /// Mutates the assignments in place and returns the survivors.
    public static Consolidation consolidate(List<StopPlace> stopplaces,
            List<PassengerStopAssignment> psas, Set<String> eligible) {
        Counters counters = new Counters();
        counters.railServed = eligible.size();
        List<StopPlace> elig = new ArrayList<>();
        for (StopPlace sp : stopplaces) {
            if (eligible.contains(sp.getId())) elig.add(sp);
            int poles = poleQuays(sp).size();
            if (poles == 0) {
                counters.noPoleQuays++;
            } else if (poles == 1) {
                counters.onePoleQuay++;
            } else {
                counters.twoOrMorePoleQuays++;
            }
        }

        Map<String, double[]> coords = new HashMap<>();
        Map<String, String> uics = new HashMap<>();
        for (StopPlace sp : elig) {
            coords.put(sp.getId(), coord(sp));
            uics.put(sp.getId(), XbIds.realUic(privateCode(sp), sp.getId()));
        }

        Map<String, List<StopPlace>> clusters = cluster(elig, coords, counters);

        Set<String> mergedAway = new HashSet<>();
        Map<String, String> repoint = new HashMap<>();
        for (List<StopPlace> members : clusters.values()) {
            if (members.size() < 2) continue;
            counters.clusters++;
            StopPlace survivor = elect(members, uics);
            for (StopPlace sp : members) {
                if (sp == survivor) continue;
                moveChildren(sp, survivor, counters);
                mergedAway.add(sp.getId());
                repoint.put(sp.getId(), survivor.getId());
            }
            counters.levelRefsCleared += clearOrphanLevelRefs(survivor);
        }

        for (PassengerStopAssignment psa : psas) {
            JAXBElement<? extends StopPlaceRefStructure> el = psa.getStopPlaceRef();
            if (el != null && repoint.containsKey(el.getValue().getRef())) {
                el.getValue().setRef(repoint.get(el.getValue().getRef()));
                counters.assignmentsRepointed++;
            }
        }
        counters.mergedAway = mergedAway.size();

        List<StopPlace> survivors = new ArrayList<>();
        for (StopPlace sp : stopplaces) {
            if (!mergedAway.contains(sp.getId())) survivors.add(sp);
        }
        counters.report();
        return new Consolidation(survivors, mergedAway, psas, counters);
    }

    /// Group the eligible StopPlaces into physical stations, root id -> members.
    ///
    /// Every pair within [XbProfile#CONSOLIDATE_RADIUS_DEG] is unioned, not just the nearest: a
    /// border station has three or four co-located copies, and nearest-only leaves the group
    /// fragmented. That is single linkage, so two rules bound the chain it can build:
    ///
    /// - a pair whose ids share a [XbMergeStops#namespace] is not unioned. One publisher's records
    ///   inside the radius are consecutive stops rather than copies of one station: `ch:2:` carries
    ///   metre-gauge halts 96-400 m apart, `IT:ITC1:` a bus corridor.
    /// - a finished cluster wider than [XbProfile#CONSOLIDATE_MAX_DIAMETER_DEG] is dropped whole,
    ///   not re-clustered.
    ///
    /// `coords` is keyed by id and may hold nulls; a StopPlace with no coordinate joins nothing and
    /// comes back as its own cluster.
    private static Map<String, List<StopPlace>> cluster(List<StopPlace> elig,
            Map<String, double[]> coords, Counters counters) {
        Map<String, String> parent = new HashMap<>();
        for (StopPlace sp : elig) parent.put(sp.getId(), sp.getId());
        Map<Long, List<String>> buckets = new HashMap<>();
        for (StopPlace sp : elig) {
            double[] c = coords.get(sp.getId());
            if (c != null) buckets.computeIfAbsent(bucket(c[0], c[1]), k -> new ArrayList<>()).add(sp.getId());
        }
        double r2 = XbProfile.CONSOLIDATE_RADIUS_DEG * XbProfile.CONSOLIDATE_RADIUS_DEG;
        for (StopPlace sp : elig) {
            double[] c = coords.get(sp.getId());
            if (c == null) continue;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (String other : buckets.getOrDefault(
                            bucket(c[0] + dx * XbProfile.BUCKET_DEG, c[1] + dy * XbProfile.BUCKET_DEG),
                            List.of())) {
                        double[] oc = coords.get(other);
                        if (oc == null || other.equals(sp.getId())) continue;
                        if (sep2Deg(c, oc) > r2) continue;
                        if (XbMergeStops.namespace(sp.getId())
                                .equals(XbMergeStops.namespace(other))) {
                            counters.unionsRefusedSameNamespace++;
                            continue;
                        }
                        union(parent, sp.getId(), other);
                    }
                }
            }
        }

        Map<String, List<StopPlace>> clusters = new LinkedHashMap<>();
        for (StopPlace sp : elig) {
            clusters.computeIfAbsent(find(parent, sp.getId()), k -> new ArrayList<>()).add(sp);
        }
        double cap2 = XbProfile.CONSOLIDATE_MAX_DIAMETER_DEG * XbProfile.CONSOLIDATE_MAX_DIAMETER_DEG;
        clusters.values().removeIf(members -> {
            if (members.size() < 2 || diameter2Deg(members, coords) <= cap2) return false;
            counters.clustersRefusedDiameter++;
            counters.refusedDiameterMembers += members.size();
            return true;
        });
        return clusters;
    }

    /// The squared separation of two {lat, lon} pairs in degrees of latitude, longitude scaled by
    /// the cosine of `a`'s latitude. Unrooted: callers compare it against a squared threshold.
    static double sep2Deg(double[] a, double[] b) {
        double coslat = Math.cos(Math.toRadians(a[0]));
        double dlat = a[0] - b[0];
        double dlon = (a[1] - b[1]) * coslat;
        return dlat * dlat + dlon * dlon;
    }

    /// The squared widest separation between any two members, on [#sep2Deg]'s metric. Members with
    /// no coordinate are skipped; they joined nothing.
    static double diameter2Deg(List<StopPlace> members, Map<String, double[]> coords) {
        double worst = 0;
        for (int i = 0; i < members.size(); i++) {
            double[] a = coords.get(members.get(i).getId());
            if (a == null) continue;
            for (int j = i + 1; j < members.size(); j++) {
                double[] b = coords.get(members.get(j).getId());
                if (b != null) worst = Math.max(worst, sep2Deg(a, b));
            }
        }
        return worst;
    }

    /// Elect the copy the surviving station keeps.
    ///
    /// The country the station physically sits in decides, and it is voted from the members' REAL
    /// UIC codes: a foreign feed references a station by its true UIC even when the owning feed's own
    /// copy has only a local id, so the codes reveal the owner from the outside. Ties — a border
    /// station both countries code natively — fall to a fixed precedence.
    ///
    /// Within the owning country the copy with the most quays wins, then a copy carrying a real UIC,
    /// then the smallest id. Quay count outranks the code because the code identifies the station
    /// and the quays are the station: a publisher that surveyed the platforms has the names, the
    /// entrances and the quay types, and a republication that carries only the UIC has none of them.
    /// The country gate is checked first, so no cross-border election reaches this.
    ///
    /// [#nQuays] and the id make the order strict and total, so the result does not depend on the
    /// order `members` arrives in.
    static StopPlace elect(List<StopPlace> members, Map<String, String> uics) {
        String owner = owningCountry(members, uics);
        StopPlace best = null;
        for (StopPlace sp : members) {
            if (best == null || compareCandidates(sp, best, owner, uics) < 0) best = sp;
        }
        return best;
    }

    /// `a` against `b` on the election's tiers, negative when `a` is the better copy.
    private static int compareCandidates(StopPlace a, StopPlace b, String owner,
            Map<String, String> uics) {
        int c = Integer.compare(foreign(a, owner), foreign(b, owner));
        if (c != 0) return c;
        c = Integer.compare(nQuays(b), nQuays(a));
        if (c != 0) return c;
        c = Integer.compare(uics.get(a.getId()) == null ? 1 : 0,
                uics.get(b.getId()) == null ? 1 : 0);
        return c != 0 ? c : a.getId().compareTo(b.getId());
    }

    private static int foreign(StopPlace sp, String owner) {
        return Objects.equals(XbIds.feedCountry(sp.getId()), owner) ? 0 : 1;
    }

    /// The embedded Quays a copy would bring, which is what [#moveChildren] moves. A bare `QuayRef`
    /// stays behind, so counting it would rank a copy on children it does not hand over.
    static int nQuays(StopPlace sp) {
        if (sp.getQuays() == null) return 0;
        int n = 0;
        for (JAXBElement<?> el : sp.getQuays().getQuayRefOrQuay()) {
            if (el.getValue() instanceof Quay) n++;
        }
        return n;
    }

    /// The country a cluster's members' real UIC codes vote for, ties by border precedence. Null when
    /// no member carries a code the table recognises.
    static String owningCountry(List<StopPlace> members, Map<String, String> uics) {
        Map<String, Integer> votes = new LinkedHashMap<>();
        for (StopPlace sp : members) {
            String country = XbIds.uicCountry(uics.get(sp.getId()));
            if (country != null && XbProfile.BORDER_PRECEDENCE.contains(country)) {
                votes.merge(country, 1, Integer::sum);
            }
        }
        String best = null;
        int bestVotes = -1;
        for (String country : XbProfile.BORDER_PRECEDENCE) {
            int n = votes.getOrDefault(country, 0);
            if (n > bestVotes) {
                bestVotes = n;
                best = country;
            }
        }
        return bestVotes > 0 ? best : null;
    }

    /// Move a merged-away copy's embedded Quays AND its Levels onto the survivor.
    ///
    /// Running before `EpipSanitize.stripOrphanLevelRefs` is what makes the levels matter: a quay's
    /// `LevelRef` has to resolve against a `Level` its OWN Site defines, and a quay that moves
    /// without its levels leaves a ref that nothing in the new document answers. Moving both keeps
    /// every ref that was valid valid; [#clearOrphanLevelRefs] deals with whatever was already
    /// broken.
    static void moveChildren(StopPlace from, StopPlace to, Counters counters) {
        List<JAXBElement<?>> movable = new ArrayList<>();
        if (from.getQuays() != null) {
            for (JAXBElement<?> el : from.getQuays().getQuayRefOrQuay()) {
                if (el.getValue() instanceof Quay) movable.add(el);
            }
        }
        if (!movable.isEmpty()) {
            if (to.getQuays() == null) to.setQuays(new Quays_RelStructure());
            to.getQuays().getQuayRefOrQuay().addAll(movable);
            counters.quaysMoved += movable.size();
        }
        if (from.getLevels() != null && !from.getLevels().getLevelRefOrLevel().isEmpty()) {
            if (to.getLevels() == null) {
                to.setLevels(new noi.netex.model.Levels_RelStructure());
            }
            List<Object> levels = from.getLevels().getLevelRefOrLevel();
            for (Object l : levels) {
                if (unwrap(l) instanceof Level) counters.levelsMoved++;
            }
            to.getLevels().getLevelRefOrLevel().addAll(levels);
        }
    }

    /// Clear every `LevelRef` on the survivor's quays whose target is not a `Level` the survivor
    /// itself defines. `LevelRef` is `minOccurs="0"`, so absence is conformant; a ref that does not
    /// resolve in the same document is not.
    static int clearOrphanLevelRefs(StopPlace sp) {
        Set<String> own = new HashSet<>();
        if (sp.getLevels() != null) {
            for (Object l : sp.getLevels().getLevelRefOrLevel()) {
                if (unwrap(l) instanceof Level level && level.getId() != null) own.add(level.getId());
            }
        }
        int cleared = 0;
        if (sp.getQuays() != null) {
            for (JAXBElement<?> el : sp.getQuays().getQuayRefOrQuay()) {
                if (!(el.getValue() instanceof Quay q)) continue;
                LevelRefStructure ref = q.getLevelRef();
                if (ref != null && !own.contains(ref.getRef())) {
                    q.setLevelRef(null);
                    cleared++;
                }
            }
        }
        return cleared;
    }

    private static Object unwrap(Object o) {
        return o instanceof JAXBElement<?> el ? el.getValue() : o;
    }

    // ------------------------------------------------------------------------------------- //

    /// (lat, lon) of a StopPlace or a Quay — both carry a centroid, so the common ancestor serves.
    public static double[] coord(Zone_VersionStructure z) {
        SimplePoint_VersionStructure c = z.getCentroid();
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

    /// The embedded Quays that carry their OWN coordinate — the poles. A coordinate-less quay (the
    /// Verbund feeds' synthetic whole-stop quay, for instance) is not a pole.
    public static List<Quay> poleQuays(StopPlace sp) {
        List<Quay> out = new ArrayList<>();
        if (sp.getQuays() != null) {
            for (JAXBElement<?> el : sp.getQuays().getQuayRefOrQuay()) {
                if (el.getValue() instanceof Quay q && coord(q) != null) out.add(q);
            }
        }
        return out;
    }

    private static String privateCode(StopPlace sp) {
        return sp.getPrivateCode() != null ? sp.getPrivateCode().getValue() : null;
    }

    private static long bucket(double lat, double lon) {
        long a = (long) Math.floor(lat / XbProfile.BUCKET_DEG);
        long b = (long) Math.floor(lon / XbProfile.BUCKET_DEG);
        return (a << 32) ^ (b & 0xffffffffL);
    }

    private static String find(Map<String, String> parent, String x) {
        while (!parent.get(x).equals(x)) {
            parent.put(x, parent.get(parent.get(x)));
            x = parent.get(x);
        }
        return x;
    }

    private static void union(Map<String, String> parent, String a, String b) {
        String ra = find(parent, a);
        String rb = find(parent, b);
        if (!ra.equals(rb)) parent.put(rb, ra);
    }
}
