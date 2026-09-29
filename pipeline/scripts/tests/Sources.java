package tests;

// This repository's source tree, as a list of .java files a test can scan.

import toolkit.test.Check;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class Sources {

    private Sources() {}

    /// The trees a fix -- or a knob -- can land in.
    public static final List<String> ROOTS = List.of(
            "scripts/transformers", "scripts/conv", "scripts/fix", "scripts/tests");

    /// Same locator idiom AllTests.sourceDir() uses: the property, then the launched file, then cwd.
    public static Path root() {
        String p = System.getProperty("toolkit.tests.dir");
        if (p != null) return Path.of(p).toAbsolutePath().getParent().getParent();
        String cmd = System.getProperty("jdk.launcher.sourcefile");
        if (cmd != null) return Path.of(cmd).toAbsolutePath().getParent().getParent().getParent();
        return Path.of("").toAbsolutePath();
    }

    /// Every `.java` file under [#ROOTS], minus the ones `skip` accepts.
    ///
    /// Proves the subject exists and is the expected size before any caller believes a pass:
    /// every root must be a real directory, and the walk must find a plausible number of files.
    public static List<Path> all(java.util.function.Predicate<Path> skip) throws IOException {
        Path repo = root();
        List<String> missing = new ArrayList<>();
        for (String r : ROOTS) if (!Files.isDirectory(repo.resolve(r))) missing.add(r);
        Check.equals(List.of(), missing,
                "source roots exist under " + repo + " (a scan is vacuous without them)");

        List<Path> out = new ArrayList<>();
        for (String r : ROOTS) {
            try (var w = Files.walk(repo.resolve(r))) {
                w.filter(p -> p.toString().endsWith(".java")).filter(p -> !skip.test(p)).forEach(out::add);
            }
        }
        // The floor is a tripwire for "a root moved and the walk went quiet", not a target --
        // raise it when the tree grows, never lower it to make a run pass.
        Check.that(out.size() >= 35,
                "the source scan found " + out.size() + " .java files under " + repo
                        + " (expect >= 35); a scan that found nothing cannot have checked anything");
        return out;
    }

    /// The file's text.
    public static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }
}
