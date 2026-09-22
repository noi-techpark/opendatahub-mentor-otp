package fix;

// Driver for transformers.feedfix.StaServiceLinks — the STA export's ServiceLinks, which no journey
// pattern names. The Makefile names this file by path and runs it last in the $(STA_DB) chain,
// after the Mentz line-version resolve, whose orphan peel would otherwise take the links the moment
// they gain a referrer.

import toolkit.harness.DbToDb;
import toolkit.store.Store;
import toolkit.store.Txn;
import transformers.feedfix.StaServiceLinks;

public class WireStaServiceLinks {

    private WireStaServiceLinks() {}

    public static void main(String[] args) {
        DbToDb.driverMain("wire-sta-service-links", args, WireStaServiceLinks::apply);
    }

    /// Raw-clone source into target, then overwrite the patterns the pass named links on. The pass
    /// reads the source, which the clone reproduces byte for byte.
    public static void apply(Store src, Txn stx, Store dst, Txn dtx) {
        dst.cloneFrom(src, stx, dtx, null, 0);
        dst.insertAnyObjects(dtx, StaServiceLinks.wireOnwardLinks(src, stx));
    }
}
