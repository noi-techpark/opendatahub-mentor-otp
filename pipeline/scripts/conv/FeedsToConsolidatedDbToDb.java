package conv;

// N per-feed databases -> N per-feed databases whose stations are consolidated across all of them:
// one consolidation over every feed at once, its rows written back out split.
//
// It has to run before EpipToDb. Consolidation moves Quays between StopPlaces, and EpipToDb phase
// C2 clears LevelRefs that do not resolve within their own Site, so a quay moved after that pass
// leaves an orphan the pass would have removed.

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import noi.netex.model.PassengerStopAssignment;
import noi.netex.model.StopPlace;
import toolkit.harness.Args;
import toolkit.store.Store;
import toolkit.store.Stores;
import toolkit.store.Txn;
import toolkit.util.Log;
import transformers.xb.XbStations;
import transformers.xb.XbStations.Consolidation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public class FeedsToConsolidatedDbToDb {

    private static final String TAG = "[consolidated]";

    public static void main(String[] args) throws Exception {
        Args a = Args.parse(args,
                "FeedsToConsolidatedDbToDb <src1> ... <srcN> --split t1,t2,...,tN "
                + "[--log-file F] [--format v2]",
                "--log-file=", "--format=", "--split=").expectAtLeast(1);
        String logFile = a.get("--log-file", null);
        if (logFile != null) Log.toFile(Path.of(logFile));
        String split = a.get("--split", null);
        if (split == null) {
            Log.error("%s --split is required: one target per source, comma-joined", TAG);
            System.exit(1);
        }
        List<String> sourceArgs = a.positional();
        if (sourceArgs.isEmpty()) {
            Log.error("%s no source databases given", TAG);
            System.exit(1);
        }
        List<Path> sources = new ArrayList<>();
        String sourceFmt = null;
        for (String s : sourceArgs) {
            Path p = Path.of(s);
            if (!Stores.exists(p)) {
                Log.error("%s does not exist.", p);
                System.exit(1);
            }
            String fmt = Stores.sniffFormat(p);
            if (sourceFmt == null) {
                sourceFmt = fmt;
            } else if (!sourceFmt.equals(fmt)) {
                Log.error("%s source %s is %s but earlier sources are %s -- refusing to merge "
                        + "mixed-format sources", TAG, p, fmt, sourceFmt);
                System.exit(1);
            }
            sources.add(p);
        }
        List<Path> targets = new ArrayList<>();
        for (String t : split.split(",")) targets.add(Path.of(t.trim()));
        if (targets.size() != sources.size()) {
            Log.error("%s --split needs one target per source: %d sources, %d targets",
                    TAG, sources.size(), targets.size());
            System.exit(1);
        }
        for (Path t : targets) {
            if (Stores.exists(t)) {
                Log.error("%s target %s already contains a database -- refusing to merge into it",
                        TAG, t);
                System.exit(1);
            }
        }
        String fmt = a.get("--format", null);
        try {
            consolidate(sources, targets, fmt != null ? fmt : sourceFmt);
        } catch (Exception e) {
            Log.error("%s", Log.trace(e));
            throw new RuntimeException(e);
        }
    }

    /// Consolidate N feeds into N per-feed targets: one consolidation, its rows written out split.
    /// The consolidation itself cannot be sharded — it clusters StopPlaces geographically and a
    /// border station's copies come from different countries by definition, so it has to see every
    /// feed at once. The split is a write-side choice, giving the EPIP conversion downstream one
    /// store per writer.
    ///
    /// Each shard gets every survivor, not just the ones its own feed contributed to. A survivor
    /// absorbs quays from every country in its cluster, and EpipLocations, the quay-name backfill
    /// and phase C2 all read the site population they are given: a shard missing the survivor its
    /// own assignments now point at would convert against a hole.
    ///
    /// Assignments are not broadcast the same way: a PassengerStopAssignment belongs to exactly one
    /// feed, and handing a shard another feed's assignments would put objects into it that its own
    /// merge would then have to remove.
    public static void consolidate(List<Path> sourceFiles, List<Path> targetFiles, String format)
            throws Exception {
        if (targetFiles.size() != sourceFiles.size()) {
            throw new IllegalArgumentException("sharded output needs one target per source");
        }
        long t0 = System.nanoTime();
        long rewired = 0;
        List<Store> dbs = new ArrayList<>();
        List<Txn> txns = new ArrayList<>();
        Store targetDb = null;
        try {
            try {
                for (Path p : sourceFiles) {
                    Store db = Stores.open(p, true);
                    dbs.add(db);
                    txns.add(db.roTxn());
                }

                // Object ids never collide across feeds, so the consolidation does not care which
                // store a row arrived from.
                List<StopPlace> stopplaces = new ArrayList<>();
                List<PassengerStopAssignment> psas = new ArrayList<>();
                for (int i = 0; i < dbs.size(); i++) {
                    for (Object o : dbs.get(i).iterOnlyObjects(txns.get(i), StopPlace.class)) {
                        stopplaces.add((StopPlace) o);
                    }
                    for (Object o : dbs.get(i).iterOnlyObjects(txns.get(i), PassengerStopAssignment.class)) {
                        psas.add((PassengerStopAssignment) o);
                    }
                }
                // Canonical order: a merged database and per-feed databases iterate differently, and
                // a survivor collecting its members' quays is order-dependent.
                stopplaces.sort(Comparator.comparing((StopPlace sp) -> nz(sp.getId()))
                        .thenComparing(sp -> nz(sp.getVersion())));
                psas.sort(Comparator.comparing((PassengerStopAssignment p) -> nz(p.getId()))
                        .thenComparing(p -> nz(p.getVersion())));

                Set<String> eligible = new java.util.HashSet<>();
                for (int i = 0; i < dbs.size(); i++) {
                    eligible.addAll(XbStations.railServed(dbs.get(i), txns.get(i), psas));
                }
                Consolidation result = XbStations.consolidate(stopplaces, psas, eligible);
                double consolidateSecs = Log.secs(t0);

                long tClone = System.nanoTime();
                for (int i = 0; i < dbs.size(); i++) {
                    Store db = dbs.get(i);
                    Txn txn = txns.get(i);
                    // Opened only after the read phase returned: a failure there must not leave an
                    // empty target database behind.
                    targetDb = Stores.create(targetFiles.get(i), format);
                    LongOpenHashSet skips = skipKeys(db, txn, result.mergedAway());
                    try (Txn targetTxn = targetDb.rwTxn()) {
                        // Clone offset 0: each shard is its own key space, one feed wide, so no
                        // earlier feed's keys are in this store to collide with. The cross-feed
                        // duplicate-id dedup runs in conv.MergeDbToDb, which puts the shards back
                        // together keep-first.
                        targetDb.cloneFrom(db, txn, targetTxn,
                                skips.isEmpty() ? null : skips::contains, 0);
                        targetTxn.commit();
                    }
                    Log.info("%s cloned %s (%d skipped keys)", TAG,
                            sourceFiles.get(i), skips.size());
                    rewired += rewireOntoKeptCopies(targetDb, db, txn, skips);
                    overlayAndResolve(targetDb, result, db, txn, targetFiles.get(i));
                    targetDb.close();
                    targetDb = null;
                }
                Log.info("%s phases: consolidate %.1f s, clone+overlay %.1f s (%d referrers of "
                        + "skipped keys rewired)", TAG, consolidateSecs, Log.secs(tClone), rewired);
            } finally {
                closeLifo(txns, dbs);
            }
        } finally {
            if (targetDb != null) targetDb.close();
        }
        Log.info("[feeds_to_consolidated_db_to_db] done (%.1f s)", Log.secs(t0));
        Log.info("%s jvm peak rss %d kB", TAG, Log.peakRssKb());
        toolkit.codec.BindEvents.report("feeds-to-consolidated-db-to-db");
    }

    /// Lay the consolidation's own objects over a freshly cloned target, then wire it up.
    /// `ownerDb`/`ownerTxn` are the shard's source feed: the assignments are filtered to the ones
    /// that feed declares, an id it does not have failing to resolve there.
    private static void overlayAndResolve(Store targetDb, Consolidation result, Store ownerDb,
            Txn ownerTxn, Path targetFile) throws Exception {
        List<PassengerStopAssignment> mine = new ArrayList<>();
        for (PassengerStopAssignment p : result.psas()) {
            if (ownerDb.lookupFullKey(ownerTxn, p.getId(), p.getVersion(),
                    PassengerStopAssignment.class) != -1) {
                mine.add(p);
            }
        }
        Log.info("%s %s: %d of %d re-pointed assignments belong to this feed", TAG,
                targetFile.getFileName(), mine.size(), result.psas().size());
        List<PassengerStopAssignment> psas = mine;
        try (Txn targetTxn = targetDb.rwTxn()) {
            targetDb.insertAnyObjects(targetTxn, result.survivors());
            targetDb.insertAnyObjects(targetTxn, psas);
            targetTxn.commit();
        }
        // The per-feed databases are unresolved (cross-feed references exist only as encoded ids)
        // and the overlay re-pointed assignments: wire everything up once.
        targetDb.resolve();
        targetDb.resolveEmbeddings();
    }

    /// Re-insert whatever referenced a key the clone skipped, so its references land on what was
    /// kept. cloneFrom drops an edge whose target is skipped without leaving an unresolved row
    /// behind, so no later resolve can find it.
    private static long rewireOntoKeptCopies(Store targetDb, Store db, Txn txn,
            LongOpenHashSet skipped) {
        if (skipped.isEmpty()) return 0;
        LongOpenHashSet referrers = new LongOpenHashSet();
        for (long[] edge : db.iterEdges(txn)) {
            // A skipped referrer is left alone: re-inserting it would resurrect the very object the
            // clone excluded, and a skipped row referencing another skipped row is ordinary (a
            // merged-away StopPlace naming its neighbour).
            if (skipped.contains(edge[1]) && !skipped.contains(edge[0])) referrers.add(edge[0]);
        }
        if (referrers.isEmpty()) return 0;
        List<Object> objects = new ArrayList<>(referrers.size());
        for (long fk : referrers) {
            Object o = db.loadObjectByFullKey(txn, fk);
            if (o != null) objects.add(o);
        }
        try (Txn targetTxn = targetDb.rwTxn()) {
            targetDb.insertAnyObjects(targetTxn, objects);
            targetTxn.commit();
        }
        return objects.size();
    }

    /// The source-space full keys of the merged-away StopPlaces, every stored version of each: a
    /// keyless lookup would drop only the first and leave stale copies in the output. Ids owned by
    /// another feed do not resolve here and are skipped, so the same call works per feed.
    static LongOpenHashSet skipKeys(Store db, Txn txn, Set<String> mergedAway) {
        LongOpenHashSet skip = new LongOpenHashSet();
        for (Object o : db.iterOnlyObjects(txn, StopPlace.class)) {
            StopPlace sp = (StopPlace) o;
            if (!mergedAway.contains(sp.getId())) continue;
            long fk = db.lookupFullKey(txn, sp.getId(), sp.getVersion(), StopPlace.class);
            if (fk != -1) skip.add(fk);
        }
        return skip;
    }

    /// Close every per-feed txn then db in reverse order: every item is closed even when one close
    /// throws, and the first throwable is rethrown after all of them.
    private static void closeLifo(List<Txn> txns, List<Store> dbs) throws Exception {
        Throwable first = null;
        List<AutoCloseable> order = new ArrayList<>();
        for (int i = txns.size() - 1; i >= 0; i--) order.add(txns.get(i));
        for (int i = dbs.size() - 1; i >= 0; i--) order.add(dbs.get(i));
        for (AutoCloseable c : order) {
            try {
                c.close();
            } catch (Throwable t) {
                if (first == null) {
                    first = t;
                } else {
                    Log.warn("%s secondary failure closing a per-feed source: %s", TAG, t);
                }
            }
        }
        if (first instanceof Exception e) throw e;
        if (first instanceof Error e) throw e;
        if (first != null) throw new RuntimeException(first);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
