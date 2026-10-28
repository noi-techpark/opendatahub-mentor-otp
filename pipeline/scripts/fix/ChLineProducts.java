package fix;

// The Swiss labelling stage: a Line that already codes a product abbreviation takes the product's
// written-out name. The Makefile names this file by path and runs it in the $(CH_DB) chain.
//
// The Swiss export is the only one stating a product on the Line itself, and it publishes a
// degenerate Line per product beside its numbered ones -- ch:2:Line:11.EC.EC carries PublicCode EC
// and repeats EC as its name. Where the code IS the abbreviation the line is the product, so the
// written-out form is its name; a numbered line carries the number beside the abbreviation (RE27
// against RE) and is left alone.
//
// It runs after the republication drop, so journeys about to be deleted do not vote on a line's
// product, and before the EPIP stage, which drops a Line's category reference.

import toolkit.harness.DbToDb;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.transform.common.ProductCategories;
import toolkit.transform.epip.GeneratorDefaults;

public class ChLineProducts {

    private ChLineProducts() {}

    public static void main(String[] args) {
        DbToDb.driverMain("ch-line-products", args, ChLineProducts::apply);
    }

    public static void apply(Store src, Txn stx, Store dst, Txn dtx) {
        dst.cloneFrom(src, stx, dtx, null, 0);
        dst.insertAnyObjects(dtx, ProductCategories.deriveLineLabels(
                src, stx, GeneratorDefaults.PRODUCTION.codespace()).iterator());
    }
}
