package conv;

// Loader driver with fresh-target semantics: stream the feed in, then the mandatory resolve() +
// resolveEmbeddings() pair. The entry point of every chain — nothing else creates a store from a
// feed, and the driver the Makefile runs for every RAP feed, Trenitalia and STA.
//
// FeedToDb accepts everything NetexLoader.load dispatches on, a bare .xml or .xml.gz included, so
// this driver is the harness call and nothing else.
//
// The format defaulted to here is what the whole downstream chain becomes: every db-to-db driver
// inherits its source's format when no --format is given.

import toolkit.harness.FeedToDb;
import toolkit.load.ZipLoad;

public class NetexToDb {

    public static void main(String[] args) throws Exception {
        FeedToDb.driverMain("netex-to-db", args, ZipLoad.Options.DEFAULTS);
    }
}
