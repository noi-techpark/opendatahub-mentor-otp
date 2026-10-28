package conv;

// The cross-border stage: epip.lmdb -> epip-xb.lmdb.
//
// A db-to-db rather than an in-place fix because this stage removes objects — publications a kept
// leg already carries, and the TrainNumbers they orphan — and a v2 store has no delete.
// `ObjectWriter` offers inserts and `cloneFrom(skipFullKeys)`, and the second is the only way to
// express a removal, so a stage that removes anything has to write a new store. epip.lmdb is left
// intact, so the coupling can be re-run against it without rebuilding the chain.
//
// It must run after EpipToDb, on two counts, and XbScan.Gates refuses the run on either:
//
//   - the EPIP profile conversion normalises every journey to passing times plus a pattern, so
//     matching, truncation and every stop-sequence reader see one journey shape;
//   - DayOffsetRewrite is EpipToDb phase D. Coupling ahead of it makes the matcher read the illegal
//     after-midnight form and score every value past midnight as 00:00:00, silently breaking the
//     handover window on exactly the night trains the corridor is made of.

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import noi.netex.model.ServiceJourney;
import noi.netex.model.ServiceJourneyInterchange;
import noi.netex.model.TrainNumber;
import toolkit.harness.Args;
import toolkit.harness.InPlace;
import toolkit.store.ObjectRow;
import toolkit.store.Store;
import toolkit.store.Stores;
import toolkit.store.Txn;
import toolkit.transform.epip.EpipInterchange;
import toolkit.transform.epip.GeneratorDefaults;
import toolkit.util.Log;
import transformers.xb.Passthrough;
import transformers.xb.XbCouple;
import transformers.xb.XbTypes.Result;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public class XbDbToDb {

    private static final String TAG = "[xb]";

    public static void main(String[] args) throws Exception {
        Args a = Args.parse(args, "XbDbToDb <epip_db> <target_db> [--log-file F] [--format v2]",
                "--log-file=", "--format=").expectPositional(2);
        String logFile = a.get("--log-file", null);
        if (logFile != null) Log.toFile(Path.of(logFile));
        Path source = Path.of(a.positional().get(0));
        Path target = Path.of(a.positional().get(1));
        if (!Stores.exists(source)) {
            Log.error("%s is not an existing database.", source);
            System.exit(1);
        }
        if (Stores.exists(target)) {
            // A populated target would silently collide local keys: cloneFrom is a raw byte copy.
            Log.error("%s already contains a database -- refusing to couple into it", target);
            System.exit(1);
        }
        String format = a.get("--format", null);
        try {
            apply(source, target, format != null ? format : Stores.sniffFormat(source));
        } catch (Exception e) {
            Log.error("%s", Log.trace(e));
            throw new RuntimeException(e);
        }
    }

    /// The stage: couple, clone-minus-drops, overlay, resolve, then repair the interchange points.
    public static void apply(Path source, Path target, String format) throws Exception {
        long t0 = System.nanoTime();
        double coupleSecs;
        double cloneSecs;
        try (Store src = Stores.open(source, true)) {
            Result result;
            LongOpenHashSet skip;
            try (Txn stx = src.roTxn()) {
                result = XbCouple.couple(src, stx, TAG);
                coupleSecs = Log.secs(t0);
                skip = skipKeys(src, stx, result);
            }
            long tClone = System.nanoTime();
            try (Store dst = Stores.create(target, format)) {
                try (Txn stx = src.roTxn(); Txn dtx = dst.rwTxn()) {
                    dst.cloneFrom(src, stx, dtx, skip::contains, 0);
                    // Overwrite only what changed; the untouched objects are never decoded.
                    dst.insertAnyObjects(dtx, result.psas());
                    dst.insertAnyObjects(dtx, result.modifiedSj().values());
                    dst.insertAnyObjects(dtx, result.modifiedPat().values());
                    dst.insertAnyObjects(dtx, result.links());
                    dtx.commit();
                }
                cloneSecs = Log.secs(tClone);
                Log.info("%s wrote %d modified journeys, %d patterns, %d assignments, %d new links; "
                        + "%d keys not cloned", TAG, result.modifiedSj().size(),
                        result.modifiedPat().size(), result.psas().size(), result.links().size(),
                        skip.size());
                // The clone carries the source's resolved state forward verbatim, so nothing this
                // stage does can unresolve what epip.lmdb already had; what needs wiring is only
                // what the overlay adds. resolveEmbeddings() is deliberately not called: a
                // post-EPIP store carries 83.5 M unresolved references by design, sweeping them
                // exhausts a 16 GB heap, and nothing downstream reads them resolved — this is the
                // last stage before the export. Should that change, the fix is to resolve the
                // overlay rather than the store, which the store API does not currently offer.
                dst.resolve();
            }
        }
        // Phase 4, its own transaction on the finished store: this stage's truncations and drops
        // can invalidate the interchanges EPIP generated from JourneyMeetings, so its repair is
        // re-run over the finished store.
        InPlace.runInPlace((db, txn) -> db.insertAnyObjects(txn,
                        EpipInterchange.epipRepairInterchangePoints(db, txn, GeneratorDefaults.PRODUCTION)),
                target, "xb-repair-interchange-points", /*resolve=*/false);
        // Phase 5, its own transaction like phase 4. It has to be here rather than in a per-feed
        // fix: it rebuilds linksInSequence to points-passthrough-1, and XbTruncate.sliceLinks
        // refuses -- and nulls -- any container whose length is not points-1, so run earlier every
        // truncated passthrough pattern would silently lose the geometry this supplies.
        InPlace.runInPlace((db, txn) -> db.insertAnyObjects(txn, Passthrough.mergeLinks(db, txn)),
                target, "xb-passthrough-merge", /*resolve=*/false);
        Log.info("%s done: couple %.1f s, clone+overlay %.1f s, total %.1f s",
                TAG, coupleSecs, cloneSecs, Log.secs(t0));
        Log.info("%s jvm peak rss %d kB", TAG, Log.peakRssKb());
        toolkit.codec.BindEvents.report("xb-db-to-db");
    }

    /// The source-space full keys the clone must not copy — the only way a v2 store expresses a
    /// deletion. Three populations:
    ///
    ///  * the dropped publications, which arrive already as full keys;
    ///  * the TrainNumbers they orphan;
    ///  * the existing interchanges that referenced them, which would otherwise dangle in the
    ///    export. Those exist here only because this stage runs after EPIP: the interchanges EPIP
    ///    generated from JourneyMeetings are already in the store.
    ///
    /// Patterns are not dropped: one that nothing references is inert in the export.
    static LongOpenHashSet skipKeys(Store db, Txn txn, Result result) {
        LongOpenHashSet skip = new LongOpenHashSet(result.droppedKeys());
        Set<String> droppedIds = new HashSet<>();
        for (long fk : result.droppedKeys()) {
            if (db.loadObjectByFullKey(txn, fk) instanceof ServiceJourney sj) {
                droppedIds.add(sj.getId());
            }
        }
        for (String tn : result.orphanTn()) {
            long fk = db.lookupFullKey(txn, tn, null, TrainNumber.class);
            if (fk != -1) skip.add(fk);
        }
        if (!droppedIds.isEmpty()) {
            for (ObjectRow row : db.iterObjects(txn, ServiceJourneyInterchange.class)) {
                if (XbCouple.danglesOnto((ServiceJourneyInterchange) row.object(), droppedIds)) {
                    skip.add(db.fullKeyOf(ServiceJourneyInterchange.class, row.localKey()));
                }
            }
        }
        return skip;
    }
}
