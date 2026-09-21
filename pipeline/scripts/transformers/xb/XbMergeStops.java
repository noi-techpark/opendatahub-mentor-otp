package transformers.xb;

// The stop-merge geometry, kept for scripts/tools/StopMergeCensus.java. That census is a standing
// regression check: a non-zero "would newly merge" row means the corpus changed shape under the
// consolidation rule. XbStations reimplemented the clustering the pipeline runs; the bus-quay merge
// here was never ported (~58 merges nationally), so the census is the only place it still exists
// and the only thing that can say what porting it would buy.

// The cross-feed stop consolidation:
//
//   consolidateStops      cluster rail-served StopPlaces purely geographically (all pairs within
//                         ~440 m union), one survivor per physical station with the owned-country
//                         copy preferred, quays moved, PSAs re-pointed
//   mergeBusQuays         quay-level merge of cross-feed duplicate BUS stops, on strict gates:
//                         2+2 poles, mutual-nearest injective pole match, code agreement, and no
//                         PSA on an unmatched quay
//   patternSspRefs        ServiceJourneyPattern id -> its ScheduledStopPoint refs
//   servedFromRailSsp     StopPlace ids a rail-served SSP set maps to via the PSAs
//
// Union fields use the binding's split accessors: the stop-place union maps to
// getStopPlaceRef()/getStopPlace(), the quay union to getQuayRef()/getQuay(), and the
// scheduled-stop-point union to getScheduledStopPointRef(), whose JAXBElement covers the Fare
// variant too since it subclasses ScheduledStopPointRefStructure.

import jakarta.xml.bind.JAXBElement;
import noi.netex.calls.Calls;
import noi.netex.model.AllVehicleModesOfTransportEnumeration;
import noi.netex.model.Call_VersionedChildStructure;
import noi.netex.model.Line;
import noi.netex.model.LocationStructure;
import noi.netex.text.Mls;
import noi.netex.model.PassengerStopAssignment;
import noi.netex.model.PointInLinkSequence_VersionedChildStructure;
import noi.netex.model.Quay;
import noi.netex.model.Quays_RelStructure;
import noi.netex.model.ServiceJourney;
import noi.netex.model.ServiceJourneyPattern;
import noi.netex.model.SimplePoint_VersionStructure;
import noi.netex.model.StopPlace;
import noi.netex.model.StopPointInJourneyPattern_VersionedChildStructure;
import noi.netex.model.Zone_VersionStructure;
import noi.netex.text.Codes;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.util.Log;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class XbMergeStops {

    static final double CONSOLIDATE_RADIUS_DEG = 0.004;
    static final double BUCKET = 0.01;

    private static final Set<String> UIC_COUNTRIES = new HashSet<>(XbProfile.BORDER_PRECEDENCE);

    // Bus overlaps between feeds duplicate stops the rail consolidation never touches. There the
    // same physical pole exists once per feed, so the merge happens at QUAY granularity.
    public static final double BUS_STOP_RADIUS_M = 165.0;  // same-named cross-feed stop copies sit within this
    public static final double BUS_QUAY_RADIUS_M = 100.0;  // pole-to-pole mutual-nearest threshold

    private XbMergeStops() {}

    // ---------------------------------------------------------------- small helpers

    /// (lat, lon) of a StopPlace or a Quay — both carry a centroid, so the common
    /// Zone_VersionStructure ancestor serves. Null when neither form of location is present.
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

    /// The canonical UIC of a StopPlace, from its PrivateCode and falling back to its id, but only
    /// when it is a real UIC country. [XbIds#canonUic] otherwise invents a code from an ÖBB local
    /// id — `at:41:StopPlace:100` yields `10_0` — which must not be read as a UIC.
    public static String stopUic(StopPlace sp) {
        String pc = sp.getPrivateCode() != null ? sp.getPrivateCode().getValue() : null;
        String c = XbIds.canonUic(pc);
        if (c == null) c = XbIds.canonUic(sp.getId());
        Set<String> uicCountries = UIC_COUNTRIES;
        String country = XbIds.uicCountry(c);
        return country != null && uicCountries.contains(country) ? c : null;
    }

    /// The feed prefix of a StopPlace id; `?` when none matches. A stop that scores `?` has no
    /// survivor vote in the border cluster it is in.
    static String feedPrefix(String spId) {
        for (String p : new String[] {"ch:", "IT", "it:", "DE", "de:", "at:"}) {
            if (spId.startsWith(p)) {
                return p.endsWith(":") ? p.substring(0, p.length() - 1) : p;
            }
        }
        return "?";
    }

    /// The country a merged station physically sits in, voted from its members' real UIC codes,
    /// ties by border precedence. Votes are counted in first-encounter order and stable-sorted by
    /// count descending.
    static String owningCountry(List<StopPlace> members, Map<String, String> uics) {
        LinkedHashMap<String, Integer> votes = new LinkedHashMap<>();
        for (StopPlace m : members) {
            String u = uics.get(m.getId());
            if (u != null) {
                votes.merge(XbIds.uicCountry(u), 1, Integer::sum);
            }
        }
        // The filter preserves insertion order.
        Set<String> uicCountries = UIC_COUNTRIES;
        LinkedHashMap<String, Integer> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : votes.entrySet()) {
            if (uicCountries.contains(e.getKey())) filtered.put(e.getKey(), e.getValue());
        }
        if (filtered.isEmpty()) return null;
        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(filtered.entrySet());
        ranked.sort((a, b) -> Integer.compare(b.getValue(), a.getValue())); // stable: ties keep insertion order
        if (ranked.size() == 1 || ranked.get(0).getValue() > ranked.get(1).getValue()) {
            return ranked.get(0).getKey();
        }
        int top = ranked.get(0).getValue();
        Set<String> tied = new HashSet<>();
        for (Map.Entry<String, Integer> e : ranked) {
            if (e.getValue() == top) tied.add(e.getKey());
        }
        for (String c : XbProfile.BORDER_PRECEDENCE) {
            if (tied.contains(c)) return c;
        }
        return ranked.get(0).getKey();
    }

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

    // ---------------------------------------------------------------- rail-served scan

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


    // ---------------------------------------------------------------- station consolidation

    /// Consolidate StopPlaces that describe the same physical station into ONE, across all feeds.
    ///
    /// Only stops in `eligible` (rail-served) are clustered and any other StopPlace passes through
    /// untouched; `eligible == null` clusters everything, for the tests. Per cluster one survivor —
    /// the copy owned by the country the station physically sits in — keeps all the Quays, every
    /// PSA on any member is re-pointed to it, and the merged-away StopPlaces are dropped. Mutates
    /// in place; returns the surviving StopPlaces.
    public static List<StopPlace> consolidateStops(List<StopPlace> stopplaces, List<PassengerStopAssignment> psas,
                                                   Set<String> eligible) {
        List<StopPlace> elig;
        if (eligible == null) {
            elig = stopplaces;
        } else {
            elig = new ArrayList<>();
            for (StopPlace sp : stopplaces) {
                if (eligible.contains(sp.getId())) elig.add(sp);
            }
        }
        Map<String, double[]> coords = new HashMap<>();
        Map<String, String> uics = new HashMap<>();
        for (StopPlace sp : elig) {
            coords.put(sp.getId(), coord(sp));
            uics.put(sp.getId(), stopUic(sp));
        }

        Map<String, String> parent = new HashMap<>();
        for (StopPlace sp : elig) parent.put(sp.getId(), sp.getId());

        // Cluster the rail-served stops purely geographically: union EVERY pair within ~440 m,
        // all-pairs rather than nearest-only. Codes are never trusted.
        record Cell(long x, long y) {}
        Map<Cell, List<String>> buckets = new HashMap<>();
        for (StopPlace sp : elig) {
            double[] c = coords.get(sp.getId());
            if (c != null) {
                buckets.computeIfAbsent(new Cell(Math.round(c[0] / BUCKET), Math.round(c[1] / BUCKET)),
                        k -> new ArrayList<>()).add(sp.getId());
            }
        }
        double r2 = CONSOLIDATE_RADIUS_DEG * CONSOLIDATE_RADIUS_DEG;
        for (StopPlace sp : elig) {
            double[] c = coords.get(sp.getId());
            if (c == null) continue;
            double coslat = Math.cos(Math.toRadians(c[0]));
            long bx = Math.round(c[0] / BUCKET);
            long by = Math.round(c[1] / BUCKET);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (String oid : buckets.getOrDefault(new Cell(bx + dx, by + dy), List.of())) {
                        double[] oc = coords.get(oid);
                        if (oid.equals(sp.getId()) || oc == null) continue;
                        double dlat = c[0] - oc[0];
                        double dlon = (c[1] - oc[1]) * coslat;
                        if (dlat * dlat + dlon * dlon <= r2) union(parent, sp.getId(), oid);
                    }
                }
            }
        }

        Map<String, List<StopPlace>> clusters = new LinkedHashMap<>();
        for (StopPlace sp : elig) {
            clusters.computeIfAbsent(find(parent, sp.getId()), k -> new ArrayList<>()).add(sp);
        }

        Set<String> mergedAway = new HashSet<>();
        Map<String, String> repoint = new HashMap<>();
        int movedQuays = 0;
        int stations = 0;
        for (List<StopPlace> members : clusters.values()) {
            if (members.size() < 2) continue;
            stations++;
            String owner = owningCountry(members, uics);

            // Survivor key, in order: does the feed's country match the owner, does the stop carry
            // a UIC, then the id. The minimum is the FIRST minimal entry (strict <). Objects.equals
            // treats two misses as equal when both the map miss and the owner are null.
            StopPlace survivor = null;
            long survivorKey = 0;
            for (StopPlace sp : members) {
                long k = (Objects.equals(XbIds.feedCountry(sp.getId()), owner) ? 0 : 2)
                        + (uics.get(sp.getId()) != null ? 0 : 1);
                if (survivor == null || k < survivorKey
                        || (k == survivorKey && sp.getId().compareTo(survivor.getId()) < 0)) {
                    survivor = sp;
                    survivorKey = k;
                }
            }
            for (StopPlace sp : members) {
                if (sp.getId().equals(survivor.getId())) continue;
                List<JAXBElement<?>> quays = sp.getQuays() != null
                        ? sp.getQuays().getQuayRefOrQuay() : List.of();
                List<JAXBElement<?>> movable = new ArrayList<>();
                for (JAXBElement<?> el : quays) {
                    if (el.getValue() instanceof Quay) movable.add(el);
                }
                if (!movable.isEmpty()) {
                    if (survivor.getQuays() == null) survivor.setQuays(new Quays_RelStructure());
                    survivor.getQuays().getQuayRefOrQuay().addAll(movable);
                    movedQuays += movable.size();
                }
                mergedAway.add(sp.getId());
                repoint.put(sp.getId(), survivor.getId());
            }
        }

        int repointed = 0;
        for (PassengerStopAssignment psa : psas) {
            // Exactly the getStopPlaceRef() side of the union.
            JAXBElement<? extends noi.netex.model.StopPlaceRefStructure> el = psa.getStopPlaceRef();
            if (el != null && repoint.containsKey(el.getValue().getRef())) {
                el.getValue().setRef(repoint.get(el.getValue().getRef()));
                repointed++;
            }
        }

        Log.info("[merge_stops] consolidated %d StopPlaces (%d quays) into "
                + "%d physical stations, re-pointed %d PassengerStopAssignments",
                mergedAway.size(), movedQuays, stations, repointed);
        List<StopPlace> out = new ArrayList<>();
        for (StopPlace sp : stopplaces) {
            if (!mergedAway.contains(sp.getId())) out.add(sp);
        }
        return out;
    }

    // ---------------------------------------------------------------- bus-quay merge helpers

    /// The accent- and punctuation-folded StopPlace name, lowercased, with runs of
    /// non-alphanumerics collapsed to ONE SPACE and stripped; null when the result is empty. Read
    /// through `Mls.text`, the accessor seam, which joins mixed content where the schema version
    /// carries it.
    public static String normName(StopPlace sp) {
        String raw = Mls.text(sp.getName());
        if (raw == null || raw.isEmpty()) return null;
        String d = Normalizer.normalize(raw, Normalizer.Form.NFKD);
        StringBuilder sb = new StringBuilder(d.length());
        for (int i = 0; i < d.length(); i++) {
            char c = d.charAt(i);
            if (c < 128) sb.append(c);
        }
        String s = sb.toString().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
        return s.isEmpty() ? null : s;
    }

    /// The embedded Quays that carry their own coordinate — the poles. A coordinate-less quay (the
    /// Verbund feeds' synthetic whole-stop `HoB` quay, for instance) is not a pole and never merges.
    public static List<Quay> poleQuays(StopPlace sp) {
        List<Quay> out = new ArrayList<>();
        if (sp.getQuays() != null) {
            for (JAXBElement<?> el : sp.getQuays().getQuayRefOrQuay()) {
                if (el.getValue() instanceof Quay q && coord(q) != null) out.add(q);
            }
        }
        return out;
    }

    /// The feed-ish id namespace, the first two tokens: it separates the Swiss feed's own records
    /// (`ch:2:...`) from an Austrian feed's copy of a Swiss stop (`ch:23016:...`).
    public static String namespace(String spId) {
        String[] parts = spId.split(":", 3); // the limit keeps trailing empties
        return parts.length >= 2 ? parts[0] + ":" + parts[1] : parts[0];
    }

    /// Equirectangular metre distance.
    public static double distM(double[] a, double[] b) {
        double coslat = Math.cos(Math.toRadians(a[0]));
        double dlat = (a[0] - b[0]) * 110_540.0;
        double dlon = (a[1] - b[1]) * 111_320.0 * coslat;
        return Math.sqrt(dlat * dlat + dlon * dlon);
    }

    /// The Quay's public code, stripped and case-folded; null when absent or empty.
    ///
    /// Read through [noi.netex.text.Codes#publicCode], the version-selected seam: the newer schema
    /// retypes the code onto a structure carrying an extra `type` attribute, which neither side
    /// reads. Same characters at both versions.
    public static String normCode(Quay q) {
        String v = Codes.publicCode(q);
        return v != null && !v.isEmpty() ? v.strip().toLowerCase(java.util.Locale.ROOT) : null;
    }

    /// Strict 1:1 pole matching: every foreign quay must have a mutual-nearest local quay within
    /// BUS_QUAY_RADIUS_M, no two foreign quays may claim the same local one, and when both sides
    /// carry a public code the codes must agree. Returns foreign quay id -> local quay id, or null.
    /// Nearest ties resolve to the FIRST minimal pool entry in list order.
    public static Map<String, String> matchQuays(List<Quay> foreign, List<Quay> local) {
        LinkedHashMap<String, double[]> fc = new LinkedHashMap<>();
        for (Quay q : foreign) fc.put(q.getId(), coord(q));
        LinkedHashMap<String, double[]> lc = new LinkedHashMap<>();
        for (Quay q : local) lc.put(q.getId(), coord(q));

        Map<String, String> mapping = new LinkedHashMap<>();
        Map<String, Quay> localById = new HashMap<>();
        for (Quay q : local) localById.put(q.getId(), q);
        for (Quay q : foreign) {
            String li = nearest(fc.get(q.getId()), lc);
            if (distM(fc.get(q.getId()), lc.get(li)) > BUS_QUAY_RADIUS_M) return null;
            if (!nearest(lc.get(li), fc).equals(q.getId())) return null; // mutual nearest, both directions
            String fCode = normCode(q);
            String lCode = normCode(localById.get(li));
            if (fCode != null && lCode != null && !fCode.equals(lCode)) return null;
            mapping.put(q.getId(), li);
        }
        if (new HashSet<>(mapping.values()).size() != mapping.size()) return null; // injective
        return mapping;
    }

    /// The pool key nearest `c`; the FIRST minimal one on ties (strict <).
    private static String nearest(double[] c, LinkedHashMap<String, double[]> pool) {
        String best = null;
        double bestD = 0;
        for (Map.Entry<String, double[]> e : pool.entrySet()) {
            double d = distM(c, e.getValue());
            if (best == null || d < bestD) {
                best = e.getKey();
                bestD = d;
            }
        }
        if (best == null) throw new IllegalStateException("min() arg is an empty sequence");
        return best;
    }

    // ---------------------------------------------------------------- bus-quay merge

    /// Quay-level merge of cross-feed duplicate bus stops: within same-named, co-located
    /// (&lt;= BUS_STOP_RADIUS_M) stops from DIFFERENT id namespaces, replace the foreign copy's
    /// pole quays with the local ones (re-pointing its PSAs' Quay/StopPlace refs) and drop the
    /// foreign StopPlace. The gates are the 2+2 rule, a mutual-nearest injective pole match with
    /// code agreement, every PSA-referenced quay of the foreign stop being in the match, and stops
    /// in `exclude` (rail-served) never participating. Any gate failing leaves the pair as-is.
    /// Mutates psas' refs in place; returns the surviving StopPlaces.
    public static List<StopPlace> mergeBusQuays(List<StopPlace> survivors, List<PassengerStopAssignment> psas,
                                                Set<String> exclude) {
        Map<String, Set<String>> psaQuayRefs = new HashMap<>(); // stop id -> quay ids its PSAs reference
        for (PassengerStopAssignment psa : psas) {
            String spRef = psaStopRef(psa);
            JAXBElement<? extends noi.netex.model.QuayRefStructure> qEl = psa.getQuayRef();
            if (spRef != null && !spRef.isEmpty() && qEl != null) {
                psaQuayRefs.computeIfAbsent(spRef, k -> new HashSet<>()).add(qEl.getValue().getRef());
            }
        }

        // Candidates: non-rail stops satisfying the 2+2 rule's per-stop half, grouped by name.
        Map<String, List<StopPlace>> byName = new HashMap<>();
        Map<String, List<Quay>> poles = new HashMap<>();
        for (StopPlace sp : survivors) {
            if (exclude.contains(sp.getId())) continue;
            String name = normName(sp);
            double[] c = coord(sp);
            List<Quay> pole = poleQuays(sp);
            if (name == null || c == null || pole.size() < 2) continue;
            poles.put(sp.getId(), pole);
            byName.computeIfAbsent(name, k -> new ArrayList<>()).add(sp);
        }

        Map<String, String> uics = new HashMap<>();
        for (List<StopPlace> group : byName.values()) {
            for (StopPlace sp : group) uics.put(sp.getId(), stopUic(sp));
        }
        Map<String, String> quayRepoint = new HashMap<>();
        Map<String, String> stopRepoint = new HashMap<>();
        LinkedHashMap<String, Integer> skipped = new LinkedHashMap<>(); // reason -> count, log only

        List<String> names = new ArrayList<>(byName.keySet());
        Collections.sort(names);
        for (String name : names) {
            List<StopPlace> group = new ArrayList<>(byName.get(name));
            group.sort((a, b) -> a.getId().compareTo(b.getId()));
            if (group.size() < 2) continue;
            // Cluster the name group geographically (groups are tiny — all-pairs union-find).
            Map<String, String> parent = new HashMap<>();
            for (StopPlace sp : group) parent.put(sp.getId(), sp.getId());
            Map<String, double[]> coords = new HashMap<>();
            for (StopPlace sp : group) coords.put(sp.getId(), coord(sp));
            for (int i = 0; i < group.size(); i++) {
                StopPlace a = group.get(i);
                for (int j = i + 1; j < group.size(); j++) {
                    StopPlace b = group.get(j);
                    if (distM(coords.get(a.getId()), coords.get(b.getId())) <= BUS_STOP_RADIUS_M) {
                        parent.put(find(parent, b.getId()), find(parent, a.getId()));
                    }
                }
            }
            Map<String, List<StopPlace>> clusters = new LinkedHashMap<>();
            for (StopPlace sp : group) {
                clusters.computeIfAbsent(find(parent, sp.getId()), k -> new ArrayList<>()).add(sp);
            }

            for (List<StopPlace> members : clusters.values()) {
                if (members.size() < 2) continue;
                // The local survivor is the FIRST minimal entry under compareLocalKey (strict <).
                StopPlace local = null;
                for (StopPlace sp : members) {
                    if (local == null || compareLocalKey(sp, local, uics, poles) < 0) local = sp;
                }
                for (StopPlace sp : members) {
                    if (sp.getId().equals(local.getId()) || namespace(sp.getId()).equals(namespace(local.getId()))) {
                        // The survivor itself merges 0 rather than being passed over silently, so
                        // the key exists in the log whenever this branch is reached at all.
                        skipped.merge("same_namespace", sp.getId().equals(local.getId()) ? 0 : 1, Integer::sum);
                        continue;
                    }
                    Map<String, String> mapping = matchQuays(poles.get(sp.getId()), poles.get(local.getId()));
                    if (mapping == null) {
                        skipped.merge("no_strict_quay_match", 1, Integer::sum);
                        continue;
                    }
                    if (!mapping.keySet().containsAll(psaQuayRefs.getOrDefault(sp.getId(), Set.of()))) {
                        skipped.merge("referenced_non_pole_quay", 1, Integer::sum);
                        continue;
                    }
                    quayRepoint.putAll(mapping);
                    stopRepoint.put(sp.getId(), local.getId());
                }
            }
        }

        int repointedStop = 0;
        int repointedQuay = 0;
        for (PassengerStopAssignment psa : psas) {
            JAXBElement<? extends noi.netex.model.StopPlaceRefStructure> spEl = psa.getStopPlaceRef();
            if (spEl != null && stopRepoint.containsKey(spEl.getValue().getRef())) {
                spEl.getValue().setRef(stopRepoint.get(spEl.getValue().getRef()));
                repointedStop++;
            }
            JAXBElement<? extends noi.netex.model.QuayRefStructure> qEl = psa.getQuayRef();
            if (qEl != null && quayRepoint.containsKey(qEl.getValue().getRef())) {
                qEl.getValue().setRef(quayRepoint.get(qEl.getValue().getRef()));
                repointedQuay++;
            }
        }

        Log.info("[merge_bus_quays] merged %d duplicate bus stops onto local poles "
                + "(%d quays, %d stop refs / %d quay refs re-pointed); skipped: %s",
                stopRepoint.size(), quayRepoint.size(), repointedStop, repointedQuay,
                skipped.isEmpty() ? "none" : skipped.toString());
        List<StopPlace> out = new ArrayList<>();
        for (StopPlace sp : survivors) {
            if (!stopRepoint.containsKey(sp.getId())) out.add(sp);
        }
        return out;
    }

    /// The bus-quay merge's local-survivor key, compared in order: carries a UIC before does not,
    /// then most poles first, then smallest id.
    private static int compareLocalKey(StopPlace a, StopPlace b,
                                       Map<String, String> uics, Map<String, List<Quay>> poles) {
        int ka = uics.get(a.getId()) != null ? 0 : 1;
        int kb = uics.get(b.getId()) != null ? 0 : 1;
        if (ka != kb) return Integer.compare(ka, kb);
        int pa = -poles.get(a.getId()).size();
        int pb = -poles.get(b.getId()).size();
        if (pa != pb) return Integer.compare(pa, pb);
        return a.getId().compareTo(b.getId());
    }

    // ----------------------------------------------------------------------------------------

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
