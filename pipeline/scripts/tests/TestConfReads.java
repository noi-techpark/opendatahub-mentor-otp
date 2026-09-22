package tests;

// No property read in this repository may name a toolkit knob directly.
//
// These drivers run under the same -D flags the Makefile pins, and a driver that read one with
// System.getProperty would take the raw string, skip the type check, skip the unknown-name refusal,
// and never appear in the [conf] line a run logs.

import toolkit.test.Check;
import toolkit.test.Runner;
import toolkit.util.Conf;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TestConfReads {

    public static void main(String[] args) {
        Runner.run(TestConfReads.class);
    }

    /// This file quotes the idioms it bans, so it cannot be its own subject.
    private static boolean isMechanism(Path p) {
        return p.toString().endsWith("tests/TestConfReads.java");
    }

    private static List<Path> sources() throws IOException {
        return Sources.all(TestConfReads::isMechanism);
    }

    /// No property read outside [toolkit.util.Conf] may name a `toolkit.*` key, or any key the
    /// table declares under a legacy spelling or as an environment variable.
    ///
    /// The JVM-provided facts are exempt and named rather than pattern-matched: `java.io.tmpdir`,
    /// `sun.java.command` and `jdk.launcher.sourcefile` are things the JVM tells us, not knobs
    /// anybody sets. `toolkit.tests.dir` is exempt because it locates the suite, and the suite is
    /// what runs this scan.
    public static void testNoUndeclaredPropertyReadSurvives() throws Exception {
        TreeSet<String> bad = new TreeSet<>();
        for (Path p : sources()) {
            Matcher m = READ.matcher(Sources.read(p));
            while (m.find()) {
                String key = m.group(2);
                if (EXEMPT.contains(key)) continue;
                if (!key.startsWith(Conf.PREFIX) && !isLegacyOrEnv(key)) continue;
                bad.add(key + " @ " + p.getFileName() + " (" + m.group(1) + ")");
            }
        }
        Check.equals(new TreeSet<String>(), bad,
                "every knob is read through toolkit.util.Conf, so the table is the whole set");
    }

    /// `System.getProperty("x")`, `Boolean.getBoolean("x")`, and the numeric pair.
    private static final Pattern READ = Pattern.compile(
            "(System\\.getProperty|System\\.getenv|Boolean\\.getBoolean"
            + "|Integer\\.getInteger|Long\\.getLong)\\(\"([^\"]*)\"");

    private static final java.util.Set<String> EXEMPT = java.util.Set.of(
            "java.io.tmpdir", "sun.java.command", "jdk.launcher.sourcefile", "toolkit.tests.dir");

    private static boolean isLegacyOrEnv(String key) {
        for (Conf.Flag f : Conf.ALL) {
            if (key.equals(f.legacyProperty()) || key.equals(f.env())) return true;
        }
        return false;
    }
}
