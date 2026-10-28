package fix;

// CLI + wiring only. The transforms themselves are transformers/feedfix/MentzLineVersions.java and
// transformers/feedfix/AuthorityNames.java.
//
// The Makefile names this file by path, so moving or renaming it breaks the sta-db and at stages.
// The algorithm carries no such constraint, so it lives in the transformer where other callers can
// reach it.
//
// The Authority name swap rides on this stage because it is the one every Mentz chain runs; its
// overlay goes onto the same target, so it costs no store of its own.

import toolkit.harness.DbToDb;
import toolkit.store.Store;
import toolkit.store.Txn;
import transformers.feedfix.AuthorityNames;
import transformers.feedfix.MentzLineVersions;

public class ResolveMentzLineVersions {

    private ResolveMentzLineVersions() {}

    public static void main(String[] args) {
        DbToDb.driverMain("resolve-mentz-line-versions", args, ResolveMentzLineVersions::apply);
    }

    public static void apply(Store src, Txn stx, Store dst, Txn dtx) {
        MentzLineVersions.apply(src, stx, dst, dtx);
        dst.insertAnyObjects(dtx, AuthorityNames.swapCodeAndName(src, stx).iterator());
    }
}
