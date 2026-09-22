package tests;

// The shared-pattern filter, and its one-sided error.
//
// The truncation asks one question of the pattern usage count — "is this pattern used by more than
// one journey?" — and the answer decides between slicing it in place and forking it.
// [XbScan.SharedPatterns] answers it from two bitsets rather than an exact map.
//
// The error stays ONE-SIDED, and nothing else here holds it. A hash collision makes an unseen
// pattern look already-seen, so the filter says SHARED when the truth is single-use: the truncation
// forks a pattern it could have sliced. The opposite error would slice a pattern out from under the
// other journeys using it, and the structure cannot produce it, because a pattern really seen twice
// always has its bit set.

import toolkit.test.Check;
import toolkit.test.Runner;
import transformers.xb.XbScan.SharedPatterns;

public class TestXbSharedPatterns {

    public static void main(String[] args) {
        Runner.run(TestXbSharedPatterns.class);
    }

    /// The plain reading: seen once is single-use, seen twice is shared.
    public static void testSeenTwiceIsShared() {
        SharedPatterns p = new SharedPatterns();
        p.note("at:obb:ServiceJourneyPattern:010S3V.j26100505:");
        p.note("at:obb:ServiceJourneyPattern:120I3.j26100505:");
        p.note("at:obb:ServiceJourneyPattern:120I3.j26100505:");

        Check.that(!p.isShared("at:obb:ServiceJourneyPattern:010S3V.j26100505:"),
                "a pattern one journey uses is sliced in place");
        Check.that(p.isShared("at:obb:ServiceJourneyPattern:120I3.j26100505:"),
                "a pattern two journeys use must be forked");
        Check.equals(2L, p.distinctPatterns(), "two distinct patterns were seen");
    }

    /// A pattern nothing ever noted is not shared.
    public static void testAnUnseenPatternIsNotShared() {
        SharedPatterns p = new SharedPatterns();
        p.note("IT:ITH10:ServiceJourneyPattern:1");
        Check.that(!p.isShared("IT:ITH10:ServiceJourneyPattern:2"), "unseen, so not shared");
    }

    /// THE property, asserted over a corpus-sized population: whatever the hash does, every pattern
    /// that was really used twice reports shared. The false-positive direction is measured rather
    /// than asserted at zero — at 564 k ids in 2^26 slots a collision is permitted, and should stay
    /// a small fraction of a percent.
    public static void testEverySharedPatternIsReportedSharedAtCorpusScale() {
        SharedPatterns p = new SharedPatterns();
        int n = 564_177;
        // Ids shaped like the corpus's: a long common prefix and a varying tail, which is the shape
        // that clusters under a weak hash.
        String prefix = "at:obb:ServiceJourneyPattern:0";
        for (int i = 0; i < n; i++) {
            p.note(prefix + i + ".j26100505:");
            if (i % 3 == 0) p.note(prefix + i + ".j26100505:");   // every third is really shared
        }
        int missed = 0;
        int falsePositives = 0;
        for (int i = 0; i < n; i++) {
            boolean reallyShared = i % 3 == 0;
            boolean says = p.isShared(prefix + i + ".j26100505:");
            if (reallyShared && !says) missed++;
            if (!reallyShared && says) falsePositives++;
        }
        Check.equals(0, missed,
                "NO pattern that is really shared may be reported single-use -- that is the error "
                + "that would slice a pattern out from under another journey");
        // Not a target, a tripwire: if this ever climbs, the table is too small for the corpus.
        Check.that(falsePositives < n / 100,
                "false 'shared' verdicts stay under 1% (harmless, they only cost a fork): "
                + falsePositives + " of " + n);
    }
}
