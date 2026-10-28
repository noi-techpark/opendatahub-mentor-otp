package fix;

// The Austrian labelling stage: a Line's public code and name, from the product its journeys run
// under. The Makefile names this file by path and runs it in the $(AT_DB) chain.
//
// It runs after the Mentz line-version resolve, which rewrites Line versions and the journeys'
// LineRefs, so the product histogram counts journeys against the lines they will ship on. It runs
// after the republication drop too, so journeys about to be deleted do not vote.
//
// Naming a line after the places it runs between used to be a second pass here, composed onto this
// one through a map. It is now an operation of the EPIP transform's second ensemble pass, which is
// the first point where every pattern carries a destination display and a direction. Nothing
// composes here any more, so the overlay is one object per store row, which is what
// `insertAnyObjects` keys on.

import toolkit.harness.DbToDb;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.transform.common.ProductCategories;
import toolkit.transform.epip.GeneratorDefaults;

public class AtLineProducts {

    private AtLineProducts() {}

    public static void main(String[] args) {
        DbToDb.driverMain("at-line-products", args, AtLineProducts::apply);
    }

    /// Raw-clone source into target, then overwrite the Lines the product pass relabelled, the Lines
    /// its split minted, and the journeys it moved onto one.
    public static void apply(Store src, Txn stx, Store dst, Txn dtx) {
        dst.cloneFrom(src, stx, dtx, null, 0);
        dst.insertAnyObjects(dtx, ProductCategories.deriveLineLabels(
                src, stx, GeneratorDefaults.PRODUCTION.codespace()).iterator());
    }
}
