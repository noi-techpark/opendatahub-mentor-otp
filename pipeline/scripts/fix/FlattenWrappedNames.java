package fix;

// Driver for transformers.feedfix.WrappedNames — the FlixBus export's <Text>-wrapped names. The
// Makefile names this file by path and runs it in the $(FLIX_DB) chain, directly after the load.
//
// It must run before the EPIP stage. That stage synthesises an Authority per Operator for the
// authorityRef every EPIP Line needs, copying the Operator's name onto it; run after, the synthesis
// has already taken a copy of the wrapper and the Authority reaches the export nameless whatever
// this does to the Operator.

import toolkit.harness.DbToDb;
import toolkit.store.Store;
import toolkit.store.Txn;
import transformers.feedfix.WrappedNames;

public class FlattenWrappedNames {

    private FlattenWrappedNames() {}

    public static void main(String[] args) {
        DbToDb.driverMain("flatten-wrapped-names", args, FlattenWrappedNames::apply);
    }

    /// Raw-clone source into target, then overwrite the objects the pass repaired. The pass reads
    /// the source, which the clone reproduces byte for byte.
    public static void apply(Store src, Txn stx, Store dst, Txn dtx) {
        dst.cloneFrom(src, stx, dtx, null, 0);
        dst.insertAnyObjects(dtx, WrappedNames.flattenWrappedNames(src, stx));
    }
}
