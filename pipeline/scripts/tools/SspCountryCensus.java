package tools;

import noi.netex.model.PointInLinkSequence_VersionedChildStructure;
import noi.netex.model.ScheduledStopPoint;
import noi.netex.model.ServiceJourneyPattern;
import noi.netex.model.StopPointInJourneyPattern_VersionedChildStructure;
import toolkit.store.Store;
import transformers.xb.XbIds;
import transformers.xb.XbProfile;
import toolkit.store.Stores;
import toolkit.store.Txn;
import toolkit.util.Log;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// What country the corridor classifier gives every ScheduledStopPoint in a store, and where that
/// answer is nonsense.
///
/// `XbIds.stopCountry` tries a HAFAS `xx-` prefix first and falls back to
/// `XbIds.uicCountry(XbIds.canonUic(ref))`. The fallback has no way to know whether the digits it
/// found are a UIC code: `canonUic` takes the LONGEST digit run in the id, and an unrecognised
/// two-digit prefix passes through as the country itself. So an id whose longest digit run is a
/// local number yields a country like `31` or `11`, which is not a country and matches nothing.
///
/// A cross-border CANDIDATE is admitted only when the journey is seen to call in its own feed's
/// country, so a journey whose domestic stops all classify as `31` is never a candidate, is never
/// truncated, and reaches the coupling only as a counterpart. The STA Pustertal runs are that
/// case: their Italian stops are `IT:ITH10:ScheduledStopPoint:1170:52:31080` and friends, they
/// classify as country `31`, and the journeys keep their Austrian tail.
///
/// Stop-level: it says WHICH id spaces the classifier cannot read and how many stops sit in them,
/// not how many JOURNEYS change classification.
///
/// `--patterns` reports what the classification of every ServiceJourneyPattern would BECOME if the
/// unreadable ids resolved to the country of their own id space.
///
/// Usage: `tools/SspCountryCensus.java <store.lmdb> [--top N] [--patterns]`
public final class SspCountryCensus {

    /// The countries the corridor tables actually name — `XbIds.uicCountry`'s value set plus the
    /// honorary override's. Anything else that comes out of `sspCountry` is a passed-through UIC
    /// prefix, i.e. the fallback failing quietly.
    static final Set<String> REAL = Set.of("IT", "CH", "FR", "AT", "DE", "SI", "HU", "CZ", "SK", "FL");

    /// One id space's tally.
    static final class Space {
        long stops;
        final Map<String, Long> countries = new LinkedHashMap<>();   // resolved country -> count
        final Map<String, String> example = new LinkedHashMap<>();   // country -> one id
    }

    private SspCountryCensus() {}

    public static void main(String[] args) throws Exception {
        Path store = null;
        int top = 25;
        boolean patterns = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--top")) top = Integer.parseInt(args[++i]);
            else if (args[i].equals("--patterns")) patterns = true;
            else store = Path.of(args[i]);
        }
        if (store == null) {
            System.err.println("usage: SspCountryCensus <store.lmdb> [--top N]");
            System.exit(2);
        }
        if (patterns) {
            patternCensus(store);
            return;
        }
        Map<String, Space> spaces = new LinkedHashMap<>();
        long total = 0, hafas = 0, uic = 0, space = 0, bogus = 0, none = 0;
        try (Store db = Stores.open(store, /*readonly=*/true); Txn txn = db.roTxn()) {
            for (Object o : db.iterOnlyObjects(txn, ScheduledStopPoint.class)) {
                String id = ((ScheduledStopPoint) o).getId();
                if (id == null) continue;
                total++;
                String country = XbIds.stopCountry(id);
                Space s = spaces.computeIfAbsent(space(id), k -> new Space());
                s.stops++;
                String key = country == null ? "(none)" : country;
                s.countries.merge(key, 1L, Long::sum);
                s.example.putIfAbsent(key, id);
                // Which arm answered, asked the way sspCountry asks it: HAFAS prefix, then a REAL
                // UIC, then the id space.
                if (country == null) none++;
                else if (!REAL.contains(country)) bogus++;
                else if (hafasAnswer(id) != null) hafas++;
                else if (XbIds.uicCountry(XbIds.canonUic(id)) != null) uic++;
                else space++;
            }
        }
        Log.info("[ssp-census] %,d ScheduledStopPoints in %s", total, store);
        Log.info("[ssp-census] resolved by HAFAS prefix %,d | by UIC %,d | by id space %,d "
                + "| NOT A COUNTRY %,d | null %,d", hafas, uic, space, bogus, none);

        List<Map.Entry<String, Space>> byBogus = new ArrayList<>(spaces.entrySet());
        byBogus.sort(Comparator.comparingLong((Map.Entry<String, Space> e) -> -bogusOf(e.getValue())));
        Log.info("[ssp-census] id spaces whose stops do NOT resolve to a country, worst first:");
        for (Map.Entry<String, Space> e : byBogus) {
            long b = bogusOf(e.getValue());
            if (b == 0) break;
            Log.info("[ssp-census]   %-18s %,9d stops, %,9d unresolvable  %s",
                    e.getKey(), e.getValue().stops, b, sample(e.getValue()));
        }
        Log.info("[ssp-census] every id space, by size (top %d):", top);
        List<Map.Entry<String, Space>> bySize = new ArrayList<>(spaces.entrySet());
        bySize.sort(Comparator.comparingLong((Map.Entry<String, Space> e) -> -e.getValue().stops));
        bySize.stream().limit(top).forEach(e -> Log.info("[ssp-census]   %-18s %,9d stops  %s",
                e.getKey(), e.getValue().stops, sample(e.getValue())));
    }

    /// The country an id SPACE belongs to, for ids the UIC fallback cannot read: the publishing
    /// authority is in the id whether or not the digits are a UIC code. `IT:` covers every Italian
    /// regional space (`IT:ITI1`, `IT:ITH10`, …) — including Marche's `IT:ITI3`, which reaches this
    /// store as `IT:` because `normalize-it-ids` strips its undeclared `epd:` token at load.
    public static String spaceCountry(String id) {
        return XbIds.stopSpaceCountry(id);
    }

    /// What `sspCountry` would say if the unreadable ids fell back to their id space instead of to
    /// a two-digit fragment of a local number.
    public static String fixedCountry(String ref) {
        String c = XbIds.stopCountry(ref);
        if (c != null && REAL.contains(c)) return c;
        return spaceCountry(ref);
    }

    /// candidate / stub / neither, as the corridor scan decides it: a candidate spans two countries
    /// INCLUDING its feed's, a stub lies wholly in one country that is not its feed's, and anything
    /// else is skipped.
    public static String classOf(Set<String> countries, String home) {
        if (home == null) return "no-home";
        if (countries.isEmpty()) return "neither";
        if (countries.size() == 1) return countries.contains(home) ? "neither" : "stub";
        return countries.contains(home) ? "candidate" : "neither";
    }

    /// The transition matrix over patterns: how each one is classified today and how it would be.
    static void patternCensus(Path store) throws Exception {
        Map<String, long[]> byFeed = new LinkedHashMap<>();     // feed -> {patterns, changed}
        Map<String, Long> moves = new LinkedHashMap<>();        // "was -> becomes" -> count
        Map<String, String> example = new LinkedHashMap<>();
        long total = 0;
        try (Store db = Stores.open(store, /*readonly=*/true); Txn txn = db.roTxn()) {
            for (Object o : db.iterOnlyObjects(txn, ServiceJourneyPattern.class)) {
                ServiceJourneyPattern pat = (ServiceJourneyPattern) o;
                if (pat.getPointsInSequence() == null) continue;
                total++;
                Set<String> cur = new LinkedHashSet<>();
                Set<String> fix = new LinkedHashSet<>();
                for (PointInLinkSequence_VersionedChildStructure p : pat.getPointsInSequence()
                        .getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern()) {
                    if (!(p instanceof StopPointInJourneyPattern_VersionedChildStructure spp)
                            || spp.getScheduledStopPointRef() == null) {
                        continue;
                    }
                    String ref = spp.getScheduledStopPointRef().getValue().getRef();
                    String a = XbIds.stopCountry(ref);
                    if (a != null && !a.isEmpty()) cur.add(a);
                    String b = fixedCountry(ref);
                    if (b != null && !b.isEmpty()) fix.add(b);
                }
                String home = XbIds.feedCountry(pat.getId());
                String was = classOf(cur, home);
                String now = classOf(fix, home);
                String feed = space(pat.getId());
                long[] f = byFeed.computeIfAbsent(feed, k -> new long[2]);
                f[0]++;
                if (!was.equals(now)) f[1]++;
                String key = was + " -> " + now;
                moves.merge(key, 1L, Long::sum);
                if (!was.equals(now)) example.putIfAbsent(key, pat.getId());
            }
        }
        Log.info("[ssp-census] %,d ServiceJourneyPatterns in %s", total, store);
        Log.info("[ssp-census] classification if unreadable stop ids fell back to their id space:");
        moves.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, Long> e) -> -e.getValue()))
                .forEach(e -> Log.info("[ssp-census]   %-26s %,9d %s", e.getKey(), e.getValue(),
                        example.getOrDefault(e.getKey(), "")));
        Log.info("[ssp-census] patterns that change class, by feed:");
        byFeed.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> -e.getValue()[1]))
                .limit(20)
                .forEach(e -> Log.info("[ssp-census]   %-18s %,9d patterns, %,9d change",
                        e.getKey(), e.getValue()[0], e.getValue()[1]));
    }

    /// The HAFAS `xx-` country the ref carries, or null — `XbIds.stopCountry`'s first arm, asked
    /// on its own so the census can name the arm that answered.
    static String hafasAnswer(String ref) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:^|:)([a-z]{2})-\\d").matcher(ref);
        return m.find() ? XbProfile.HAFAS_COUNTRY.get(m.group(1)) : null;
    }

    /// The two leading colon-separated tokens of an id — the feed-ish namespace, the same shape
    /// `XbStopRefs.namespace` uses for StopPlaces.
    static String space(String id) {
        String[] p = id.split(":", 3);
        return p.length >= 2 ? p[0] + ":" + p[1] : p[0];
    }

    private static long bogusOf(Space s) {
        long n = 0;
        for (Map.Entry<String, Long> e : s.countries.entrySet()) {
            if (e.getKey().equals("(none)") || !REAL.contains(e.getKey())) n += e.getValue();
        }
        return n;
    }

    /// The countries this id space produces, commonest first, each with one real id behind it.
    private static String sample(Space s) {
        List<Map.Entry<String, Long>> l = new ArrayList<>(s.countries.entrySet());
        l.sort(Comparator.comparingLong((Map.Entry<String, Long> e) -> -e.getValue()));
        Set<String> seen = new LinkedHashSet<>();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e : l) {
            if (seen.size() == 4) {
                sb.append(", …");
                break;
            }
            seen.add(e.getKey());
            if (sb.length() > 0) sb.append(", ");
            sb.append(String.format("%s=%,d", e.getKey(), e.getValue()));
            if (!REAL.contains(e.getKey())) sb.append(" [").append(s.example.get(e.getKey())).append(']');
        }
        return sb.toString();
    }
}
