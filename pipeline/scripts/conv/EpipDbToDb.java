package conv;

// One consolidated country -> its EPIP conversion. conv.FeedsToConsolidatedDbToDb --split writes
// the per-country stores this reads.
//
// A separate stage per country because every EPIP phase is one LMDB write transaction and a write
// txn is bound to the thread that opened it -- only the JAXB decode and marshal can leave that
// thread, which is why -Dtoolkit.epip.parallelJourneys saturates at 2. One store has one writer no
// matter how many cores are idle, so a second store is the only way to put a second writer on this
// work.
//
// Clone-and-transform rather than in place: it leaves the consolidated store standing, so
// re-running the conversion alone does not re-run the consolidation, and make can express the
// dependency instead of a stage having to consume its own input.

import toolkit.harness.Args;
import toolkit.harness.EpipToDb;
import toolkit.store.Stores;
import toolkit.util.Log;

import java.nio.file.Path;

public class EpipDbToDb {

    public static void main(String[] args) throws Exception {
        Args a = Args.parse(args,
                "EpipDbToDb <consolidated_country_db> <target> [--log-file F] [--format v2]",
                "--log-file=", "--format=").expectPositional(2);
        String logFile = a.get("--log-file", null);
        if (logFile != null) Log.toFile(Path.of(logFile));
        Path source = Path.of(a.positional().get(0));
        Path target = Path.of(a.positional().get(1));
        if (!Stores.exists(source)) {
            Log.error("%s does not exist.", source);
            System.exit(1);
        }
        String sourceFmt = Stores.sniffFormat(source);
        String fmt = a.get("--format", null);
        if (fmt == null) {
            fmt = sourceFmt;
        } else if (!fmt.equals(sourceFmt)) {
            Log.error("[epip] --format %s does not match source format %s -- "
                    + "a raw clone cannot change formats", fmt, sourceFmt);
            System.exit(1);
        }
        if (Stores.exists(target)) {
            // Cloning into a populated db would silently collide local keys.
            Log.error("[epip] target %s already contains a database (data.mdb) -- "
                    + "refusing to clone into it", target);
            System.exit(1);
        }
        try {
            EpipToDb.cloneAndTransform(source, target, fmt);
        } catch (Exception e) {
            Log.error("%s", Log.trace(e));
            throw new RuntimeException(e);
        }
    }
}
