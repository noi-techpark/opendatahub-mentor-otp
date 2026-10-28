package fix;

// Driver for transformers.feedfix.ItcTrailingLink — GTT's one-link-per-point-including-the-terminus
// defect. The Makefile names this file by path and runs it in the $(IT_DB) chain, between the
// national merge and sanitize-stop-assignments, before the EPIP stage.

import toolkit.harness.DbToDb;
import toolkit.store.Store;
import toolkit.store.Txn;
import transformers.feedfix.ItcTrailingLink;

public class FixItcTrailingLink {

    private FixItcTrailingLink() {}

    public static void main(String[] args) {
        DbToDb.driverMain("fix-itc-trailing-link", args, FixItcTrailingLink::apply);
    }

    /// Raw-clone source into target, then overwrite the patterns the pass repaired. The pass reads
    /// the source, which the clone reproduces byte for byte.
    public static void apply(Store src, Txn stx, Store dst, Txn dtx) {
        dst.cloneFrom(src, stx, dtx, null, 0);
        dst.insertAnyObjects(dtx, ItcTrailingLink.dropTrailingDuplicates(src, stx));
    }
}
