package tools;

import noi.netex.model.LocationStructure;
import noi.netex.model.SimplePoint_VersionStructure;
import noi.netex.model.PublicCodeStructure;
import noi.netex.model.Quay;
import noi.netex.model.StopPlace;
import noi.netex.text.Mls;
import toolkit.store.Store;
import transformers.xb.XbIds;
import toolkit.store.Stores;
import toolkit.store.Txn;
import toolkit.util.Log;
import transformers.xb.XbMergeStops;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// What a UIC arm in the station consolidation would merge, and what it would merge WRONGLY.
///
/// The consolidation clusters purely geographically (~440 m) and trusts no code, so two
/// publications of one station further apart than the radius stay separate and every rule keyed on
/// "the same station" — matching, anchoring, the redundancy test — reads them as different places.
/// Abfaltersbach is the worked case: 1,378 m between the ÖBB and STA copies, both with
/// coordinates, the STA copy carrying the station's true UIC.
///
/// Groups every StopPlace by its canonical UIC (the `XbMergeStops.stopUic` rule: PrivateCode first,
/// falling back to the id, and only when the two-digit prefix is a real UIC country) and reports,
/// per group that spans more than one id namespace:
///
///  - how far apart the members are — under the radius the geographic rule already merges them,
///    over it is what a UIC arm would newly merge;
///  - groups whose members are kilometres apart, which are code collisions a UIC arm must not merge
///    blindly.
///
/// `--bus <store> [<store>…]` reports where the quay-level BUS merge loses its candidates. It
/// rebuilds that merge's candidate population and calls `XbMergeStops.matchQuays` for the verdict
/// on each cross-feed pair, then characterises the failures: equal pole counts, a mutual-nearest
/// violation, a public code disagreement.
///
/// Usage: `tools/StopMergeCensus.java <store.lmdb> [--top N] [--far-metres M]`
///        `tools/StopMergeCensus.java --bus <store.lmdb> [<store.lmdb>…]`
public final class StopMergeCensus {

    /// The radius `XbMergeStops.CONSOLIDATE_RADIUS_DEG` merges at, in metres.
    static final double RADIUS_M = 0.004 * 110_540.0;

    /// One stop, as this census needs it. `fromPrivateCode` says whether its canon came from a
    /// published `PrivateCode` or from `XbMergeStops.stopUic`'s fallback to the ID, which is the
    /// same longest-digit-run guess that proved unreadable on stop points.
    record Stop(String id, String name, String namespace, Double lat, Double lon, boolean fromPrivateCode) {}

    private StopMergeCensus() {}

    public static void main(String[] args) throws Exception {
        Path store = null;
        int top = 25;
        double far = 5_000;
        boolean bus = false;
        boolean nameMerge = false;
        String radii = "15";
        boolean mintMissing = false;
        List<Path> stores = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--top" -> top = Integer.parseInt(args[++i]);
                case "--far-metres" -> far = Double.parseDouble(args[++i]);
                case "--bus" -> bus = true;
                case "--name-merge" -> nameMerge = true;
                case "--radii" -> radii = args[++i];
                case "--mint-missing-quays" -> mintMissing = true;
                default -> {
                    store = Path.of(args[i]);
                    stores.add(store);
                }
            }
        }
        if (bus) {
            busCensus(stores);
            return;
        }
        if (nameMerge) {
            nameMergeCensus(stores, radii, mintMissing);
            return;
        }
        if (store == null) {
            System.err.println("usage: StopMergeCensus <store.lmdb> [--top N] [--far-metres M]");
            System.exit(2);
        }

        Map<String, List<Stop>> byCanon = new LinkedHashMap<>();
        long stops = 0, withCanon = 0, withCoord = 0, fromCode = 0, fromId = 0;
        try (Store db = Stores.open(store, /*readonly=*/true); Txn txn = db.roTxn()) {
            for (Object o : db.iterOnlyObjects(txn, StopPlace.class)) {
                StopPlace sp = (StopPlace) o;
                stops++;
                double[] c = coord(sp);
                if (c != null) withCoord++;
                String canon = stopUic(sp);
                if (canon == null) continue;
                withCanon++;
                String pc = sp.getPrivateCode() != null ? sp.getPrivateCode().getValue() : null;
                boolean published = XbIds.canonUic(pc) != null
                        && XbIds.uicCountry(XbIds.canonUic(pc)) != null;
                if (published) fromCode++;
                else fromId++;
                byCanon.computeIfAbsent(canon, k -> new ArrayList<>()).add(new Stop(sp.getId(),
                        label(sp), namespace(sp.getId()), c == null ? null : c[0], c == null ? null : c[1],
                        published));
            }
        }
        Log.info("[stop-merge] %,d StopPlaces: %,d carry a real UIC canon (%,d from a published "
                + "PrivateCode, %,d only from the id fallback), %,d carry a coordinate",
                stops, withCanon, fromCode, fromId, withCoord);

        // Only groups that span more than one id namespace are candidates to merge: the cross-feed
        // merge never touches two stops of one publisher that share a UIC.
        int multi = 0, multiPublished = 0, alreadyClose = 0, wouldMerge = 0, farApart = 0, noCoord = 0;
        List<Object[]> newMerges = new ArrayList<>();
        List<Object[]> collisions = new ArrayList<>();
        for (Map.Entry<String, List<Stop>> e : byCanon.entrySet()) {
            List<Stop> g = e.getValue();
            Set<String> spaces = new LinkedHashSet<>();
            for (Stop s : g) spaces.add(s.namespace());
            if (g.size() < 2 || spaces.size() < 2) continue;
            multi++;
            boolean allPublished = true;
            for (Stop st : g) allPublished &= st.fromPrivateCode();
            if (allPublished) multiPublished++;
            Double spread = spread(g);
            if (spread == null) {
                noCoord++;
                continue;
            }
            if (spread <= RADIUS_M) {
                alreadyClose++;
            } else if (spread <= far) {
                wouldMerge++;
                newMerges.add(new Object[] {spread, e.getKey(), g});
            } else {
                farApart++;
                collisions.add(new Object[] {spread, e.getKey(), g});
            }
        }
        Log.info("[stop-merge] %,d canonical UICs are shared across id namespaces, %,d of them with "
                + "EVERY member's canon published rather than guessed from an id:", multi, multiPublished);
        Log.info("[stop-merge]   %,6d already within %.0f m — the geographic rule merges these already",
                alreadyClose, RADIUS_M);
        Log.info("[stop-merge]   %,6d between %.0f m and %,.0f m — what a UIC arm would NEWLY merge",
                wouldMerge, RADIUS_M, far);
        Log.info("[stop-merge]   %,6d further than %,.0f m apart — CODE COLLISIONS, must not merge",
                farApart, far);
        Log.info("[stop-merge]   %,6d have a member with no coordinate — undecidable geographically",
                noCoord);

        newMerges.sort(Comparator.comparingDouble(x -> -(Double) x[0]));
        Log.info("[stop-merge] widest of what a UIC arm would newly merge (top %d):", top);
        newMerges.stream().limit(top).forEach(x -> Log.info("[stop-merge]   %,7.0f m  %-10s %s",
                (Double) x[0], x[1], render(x[2])));
        collisions.sort(Comparator.comparingDouble(x -> -(Double) x[0]));
        Log.info("[stop-merge] worst code collisions (top %d):", top);
        collisions.stream().limit(top).forEach(x -> Log.info("[stop-merge]   %,7.0f m  %-10s %s",
                (Double) x[0], x[1], render(x[2])));
    }

    /// Would the real `matchQuays` accept this pair if the public-code test could not fire?
    ///
    /// Asked by clearing the codes, calling the rule, and putting them back. The restore is
    /// required: the stops are read once and a stop appears in several pairs, so codes cleared in
    /// place would carry into every later pair.
    static boolean matchesWithoutCodes(List<Quay> a, List<Quay> b) {
        Map<Quay, PublicCodeStructure> saved = new LinkedHashMap<>();
        for (Quay q : a) saved.put(q, q.getPublicCode());
        for (Quay q : b) saved.put(q, q.getPublicCode());
        saved.keySet().forEach(q -> q.setPublicCode(null));
        try {
            return XbMergeStops.matchQuays(a, b) != null;
        } finally {
            saved.forEach(Quay::setPublicCode);
        }
    }

    /// Where the quay-level bus merge loses candidates, and what a relaxation would buy.
    ///
    /// The population is rebuilt the way `XbMergeStops.mergeBusQuays` builds it — a stop needs a
    /// normalised name, a coordinate and at least TWO pole quays — then grouped by name and
    /// clustered within `BUS_STOP_RADIUS_M`, and every pair from different id namespaces inside a
    /// cluster is put to `matchQuays`.
    ///
    /// Rail-served stops are NOT excluded here as they are in the run, because that set comes from
    /// the journey scan. Every count below is therefore an upper bound on the real candidate
    /// population.
    static void busCensus(List<Path> stores) throws Exception {
        List<StopPlace> all = new ArrayList<>();
        for (Path p : stores) {
            try (Store db = Stores.open(p, true); Txn txn = db.roTxn()) {
                for (Object o : db.iterOnlyObjects(txn, StopPlace.class)) all.add((StopPlace) o);
            }
            Log.info("[bus-merge] read %s (%,d StopPlaces so far)", p, all.size());
        }
        long noName = 0, noCoord = 0, poles0 = 0, poles1 = 0, eligible = 0;
        Map<String, List<StopPlace>> byName = new LinkedHashMap<>();
        for (StopPlace sp : all) {
            String name = XbMergeStops.normName(sp);
            double[] c = XbMergeStops.coord(sp);
            List<Quay> poles = XbMergeStops.poleQuays(sp);
            if (name == null) { noName++; continue; }
            if (c == null) { noCoord++; continue; }
            if (poles.isEmpty()) { poles0++; continue; }
            if (poles.size() < 2) { poles1++; continue; }
            eligible++;
            byName.computeIfAbsent(name, k -> new ArrayList<>()).add(sp);
        }
        Log.info("[bus-merge] %,d StopPlaces: %,d eligible; excluded %,d unnamed, %,d without a "
                + "coordinate, %,d with no pole quay, %,d with exactly one",
                all.size(), eligible, noName, noCoord, poles0, poles1);

        long pairs = 0, sameNs = 0, matched = 0, failed = 0;
        long failEqualCount = 0, failCodeOnly = 0, failFarApart = 0;
        List<String> examples = new ArrayList<>();
        for (Map.Entry<String, List<StopPlace>> e : byName.entrySet()) {
            List<StopPlace> g = e.getValue();
            if (g.size() < 2) continue;
            for (int i = 0; i < g.size(); i++) {
                for (int j = i + 1; j < g.size(); j++) {
                    StopPlace a = g.get(i);
                    StopPlace b = g.get(j);
                    double[] ca = XbMergeStops.coord(a);
                    double[] cb = XbMergeStops.coord(b);
                    if (XbMergeStops.distM(ca, cb) > XbMergeStops.BUS_STOP_RADIUS_M) continue;
                    pairs++;
                    if (XbMergeStops.namespace(a.getId()).equals(XbMergeStops.namespace(b.getId()))) {
                        sameNs++;
                        continue;
                    }
                    List<Quay> qa = XbMergeStops.poleQuays(a);
                    List<Quay> qb = XbMergeStops.poleQuays(b);
                    if (XbMergeStops.matchQuays(qa, qb) != null) {
                        matched++;
                        continue;
                    }
                    failed++;
                    if (qa.size() == qb.size()) failEqualCount++;
                    // What the CODE gate costs on its own: everything else — the mutual-nearest
                    // test, the radius, the injectivity — is unchanged, so a pair that passes only
                    // now is one the code test alone rejected.
                    if (matchesWithoutCodes(qa, qb)) failCodeOnly++;
                    double worst = 0;
                    for (Quay x : qa) {
                        double best = Double.MAX_VALUE;
                        for (Quay y : qb) {
                            best = Math.min(best, XbMergeStops.distM(XbMergeStops.coord(x), XbMergeStops.coord(y)));
                        }
                        worst = Math.max(worst, best);
                    }
                    if (worst > XbMergeStops.BUS_QUAY_RADIUS_M) failFarApart++;
                    if (examples.size() < 10) {
                        examples.add(String.format("%-34s %d vs %d poles, worst nearest-pole %,.0f m  %s | %s",
                                e.getKey(), qa.size(), qb.size(), worst, a.getId(), b.getId()));
                    }
                }
            }
        }
        Log.info("[bus-merge] %,d co-located same-name pairs: %,d same namespace (never merged by "
                + "design), %,d cross-feed", pairs, sameNs, pairs - sameNs);
        Log.info("[bus-merge]   %,d pass the strict quay match, %,d fail", matched, failed);
        Log.info("[bus-merge]   of the failures: %,d have EQUAL pole counts, %,d have a pole further "
                + "than %.0f m from its nearest counterpart, and %,d would MATCH but for the "
                + "public-code test", failEqualCount, failFarApart, XbMergeStops.BUS_QUAY_RADIUS_M,
                failCodeOnly);
        examples.forEach(x -> Log.info("[bus-merge]   e.g. %s", x));
    }

    /// What the same-name / co-located StopPlace merge does to a store, by calling the production
    /// rule.
    ///
    /// Union-find CHAINS, so the counts alone do not say how wide a cluster got. Both distances
    /// that answer that are reported: member to member, and member to the survivor whose centroid
    /// the station inherits.
    ///
    /// Reads only — the plan mutates the in-memory StopPlaces and PSAs, and nothing is written back.
    static void nameMergeCensus(List<Path> stores, String radii, boolean mintMissing)
            throws Exception {
        // Reloaded per radius: planStopplaceMerge rewrites the survivor's quay list and the PSAs'
        // StopPlaceRefs in place, so a second radius run over the same objects would be measuring
        // the first run's output.
        for (String rs : radii.split(",")) {
            double radiusM = Double.parseDouble(rs.trim());
            // Pooled, because every store on disk is already the output of the merge at its own
            // tier and re-running it there is a no-op. The rule only does work on a corpus that has
            // not seen it yet, which is what pooling the inputs reproduces.
            List<StopPlace> sps = new ArrayList<>();
            List<noi.netex.model.PassengerStopAssignment> psas = new ArrayList<>();
            Map<String, double[]> coordBefore = new LinkedHashMap<>();
            for (Path src : stores) {
                try (Store db = Stores.open(src, /*readonly=*/true); Txn txn = db.roTxn()) {
                    for (Object o : db.iterOnlyObjects(txn, StopPlace.class)) {
                        StopPlace sp = (StopPlace) o;
                        sps.add(sp);
                        if (sp.getId() != null) coordBefore.put(sp.getId(), coord(sp));
                    }
                    for (Object o : db.iterOnlyObjects(txn, noi.netex.model.PassengerStopAssignment.class)) {
                        psas.add((noi.netex.model.PassengerStopAssignment) o);
                    }
                }
            }
            Path p = Path.of(stores.size() == 1 ? stores.get(0).toString()
                    : stores.size() + " stores pooled");

            int minted = 0;
            if (mintMissing) minted = mintQuaysForQuaylessStops(stores, sps, psas);
            // The plan rewrites each re-pointed PSA's StopPlaceRef IN PLACE and the MergePlan
            // exposes no member->survivor mapping, so that mapping only exists as the difference
            // between the ref before and after.
            Map<String, String> refBefore = new LinkedHashMap<>();
            for (var psa : psas) {
                var el = psa.getStopPlaceRef();
                if (psa.getId() != null && el != null && el.getValue() != null) {
                    refBefore.put(psa.getId(), el.getValue().getRef());
                }
            }

            var plan = toolkit.transform.common.ScheduledStopPoints
                    .planStopplaceMerge(sps, psas, radiusM);

            Map<String, Set<String>> clusters = new LinkedHashMap<>(); // survivor -> members
            for (var psa : psas) {
                if (!plan.repointed().containsKey(psa.getId())) continue;
                var el = psa.getStopPlaceRef();
                String surv = el == null || el.getValue() == null ? null : el.getValue().getRef();
                String was = refBefore.get(psa.getId());
                if (surv == null || was == null) continue;
                clusters.computeIfAbsent(surv, k -> new LinkedHashSet<>()).add(was);
            }

            double worstDiameter = 0, worstToSurvivor = 0;
            String worstDiaId = "", worstSurvId = "";
            int biggest = 0;
            String biggestId = "";
            List<Double> diameters = new ArrayList<>();
            int over100 = 0, over250 = 0;
            for (Map.Entry<String, Set<String>> e : clusters.entrySet()) {
                List<double[]> pts = new ArrayList<>();
                double[] sc = coordBefore.get(e.getKey());
                if (sc != null) pts.add(sc);
                for (String m : e.getValue()) {
                    double[] mc = coordBefore.get(m);
                    if (mc == null) continue;
                    pts.add(mc);
                    if (sc != null) {
                        double d = haversineM(sc, mc);
                        if (d > worstToSurvivor) {
                            worstToSurvivor = d;
                            worstSurvId = e.getKey();
                        }
                    }
                }
                if (e.getValue().size() + 1 > biggest) {
                    biggest = e.getValue().size() + 1;
                    biggestId = e.getKey();
                }
                double dia = 0;
                for (int i = 0; i < pts.size(); i++) {
                    for (int j = i + 1; j < pts.size(); j++) {
                        dia = Math.max(dia, haversineM(pts.get(i), pts.get(j)));
                    }
                }
                diameters.add(dia);
                if (dia > 100) over100++;
                if (dia > 250) over250++;
                if (dia > worstDiameter) {
                    worstDiameter = dia;
                    worstDiaId = e.getKey();
                }
            }
            java.util.Collections.sort(diameters);
            Log.info("[name-merge] radius %.0f m over %s", radiusM, p);
            Log.info("[name-merge]   %,d StopPlaces in, %,d PSAs in", sps.size(), psas.size());
            Log.info("[name-merge]   dropped %,d | survivors that changed %,d | PSAs re-pointed %,d",
                    plan.dropped().size(), plan.kept().size(), plan.repointed().size());
            Log.info("[name-merge]   %,d clusters visible via re-pointed PSAs; largest %d members (%s)",
                    clusters.size(), biggest, biggestId);
            long quayless = 0, withQuays = 0;
            java.util.TreeSet<String> survivorIds = new java.util.TreeSet<>();
            Map<String, Boolean> anyQuayById = new LinkedHashMap<>();
            for (StopPlace sp : sps) {
                if (sp.getId() == null || plan.dropped().contains(sp.getId())) continue;
                survivorIds.add(sp.getId());
                boolean any = anyQuayById.getOrDefault(sp.getId(), false);
                if (!any && sp.getQuays() != null) {
                    for (var el : sp.getQuays().getQuayRefOrQuay()) {
                        if (el.getValue() instanceof Quay) { any = true; break; }
                    }
                }
                anyQuayById.put(sp.getId(), any);
            }
            for (String id : survivorIds) {
                if (anyQuayById.getOrDefault(id, false)) withQuays++; else quayless++;
            }
            Log.info("[name-merge]   surviving StopPlaces %,d: %,d with quays, %,d WITHOUT",
                    survivorIds.size(), withQuays, quayless);
            if (System.getenv("DUMP_SURVIVORS") != null) {
                java.nio.file.Files.write(java.nio.file.Path.of(System.getenv("DUMP_SURVIVORS")),
                        survivorIds);
            }
            Log.info("[name-merge]   worst survivor-to-member %.1f m (%s)", worstToSurvivor, worstSurvId);
            Log.info("[name-merge]   diameter p50 %.1f  p99 %.1f  p99.9 %.1f  max %.1f m (%s)",
                    pct(diameters, 0.50), pct(diameters, 0.99), pct(diameters, 0.999),
                    worstDiameter, worstDiaId);
            Log.info("[name-merge]   clusters wider than 100 m: %,d (%.3f%%) | wider than 250 m: %,d",
                    over100, diameters.isEmpty() ? 0.0 : 100.0 * over100 / diameters.size(), over250);
        }
    }

    /// Simulate the WIDENED synthesis guard, so its effect on the merge can be measured before the
    /// production transform is changed.
    ///
    /// Today a Quay is minted only alongside a StopPlace we minted ourselves. On the national
    /// corpus 41,364 stops carry ZERO quays and not one of their assignments names a quay.
    ///
    /// This mints exactly what widening the guard would: for every PassengerStopAssignment with no
    /// QuayRef whose StopPlace has no quays, a Quay at the bound ScheduledStopPoint's own position,
    /// attached to EVERY stored version of that StopPlace so the rows stay consistent.
    ///
    /// It matters for the merge because survivor election is "most quays first" — handing a quay to
    /// a stop that had none can change which copy of a duplicated station survives, and hence which
    /// id reaches the export.
    static int mintQuaysForQuaylessStops(List<Path> stores, List<StopPlace> sps,
            List<noi.netex.model.PassengerStopAssignment> psas) throws Exception {
        Map<String, LocationStructure> sspLoc = new LinkedHashMap<>();
        for (Path src : stores) {
            try (Store db = Stores.open(src, /*readonly=*/true); Txn txn = db.roTxn()) {
                for (Object o : db.iterOnlyObjects(txn, noi.netex.model.ScheduledStopPoint.class)) {
                    var ssp = (noi.netex.model.ScheduledStopPoint) o;
                    if (ssp.getId() != null && ssp.getLocation() != null) {
                        sspLoc.putIfAbsent(ssp.getId(), ssp.getLocation());
                    }
                }
            }
        }
        Map<String, List<StopPlace>> byId = new LinkedHashMap<>();
        for (StopPlace sp : sps) {
            if (sp.getId() != null) byId.computeIfAbsent(sp.getId(), k -> new ArrayList<>()).add(sp);
        }
        var factory = new noi.netex.model.ObjectFactory();
        int minted = 0;
        for (var psa : psas) {
            if (psa.getQuayRef() != null) continue;
            var srEl = psa.getStopPlaceRef();
            if (srEl == null || srEl.getValue() == null) continue;
            List<StopPlace> rows = byId.get(srEl.getValue().getRef());
            if (rows == null) continue;
            boolean anyQuay = false;
            for (StopPlace sp : rows) {
                if (sp.getQuays() == null) continue;
                for (var el : sp.getQuays().getQuayRefOrQuay()) {
                    if (el.getValue() instanceof Quay) anyQuay = true;
                }
            }
            if (anyQuay) continue;
            var sspEl = psa.getScheduledStopPointRef();
            String sspId = sspEl == null || sspEl.getValue() == null ? null : sspEl.getValue().getRef();
            LocationStructure loc = sspId == null ? null : sspLoc.get(sspId);
            if (loc == null) continue;                       // no position -> OTP would drop the quay

            String quayId = sspId.replace(":ScheduledStopPoint:", ":Quay:");
            for (StopPlace sp : rows) {
                Quay q = new Quay();
                q.setId(quayId);
                q.setVersion(sp.getVersion() == null || sp.getVersion().isEmpty() ? "1" : sp.getVersion());
                q.setName(sp.getName());
                LocationStructure c = new LocationStructure();
                c.setLatitude(loc.getLatitude());
                c.setLongitude(loc.getLongitude());
                c.setPos(loc.getPos());
                q.setCentroid(new SimplePoint_VersionStructure().withLocation(c));
                var rel = new noi.netex.model.Quays_RelStructure();
                rel.getQuayRefOrQuay().add(factory.createQuay(q));
                sp.setQuays(rel);
            }
            psa.setQuayRef(factory.createQuayRef(
                    new noi.netex.model.QuayRefStructure().withRef(quayId)));
            minted++;
        }
        Log.info("[name-merge]   simulated widened guard: minted %,d quays onto quay-less StopPlaces",
                minted);
        return minted;
    }

    /// The `q` quantile of an already-sorted list, 0 when empty.
    static double pct(List<Double> sorted, double q) {
        if (sorted.isEmpty()) return 0;
        int i = (int) Math.min(sorted.size() - 1L, Math.round(q * (sorted.size() - 1)));
        return sorted.get(i);
    }

    /// Great-circle metres between two {lat, lon} pairs.
    static double haversineM(double[] a, double[] b) {
        double dLat = Math.toRadians(b[0] - a[0]);
        double dLon = Math.toRadians(b[1] - a[1]);
        double s = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(a[0])) * Math.cos(Math.toRadians(b[0]))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6_371_000.0 * 2 * Math.atan2(Math.sqrt(s), Math.sqrt(1 - s));
    }

    static String stopUic(StopPlace sp) {
        return XbMergeStops.stopUic(sp);
    }

    /// The greatest distance between any two members, or null when a member has no coordinate.
    static Double spread(List<Stop> g) {
        double worst = 0;
        for (int i = 0; i < g.size(); i++) {
            if (g.get(i).lat() == null) return null;
            for (int j = i + 1; j < g.size(); j++) {
                if (g.get(j).lat() == null) return null;
                worst = Math.max(worst, distM(g.get(i), g.get(j)));
            }
        }
        return worst;
    }

    static double distM(Stop a, Stop b) {
        double coslat = Math.cos(Math.toRadians(a.lat()));
        double dlat = (a.lat() - b.lat()) * 110_540.0;
        double dlon = (a.lon() - b.lon()) * 111_320.0 * coslat;
        return Math.hypot(dlat, dlon);
    }

    static String namespace(String id) {
        String[] p = id.split(":", 3);
        return p.length >= 2 ? p[0] + ":" + p[1] : p[0];
    }

    static String label(StopPlace sp) {
        String n = Mls.text(sp.getName());
        if (n == null || n.isBlank() || n.chars().allMatch(Character::isDigit)) {
            String s = Mls.text(sp.getShortName());
            if (s != null && !s.isBlank()) return s;
        }
        return n == null || n.isBlank() ? sp.getId() : n;
    }

    @SuppressWarnings("unchecked")
    private static String render(Object group) {
        List<Stop> g = (List<Stop>) group;
        StringBuilder sb = new StringBuilder();
        for (Stop s : g) {
            if (sb.length() > 0) sb.append("  |  ");
            sb.append(s.name()).append(" [").append(s.id()).append(']');
        }
        return sb.toString();
    }

    static double[] coord(StopPlace sp) {
        SimplePoint_VersionStructure c = sp.getCentroid();
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
