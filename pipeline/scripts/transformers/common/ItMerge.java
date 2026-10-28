package transformers.common;

// Consolidating merge of Italian feed databases into one, used at both tiers of the RAP pipeline:
// the per-operator union, then the national union. Two things beyond a plain N-source clone, which
// on a duplicate (id, version) would silently drop the shadow:
//
//   1. QUALIFY colliding ids instead of dropping. A later source's object whose id an earlier
//      source already occupies is re-inserted as `<id>:<tag>` (tag = the source's operator code,
//      or an `x<ordinal>` fallback), with every reference to it within the same source rewritten.
//      One exception: a Codespace repeating one the target already holds, field for field, is the
//      same declaration twice rather than two objects, so the kept copy stands for both. See
//      `repeatedDeclarations` for why that is safe where qualifying is what the rest of a collision
//      needs.
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
import noi.netex.model.Codespace;
import noi.netex.model.EntityInVersionStructure;
import noi.netex.model.EntityStructure;
import noi.netex.model.PassengerStopAssignment;
import noi.netex.model.ScheduledStopPoint;
import noi.netex.model.StopPlace;
import noi.netex.model.VersionOfObjectRefStructure;
import noi.netex.text.Mls;
import toolkit.harness.CloneSupport;
import toolkit.keycodec.NulKeyCodec;
import toolkit.model.RecursiveAttributes;
import toolkit.store.IdRow;
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
import java.util.function.Predicate;

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

    /// The ids of this source's declarations that repeat, field for field, one the target already
    /// holds.
    ///
    /// Only Codespace, and only on exact equality. A declaration is not an object with a life of its
    /// own — nothing in a store references one — so two feeds shipping one national profile's
    /// boilerplate are saying the same thing twice, and the copy already in the target stands for
    /// both. Qualifying the repeat instead puts one Codespace per feed in the export, differing only
    /// in the suffix this merge gave them: the Italian NAP ships `<Codespace id="ita">` from every
    /// one of its feeds, so the national store carried 160 of them.
    ///
    /// Equality of the declaration ITSELF is the whole test, and it is why dropping is safe where
    /// qualifying two genuinely different objects is not: the kept copy has the same id, so every
    /// reference still resolves and no referrer needs patching. Two declarations under one id that
    /// differ in any field are two declarations, and qualify as before.
    ///
    /// Compared field by field rather than on stored bytes, because the two merge paths do not store
    /// the same bytes for one object: the fast path clones a source verbatim, the object-level oracle
    /// re-marshals it on insert. A byte test makes the oracle disagree with the path it exists to
    /// check.
    static Set<String> repeatedDeclarations(Store sdb, Txn rtx, Store tdb, Txn ttx) {
        Class<?> cs = sdb.classForName("Codespace");
        if (cs == null || !tdb.dbNames(ttx).contains(cs)) return Set.of();
        Set<String> kept = new LinkedHashSet<>();
        for (Object o : tdb.iterOnlyObjects(ttx, cs)) kept.add(declaration((Codespace) o));
        Set<String> out = new LinkedHashSet<>();
        for (Object o : sdb.iterOnlyObjects(rtx, cs)) {
            Codespace c = (Codespace) o;
            if (kept.contains(declaration(c))) out.add(c.getId());
        }
        return out;
    }

    /// Everything a Codespace says, NUL-joined: its id and every field the element carries.
    private static String declaration(Codespace c) {
        return c.getId() + "\0" + c.getXmlns() + "\0" + c.getXmlnsUrl()
                + "\0" + Mls.textOrEmpty(c.getDescription()) + "\0" + c.getDataSourceRef();
    }

    /// Put the embedded children of `container` that `claimedEarlier` answers for into `remap`
    /// under their qualified ids, so the rename and the reference rewrite both follow from
    /// [#applyRemap]. `claimedEarlier` takes a [#classIdKey].
    ///
    /// The collision detector sees first-class rows only: an embedded object has no id-index row, so
    /// `duplicateIdFullKeys` cannot reach one. Two publishers describing a single national stop id
    /// therefore ship one Quay id twice, and qualifying their StopPlaces apart left the Quays
    /// sharing it -- a QuayRef then binds to whichever container the closing resolve reaches first,
    /// which is to say to an arbitrary one of two publishers' platforms.
    ///
    /// `claimedEarlier` holds the ids an earlier source already embeds under this container's own
    /// address: [#heldEmbeddedKeys] reads them off the copy the target holds there, and for a class
    /// held back from the clone the buffer holds them. A child whose id no earlier source embeds
    /// keeps it, because renaming an id that does not collide is how a sibling source's reference
    /// to it stops resolving.
    ///
    /// Two shapes stay out of reach: an id a sibling container of an earlier source embeds, and
    /// one an intermediate source embedded under a container this merge has already qualified.
    /// Both of those are claims this reads no copy for.
    ///
    /// A child the source stores first-class keeps its id. Renaming it here rewrites every
    /// reference in the source while the row they name keeps the published id, and the row is out
    /// of reach: it is cloned raw unless the collision detector claimed it, which it does only when
    /// the target holds that exact (id, version, class).
    ///
    /// The remap is keyed by id alone, so [#applyRemap] renames a registered child wherever it
    /// sits. The fast path reaches the colliding container and the patch set; a non-colliding
    /// container of the same source is cloned raw and keeps its copy, while the object-level oracle
    /// remaps every object it inserts, so the two diverge on a source embedding one child id under
    /// two containers.
    static void registerEmbedded(Object container, String tag, Map<String, String> remap,
            Store sdb, Txn rtx, Predicate<String> claimedEarlier) {
        for (EntityStructure child : RecursiveAttributes.embeddedEntities(container, c -> true)) {
            String id = child.getId();
            if (!claimedEarlier.test(classIdKey(child.getClass().getSimpleName(), id))) continue;
            if (firstClassInSource(sdb, rtx, child)) continue;
            remap.putIfAbsent(id, qualified(id, tag));
        }
    }

    /// The embedded children of the object the target holds at `container`'s own
    /// (id, version, class) address, as [#classIdKey]s; empty where it holds nothing there.
    ///
    /// The class and id of what came back are re-read: a version-less address takes
    /// `lookupFullKey`'s prefix arm, which answers with the first row at the id and names no class.
    static Set<String> heldEmbeddedKeys(Store tdb, Txn ttx, Object container) {
        String id = ((EntityStructure) container).getId();
        if (id == null || !tdb.dbNames(ttx).contains(container.getClass())) return Set.of();
        String version = (container instanceof EntityInVersionStructure eiv) ? eiv.getVersion() : null;
        long fk = tdb.lookupFullKey(ttx, id, version, container.getClass());
        if (fk < 0) return Set.of();
        Object held = tdb.loadObjectByFullKey(ttx, fk);
        if (held == null || held.getClass() != container.getClass()
                || !id.equals(((EntityStructure) held).getId())) {
            return Set.of();
        }
        return embeddedKeys(held);
    }

    /// Every id-bearing descendant of `container` as a [#classIdKey]. [RecursiveAttributes#walk]
    /// starts below the root, so the container itself is not among them. The key carries the class,
    /// as the seen sets beside it do, so one class's child id is not a claim on another's.
    private static Set<String> embeddedKeys(Object container) {
        Set<String> out = new LinkedHashSet<>();
        for (EntityStructure e : RecursiveAttributes.embeddedEntities(container, c -> true)) {
            out.add(classIdKey(e.getClass().getSimpleName(), e.getId()));
        }
        return out;
    }

    /// Whether the source stores `child` as a row of its own as well as inside its container's
    /// bytes, at any version.
    ///
    /// Every row at the child's id is read and its class taken from the full key. A row of this
    /// class at another version is a row all the same, and the probe that takes a class wants an
    /// exact version -- without one it answers off the `id NUL` prefix, where the row it finds may
    /// belong to any class. The class-table test comes first because `classIdx` throws for a class
    /// the store never stored, which is most embedded classes.
    private static boolean firstClassInSource(Store sdb, Txn rtx, EntityStructure child) {
        if (!sdb.dbNames(rtx).contains(child.getClass())) return false;
        int want = sdb.classIdxInt(child.getClass());
        for (IdRow row : sdb.lookupById(rtx, child.getId(), null)) {
            if (NulKeyCodec.fullKeyClassIdx(row.fullKey()) == want) return true;
        }
        return false;
    }

    /// rewrite every reference `obj` holds whose target id is in
    /// `remap`, and the id of `obj` itself when `renameSelf` and of every EMBEDDED child whose id
    /// the remap names. The refs the walk yields are the
    /// live objects in the tree, so mutating in place is enough; insert re-extracts the index.
    ///
    /// An embedded child is renamed unconditionally of `renameSelf`, which governs the top-level id
    /// alone. The match is on the bare id at any depth, so a child reached here is renamed wherever
    /// it sits, not only under the container [#registerEmbedded] registered it from. That is the
    /// price of an id-keyed remap, and the reason registration is bounded to an id an earlier
    /// source claims: what the walk renames has to be an id the merge has established collides.
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
            public void node(Object o) {
                if (!(o instanceof EntityStructure e) || e.getId() == null) return;
                String mapped = remap.get(e.getId());
                if (mapped != null) e.setId(mapped);
            }
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
        // (class name, id) of the buffered objects' embedded children. A buffered class is held
        // back from every clone, so the target holds no copy for `heldEmbeddedKeys` to read.
        Set<String> seenEmbedded = new LinkedHashSet<>();

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
                                consolidateStops || synthesizeStops, seenBuffered, seenEmbedded,
                                stopBuf, psaBuf);
                        continue;
                    }

                    Map<String, String> remap = new LinkedHashMap<>();
                    // The remap also carries the embedded children a qualified container drags with
                    // it, so its size is not the collision count the message reports.
                    int collided = 0;
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
                                String oldId = String.valueOf(obj.getId());
                                String newId = qualified(oldId, tag);
                                remap.put(oldId, newId);
                                obj.setId(newId);
                                collided++;
                                registerEmbedded(obj, tag, remap, sdb, rtx,
                                        seenEmbedded::contains);
                            }
                            pending.add(obj);
                        }
                    }

                    LongOpenHashSet dups;
                    List<Object> dupObjs = new ArrayList<>();
                    int dropped = 0;              // repeated declarations, kept once
                    LongOpenHashSet skip;
                    LongOpenHashSet patch;
                    try (Txn wtx = tdb.rwTxn()) {
                        // (b) the objects whose (id, version) an earlier source already occupies.
                        // Skipped by the clone either way: qualified + re-inserted below, or
                        // dropped outright (no-qualify).
                        dups = offset != 0 ? CloneSupport.duplicateIdFullKeys(sdb, rtx, tdb, wtx)
                                : new LongOpenHashSet();
                        // A repeat stays in `skip`, so the clone omits it, and never reaches
                        // `dupObjs` -- dropped rather than qualified, with the kept copy standing
                        // for both.
                        Set<String> declRepeats = repeatedDeclarations(sdb, rtx, tdb, wtx);
                        if (qualify) {
                            // A set iterates unpredictably; the re-insert order must be stable.
                            LongArrayList sorted = new LongArrayList(dups);
                            sorted.sort(NulKeyCodec::compareFullKeysLe);
                            for (long k : sorted) {
                                Object o = sdb.loadObjectByFullKey(rtx, k);
                                if (o == null || ((EntityStructure) o).getId() == null) continue;
                                if (o instanceof Codespace
                                        && declRepeats.contains(((EntityStructure) o).getId())) {
                                    dropped++;
                                    continue;
                                }
                                dupObjs.add(o);
                            }
                            for (Object o : dupObjs) {
                                String oid = ((EntityStructure) o).getId();
                                remap.put(oid, qualified(oid, tag));
                                collided++;
                                Set<String> held = heldEmbeddedKeys(tdb, wtx, o);
                                registerEmbedded(o, tag, remap, sdb, rtx,
                                        k -> held.contains(k) || seenEmbedded.contains(k));
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
                        seenEmbedded.addAll(embeddedKeys(obj));
                    }

                    String msg = String.format("[it-merge] cloned %s (next offset %d)", src, offset);
                    // Not `qualify && !remap.isEmpty()`: a source whose only collisions were repeated
                    // declarations leaves the remap empty, and falling through would report a
                    // qualifying merge as a no-qualify one.
                    if (qualify) {
                        if (collided > 0) {
                            msg += String.format(" -- qualified %d colliding ids (tag %s)",
                                    collided, tag);
                        }
                    } else if (!dups.isEmpty()) {
                        msg += String.format(" -- dropped %d duplicate ids (no-qualify)", dups.size());
                    }
                    if (dropped > 0) {
                        msg += String.format(" -- kept %d repeated declaration(s) once", dropped);
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
            boolean qualify, boolean consolidateStops, Set<String> seen, Set<String> seenEmbedded,
            List<StopPlace> stopBuf, List<PassengerStopAssignment> psaBuf) {
        List<Class<?>> classes = new ArrayList<>(sdb.dbNames(rtx));

        // pass 1 — collisions with EARLIER sources only (so a source's own versions of an id are
        // never treated as colliding with each other).
        Map<String, String> remap = new LinkedHashMap<>();
        // The remap also carries the embedded children a qualified container drags with it, so its
        // size is not the collision count the message reports.
        int collided = 0;
        Set<String> skip = new LinkedHashSet<>();
        Set<String> drop = new LinkedHashSet<>();   // repeated declarations, dropped in both modes
        // One read txn over both: the embedded-child claim is read off the copy the target already
        // holds, which is the same generation `repeatedDeclarations` compares against.
        try (Txn ttx = tdb.roTxn()) {
            Set<String> declRepeats = repeatedDeclarations(sdb, rtx, tdb, ttx);
            for (Class<?> clazz : classes) {
                String cn = clazz.getSimpleName();
                for (Object obj : sdb.iterOnlyObjects(rtx, clazz)) {
                    String oid = String.valueOf(((EntityStructure) obj).getId());
                    String key = classIdKey(cn, oid);
                    if (!seen.contains(key)) continue;
                    if (obj instanceof Codespace && declRepeats.contains(oid)) drop.add(key);
                    else if (qualify) {
                        remap.put(oid, qualified(oid, tag));
                        collided++;
                        Set<String> held = heldEmbeddedKeys(tdb, ttx, obj);
                        registerEmbedded(obj, tag, remap, sdb, rtx,
                                k -> held.contains(k) || seenEmbedded.contains(k));
                    } else skip.add(key);
                }
            }
        }

        // pass 2 — insert everything (buffering stops/psas), applying the remap; collect this
        // source's FINAL ids locally, fold into `seen` only after the source is done.
        Set<String> local = new LinkedHashSet<>();
        Set<String> localEmbedded = new LinkedHashSet<>();
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
                    if (drop.contains(classIdKey(cn, String.valueOf(e.getId())))) {
                        continue; // a declaration the target already holds, byte for byte
                    }
                    if (!qualify && skip.contains(classIdKey(cn, String.valueOf(e.getId())))) {
                        continue; // first-wins drop (no-qualify mode)
                    }
                    if (!remap.isEmpty()) applyRemap(obj, remap, true);
                    local.add(classIdKey(cn, String.valueOf(e.getId())));
                    if (consolidateStops && clazz == StopPlace.class) {
                        stopBuf.add((StopPlace) obj);
                        localEmbedded.addAll(embeddedKeys(obj));
                    } else if (consolidateStops && clazz == PassengerStopAssignment.class) {
                        psaBuf.add((PassengerStopAssignment) obj);
                        localEmbedded.addAll(embeddedKeys(obj));
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
        seenEmbedded.addAll(localEmbedded);
        String msg = String.format("[it-merge] copied %s object-level", src);
        if (collided > 0) msg += String.format(" -- qualified %d colliding ids (tag %s)", collided, tag);
        if (!drop.isEmpty()) msg += String.format(" -- kept %d repeated declaration(s) once", drop.size());
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
