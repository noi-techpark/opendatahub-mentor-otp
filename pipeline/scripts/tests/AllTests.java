package tests;

// This repository's test suite in one JVM.
//
// Since JEP 458 the source launcher compiles a multi-file source program on demand, so launching
// this file compiles every `tests.Test*` class it names, in memory, from the same source root. The
// tests stay uncompiled scripts and there is no javac step. Nothing runs this file automatically --
// this repository has no CI -- so it is `make test` or it is nobody.
//
// Shared for the whole run: the JAXBContext, ModelRegistry, RefElementIndex and EnumWhitespace's
// derived tables, all immutable caches derived from the model. Everything else must stay per test:
// TestStore.create makes a fresh temp directory and store per test method and closes it, and the
// two tests that set process-wide state clear it in a finally. `Runner.runReporting` does not exit
// on failure, so a failing file does not hide the ones after it.

import toolkit.test.Runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

public class AllTests {

    static final Class<?>[] CLASSES = {
        TestConfReads.class,
        TestDedupDefinitionDays.class,
        TestDropForeignJourneys.class,
        TestEpipSplitExport.class,
        TestFeedsToConsolidated.class,
        TestFlattenWrappedNames.class,
        TestItaloAuthorityCodespace.class,
        TestItMergeDbToDb.class,
        TestItRapDbToDb.class,
        TestItcTrailingLink.class,
        TestNormalizeItIds.class,
        TestPassthrough.class,
        TestResolveMentzLineVersions.class,
        TestRewriteStaSspIds.class,
        TestSanitizeStopAssignments.class,
        TestSortItaloSequences.class,
        TestSplitJourneys.class,
        TestSspCountryCensus.class,
        TestSspSynthesis.class,
        TestStaServiceLinks.class,
        TestTrenitaliaDbToDb.class,
        TestVerifyCrossborder.class,
        TestXbCalendar.class,
        TestXbEdges.class,
        TestXbIds.class,
        TestXbSharedPatterns.class,
        TestXbStitch.class,
        TestXbCoupleAtlas.class,
        TestXbStations.class,
        TestXbTiling.class,
        TestXbTruncate.class,
    };

    public static void main(String[] args) {
        List<Class<?>> run = select(args);
        if (!verifyComplete()) System.exit(2);

        int files = 0, tests = 0, fails = 0;
        List<String> failed = new ArrayList<>();
        for (Class<?> c : run) {
            System.out.println("===== " + c.getSimpleName() + ".java");
            files++;
            Runner.Tally t;
            try {
                t = Runner.runReporting(c);
            } catch (Throwable e) {           // a class that will not even load or has no tests
                System.out.println("FAIL   " + c.getSimpleName() + " did not run");
                e.printStackTrace(System.out);
                System.out.printf("%d tests, %d failures%n", 0, 1);
                failed.add(c.getSimpleName() + ":nostart");
                fails++;
                continue;
            }
            tests += t.tests();
            fails += t.failures();
            if (t.failures() > 0) failed.add(c.getSimpleName() + ":" + t.failures());
        }
        System.out.printf("TOTAL: %d files / %d tests / %d failures%n", files, tests, fails);
        if (!failed.isEmpty()) {
            System.out.println("FAILED:" + String.join(" ", failed));
            System.exit(1);
        }
    }

    /// `AllTests \[shards shardIndex\]` — an optional round-robin split of the class list, so a
    /// runner with spare cores can host the suite in a few parallel JVMs instead of one.
    static List<Class<?>> select(String[] args) {
        List<Class<?>> out = new ArrayList<>();
        int shards = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        int idx = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        for (int i = 0; i < CLASSES.length; i++) if (i % shards == idx) out.add(CLASSES[i]);
        return out;
    }

    /// The list above must name exactly the `Test*.java` files on disk. Returns false — and the
    /// run fails — when it does not.
    static boolean verifyComplete() {
        Path dir = sourceDir();
        if (dir == null) {
            System.out.println("NOTE: tests/ source directory not found; class-list completeness NOT checked");
            return true;
        }
        TreeSet<String> onDisk = new TreeSet<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.map(p -> p.getFileName().toString())
             .filter(n -> n.startsWith("Test") && n.endsWith(".java"))
             .map(n -> n.substring(0, n.length() - 5))
             .forEach(onDisk::add);
        } catch (IOException e) {
            System.out.println("NOTE: could not list " + dir + ": " + e);
            return true;
        }
        TreeSet<String> listed = new TreeSet<>();
        for (Class<?> c : CLASSES) listed.add(c.getSimpleName());
        TreeSet<String> missing = new TreeSet<>(onDisk);
        missing.removeAll(listed);
        TreeSet<String> extra = new TreeSet<>(listed);
        extra.removeAll(onDisk);
        if (missing.isEmpty() && extra.isEmpty()) return true;
        System.out.println("FAIL: AllTests.CLASSES does not match tests/ on disk");
        if (!missing.isEmpty()) System.out.println("  on disk but not listed: " + missing);
        if (!extra.isEmpty()) System.out.println("  listed but not on disk:  " + extra);
        System.out.println("TOTAL: 0 files / 0 tests / 1 failures");
        return false;
    }

    /// This file's own directory, located from the class's source, or null.
    static Path sourceDir() {
        String p = System.getProperty("toolkit.tests.dir");
        if (p != null) return Path.of(p);
        // The source launcher exposes the launched file; fall back to a probe from cwd.
        String cmd = System.getProperty("jdk.launcher.sourcefile");
        if (cmd != null) return Path.of(cmd).toAbsolutePath().getParent();
        for (Path c : new Path[] {Path.of("scripts/tests"), Path.of("tests"), Path.of(".")}) {
            if (Files.isRegularFile(c.resolve("AllTests.java"))) return c.toAbsolutePath();
        }
        return null;
    }
}
