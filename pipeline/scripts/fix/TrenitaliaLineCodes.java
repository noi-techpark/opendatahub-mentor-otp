package fix;

// The Trenitalia labelling stage: the product category, from behind the company prefix. The Makefile
// names this file by path and runs it in the $(IT2_DB) chain.
//
// The feed publishes one Line per product category and prefixes the company code onto both of its
// codes, so PublicCode reads `0083_REG` beside PrivateCode `0083_10083` where a rider reads `REG`.
// The Name is already the category spelled out and is left alone.

import toolkit.harness.DbToDb;
import toolkit.store.Store;
import toolkit.store.Txn;
import transformers.feedfix.LineCodes;

import java.util.LinkedHashMap;

public class TrenitaliaLineCodes {

    private TrenitaliaLineCodes() {}

    public static void main(String[] args) {
        DbToDb.driverMain("trenitalia-line-codes", args, TrenitaliaLineCodes::apply);
    }

    public static void apply(Store src, Txn stx, Store dst, Txn dtx) {
        dst.cloneFrom(src, stx, dtx, null, 0);
        dst.insertAnyObjects(dtx, LineCodes.repairLineCodes(
                src, stx, LineCodes.Policy.TRENITALIA, new LinkedHashMap<>()).iterator());
    }
}
