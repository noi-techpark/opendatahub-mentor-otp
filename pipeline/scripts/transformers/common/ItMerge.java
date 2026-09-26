package transformers.common;

// Consolidating merge of Italian feed databases into one, used at both tiers of the RAP pipeline:
// the per-operator union, then the national union. Two things beyond a plain N-source clone, which
// on a duplicate (id, version) would silently drop the shadow:
//
//   1. QUALIFY colliding ids instead of dropping. A later source's object whose id an earlier
//      source already occupies is re-inserted as `<id>:<tag>` (tag = the source's operator code,
//      or an `x<ordinal>` fallback), with every reference to it within the same source rewritten.
//   2. CONSOLIDATE co-located same-name StopPlaces (<= ~15 m) over the whole merged corpus.
//      StopPlace and PassengerStopAssignment are buffered — held back from every clone, collected
//      across all sources, and emitted once at the tail, a db-to-db delete being "never copied in
//      the first place".
//
// The bulk of each source is a raw byte-level clone with a cumulative key offset. Only three small
// sets go object-level: the buffered classes, the colliding objects, and the PATCH set —
// everything referencing either, found over BOTH reference maps. The unresolved map matters as
// much as the resolved one: a row whose stored target id is in the qualified set would otherwise
// be bound by the final resolve() to the EARLIER source's object, wiring one operator's journeys
// onto another operator's line.
//
// Re-insert order is by stored little-endian key, so a merge is deterministic. A non-empty target
// is refused, as is a source directory that holds no data.mdb.

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import noi.netex.model.EntityStructure;
import noi.netex.model.PassengerStopAssignment;
import noi.netex.model.ScheduledStopPoint;
import noi.netex.model.StopPlace;
import noi.netex.model.VersionOfObjectRefStructure;
import toolkit.harness.CloneSupport;
import toolkit.keycodec.NulKeyCodec;
import toolkit.model.RecursiveAttributes;
import toolkit.store.ObjectRow;
import toolkit.store.Store;
import toolkit.store.Stores;
import toolkit.store.Txn;
import toolkit.transform.common.ScheduledStopPoints;
import toolkit.util.Log;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

public class ItMerge {

    private static boolean isBuffered(Class<?> c) {
        return c == StopPlace.class || c == PassengerStopAssignment.class;
    }

    /// a collision-free variant of `oid` tagged by its source.
    static String qualified(String oid, String tag) {
        return (tag != null && !tag.isEmpty()) ? oid + ":" + tag : oid + ":x";
    }

    /// the explicit tag — an empty one counting as absent — or, for untagged sources, a positional
    /// `x<ordinal>` (1-based). The fallback MUST be unique per source: a shared ":x" would map two
    /// different sources' collisions with an earlier id onto the IDENTICAL qualified id.
    static String effectiveTag(String tag, int ordinal) {
        return (tag != null && !tag.isEmpty()) ? tag : "x" + ordinal;
    }

    /// The (class name, id) key of the seen/seen-buffered sets — NUL-joined, because an XML id
    /// cannot hold a NUL.
    private static String classIdKey(String className, String id) {
        return className + '\0' + id;
    }

    /// rewrite every reference `obj` holds whose target id is in
    /// `remap` (and, when `renameSelf`, its own id). The refs the walk yields are the
    /// live objects in the tree, so mutating in place is enough; insert re-extracts the index.
    static void applyRemap(Object obj, Map<String, String> remap, boolean renameSelf) {
        if (renameSelf && obj instanceof EntityStructure e && e.getId() != null) {
            String mapped = remap.get(e.getId());
            if (mapped != null) e.setId(mapped);
        }
        RecursiveAttributes.walk(obj, new RecursiveAttributes.Visitor() {
            @Override
            public void reference(VersionOfObjectRefStructure ref) {
                String r = ref.getRef();
                if (r == null) return;
                String mapped = remap.get(r);
                if (mapped != null) ref.setRef(mapped);
            }

            @Override
            public void node(Object o) {}
        });
    }

    /// The merge phase function. Public so tests drive the production path.
    public static void merge(List<Path> sources, Path target, List<String> tags,
            boolean qualify, boolean consolidateStops, boolean fastPath, String format)
            throws Exception {
        merge(sources, target, tags, qualify, consolidateStops, fastPath, format, false);
    }

    /// As above, with `synthesizeStops` running the SSP synthesis over the whole merged corpus
    /// before the consolidation plan.
    public static void merge(List<Path> sources, Path target, List<String> tags,
            boolean qualify, boolean consolidateStops, boolean fastPath, String format,
            boolean synthesizeStops)
            throws Exception {
        merge(sources, target, tags, qualify, consolidateStops, fastPath, format, synthesizeStops,
                0);
    }

    /// As above, at a caller-chosen consolidation radius; `mergeRadiusM <= 0` takes the toolkit's,
    /// which stays the only place the national figure is written down.
    ///
    /// A wider one is for a merge whose inputs are all rail. The national radius answers for a
    /// corpus holding urban stops 150 m apart, where two publishers' copies of one station sit
    /// further apart than two neighbouring stops do; over rail publishers alone that tension is
    /// gone, because the stations are sparse and a second copy is the only thing nearby.
    public static void merge(List<Path> sources, Path target, List<String> tags,
            boolean qualify, boolean consolidateStops, boolean fastPath, String format,
            boolean synthesizeStops, double mergeRadiusM)
            throws Exception {
        if (tags == null) tags = new ArrayList<>(Collections.nCopies(sources.size(), (String) null));
        if (tags.size() != sources.size()) throw new IllegalArgumentException("tags must match sources 1:1");

        List<StopPlace> stopBuf = new ArrayList<>();              // buffered StopPlaces (post-remap)
        List<PassengerStopAssignment> psaBuf = new ArrayList<>(); // buffered PSAs (post-remap)
        Set<String> seenBuffered = new LinkedHashSet<>();         // (class name, id) buffered so far

        try (Store tdb = Stores.create(target, format)) {
            long offset = 0;
            // The target inherits the blob-repair marker only if every source carries it: the only
            // refs this merge touches re-enter DB_UNRESOLVED and are repaired by resolve() pass-1
            // at bind time, so the target's full sweep is skippable.
            boolean allSourcesSwept = true;
            for (int i = 0; i < sources.size(); i++) {
                Path src = sources.get(i);
                String tag = effectiveTag(tags.get(i), i + 1);
                try (Store sdb = Stores.open(src, true); Txn rtx = sdb.roTxn()) {
                    allSourcesSwept = allSourcesSwept && sdb.blobRepairMarkerPresent(rtx);
                    if (!fastPath) {
                        offset = mergeSourceObjectLevel(sdb, rtx, tdb, src, tag, qualify,
                                consolidateStops || synthesizeStops, seenBuffered, stopBuf, psaBuf);
                        continue;
                    }

                    Map<String, String> remap = new LinkedHashMap<>();
                    List<Class<?>> buffered = new ArrayList<>();
                    if (consolidateStops || synthesizeStops) {
                        for (Class<?> c : sdb.dbNames(rtx)) if (isBuffered(c)) buffered.add(c);
                    }

                    // (a) read the buffered classes — needed whole for the consolidation plan, and
                    // held back from the clone. Their own id collisions are settled here: they
                    // never reach the target's id index, so duplicateIdFullKeys cannot see them.
                    LongOpenHashSet bufKeys = new LongOpenHashSet();
                    List<Object> pending = new ArrayList<>();
                    for (Class<?> clazz : buffered) {
                        String cn = clazz.getSimpleName();
                        for (ObjectRow row : sdb.iterObjects(rtx, clazz)) {
                            bufKeys.add(sdb.fullKeyOf(clazz, row.localKey()));
                            EntityStructure obj = (EntityStructure) row.object();
                            if (qualify && seenBuffered.contains(classIdKey(cn, String.valueOf(obj.getId())))) {
                                String newId = qualified(String.valueOf(obj.getId()), tag);
                                remap.put(String.valueOf(obj.getId()), newId);
                                obj.setId(newId);
                            }
                            pending.add(obj);
                        }
                    }

                    LongOpenHashSet dups;
                    List<Object> dupObjs = new ArrayList<>();
                    LongOpenHashSet skip;
                    LongOpenHashSet patch;
                    try (Txn wtx = tdb.rwTxn()) {
                        // (b) the objects whose (id, version) an earlier source already occupies.
                        // Skipped by the clone either way: qualified + re-inserted below, or
                        // dropped outright (no-qualify).
                        dups = offset != 0 ? CloneSupport.duplicateIdFullKeys(sdb, rtx, tdb, wtx)
                                : new LongOpenHashSet();
                        if (qualify) {
                            // A set iterates unpredictably; the re-insert order must be stable.
                            LongArrayList sorted = new LongArrayList(dups);
                            sorted.sort(NulKeyCodec::compareFullKeysLe);
                            for (long k : sorted) {
                                Object o = sdb.loadObjectByFullKey(rtx, k);
                                if (o != null && ((EntityStructure) o).getId() != null) dupObjs.add(o);
                            }
                            for (Object o : dupObjs) {
                                String oid = ((EntityStructure) o).getId();
                                remap.put(oid, qualified(oid, tag));
                            }
                        }
                        skip = new LongOpenHashSet(dups);
                        skip.addAll(bufKeys);
                        // (c) everything referencing a skipped object or a qualified id — over
                        // BOTH reference maps (resolved outward + unresolved). The ids are
                        // matched as raw UTF-8, as stored in the index; the helper subtracts skip.
                        patch = CloneSupport.referrerFullKeys(sdb, rtx, skip, null, remap.keySet());
                        tdb.cloneFrom(sdb, rtx, wtx, skip.isEmpty() ? null : skip::contains, offset);
                        wtx.commit();
                    }

                    try (Txn wtx = tdb.rwTxn()) {
                        // The colliding objects under their qualified ids, then everything that
                        // references them or a buffered object, refs rewritten.
                        for (Object o : dupObjs) applyRemap(o, remap, true);
                        tdb.insertAnyObjects(wtx, concat(dupObjs.iterator(),
                                remapped(CloneSupport.loadPatchObjects(sdb, rtx, patch), remap)));
                        // The ONLY safe next key offset — never cloneFrom's return, never
                        // max-key+1: the object-level inserts above allocate from the sequence too.
                        offset = tdb.sequence(wtx, 0);
                        wtx.commit();
                    }

                    // (d) the buffered objects join the global pool, with this source's qualified
                    // ids rewritten in their references.
                    for (Object obj : pending) {
                        if (!remap.isEmpty()) applyRemap(obj, remap, false);
                        if (obj instanceof StopPlace sp) stopBuf.add(sp);
                        else psaBuf.add((PassengerStopAssignment) obj);
                        seenBuffered.add(classIdKey(obj.getClass().getSimpleName(),
                                String.valueOf(((EntityStructure) obj).getId())));
                    }

                    String msg = String.format("[it-merge] cloned %s (next offset %d)", src, offset);
                    if (qualify && !remap.isEmpty()) {
                        msg += String.format(" -- qualified %d colliding ids (tag %s)", remap.size(), tag);
                    } else if (!dups.isEmpty()) {
                        msg += String.format(" -- dropped %d duplicate ids (no-qualify)", dups.size());
                    }
                    if (!patch.isEmpty()) {
                        msg += String.format(", re-extracted %d referrers", patch.size());
                    }
                    Log.info("%s", msg);
                }
            }

            // Synthesise the missing stops BEFORE the consolidation, over the whole merged corpus,
            // and not per operator: the ids it derives are pure string substitutions on a
            // ScheduledStopPoint id, so they are unique exactly when the SSP ids are, which is only
            // once qualification above has settled them. An embedded Quay's own id is neither a
            // top-level id nor a reference, so `applyRemap` would leave it holding the losing
            // source's value.
            //
            // stopBuf/psaBuf hold every StopPlace and PSA in the corpus and nothing has been
            // written yet, so the guards come from them. Reading them off `tdb` would see neither
            // -- both classes were held back from the clone at (a) -- and synthesise a duplicate
            // for every already-bound SSP in the country.
            if (synthesizeStops) {
                Set<String> assigned = new LinkedHashSet<>();
                for (PassengerStopAssignment psa : psaBuf) {
                    assigned.addAll(ScheduledStopPoints.assignedSspRef(psa));
                }
                Set<String> existing = new LinkedHashSet<>();
                for (StopPlace sp : stopBuf) if (sp.getId() != null) existing.add(sp.getId());
                List<ScheduledStopPoint> ssps = new ArrayList<>();
                try (Txn rtx = tdb.roTxn()) {
                    for (Object o : tdb.iterOnlyObjects(rtx, ScheduledStopPoint.class)) {
                        ssps.add((ScheduledStopPoint) o);
                    }
                }
                for (Object o : ScheduledStopPoints.synthesizeStopPlaces(ssps, assigned, existing,
                        new ScheduledStopPoints.SspSynthesis(
                                ssp -> ssp.getId() != null,
                                ssp -> null,
                                "[it-merge] synthesised %d StopPlaces + Quays + PSAs from "
                                        + "unassigned locatable ScheduledStopPoints"))) {
                    if (o instanceof StopPlace sp) stopBuf.add(sp);
                    else psaBuf.add((PassengerStopAssignment) o);
                }

                // Then the stops the feed DID assign but left without a boarding position: 41,364
                // StopPlaces carry zero quays, none of the assignments naming them carries a
                // QuayRef, and OTP invents a stop at the centroid for every one of them. Scoped to
                // the IT: id space by isRapCodespace, which includes Marche, whose ids
                // normalize-it-ids corrects from epd:IT:ITI3 to IT:ITI3 at load.
                ScheduledStopPoints.backfillQuaysForAssignedSsps(
                        stopBuf, psaBuf, ssps, ScheduledStopPoints::isRapCodespace);
            }

            // The consolidation is optional; the emit is not. Whatever was held back from the clone
            // has to be written whether or not it merged, and so does anything synthesis added.
            if (consolidateStops || synthesizeStops) {
                ScheduledStopPoints.MergePlan plan;
                if (!consolidateStops) {
                    plan = new ScheduledStopPoints.MergePlan(Map.of(), Set.of(), Map.of());
                } else if (mergeRadiusM > 0) {
                    plan = ScheduledStopPoints.planStopplaceMerge(stopBuf, psaBuf, mergeRadiusM);
                } else {
                    plan = ScheduledStopPoints.planStopplaceMerge(stopBuf, psaBuf);
                }
                if (consolidateStops) {
                    Log.info("[it-merge] stop consolidation: %d of %d StopPlaces merged away "
                            + "into %d survivors, %d assignments re-pointed",
                            plan.dropped().size(), stopBuf.size(), plan.kept().size(),
                            plan.repointed().size());
                }

                // Merged away -> deleted by omission; a survivor is substituted by id.
                List<Object> stopGen = new ArrayList<>();
                for (StopPlace sp : stopBuf) {
                    if (plan.dropped().contains(sp.getId())) continue;
                    stopGen.add(plan.kept().getOrDefault(sp.getId(), sp));
                }
                List<Object> psaGen = new ArrayList<>();
                for (PassengerStopAssignment psa : psaBuf) {
                    psaGen.add(plan.repointed().getOrDefault(psa.getId(), psa));
                }
                try (Txn wtx = tdb.rwTxn()) {
                    tdb.insertAnyObjects(wtx, stopGen);
                    tdb.insertAnyObjects(wtx, psaGen);
                    wtx.commit();
                }
            }

            // wire cross-source references (each source loaded them as unresolved) and rebuild
            // embeddings. Marker inheritance first, so resolve() can skip the full sweep.
            if (allSourcesSwept) {
                try (Txn wtx = tdb.rwTxn()) {
                    tdb.setBlobRepairMarker(wtx);
                    wtx.commit();
                }
                Log.info("[it-merge] all sources blob-repaired -- target inherits the marker; "
                        + "resolve() skips the full sweep");
            }
            tdb.resolve();
            tdb.resolveEmbeddings();
            Log.info("[it-merge] done: merged %d databases into %s", sources.size(), target);
            toolkit.codec.BindEvents.report("it-merge");
        }
    }

    /// The `--object-level` fallback: read and re-insert every object instead of raw-cloning the
    /// bulk. Same result, several times slower — it is kept as the slow oracle the fast path is
    /// checked against.
    private static long mergeSourceObjectLevel(Store sdb, Txn rtx, Store tdb, Path src, String tag,
            boolean qualify, boolean consolidateStops, Set<String> seen,
            List<StopPlace> stopBuf, List<PassengerStopAssignment> psaBuf) {
        List<Class<?>> classes = new ArrayList<>(sdb.dbNames(rtx));

        // pass 1 — collisions with EARLIER sources only (so a source's own versions of an id are
        // never treated as colliding with each other).
        Map<String, String> remap = new LinkedHashMap<>();
        Set<String> skip = new LinkedHashSet<>();
        for (Class<?> clazz : classes) {
            String cn = clazz.getSimpleName();
            for (Object obj : sdb.iterOnlyObjects(rtx, clazz)) {
                String oid = String.valueOf(((EntityStructure) obj).getId());
                String key = classIdKey(cn, oid);
                if (seen.contains(key)) {
                    if (qualify) remap.put(oid, qualified(oid, tag));
                    else skip.add(key);
                }
            }
        }

        // pass 2 — insert everything (buffering stops/psas), applying the remap; collect this
        // source's FINAL ids locally, fold into `seen` only after the source is done.
        Set<String> local = new LinkedHashSet<>();
        Iterator<Object> emit = new Iterator<>() {
            private int nextClass = 0;
            private Iterator<Object> objects = Collections.emptyIterator();
            private Class<?> clazz;
            private String cn;
            private Object next;

            @Override
            public boolean hasNext() {
                while (next == null) {
                    if (!objects.hasNext()) {
                        if (nextClass == classes.size()) return false;
                        clazz = classes.get(nextClass++);
                        cn = clazz.getSimpleName();
                        // cursor constructed at the point of consumption (one live cursor)
                        objects = sdb.iterOnlyObjects(rtx, clazz).iterator();
                        continue;
                    }
                    Object obj = objects.next();
                    EntityStructure e = (EntityStructure) obj;
                    if (!qualify && skip.contains(classIdKey(cn, String.valueOf(e.getId())))) {
                        continue; // first-wins drop (no-qualify mode)
                    }
                    if (!remap.isEmpty()) applyRemap(obj, remap, true);
                    local.add(classIdKey(cn, String.valueOf(e.getId())));
                    if (consolidateStops && clazz == StopPlace.class) {
                        stopBuf.add((StopPlace) obj);
                    } else if (consolidateStops && clazz == PassengerStopAssignment.class) {
                        psaBuf.add((PassengerStopAssignment) obj);
                    } else {
                        next = obj;
                    }
                }
                return true;
            }

            @Override
            public Object next() {
                if (!hasNext()) throw new NoSuchElementException();
                Object out = next;
                next = null;
                return out;
            }
        };

        long offset;
        try (Txn wtx = tdb.rwTxn()) {
            tdb.insertAnyObjects(wtx, emit);
            offset = tdb.sequence(wtx, 0);
            wtx.commit();
        }
        seen.addAll(local);
        String msg = String.format("[it-merge] copied %s object-level", src);
        if (!remap.isEmpty()) msg += String.format(" -- qualified %d colliding ids (tag %s)", remap.size(), tag);
        if (!skip.isEmpty()) msg += String.format(" -- dropped %d duplicate ids (no-qualify)", skip.size());
        Log.info("%s", msg);
        return offset;
    }

    /// Patch objects with this source's remap applied — REFERENCES ONLY, an object's own id is
    /// left alone.
    private static Iterator<Object> remapped(Iterator<Object> objects, Map<String, String> remap) {
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return objects.hasNext();
            }

            @Override
            public Object next() {
                Object obj = objects.next();
                applyRemap(obj, remap, false);
                return obj;
            }
        };
    }

    private static Iterator<Object> concat(Iterator<Object> a, Iterator<Object> b) {
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return a.hasNext() || b.hasNext();
            }

            @Override
            public Object next() {
                return a.hasNext() ? a.next() : b.next();
            }
        };
    }
}
