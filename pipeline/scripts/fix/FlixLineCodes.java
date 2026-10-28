package fix;

// The FlixBus labelling stage: the line's own code, from behind the publisher prefix. The Makefile
// names this file by path and runs it in the $(FLIX_DB) chain.
//
// The export carries the code as a `route_id` private code and prefixes the public one with the
// publisher, so PublicCode reads `FlixBus N177` where a rider reads `N177`. The Name is already the
// pair of cities and is left alone.
//
// It runs after the name unwrap, which is what makes the ShortName beside the code readable at all.

import toolkit.harness.DbToDb;
import toolkit.store.Store;
import toolkit.store.Txn;
import transformers.feedfix.LineCodes;

import java.util.LinkedHashMap;

public class FlixLineCodes {

    private FlixLineCodes() {}

    public static void main(String[] args) {
        DbToDb.driverMain("flix-line-codes", args, FlixLineCodes::apply);
    }

    public static void apply(Store src, Txn stx, Store dst, Txn dtx) {
        dst.cloneFrom(src, stx, dtx, null, 0);
        dst.insertAnyObjects(dtx, LineCodes.repairLineCodes(
                src, stx, LineCodes.Policy.FLIX, new LinkedHashMap<>()).iterator());
    }
}
