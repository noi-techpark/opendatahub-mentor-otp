package transformers.xb;

// The cross-border stage's read/couple phase: everything between opening the EPIP store and handing
// the write phase a list of things to change. The order of the phases is forced.
//
//   read      calendars, stations                       one pass each over small class maps
//   pass 1    every ServiceJourney                      retains 8 bytes per journey
//   pass 2    the multi-country numbers, by key         retains a Node per plausible journey
//   alias     stations the consolidation left split     expressed as an identity not a merge
//   group     pairwise edges -> connected components
//   tile      one ownership decision per group
//   cut       each surviving leg and its variants
//   link      one interchange per junction per variant
//   quay      one Quay per handover
//   validate  point refs on the final patterns
//
// Nothing here writes. The result is a list of beans to overwrite and a set of keys not to clone,
// which is the only way a v2 store can express a deletion.

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import noi.netex.model.PassengerStopAssignment;
import noi.netex.model.ServiceJourney;
import noi.netex.model.ServiceJourneyInterchange;
import noi.netex.model.TrainNumberRefStructure;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.util.Log;
import toolkit.util.Strictness;
import transformers.xb.XbCalendar.DaySet;
import transformers.xb.XbGroups.Tiling;
import transformers.xb.XbLinks.Counters;
import transformers.xb.XbLinks.End;
import transformers.xb.XbTypes.Dict;
import transformers.xb.XbTypes.Group;
import transformers.xb.XbTypes.Leg;
import transformers.xb.XbTypes.Node;
import transformers.xb.XbTypes.Result;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class XbCouple {

    private XbCouple() {}

    /// The whole read/couple phase over one EPIP store.
    public static Result couple(Store db, Txn txn, String tag) {
        Counters counters = new Counters();
        Dict countries = new Dict();

        Dict publishers = new Dict();
        Map<String, DaySet> dayTypeDates = XbCalendar.readDayTypeDates(db, txn);
        XbScan.Stations stations = XbScan.readStations(db, txn, countries);
        // Line -> mode, read once here rather than per journey: the maps are Lines and patterns,
        // not journeys. Before pass 1, which uses them to classify each journey as rail or not
        // while it has the bean open.
        XbLines.LineMaps<noi.netex.model.AllVehicleModesOfTransportEnumeration> modes =
                XbLines.lineValueMaps(db, txn, noi.netex.model.Line::getTransportMode);
        XbScan.Pass1 p1 = XbScan.pass1(db, txn, dayTypeDates, modes);
        Int2ObjectMap<List<Node>> byNum =
                XbScan.pass2(db, txn, p1, stations, dayTypeDates, countries, publishers, modes);
        modes = null;                                 // spent: folded into each node's mode byte
        // Both are spent: the calendar is folded into each node's DaySet, the stop-point index into
        // each node's station ints.
        dayTypeDates = null;
        stations.releaseStopPointIndex();

        // The foreign-leg identity, applied once so everything after it compares canons. Each slot
        // is rewritten in place: a second map of aliased nodes holds every node twice at the moment
        // the corpus is at its widest.
        //
        // The rail and road pools are classified here, in one pass over the assignments and the
        // patterns — the same classification the consolidation uses.
        long tPsa = System.nanoTime();
        List<PassengerStopAssignment> aliasPsas = new ArrayList<>();
        for (Object o : db.iterOnlyObjects(txn, PassengerStopAssignment.class)) {
            aliasPsas.add((PassengerStopAssignment) o);
        }
        double psaSecs = Log.secs(tPsa);

        long tRail = System.nanoTime();
        java.util.BitSet railPool = new java.util.BitSet(stations.ids().size());
        for (String id : XbStations.railServed(db, txn, aliasPsas, p1.rail())) {
            int st = stations.ids().lookup(id);
            if (st != XbTypes.NONE) railPool.set(st);
        }
        aliasPsas.clear();
        double railSecs = Log.secs(tRail);

        long tAlias = System.nanoTime();
        XbGroups.Alias alias = XbGroups.aliasStations(byNum, stations, railPool);
        double pairSecs = Log.secs(tAlias);
        long tRewrite = System.nanoTime();
        for (List<Node> ns : byNum.values()) {
            for (int i = 0; i < ns.size(); i++) ns.set(i, XbGroups.aliased(ns.get(i), alias.rep()));
        }
        Log.info("%s alias window: assignments read %.1f s, rail/road classification %.1f s, "
                + "pairing %.1f s, node rewrite %.1f s", tag, psaSecs, railSecs, pairSecs,
                Log.secs(tRewrite));

        // After the alias, so the parts already compare canons, and before the grouping, so nothing
        // downstream sees the halves.
        XbStitch.stitch(db, txn, byNum, counters.stitch, tag);

        List<Group> groups = XbGroups.groups(byNum);
        counters.groups = groups.size();
        // Most retained journeys pair with nothing — a number two countries carry is not yet a train
        // two countries publish — and this is the point where the stage is holding the most.
        byNum.clear();

        // ---- the per-group work -------------------------------------------------------------
        Map<String, ServiceJourney> modifiedSj = new LinkedHashMap<>();
        Map<String, Object> modifiedPat = new LinkedHashMap<>();
        Map<String, Set<String>> patternStops = new HashMap<>();   // journey id -> its final stops
        LongOpenHashSet droppedKeys = new LongOpenHashSet();
        List<ServiceJourneyInterchange> links = new ArrayList<>();
        // Deterministic group order: the store's cursor order differs from a rebuild's, and the
        // interchange list is emitted in this order.
        groups.sort(Comparator.<Group, String>comparing(g -> p1.numbers().value(g.num()))
                .thenComparing(g -> g.members().get(0).id));

        // Reason -> how many groups, and a sample of the train numbers.
        Map<String, long[]> refusalCounts = new TreeMap<>();
        Map<String, String> refusals = new TreeMap<>();
        Map<String, String> refusalExample = new TreeMap<>();
        // One writer per journey. `modifiedSj` is keyed by journey id, so a second cut of the same
        // ServiceJourney would overwrite the first and leave links anchored at stops the journey no
        // longer has. The first group to commit keeps it, the second is abandoned whole.
        Set<String> writtenJourneys = new HashSet<>();
        for (Group group : groups) {
            Tiling tiling = XbGroups.tile(group, alias.blind(), alias.narrow());
            if (tiling.refusal() != null) {
                counters.groupsRefused++;
                String why = reason(tiling.refusal());
                refusalCounts.computeIfAbsent(why, k -> new long[1])[0]++;
                refusalExample.putIfAbsent(why, tiling.refusal());
                refusals.merge(why, p1.numbers().value(group.num()),
                        (a, b) -> a.equals(b) ? a : a + "," + b);
                continue;
            }
            Staged staged = new Staged();
            List<List<End>> ends = new ArrayList<>();
            for (int li = 0; li < tiling.legs().size(); li++) {
                Leg leg = tiling.legs().get(li);
                List<End> legEnds = cutLeg(db, txn, leg, p1.sharedPatterns(), staged,
                        li > 0, li + 1 < tiling.legs().size());
                if (legEnds.isEmpty()) {
                    ends = null;
                    break;
                }
                ends.add(legEnds);
                staged.legs++;
                staged.variants += leg.variants().size();
            }
            if (ends == null) {
                counters.groupsAbandoned++;
                continue;
            }
            for (int i = 0; i + 1 < ends.size(); i++) {
                int station = tiling.legs().get(i).lastStation();
                staged.links.addAll(XbLinks.junction(ends.get(i), ends.get(i + 1), station,
                        p1.numbers(), countries, counters));
            }
            // Only now is it known which publications a link actually reached.
            staged.withdrawUnlinkedVariants();
            staged.variantsInexact += tiling.variantsInexact();
            for (Node d : tiling.dropped()) {
                staged.dropped.add(d);
            }
            staged.leftAlone += tiling.leftAlone();
            staged.leftAloneCrossing += tiling.leftAloneCrossing();
            for (int st : tiling.blockedBy()) staged.blockedBy.add(st);
            for (int i = 0; i < tiling.parallelRefusals().length; i++) {
                staged.parallelRefusals[i] += tiling.parallelRefusals()[i];
                staged.parallelRefusalsBlind[i] += tiling.parallelRefusalsBlind()[i];
                staged.parallelRefusalsNarrow[i] += tiling.parallelRefusalsNarrow()[i];
            }
            for (XbGroups.RouteMiss rm : tiling.routeMisses()) {
                if (staged.routeMisses.size() < 4) staged.routeMisses.add(rm);
            }
            Set<String> mine = staged.mutates();
            if (!java.util.Collections.disjoint(mine, writtenJourneys)) {
                counters.groupsAbandonedConflict++;
                continue;
            }
            writtenJourneys.addAll(mine);
            staged.commit(modifiedSj, modifiedPat, patternStops, links, droppedKeys, counters);
        }

        // ---- the postcondition, before anything is written ------------------------------------
        List<ServiceJourneyInterchange> kept = XbLinks.validate(links, patternStops, counters);
        counters.links = kept.size();

        // ---- one Quay per handover ------------------------------------------------------------
        //
        // Driven off the links that survived validation. Sharing a quay mutates an assignment every
        // journey at that stop point uses, so doing it for a link that was then dropped moves an
        // unrelated journey's platform.
        List<String[]> handovers = new ArrayList<>();
        Set<String> handoverRefs = new HashSet<>();
        for (ServiceJourneyInterchange x : kept) {
            String from = x.getFromPointRef().getRef();
            String to = x.getToPointRef().getRef();
            handovers.add(new String[] {from, to});
            handoverRefs.add(from);
            handoverRefs.add(to);
        }
        Map<String, PassengerStopAssignment> psaBySsp = representativeAssignments(db, txn, handoverRefs);
        Map<String, PassengerStopAssignment> modifiedPsas = new LinkedHashMap<>();
        for (String[] h : handovers) {
            XbLinks.shareQuay(h[0], h[1], psaBySsp, modifiedPsas, counters);
        }

        // ---- what the write must not clone ----------------------------------------------------
        Set<String> orphanTn = orphanTrainNumbers(db, txn, droppedKeys, p1.tnRefcount());
        counters.prunedExisting = countPrunedInterchanges(db, txn, droppedIds(db, txn, droppedKeys));

        counters.report(tag);
        reportParallelRefusals(tag, counters);
        reportRouteMisses(tag, counters, stations);
        reportBlockingStations(tag, counters, stations);
        for (Map.Entry<String, long[]> e : refusalCounts.entrySet()) {
            Log.warn("%s %d groups refused -- %s", tag, e.getValue()[0], e.getKey());
            Log.warn("%s     e.g. %s", tag, refusalExample.get(e.getKey()));
            Log.warn("%s     trains %s", tag, truncate(refusals.get(e.getKey())));
        }
        Strictness.require(Strictness.DROPPED_OUTPUT, "droppedOutput", counters.offPattern, "xb",
                "cross-border links named a stop point that is not on the journey's final pattern, "
                + "so the consumer would drop them as unmappable");
        Strictness.require(Strictness.DROPPED_OUTPUT, "droppedOutput", counters.idCollisions, "xb",
                "cross-border interchanges share an (id, version) with another, so the store would "
                + "keep only the last writer and the rest would be lost silently");

        return new Result(List.of(), new ArrayList<>(modifiedPsas.values()), modifiedSj, modifiedPat,
                kept, droppedKeys, orphanTn);
    }

    // ------------------------------------------------------------------------------------- //

    /// One group's pending mutations. Nothing here reaches the shared accumulators until
    /// [#commit] runs, and commit runs only when every leg of the group was cut and linked.
    private static final class Staged {
        final Map<String, ServiceJourney> sj = new LinkedHashMap<>();
        final Map<String, Object> pat = new LinkedHashMap<>();
        final Map<String, Set<String>> stops = new LinkedHashMap<>();
        final List<ServiceJourneyInterchange> links = new ArrayList<>();
        final List<Node> dropped = new ArrayList<>();
        final Set<String> forkedFrom = new HashSet<>();
        long legs;
        long variants;
        long leftAlone;
        long leftAloneCrossing;
        final it.unimi.dsi.fastutil.ints.IntArrayList blockedBy =
                new it.unimi.dsi.fastutil.ints.IntArrayList();
        final long[] parallelRefusals = new long[XbGroups.PARALLEL_REFUSAL.length];
        final long[] parallelRefusalsBlind = new long[XbGroups.PARALLEL_REFUSAL.length];
        final long[] parallelRefusalsNarrow = new long[XbGroups.PARALLEL_REFUSAL.length];
        final List<XbGroups.RouteMiss> routeMisses = new ArrayList<>();
        long truncated;
        long forked;
        long misaligned;
        long variantsInexact;
        long variantsUncuttable;
        long variantsUnlinked;
        long partsLeftWhole;
        long partsKeptWhole;
        long partsUncuttable;

        /// What one variant's cut staged, so it can be taken back out if no link was built for it.
        /// Legs are not recorded: a leg is the publication that OWNS its section, so its truncation
        /// stands on the tiling's ownership decision rather than on any link.
        record VariantCut(String sjId, String patId, boolean forked) {}

        final List<VariantCut> variantCuts = new ArrayList<>();

        /// Un-stage every variant that ended up in no link.
        ///
        /// Truncation happens before linking: `cutLeg` cuts every publication of a leg, and only
        /// afterwards does `XbLinks.junction` decide which pairs actually meet at the handover —
        /// it refused 1,709 of them against 1,842 links built on the 2026-08-30 national run. A
        /// variant that loses that test is left shortened to the leg's endpoints with no
        /// interchange, its through service destroyed rather than merely duplicated.
        ///
        /// The journey and pattern beans here are fresh decodes, not store rows, so a bean that
        /// never reaches `modifiedSj` is cloned exactly as published. `forkedFrom` keeps the
        /// withdrawn entry: it only makes a later cut in this group fork a pattern it might have
        /// sliced, which is the conservative direction.
        void withdrawUnlinkedVariants() {
            if (variantCuts.isEmpty()) return;
            Set<String> linked = new HashSet<>();
            for (ServiceJourneyInterchange x : links) {
                if (x.getFromJourneyRef() != null) linked.add(x.getFromJourneyRef().getRef());
                if (x.getToJourneyRef() != null) linked.add(x.getToJourneyRef().getRef());
            }
            for (VariantCut v : variantCuts) {
                if (linked.contains(v.sjId())) continue;
                sj.remove(v.sjId());
                stops.remove(v.sjId());
                if (v.patId() != null) pat.remove(v.patId());
                truncated--;
                if (v.forked()) forked--;
                variantsUnlinked++;
            }
        }

        void commit(Map<String, ServiceJourney> modifiedSj, Map<String, Object> modifiedPat,
                Map<String, Set<String>> patternStops, List<ServiceJourneyInterchange> allLinks,
                LongOpenHashSet droppedKeys, Counters counters) {
            modifiedSj.putAll(sj);
            modifiedPat.putAll(pat);
            patternStops.putAll(stops);
            allLinks.addAll(links);
            for (Node d : dropped) {
                droppedKeys.add(d.key);
                counters.dropped++;
            }
            counters.legs += legs;
            counters.variants += variants;
            counters.leftAlone += leftAlone;
            counters.leftAloneCrossing += leftAloneCrossing;
            for (int st : blockedBy) counters.blockedBy.merge(st, 1L, Long::sum);
            for (int i = 0; i < parallelRefusals.length; i++) {
                counters.parallelRefusals[i] += parallelRefusals[i];
                counters.parallelRefusalsBlind[i] += parallelRefusalsBlind[i];
                counters.parallelRefusalsNarrow[i] += parallelRefusalsNarrow[i];
            }
            // One per rung, for the reason XbGroups.note gives.
            for (XbGroups.RouteMiss rm : routeMisses) {
                boolean have = false;
                for (XbGroups.RouteMiss seen : counters.routeMisses) {
                    if (seen.refusal() == rm.refusal()) {
                        have = true;
                        break;
                    }
                }
                if (!have) counters.routeMisses.add(rm);
            }
            counters.truncated += truncated;
            counters.patternsForked += forked;
            counters.patternsMisaligned += misaligned;
            counters.variantsInexact += variantsInexact;
            counters.variantsUncuttable += variantsUncuttable;
            counters.variantsUnlinked += variantsUnlinked;
            counters.partsLeftWhole += partsLeftWhole;
            counters.partsKeptWhole += partsKeptWhole;
            counters.partsUncuttable += partsUncuttable;
        }

        /// Every journey this group would mutate — the truncations it staged and the publications
        /// it drops, never one it merely reads. What the conflict guard compares against.
        Set<String> mutates() {
            Set<String> out = new HashSet<>(sj.keySet());
            for (Node d : dropped) out.add(d.id);
            return out;
        }
    }

    /// Cut one leg and every calendar variant of it, and return an [End] per publication.
    ///
    /// A variant covers the same route under a different calendar, so its own positions for the leg's
    /// first and last station are looked up rather than assumed: the two publications need not list
    /// the same intermediate stops, nor list them at the same indices.
    ///
    /// An empty return means this leg could not be cut and the whole group is abandoned — a leg that
    /// was truncated while its neighbour was not would leave a gap no link spans. Everything it did
    /// lands in `staged` and is thrown away with it. A variant is not fatal: one that cannot be cut
    /// is skipped and left as published, the state the tiling found it in.
    ///
    /// Skipping is clean because [XbTruncate#cut] reconciles the journey against its own pattern
    /// before it slices anything: both of its failure returns happen with nothing yet mutated, and
    /// every publication is a fresh decode by full key.
    ///
    /// @param junctionAtStart whether a leg hands over to this one, so a variant must reach the
    ///                        leg's first station. False at the head of the chain, where a
    ///                        publication may start late and is cut to its own first stop.
    /// @param junctionAtEnd   the same at the other end.
    private static List<End> cutLeg(Store db, Txn txn, Leg leg,
            XbScan.SharedPatterns sharedPatterns, Staged staged,
            boolean junctionAtStart, boolean junctionAtEnd) {
        List<End> out = new ArrayList<>();
        List<Node> publications = new ArrayList<>();
        publications.add(leg.node());
        publications.addAll(leg.variants());
        for (Node node : publications) {
            boolean isLeg = node == leg.node();
            int from = isLeg ? leg.from() : node.firstIndexOf(leg.firstStation());
            int to = isLeg ? leg.to() : node.lastIndexOf(leg.lastStation());
            // Mirror parallelTo's relaxation, or the variant it accepted cannot be cut and is lost
            // in staging instead of being refused honestly.
            if (!isLeg && from < 0 && !junctionAtStart) from = 0;
            if (!isLeg && to < 0 && !junctionAtEnd) to = node.size() - 1;
            List<End> ends = from < 0 || to < 0 || from >= to ? List.of()
                    : cutPublication(db, txn, node, from, to, sharedPatterns, staged, !isLeg);
            if (ends.isEmpty()) {
                if (isLeg) return List.of();
                staged.variantsUncuttable++;
                continue;
            }
            out.addAll(ends);
        }
        return out;
    }

    /// Cut one publication — or, when it is a stitched vehicle, each of the journeys it stands for.
    ///
    /// A leg's kept range is a range over the vehicle's positions, and the vehicle may be two or
    /// three published journeys. Each of them is truncated on its own positions and gets its own
    /// [End], because that is what the store holds and what an interchange names.
    ///
    /// A part lying wholly outside the kept range is left as published rather than truncated to
    /// nothing: shortening it would delete a journey on the strength of a tiling decision that was
    /// never made about it.
    ///
    /// Empty means the cut failed, and for the leg that aborts the group. All or nothing per
    /// publication: a vehicle with one part truncated and another not is a gap no link spans.
    private static List<End> cutPublication(Store db, Txn txn, Node node, int from, int to,
            XbScan.SharedPatterns sharedPatterns, Staged staged, boolean isVariant) {
        if (!node.stitched()) {
            End end = cutOne(db, txn, node, from, to, sharedPatterns, staged, isVariant, false);
            return end == null ? List.of() : List.of(end);
        }
        List<End> out = new ArrayList<>();
        for (int p : XbStitch.partsInside(node, from, to)) {
            int[] range = XbStitch.partRange(node, p, from, to);
            Node part = node.parts[p];
            if (range == null) return List.of();
            // A part the leg touches at one stop is kept whole rather than cut: the range can end
            // one position into the next journey — at the handover station, which is where a leg
            // usually ends — and there is no such thing as a journey truncated to one stop. Left at
            // its published extent it is still linkable, since the End below covers all of it.
            boolean single = range[0] >= range[1];
            if (single) {
                staged.partsKeptWhole++;
                range = new int[] {0, part.size() - 1};
            }
            End end = cutOne(db, txn, part, range[0], range[1], sharedPatterns, staged, isVariant,
                    true);
            if (end == null) {
                staged.partsUncuttable++;
                return List.of();
            }
            out.add(end);
        }
        staged.partsLeftWhole += XbStitch.partsOutside(node, from, to).size();
        return out;
    }

    private static End cutOne(Store db, Txn txn, Node node, int from, int to,
            XbScan.SharedPatterns sharedPatterns, Staged staged, boolean isVariant,
            boolean ofStitched) {
        Object o = db.loadObjectByFullKey(txn, node.key);
        if (!(o instanceof ServiceJourney sj) || sj.getPassingTimes() == null) return null;
        boolean whole = from == 0 && to == node.size() - 1;
        List<String> refs;
        if (whole) {
            // Nothing to cut: this publication already covers exactly what it owns. Its pattern is
            // untouched, so it keeps whatever consistency the feed gave it.
            refs = XbScan.stopRefs(sj, sj.getPassingTimes().getTimetabledPassingTime(),
                    new XbScan.PatternCache(db, txn));
            if (refs == null) return null;
        } else {
            String patRef = sj.getJourneyPatternRef() == null ? null
                    : sj.getJourneyPatternRef().getValue().getRef();
            // Fork whenever the pattern is used by more than one journey, and also whenever this
            // group has already forked away from it: a second leg on the same pattern must not slice
            // what the first one left behind.
            boolean shared = patRef != null
                    && (sharedPatterns.isShared(patRef) || staged.forkedFrom.contains(patRef));
            XbTruncate.Cut cut = XbTruncate.cut(db, txn, sj, from, to, shared);
            if (cut == null) return null;
            if (cut.misaligned()) {
                staged.misaligned++;
                return null;
            }
            refs = cut.refs();
            staged.truncated++;
            if (cut.forked()) {
                staged.forked++;
                staged.forkedFrom.add(cut.oldPattern());
            }
            staged.sj.put(sj.getId(), sj);
            if (cut.pattern() != null) staged.pat.put(cut.pattern().getId(), cut.pattern());
            if (isVariant) {
                staged.variantCuts.add(new Staged.VariantCut(sj.getId(),
                        cut.pattern() == null ? null : cut.pattern().getId(), cut.forked()));
            }
        }
        Set<String> stops = new HashSet<>();
        for (String r : refs) {
            if (r != null) stops.add(r);
        }
        staged.stops.put(sj.getId(), stops);
        return new End(node, from, to, refs, sj.getVersion(), ofStitched);
    }

    // ------------------------------------------------------------------------------------- //

    /// The min-id assignment per stop point, restricted to the handover stops: the corpus holds
    /// 418,619 of them and only the handovers are ever written.
    static Map<String, PassengerStopAssignment> representativeAssignments(Store db, Txn txn,
            Set<String> wanted) {
        Map<String, PassengerStopAssignment> best = new HashMap<>();
        if (wanted.isEmpty()) return best;
        for (Object o : db.iterOnlyObjects(txn, PassengerStopAssignment.class)) {
            PassengerStopAssignment psa = (PassengerStopAssignment) o;
            String ssp = XbScan.sspRef(psa);
            if (ssp == null || !wanted.contains(ssp)) continue;
            PassengerStopAssignment cur = best.get(ssp);
            if (cur == null || nz(psa.getId()).compareTo(nz(cur.getId())) < 0) best.put(ssp, psa);
        }
        return best;
    }

    /// TrainNumbers no surviving journey references. The only thing that can orphan one here is a
    /// dropped publication — nothing re-points a journey's train numbers — so the answer is the
    /// pass-1 reference count minus what the dropped journeys held.
    static Set<String> orphanTrainNumbers(Store db, Txn txn, LongOpenHashSet droppedKeys,
            Object2IntMap<String> tnRefcount) {
        Map<String, Integer> lost = new HashMap<>();
        for (long fk : droppedKeys) {
            if (!(db.loadObjectByFullKey(txn, fk) instanceof ServiceJourney sj)) continue;
            if (sj.getTrainNumbers() == null) continue;
            for (TrainNumberRefStructure r : sj.getTrainNumbers().getTrainNumberRef()) {
                lost.merge(r.getRef(), 1, Integer::sum);
            }
        }
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, Integer> e : lost.entrySet()) {
            if (tnRefcount.getInt(e.getKey()) - e.getValue() <= 0) out.add(e.getKey());
        }
        return out;
    }

    /// The ids of the dropped publications, loaded when they are needed rather than carried. There
    /// are a few hundred of them.
    static Set<String> droppedIds(Store db, Txn txn, LongOpenHashSet droppedKeys) {
        Set<String> out = new HashSet<>();
        for (long fk : droppedKeys) {
            if (db.loadObjectByFullKey(txn, fk) instanceof ServiceJourney sj) out.add(sj.getId());
        }
        return out;
    }

    /// How many existing interchanges reference a dropped journey. They are removed by the write's
    /// skip set; this is the number for the transcript.
    ///
    /// The interchanges EPIP generated from JourneyMeetings are in the store already, so dropping a
    /// journey out from under one of them would leave a dangling ref in the export.
    static long countPrunedInterchanges(Store db, Txn txn, Set<String> droppedIds) {
        long n = 0;
        for (Object o : db.iterOnlyObjects(txn, ServiceJourneyInterchange.class)) {
            if (danglesOnto((ServiceJourneyInterchange) o, droppedIds)) n++;
        }
        return n;
    }

    /// Does this interchange reference a journey that is being dropped?
    public static boolean danglesOnto(ServiceJourneyInterchange x, Set<String> droppedIds) {
        return (x.getFromJourneyRef() != null && droppedIds.contains(x.getFromJourneyRef().getRef()))
                || (x.getToJourneyRef() != null && droppedIds.contains(x.getToJourneyRef().getRef()));
    }

    /// How far each publication that stayed uncoupled got before it was refused.
    static void reportParallelRefusals(String tag, Counters counters) {
        long total = 0;
        for (long n : counters.parallelRefusals) total += n;
        if (total == 0) {
            Log.info("%s every uncoupled publication was placed on a leg", tag);
            return;
        }
        long blind = 0;
        for (long n : counters.parallelRefusalsBlind) blind += n;
        long narrow = 0;
        for (long n : counters.parallelRefusalsNarrow) narrow += n;
        Log.warn("%s %d publications stayed uncoupled; how far each got before being refused, and "
                + "how many of each call somewhere the alias is blind (`copy` counts only the "
                + "blind spots next to a space too small to be a region):", tag, total);
        for (int i = 0; i < counters.parallelRefusals.length; i++) {
            Log.warn("%s     %,6d  %,6d blind  %,6d copy  %s", tag, counters.parallelRefusals[i],
                    counters.parallelRefusalsBlind[i], counters.parallelRefusalsNarrow[i],
                    XbGroups.PARALLEL_REFUSAL[i]);
        }
        Log.warn("%s     %,6d of %,6d uncoupled (%.1f %%) call at a station the alias cannot see; "
                + "%,d (%.1f %%) at one hidden behind a copy space, which is the part a finer "
                + "namespace gate could fix without loosening the merge",
                tag, blind, total, total == 0 ? 0.0 : 100.0 * blind / total,
                narrow, total == 0 ? 0.0 : 100.0 * narrow / total);
    }

    /// A handful of route misses, spelled out — the leg's run against the publication's own, in
    /// station ids.
    static void reportRouteMisses(String tag, Counters counters, XbScan.Stations stations) {
        if (counters.routeMisses.isEmpty()) return;
        Log.warn("%s worked examples of route misses:", tag);
        for (XbGroups.RouteMiss rm : counters.routeMisses) {
            Log.warn("%s   %s [%s]", tag, XbGroups.PARALLEL_REFUSAL[rm.refusal()], rm.member());
            Log.warn("%s     leg %s wants %s .. %s", tag, rm.leg(),
                    stations.ids().value(rm.legFirst()), stations.ids().value(rm.legLast()));
            Log.warn("%s     leg run    : %s", tag, names(rm.legRun(), stations));
            Log.warn("%s     member run : %s", tag, names(rm.memberRun(), stations));
        }
    }

    private static String names(int[] st, XbScan.Stations stations) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < st.length && i < 14; i++) {
            if (i > 0) b.append(" -> ");
            b.append(st[i] == XbTypes.NONE ? "?" : stations.ids().value(st[i]));
        }
        if (st.length > 14) b.append(" -> ...(").append(st.length).append(" stops)");
        return b.toString();
    }

    /// The stations that most often defeat a parallel cut, by name.
    static void reportBlockingStations(String tag, Counters counters, XbScan.Stations stations) {
        if (counters.blockedBy.isEmpty()) {
            Log.info("%s no parallel cut was blocked by an unserved stop", tag);
            return;
        }
        List<Map.Entry<Integer, Long>> worst = new ArrayList<>(counters.blockedBy.entrySet());
        worst.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        Log.warn("%s %d parallel cuts refused because a stop they give up is served by no kept leg; "
                + "the %d stations responsible, worst first:",
                tag, worst.stream().mapToLong(Map.Entry::getValue).sum(), worst.size());
        for (int i = 0; i < Math.min(15, worst.size()); i++) {
            Log.warn("%s     %,6d x %s", tag, worst.get(i).getValue(),
                    stations.ids().value(worst.get(i).getKey()));
        }
    }

    private static String reason(String refusal) {
        int at = refusal.indexOf(' ');
        // "member <id> has ..." -> "member has ...": the id belongs in the train list, not the key.
        return refusal.startsWith("member ") || refusal.startsWith("owner ")
                ? refusal.substring(0, at + 1) + refusal.substring(refusal.indexOf(' ', at + 1) + 1)
                : refusal;
    }

    private static String truncate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
