package conv;

// CLI + wiring only. The merge itself is transformers/ItMerge.java.
//
// This driver keeps a hand-rolled main rather than a harness call: DbToDb's sandwich is one source
// to one target, and this takes N sources with per-source tags, a shared-format check across all of
// them, and a refusal on a non-empty target. Those checks are CLI validation and belong to the
// driver; the merge underneath them does not, and TestItMergeDbToDb drives it directly.

import toolkit.harness.Args;
import toolkit.store.Stores;
import toolkit.util.Log;
import transformers.common.ItMerge;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ItMergeDbToDb {

    private ItMergeDbToDb() {}

    public static void main(String[] args) throws Exception {
        Args a = Args.parse(args,
                "ItMergeDbToDb <src1> [<src2> ...] <target> [--tags t1,t2,...] [--no-qualify] "
                        + "[--no-consolidate-stops] [--synthesize-stops] [--object-level] "
                        + "[--format v2] [--log-file F]",
                "--tags=", "--log-file=", "--format=",
                "--no-qualify", "--no-consolidate-stops", "--synthesize-stops",
                "--object-level").expectAtLeast(2);
        String logFile = a.get("--log-file", null);
        if (logFile != null) Log.toFile(Path.of(logFile));
        List<String> positional = a.positional();
        List<Path> sources = new ArrayList<>();
        String sourceFmt = null;
        for (String s : positional.subList(0, positional.size() - 1)) {
            Path p = Path.of(s);
            // The directory existing is not enough; the toolkit gates on Stores.exists()
            // (data.mdb inside it) so a non-db path can never be raw-cloned from. The two
            // conditions get distinct messages: "does not exist" for a directory that plainly does
            // exist is misleading.
            if (!java.nio.file.Files.exists(p)) {
                Log.error("%s does not exist.", s);
                System.exit(1);
            }
            if (!Stores.exists(p)) {
                Log.error("[it-merge] source %s exists but contains no database (data.mdb) -- "
                        + "refusing to merge from it", p);
                System.exit(1);
            }
            // All sources must share one format: cloneFrom is raw.
            String fmt = Stores.sniffFormat(p);
            if (sourceFmt == null) {
                sourceFmt = fmt;
            } else if (!sourceFmt.equals(fmt)) {
                Log.error("[it-merge] source %s is %s but earlier sources are %s -- "
                        + "refusing to merge mixed-format sources", p, fmt, sourceFmt);
                System.exit(1);
            }
            sources.add(p);
        }
        Path target = Path.of(positional.get(positional.size() - 1));
        // Merging into a non-empty target would silently collide local keys — fail fast instead.
        if (Stores.exists(target)) {
            Log.error("[it-merge] target %s already contains a database (data.mdb) -- "
                    + "refusing to merge into it", target);
            System.exit(1);
        }
        String tagsArg = a.get("--tags", null);
        // An empty --tags is the same as none given;
        // split keeps empty segments, so limit -1.
        List<String> tags = (tagsArg != null && !tagsArg.isEmpty())
                ? List.of(tagsArg.split(",", -1)) : null;
        String fmt = a.get("--format", null);
        if (fmt == null) fmt = sourceFmt;
        try {
            ItMerge.merge(sources, target, tags, !a.has("--no-qualify"),
                    !a.has("--no-consolidate-stops"), !a.has("--object-level"), fmt,
                    a.has("--synthesize-stops"));
        } catch (Exception e) {
            Log.error("%s", Log.trace(e));
            throw new RuntimeException(e);
        }
    }
}
