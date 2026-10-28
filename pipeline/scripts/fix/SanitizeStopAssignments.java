package fix;

// Driver for transformers.feedfix.StopAssignments. The Makefile names this file by path and runs it
// last in each of the three feed chains ($(IT_DB), $(CH_DB), $(AT_DB)); moving or renaming it
// breaks all three.
//
// It must run before the EPIP stage, not after: EpipToDb phase C2 clears LevelRefs that do not
// resolve within their own Site, and the consolidation ahead of it moves Quays between Sites.

import toolkit.harness.DbToDb;
import toolkit.store.Store;
import toolkit.store.Txn;
import transformers.feedfix.StopAssignments;

public class SanitizeStopAssignments {

    private SanitizeStopAssignments() {}

    public static void main(String[] args) {
        DbToDb.driverMain("sanitize-stop-assignments", args, SanitizeStopAssignments::apply);
    }

    /// Raw-clone source into target, then overwrite the objects the pass repaired. The pass reads
    /// the source, which the clone reproduces byte for byte.
    public static void apply(Store src, Txn stx, Store dst, Txn dtx) {
        dst.cloneFrom(src, stx, dtx, null, 0);
        dst.insertAnyObjects(dtx, StopAssignments.sanitizeStopAssignments(src, stx));
    }
}
