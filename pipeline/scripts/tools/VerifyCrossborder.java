package tools;

import toolkit.util.Log;
import transformers.feedfix.CodeNames;
import transformers.xb.XbIds;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/// Verifies that the cross-border services on the IT/AT/CH corridors are linked in an exported
/// EPIP zip. Reads the SHIPPED ARTEFACT only -- no store, no rebuild -- so it also runs against an
/// archive whose `data/` stores are long gone. The coupling reports interchanges BUILT; an id
/// collision or a dropped leg loses some between there and the artefact OTP loads.
///
/// Three passes, all streaming:
///
///  - A: corridor inventory. Resolve every `CrossBorder=true` ServiceJourneyInterchange's
///    From/ToPointRef through the PassengerStopAssignments to its consolidated StopPlace -- that is
///    WHERE the handover happens -- and take the corridor itself from the two coupled JOURNEYS'
///    countries, not from the interchange id's `<cc>-<cc>` prefix. The prefix comes from
///    `XbIds.feedCountry`, which recognises `IT:` `ch:` `at:` `DE:` and nothing else, so every STA
///    (`it:apb:`) journey is miscounted there.
///  - B: link integrity. Both ends resolvable, anchored on one Quay (`XbLinks.shareQuay`) or at
///    least one StopPlace, flags set, and the two journeys from different feeds. B1 then says how
///    far apart the two ends are and whether they lie in one country. B2 rolls the same links up
///    per TRAIN.
///  - C (`--full`): completeness. One pass over the `gSS-NNNN.xml` journey groups collecting rail
///    journeys that call at a border station, then the pairs that share a normalised train number
///    across two countries and were NEVER linked -- what the matcher should have caught.
///  - E (`--full`): why the two ends differ, from the same traversal C already pays for -- whether
///    a station both legs serve was available to anchor on, whether one leg is redundant, and
///    whether the link runs backwards along the continuing leg.
///
/// Usage: `tools/VerifyCrossborder.java <export.zip> [--full] [--out F] [--top N]`
public final class VerifyCrossborder {

    /// The corridor countries. A link touching anything else is reported, not counted.
    private static final Set<String> CORRIDOR = Set.of("IT", "AT", "CH");

    /// The export's three journey-bearing entry shapes. A numbered group entry holds the patterns
    /// and their journeys; a shard's shared group entry holds its ServiceLinks and interchanges;
    /// the TrainNumbers every group's journeys reference are globally shared. [#readLinks] takes
    /// the last two on one walk, hence the alternation.
    private static final Pattern GROUP_ENTRY = Pattern.compile("g\\d+-\\d+\\.xml");
    private static final Pattern LINK_ENTRIES =
            Pattern.compile("g\\d+-shared\\.xml|shared-04-links\\.xml");

    /// One consolidated StopPlace, as far as this report needs it.
    ///
    /// Both names are kept because either can be the useless one: the Trenitalia RAP feeds put the
    /// UIC code in `Name` and the station in `ShortName` (`830008217` / `ROMA TIBURTINA`), while
    /// the Verbund feeds publish neither. An export built after `CodeNames` ran carries the station
    /// in `Name`, so the `ShortName` arm is what reads an older artefact.
    public record Stop(String id, String name, String shortName, Double lat, Double lon) {
        public String label() {
            if (CodeNames.isLabel(name)) return name;
            return CodeNames.isLabel(shortName) ? shortName : id;
        }
    }

    /// A PassengerStopAssignment flattened to the two refs the anchoring check reads.
    record Assign(String psaId, String stopPlace, String quay) {}

    /// One `CrossBorder=true` ServiceJourneyInterchange.
    static final class Link {
        String id, version, fromPt, toPt, fromJy, toJy;
        boolean staySeated, crossBorder, planned;
        String fromStop, toStop, fromQuay, toQuay, corridor;
    }

    /// One rail journey seen by pass C.
    record Journey(String id, String pattern, String mode, List<String> trainNumberRefs) {}

    private VerifyCrossborder() {}

    public static void main(String[] args) throws Exception {
        Path zip = null, out = null, otpReport = null;
        boolean full = false;
        int top = 20;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--full" -> full = true;
                case "--out" -> out = Path.of(args[++i]);
                case "--top" -> top = Integer.parseInt(args[++i]);
                case "--otp-report" -> otpReport = Path.of(args[++i]);
                default -> {
                    if (args[i].startsWith("--")) throw new IllegalArgumentException("unknown flag " + args[i]);
                    zip = Path.of(args[i]);
                }
            }
        }
        if (zip == null) {
            System.err.println("usage: VerifyCrossborder <export.zip> [--full] [--out F] [--top N]");
            System.exit(2);
        }

        Map<String, Stop> stops = readStops(zip);
        Log.info("[verify-xb] %,d StopPlaces", stops.size());
        Map<String, Assign> assigns = readAssignments(zip);
        Log.info("[verify-xb] %,d PassengerStopAssignments", assigns.size());
        Links links = readLinks(zip);
        Log.info("[verify-xb] %,d interchanges, %,d cross-border, %,d TrainNumbers",
                links.total, links.crossBorder.size(), links.trainNumbers.size());

        for (Link l : links.crossBorder) {
            Assign a = assigns.get(l.fromPt);
            Assign b = assigns.get(l.toPt);
            if (a != null) { l.fromStop = a.stopPlace(); l.fromQuay = a.quay(); }
            if (b != null) { l.toStop = b.stopPlace(); l.toQuay = b.quay(); }
            l.corridor = corridorOf(l, stops);
        }

        StringBuilder md = new StringBuilder();
        passA(md, links, stops, top);
        passB(md, links, stops, top);
        if (otpReport != null) passD(md, links, otpReport);
        if (full) {
            Map<String, List<String>> legStops = new HashMap<>();
            passC(md, zip, links, assigns, stops, top, legStops);
            passE(md, links, legStops, stops);
        }

        System.out.print(md);
        if (out != null) {
            Files.createDirectories(out.toAbsolutePath().getParent());
            Files.writeString(out, md.toString(), StandardCharsets.UTF_8);
            Log.info("[verify-xb] wrote %s", out);
        }
    }

    // ----------------------------------------------------------------------------------- //
    // Corridor derivation
    // ----------------------------------------------------------------------------------- //

    /// The corridor of a link, from the countries of the two JOURNEYS it couples. Sorted and
    /// joined, so `AT-IT` and `IT-AT` are one bucket.
    ///
    /// NOT the countries of the two handover stops: a correctly anchored link puts both ends on the
    /// one consolidated StopPlace, so the stop countries are trivially equal and every good link
    /// would read as domestic.
    static String corridorOf(Link l, Map<String, Stop> stops) {
        String a = countryOfJourney(l.fromJy, l.fromStop);
        String b = countryOfJourney(l.toJy, l.toStop);
        if (a == null || b == null) return "unresolved";
        if (a.equals(b)) return a + " (same country)";
        List<String> cc = new ArrayList<>(List.of(a, b));
        cc.sort(Comparator.naturalOrder());
        return cc.get(0) + "-" + cc.get(1);
    }

    /// Feed country of the journey id, then of its handover stop, then the stop's UIC country.
    ///
    /// The UIC is last because `XbIds.canonUic` takes the longest digit run, and a Verbund id like
    /// `at:48:1313` yields the nonsense canon `13_13`. `XbIds.uicCountry` misses on such a canon,
    /// so an id nothing can classify makes the link `unresolved`.
    public static String countryOfJourney(String journeyId, String stopId) {
        String c = feedCountryLoose(journeyId);
        if (c != null) return c;
        c = feedCountryLoose(stopId);
        if (c != null) return c;
        return XbIds.uicCountry(XbIds.canonUic(stopId));
    }

    /// `XbIds.feedCountry` plus the prefixes it does not know: the STOP id spaces the corridor
    /// profile has no reason to carry, `de:`, `fl:` and `si:`. A link anchored on one of those
    /// still needs a country for the inventory. Journey ids are fully covered by `XbIds`.
    public static String feedCountryLoose(String id) {
        if (id == null || id.isEmpty()) return null;
        String c = XbIds.feedCountry(id);
        if (c != null) return c;
        if (id.startsWith("de:")) return "DE";
        if (id.startsWith("fl:")) return "FL";
        if (id.startsWith("si:")) return "SI";
        return null;
    }

    /// Does this interchange id have an empty journey-identity slot?
    ///
    /// `XbLinks` builds the id as
    /// `<cc>-<cc>:ServiceJourneyInterchange:<num>:<localFrom>:<localTo>`, where the two local parts
    /// come from `localPart()` = everything after the LAST colon. Every ÖBB and STA journey id ends
    /// *with* a colon, so `localPart` returns `""` for them and the slot is empty — `::` in the
    /// middle, or a trailing `:` when it is the last slot.
    public static boolean hasEmptyIdentitySlot(String id) {
        return id != null && (id.contains("::") || id.endsWith(":"));
    }

    /// Are BOTH identity slots empty? Then the id is a pure function of corridor and train number,
    /// so two journeys of that number in that corridor collide on `(id, version)` and one silently
    /// overwrites the other.
    public static boolean hasNoIdentity(String id) {
        return id != null && id.endsWith("::");
    }

    /// The `<cc>-<cc>` prefix the coupling stamped into the interchange id, or null when the id
    /// carries none (every non-cross-border interchange).
    public static String idPrefixCorridor(String id) {
        int c = id.indexOf(':');
        if (c < 0) return null;
        String head = id.substring(0, c);
        int d = head.indexOf('-');
        if (d < 0) return null;
        List<String> cc = new ArrayList<>(List.of(head.substring(0, d), head.substring(d + 1)));
        if (!CORRIDOR.containsAll(cc)) return null;
        cc.sort(Comparator.naturalOrder());
        return cc.get(0) + "-" + cc.get(1);
    }

    // ----------------------------------------------------------------------------------- //
    // Pass A -- corridor inventory
    // ----------------------------------------------------------------------------------- //

    static void passA(StringBuilder md, Links links, Map<String, Stop> stops, int top) {
        md.append("# Cross-border link verification\n\n");
        md.append("## A. Corridor inventory\n\n");
        md.append(String.format("%,d ServiceJourneyInterchange total, %,d with `CrossBorder=true`.%n%n",
                links.total, links.crossBorder.size()));

        Map<String, Map<String, Integer>> byCorridor = new TreeMap<>();
        Map<String, Integer> corridorTotal = new TreeMap<>();
        int prefixDisagree = 0, degenerateId = 0, fullyDegenerateId = 0;
        for (Link l : links.crossBorder) {
            corridorTotal.merge(l.corridor, 1, Integer::sum);
            String station = l.fromStop != null && l.fromStop.equals(l.toStop) ? l.fromStop
                    : l.fromStop != null ? l.fromStop : l.toStop;
            byCorridor.computeIfAbsent(l.corridor, k -> new HashMap<>())
                    .merge(station == null ? "(unresolved)" : station, 1, Integer::sum);
            String pfx = idPrefixCorridor(l.id);
            if (pfx != null && !pfx.equals(l.corridor)) prefixDisagree++;
            if (hasEmptyIdentitySlot(l.id)) degenerateId++;
            if (hasNoIdentity(l.id)) fullyDegenerateId++;
        }

        md.append("| corridor | links |\n|---|---:|\n");
        corridorTotal.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> md.append(String.format("| %s | %,d |%n", e.getKey(), e.getValue())));
        md.append('\n');

        for (Map.Entry<String, Map<String, Integer>> e : byCorridor.entrySet()) {
            md.append(String.format("### %s — %,d links%n%n", e.getKey(), corridorTotal.get(e.getKey())));
            md.append("| links | handover station | UIC | StopPlace |\n|---:|---|---|---|\n");
            e.getValue().entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(top)
                    .forEach(s -> {
                        Stop st = stops.get(s.getKey());
                        String uic = XbIds.canonUic(s.getKey());
                        md.append(String.format("| %,d | %s | %s | `%s` |%n", s.getValue(),
                                st == null ? "(unknown)" : st.label(), uic == null ? "" : uic, s.getKey()));
                    });
            md.append('\n');
        }

        md.append(String.format("Interchange ids whose `<cc>-<cc>` prefix disagrees with the resolved "
                + "corridor: **%,d**.%n", prefixDisagree));
        md.append(String.format("Interchange ids with an empty journey-identity slot: **%,d**, of which "
                + "**%,d** carry no journey identity at all and so collide on corridor + train number "
                + "alone.%n%n", degenerateId, fullyDegenerateId));
    }

    // ----------------------------------------------------------------------------------- //
    // Pass B -- link integrity
    // ----------------------------------------------------------------------------------- //

    static void passB(StringBuilder md, Links links, Map<String, Stop> stops, int top) {
        int n = links.crossBorder.size();
        int unresolved = 0, sameStop = 0, diffStop = 0, sameQuay = 0, noQuay = 0;
        int notStaySeated = 0, notPlanned = 0, sameFeed = 0;
        int oneCountry = 0, twoCountries = 0, unknownCountry = 0;
        // Every bucket is printed even at zero: the "< 440 m" row is the stop-consolidation gate,
        // and a gate that disappears when it passes cannot be read as having passed.
        Map<String, Integer> byBucket = new LinkedHashMap<>();
        Map<String, String> bucketExample = new LinkedHashMap<>();
        for (String b : DISTANCE_BUCKETS) byBucket.put(b, 0);
        for (Link l : links.crossBorder) {
            if (l.fromStop == null || l.toStop == null) unresolved++;
            else if (l.fromStop.equals(l.toStop)) sameStop++;
            else {
                diffStop++;
                Double m = distM(stops.get(l.fromStop), stops.get(l.toStop));
                String bucket = distanceBucket(m);
                byBucket.merge(bucket, 1, Integer::sum);
                bucketExample.putIfAbsent(bucket, example(l, stops, m));
                Boolean one = sameCountryHandover(l.fromStop, l.toStop);
                if (one == null) unknownCountry++;
                else if (one) oneCountry++;
                else twoCountries++;
            }
            if (l.fromQuay != null && l.fromQuay.equals(l.toQuay)) sameQuay++;
            if (l.fromQuay == null || l.toQuay == null) noQuay++;
            if (!l.staySeated) notStaySeated++;
            if (!l.planned) notPlanned++;
            String a = feedCountryLoose(l.fromJy);
            String b = feedCountryLoose(l.toJy);
            if (a != null && a.equals(b)) sameFeed++;
        }
        md.append("## B. Link integrity\n\n");
        md.append("| check | links | share |\n|---|---:|---:|\n");
        row(md, "point ref does not resolve to a StopPlace", unresolved, n);
        row(md, "both ends on the SAME StopPlace", sameStop, n);
        row(md, "ends on DIFFERENT StopPlaces (no shared handover)", diffStop, n);
        row(md, "both ends on the SAME Quay (XbLinks.shareQuay contract)", sameQuay, n);
        row(md, "at least one end carries no QuayRef", noQuay, n);
        row(md, "StaySeated not true", notStaySeated, n);
        row(md, "Planned not true", notPlanned, n);
        row(md, "both journeys from the same feed country", sameFeed, n);
        md.append('\n');

        // How far apart the two ends are decides what the row above MEANS: two copies of one
        // station that never consolidated and two stations 30 km apart are different defects with
        // different fixes, and the single count above cannot tell them apart.
        md.append("### B1. How far apart the two ends are\n\n");
        md.append(String.format("Of the %,d links whose ends are on different StopPlaces:%n%n", diffStop));
        md.append("| separation | links | share of the ").append(diffStop).append(" |\n|---|---:|---:|\n");
        for (String b : byBucket.keySet()) row(md, b, byBucket.get(b), diffStop);
        md.append('\n');
        md.append("| handover stops | links | share of the ").append(diffStop).append(" |\n|---|---:|---:|\n");
        row(md, "lie in ONE country (the two legs overlap on domestic track)", oneCountry, diffStop);
        row(md, "lie in two countries (complementary legs, a border station pair)", twoCountries, diffStop);
        row(md, "country of at least one end unknown", unknownCountry, diffStop);
        md.append('\n');
        if (!bucketExample.isEmpty()) {
            md.append("One example per bucket:\n\n");
            for (String b : byBucket.keySet()) {
                String ex = bucketExample.get(b);
                if (ex != null) md.append("- ").append(b).append(" — ").append(ex).append('\n');
            }
            md.append('\n');
        }
        passB2(md, links, stops, top);
    }

    /// One service — a train number on a corridor — as the report counts it.
    static final class Service {
        int links;
        int anchored;                                   // links whose two ends are one StopPlace
        final Map<String, Integer> handovers = new LinkedHashMap<>();   // "A → B" or "A" -> count
    }

    /// The link counts of pass B, rolled up per TRAIN, which is the row an operator can check
    /// against a timetable.
    ///
    /// The train number is the interchange id's third field, which both builders fill from the
    /// normalised number the two legs matched on; the corridor is the resolved one from pass A, not
    /// the id prefix.
    static void passB2(StringBuilder md, Links links, Map<String, Stop> stops, int top) {
        Map<String, Service> byService = new LinkedHashMap<>();
        for (Link l : links.crossBorder) {
            String key = l.corridor + " " + trainNumber(l.id);
            Service s = byService.computeIfAbsent(key, k -> new Service());
            s.links++;
            boolean one = l.fromStop != null && l.fromStop.equals(l.toStop);
            if (one) s.anchored++;
            String where = one ? label(stops, l.fromStop)
                    : label(stops, l.fromStop) + " → " + label(stops, l.toStop);
            s.handovers.merge(where, 1, Integer::sum);
        }
        int whole = 0;
        for (Service s : byService.values()) if (s.anchored == s.links) whole++;

        md.append("### B2. By service\n\n");
        md.append(String.format("%,d corridor services (a train number on a corridor) carry the "
                + "%,d links. **%,d of them hand over at ONE station on every link**; %,d do not.%n%n",
                byService.size(), links.crossBorder.size(), whole, byService.size() - whole));

        List<Map.Entry<String, Service>> bad = new ArrayList<>();
        for (Map.Entry<String, Service> e : byService.entrySet()) {
            if (e.getValue().anchored < e.getValue().links) bad.add(e);
        }
        bad.sort((a, b) -> Integer.compare(b.getValue().links - b.getValue().anchored,
                a.getValue().links - a.getValue().anchored));
        if (!bad.isEmpty()) {
            md.append("Services with at least one link still handing over between two stations:\n\n");
            md.append("| corridor | train | links | anchored | handover |\n|---|---:|---:|---:|---|\n");
            for (Map.Entry<String, Service> e : bad) {
                Service s = e.getValue();
                md.append(String.format("| %s | %s | %,d | %,d | %s |%n",
                        corridorOf(e.getKey()), numberOf(e.getKey()), s.links, s.anchored,
                        topHandover(s)));
            }
            md.append('\n');
        }
        List<Map.Entry<String, Service>> good = new ArrayList<>();
        for (Map.Entry<String, Service> e : byService.entrySet()) {
            if (e.getValue().anchored == e.getValue().links) good.add(e);
        }
        good.sort((a, b) -> Integer.compare(b.getValue().links, a.getValue().links));
        if (!good.isEmpty()) {
            md.append(String.format("The %,d fully anchored services, heaviest first (top %d):%n%n",
                    good.size(), top));
            md.append("| corridor | train | links | handover station |\n|---|---:|---:|---|\n");
            good.stream().limit(top).forEach(e -> md.append(String.format("| %s | %s | %,d | %s |%n",
                    corridorOf(e.getKey()), numberOf(e.getKey()), e.getValue().links,
                    topHandover(e.getValue()))));
            md.append('\n');
        }
    }

    /// The train number an interchange id carries — its third colon-separated field. The two
    /// identity slots after it are `slot()`ed journey ids, which hold no colons, so the split is
    /// unambiguous. `?` when the id has no such field (nothing this report reads should).
    public static String trainNumber(String linkId) {
        if (linkId == null) return "?";
        String[] f = linkId.split(":");
        return f.length >= 3 ? f[2] : "?";
    }

    private static String corridorOf(String key) {
        return key.substring(0, key.lastIndexOf(' '));
    }

    private static String numberOf(String key) {
        return key.substring(key.lastIndexOf(' ') + 1);
    }

    /// The service's most common handover, with the count of the others when there are any.
    private static String topHandover(Service s) {
        String best = null;
        int bestN = -1;
        for (Map.Entry<String, Integer> e : s.handovers.entrySet()) {
            if (e.getValue() > bestN) {
                best = e.getKey();
                bestN = e.getValue();
            }
        }
        return s.handovers.size() == 1 ? best
                : String.format("%s (+%d other)", best, s.handovers.size() - 1);
    }

    private static String label(Map<String, Stop> stops, String id) {
        if (id == null) return "(unresolved)";
        Stop s = stops.get(id);
        return s == null ? id : s.label();
    }

    /// The distance buckets, in report order. `XbMergeStops.CONSOLIDATE_RADIUS_DEG` is 0.004
    /// degrees of latitude, so anything below [#CONSOLIDATE_RADIUS_M] is a pair the rail
    /// consolidation would have folded into one station had it seen both. Above it the two ends are
    /// different stations and no stop merge can help.
    public static final List<String> DISTANCE_BUCKETS = List.of(
            "< 440 m — two copies of one station",
            "440 m – 5 km",
            "5 – 20 km",
            "> 20 km",
            "a handover stop carries no coordinate");

    /// ~442 m: `XbMergeStops.CONSOLIDATE_RADIUS_DEG` (0.004 deg) in metres of latitude.
    static final double CONSOLIDATE_RADIUS_M = 0.004 * 110_540.0;

    /// Which [#DISTANCE_BUCKETS] entry `m` falls in; the no-coordinate bucket for null.
    public static String distanceBucket(Double m) {
        if (m == null) return DISTANCE_BUCKETS.get(4);
        if (m < CONSOLIDATE_RADIUS_M) return DISTANCE_BUCKETS.get(0);
        if (m < 5_000) return DISTANCE_BUCKETS.get(1);
        if (m < 20_000) return DISTANCE_BUCKETS.get(2);
        return DISTANCE_BUCKETS.get(3);
    }

    /// Are the two handover stops in one country? Null when either country is unknown, which the
    /// report counts as a third answer: `feedCountryLoose` returns null for an id space nobody has
    /// taught it.
    public static Boolean sameCountryHandover(String fromStop, String toStop) {
        String a = feedCountryLoose(fromStop);
        String b = feedCountryLoose(toStop);
        return a == null || b == null ? null : a.equals(b);
    }

    /// Metres between two stops, or null when either lacks a coordinate. Fast equirectangular
    /// form, the same one `XbMergeStops.distM` uses.
    public static Double distM(Stop a, Stop b) {
        if (a == null || b == null || a.lat() == null || a.lon() == null
                || b.lat() == null || b.lon() == null) {
            return null;
        }
        double coslat = Math.cos(Math.toRadians(a.lat()));
        double dlat = (a.lat() - b.lat()) * 110_540.0;
        double dlon = (a.lon() - b.lon()) * 111_320.0 * coslat;
        return Math.hypot(dlat, dlon);
    }

    private static String example(Link l, Map<String, Stop> stops, Double m) {
        Stop a = stops.get(l.fromStop);
        Stop b = stops.get(l.toStop);
        return String.format("%s → %s%s, `%s`",
                a == null ? l.fromStop : a.label(), b == null ? l.toStop : b.label(),
                m == null ? "" : String.format(" (%,.0f m)", m), l.id);
    }

    private static void row(StringBuilder md, String label, int v, int n) {
        md.append(String.format(Locale.ROOT, "| %s | %,d | %.1f%% |%n", label, v, n == 0 ? 0.0 : 100.0 * v / n));
    }

    // ----------------------------------------------------------------------------------- //
    // Pass D -- what the consumer did with them
    // ----------------------------------------------------------------------------------- //

    /// Cross-reference the links against OTP's own data-import report (`graph/report/`, written
    /// when `build-config.json` sets `dataImportReport`). A link is DEAD in the graph when the
    /// journey it references was skipped, and OTP skips a journey whose passing-time count does not
    /// match its pattern's stop points -- which is what `XbTruncate.cut` produces when it slices a
    /// journey but leaves a SHARED pattern intact. The gate for that defect is the two counts below
    /// going to zero.
    static void passD(StringBuilder md, Links links, Path reportDir) throws IOException {
        List<Path> pages = mismatchPages(reportDir);
        Set<String> skipped = new HashSet<>();
        Pattern sj = Pattern.compile("ServiceJourney=([^,<]+),");
        for (Path p : pages) {
            java.util.regex.Matcher m = sj.matcher(Files.readString(p, StandardCharsets.UTF_8));
            while (m.find()) skipped.add(m.group(1).trim());
        }
        int dead = 0;
        Map<String, Integer> byCorridor = new TreeMap<>();
        for (Link l : links.crossBorder) {
            if (!skipped.contains(l.fromJy) && !skipped.contains(l.toJy)) continue;
            dead++;
            byCorridor.merge(l.corridor, 1, Integer::sum);
        }
        md.append("## D. What OTP did with them\n\n");
        md.append(String.format("%,d journeys were skipped for `StopPointsMismatch` on the build that "
                + "wrote `%s`.%n%n", skipped.size(), reportDir));
        md.append(String.format("**%,d of the %,d cross-border links reference a skipped journey**, so "
                + "they are dead in the graph — and the leg they pointed at is missing from it too "
                + "%n%n", dead, links.crossBorder.size()));
        if (!byCorridor.isEmpty()) {
            md.append("| corridor | dead links |\n|---|---:|\n");
            byCorridor.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(e -> md.append(String.format("| %s | %,d |%n", e.getKey(), e.getValue())));
            md.append('\n');
        }
    }

    /// The `StopPointsMismatch` pages OTP wrote on the LAST build, taken from `index.html`.
    ///
    /// Not a directory glob. OTP paginates each issue type and does not clean the directory first,
    /// so a build that produces fewer pages than its predecessor leaves the tail behind and a glob
    /// silently unions two builds. `index.html` is rewritten every build and lists only that
    /// build's pages.
    ///
    /// Falls back to the glob when there is no index, and warns when it does.
    static List<Path> mismatchPages(Path reportDir) throws IOException {
        Pattern page = Pattern.compile("StopPointsMismatch\\d*\\.html");
        Path index = reportDir.resolve("index.html");
        if (Files.isRegularFile(index)) {
            java.util.regex.Matcher m = page.matcher(Files.readString(index, StandardCharsets.UTF_8));
            Set<String> named = new java.util.LinkedHashSet<>();
            while (m.find()) named.add(m.group());
            if (!named.isEmpty()) {
                List<Path> out = new ArrayList<>();
                for (String n : named) {
                    Path p = reportDir.resolve(n);
                    if (Files.isRegularFile(p)) out.add(p);
                }
                return out;
            }
        }
        Log.warn("[verify-xb] %s has no usable index.html; falling back to a directory glob, which "
                + "may mix this build's pages with a previous build's leftovers", reportDir);
        try (var files = Files.list(reportDir)) {
            return files.filter(f -> page.matcher(f.getFileName().toString()).matches()).sorted().toList();
        }
    }

    // ----------------------------------------------------------------------------------- //
    // Pass C -- completeness
    // ----------------------------------------------------------------------------------- //

    /// Rail journeys that call at a station both countries serve, share a normalised train number
    /// across the border and carry no link. One pass over every `gSS-NNNN.xml` -- the numbered
    /// entries only, which is where the patterns and journeys are; a shard's `gSS-shared.xml`
    /// holds its ServiceLinks and interchanges and has neither.
    ///
    /// The border-station set is DERIVED, not hardcoded: a consolidated StopPlace is a border
    /// station when its PassengerStopAssignments arrive from two different countries' feeds, which
    /// is the condition the matcher's shared-stop test relies on.
    ///
    /// `legStops` is pass E's side output, filled here rather than by a second traversal of the
    /// ~44 GB of journey groups: journey id -> the consolidated StopPlaces its pattern calls at, in
    /// order, for the journeys the cross-border links name.
    static void passC(StringBuilder md, Path zip, Links links, Map<String, Assign> assigns,
            Map<String, Stop> stops, int top, Map<String, List<String>> legStops) throws Exception {
        Map<String, Set<String>> stopCountries = new HashMap<>();
        Map<String, String> sspStop = new HashMap<>();
        for (Map.Entry<String, Assign> e : assigns.entrySet()) {
            String stop = e.getValue().stopPlace();
            if (stop == null) continue;
            sspStop.put(e.getKey(), stop);
            String c = feedCountryLoose(e.getKey());
            if (c != null) stopCountries.computeIfAbsent(stop, k -> new HashSet<>()).add(c);
        }
        Set<String> border = new HashSet<>();
        for (Map.Entry<String, Set<String>> e : stopCountries.entrySet()) {
            Set<String> cc = new HashSet<>(e.getValue());
            cc.retainAll(CORRIDOR);
            if (cc.size() >= 2) border.add(e.getKey());
        }
        md.append("## C. Completeness\n\n");
        md.append(String.format("%,d consolidated StopPlaces carry assignments from two or more of "
                + "IT/AT/CH — the derived border set.%n%n", border.size()));

        Set<String> linked = new HashSet<>();
        for (Link l : links.crossBorder) { linked.add(l.fromJy); linked.add(l.toJy); }

        // The journeys pass E needs a stop sequence for: both ends of every cross-border link.
        Set<String> wanted = new HashSet<>();
        for (Link l : links.crossBorder) {
            if (l.fromJy != null) wanted.add(strip(l.fromJy));
            if (l.toJy != null) wanted.add(strip(l.toJy));
        }

        // pattern -> the border StopPlaces it calls at, collected from the group entries' patterns.
        Map<String, Set<String>> patternBorder = new HashMap<>();
        Map<String, List<Journey>> byNumber = new HashMap<>();
        long journeys = 0;
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (!GROUP_ENTRY.matcher(e.getName()).matches()) continue;
                journeys += scanGroup(zis, sspStop, border, patternBorder, byNumber,
                        links.trainNumbers, wanted, legStops);
            }
        }
        md.append(String.format("%,d journeys scanned; %,d patterns call at a border station.%n%n",
                journeys, patternBorder.size()));
        if (legStops.size() < wanted.size()) {
            Log.warn("[verify-xb] %,d of the %,d journeys the cross-border links name have no "
                    + "resolvable pattern in the journey groups; pass E counts them separately",
                    wanted.size() - legStops.size(), wanted.size());
        }

        record Missed(String num, String station, String a, String b) {}
        List<Missed> missed = new ArrayList<>();
        for (Map.Entry<String, List<Journey>> e : byNumber.entrySet()) {
            Map<String, List<Journey>> byCc = new HashMap<>();
            for (Journey j : e.getValue()) {
                String c = feedCountryLoose(j.id());
                if (c != null && CORRIDOR.contains(c)) byCc.computeIfAbsent(c, k -> new ArrayList<>()).add(j);
            }
            if (byCc.size() < 2) continue;
            List<String> cc = new ArrayList<>(byCc.keySet());
            for (int i = 0; i < cc.size(); i++) {
                for (int k = i + 1; k < cc.size(); k++) {
                    for (Journey a : byCc.get(cc.get(i))) {
                        for (Journey b : byCc.get(cc.get(k))) {
                            if (linked.contains(a.id()) || linked.contains(b.id())) continue;
                            Set<String> shared = new HashSet<>(
                                    patternBorder.getOrDefault(a.pattern(), Set.of()));
                            shared.retainAll(patternBorder.getOrDefault(b.pattern(), Set.of()));
                            if (shared.isEmpty()) continue;
                            missed.add(new Missed(e.getKey(), shared.iterator().next(), a.id(), b.id()));
                        }
                    }
                }
            }
        }
        Map<String, Integer> byStation = new HashMap<>();
        for (Missed m : missed) byStation.merge(m.station(), 1, Integer::sum);
        md.append(String.format("**%,d unlinked rail journey pairs** share a normalised train number "
                + "across two corridor countries and call at the same border station.%n%n", missed.size()));
        if (!byStation.isEmpty()) {
            md.append("| pairs | border station | StopPlace |\n|---:|---|---|\n");
            byStation.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(top)
                    .forEach(s -> {
                        Stop st = stops.get(s.getKey());
                        md.append(String.format("| %,d | %s | `%s` |%n", s.getValue(),
                                st == null ? "(unknown)" : st.label(), s.getKey()));
                    });
            md.append('\n');
        }
    }

    /// One NUMBERED group entry: patterns first (they precede their journeys in the entry), then
    /// the rail journeys on them. Returns the journey count.
    ///
    /// `patternSeq` is scoped to THIS entry and dropped with it: a journey's pattern is written in
    /// the same entry as the journey. That colocation is an invariant of the export, because OTP's
    /// TripPatternMapper indexes a pattern's journeys from one file's localValues() only — which is
    /// why the ServiceLinks could move up to `gSS-shared.xml` and the patterns could not.
    static long scanGroup(InputStream in, Map<String, String> sspStop, Set<String> border,
            Map<String, Set<String>> patternBorder, Map<String, List<Journey>> byNumber,
            Map<String, String> trainNumbers, Set<String> wanted,
            Map<String, List<String>> legStops) throws Exception {
        XMLStreamReader r = factory().createXMLStreamReader(shield(in));
        long journeys = 0;
        String patternId = null;
        Set<String> patternStops = null;
        List<String> patternStopSeq = null;
        Map<String, List<String>> patternSeq = new HashMap<>();
        String journeyId = null, journeyPattern = null, mode = null, text = null;
        List<String> tnRefs = new ArrayList<>();
        boolean inJourney = false;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.CHARACTERS) { text = r.getText(); continue; }
            if (ev == XMLStreamConstants.START_ELEMENT) {
                String n = r.getLocalName();
                switch (n) {
                    case "ServiceJourneyPattern" -> {
                        patternId = r.getAttributeValue(null, "id");
                        patternStops = new HashSet<>();
                        patternStopSeq = new ArrayList<>();
                    }
                    case "ScheduledStopPointRef" -> {
                        if (patternStops != null && !inJourney) {
                            String stop = sspStop.get(r.getAttributeValue(null, "ref"));
                            // The sequence keeps unassigned points as null, so a position in it is
                            // a position in the pattern.
                            patternStopSeq.add(stop);
                            if (stop != null && border.contains(stop)) patternStops.add(stop);
                        }
                    }
                    case "ServiceJourney" -> {
                        inJourney = true;
                        journeyId = r.getAttributeValue(null, "id");
                        journeyPattern = null; mode = null; tnRefs.clear();
                    }
                    case "ServiceJourneyPatternRef", "JourneyPatternRef" -> {
                        if (inJourney) journeyPattern = strip(r.getAttributeValue(null, "ref"));
                    }
                    case "TrainNumberRef" -> {
                        if (inJourney) tnRefs.add(r.getAttributeValue(null, "ref"));
                    }
                    default -> { }
                }
                continue;
            }
            if (ev != XMLStreamConstants.END_ELEMENT) continue;
            String n = r.getLocalName();
            if (n.equals("TransportMode") && inJourney) mode = text;
            else if (n.equals("ServiceJourneyPattern") && patternId != null) {
                if (patternStops != null && !patternStops.isEmpty()) {
                    patternBorder.put(strip(patternId), patternStops);
                }
                patternSeq.put(strip(patternId), patternStopSeq);
                patternId = null; patternStops = null; patternStopSeq = null;
            } else if (n.equals("ServiceJourney")) {
                inJourney = false;
                journeys++;
                if (journeyPattern != null && wanted.contains(strip(journeyId))) {
                    List<String> seq = patternSeq.get(journeyPattern);
                    if (seq != null) legStops.put(strip(journeyId), seq);
                }
                if (journeyPattern != null && "rail".equals(mode) && patternBorder.containsKey(journeyPattern)) {
                    for (String ref : tnRefs) {
                        String v = trainNumbers.get(ref);
                        if (v == null) continue;
                        String num = XbIds.normalizeTrainNumber(v);
                        if (num == null || num.isEmpty()) continue;
                        byNumber.computeIfAbsent(num, k -> new ArrayList<>())
                                .add(new Journey(journeyId, journeyPattern, mode, List.copyOf(tnRefs)));
                    }
                }
            }
        }
        r.close();
        return journeys;
    }

    // ----------------------------------------------------------------------------------- //
    // Pass E -- what the two legs actually share
    // ----------------------------------------------------------------------------------- //

    /// The reading of a link whose two ends sit on DIFFERENT StopPlaces, from the two legs' own
    /// stop sequences: whether they had to be different.
    public enum Handover {
        SHARED_AVAILABLE("a leg serves the other's handover stop — the link could anchor there"),
        SHARED_ELSEWHERE("the legs share a station, but neither serves the other's handover stop"),
        DISJOINT("the legs share no station at all (the stub shape)"),
        UNRESOLVED("a leg's pattern was not found in the journey groups");

        public final String label;

        Handover(String label) {
            this.label = label;
        }
    }

    /// Which [Handover] the link is, from the two legs' stop sequences and the two handover stops.
    ///
    /// `SHARED_AVAILABLE` is the finding: the matcher admits a counterpart only on two shared
    /// canonical StopPlaces (or one with a coincident handover time), so a candidate-derived link
    /// always HAS a station both legs serve — `XbLinks.junction` just does not anchor at one,
    /// taking the counterpart's first/last stop overall instead.
    public static Handover classify(List<String> fromSeq, List<String> toSeq,
            String fromStop, String toStop) {
        if (fromSeq == null || toSeq == null || fromSeq.isEmpty() || toSeq.isEmpty()) {
            return Handover.UNRESOLVED;
        }
        if (toSeq.contains(fromStop) || fromSeq.contains(toStop)) return Handover.SHARED_AVAILABLE;
        Set<String> shared = new HashSet<>(fromSeq);
        shared.remove(null);
        shared.retainAll(toSeq);
        return shared.isEmpty() ? Handover.DISJOINT : Handover.SHARED_ELSEWHERE;
    }

    /// Does one leg call at every stop of the other? Then the shorter leg adds no service the
    /// longer one does not already carry — the case the coupling already handles for a
    /// single-stop block by dropping it ("fully covered by their authoritative counterpart").
    /// Counted, not classified, because it cuts across [#classify]'s classes.
    public static boolean fullyCovered(List<String> a, List<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        Set<String> sa = new HashSet<>(a);
        Set<String> sb = new HashSet<>(b);
        sa.remove(null);
        sb.remove(null);
        return !sa.isEmpty() && !sb.isEmpty() && (sb.containsAll(sa) || sa.containsAll(sb));
    }

    /// Does the link run BACKWARDS along the continuing leg — the passenger leaves the first leg
    /// at a station the second leg reaches only AFTER the one it is told to join at?
    ///
    /// The Lienz → Weitlanbrunn shape is the worked case: both Austrian, 30 km apart, and the
    /// continuing ÖBB leg passes the joining stop first and the leaving stop last.
    public static boolean backwards(List<String> toSeq, String fromStop, String toStop) {
        if (toSeq == null || fromStop == null || toStop == null) return false;
        int leave = toSeq.indexOf(fromStop);
        int join = toSeq.indexOf(toStop);
        return leave >= 0 && join >= 0 && leave > join;
    }

    static void passE(StringBuilder md, Links links, Map<String, List<String>> legStops,
            Map<String, Stop> stops) {
        Map<Handover, Integer> counts = new LinkedHashMap<>();
        for (Handover h : Handover.values()) counts.put(h, 0);
        Map<Handover, String> examples = new LinkedHashMap<>();
        int diff = 0, covered = 0, back = 0;
        for (Link l : links.crossBorder) {
            if (l.fromStop == null || l.toStop == null || l.fromStop.equals(l.toStop)) continue;
            diff++;
            List<String> fromSeq = legStops.get(strip(l.fromJy));
            List<String> toSeq = legStops.get(strip(l.toJy));
            Handover h = classify(fromSeq, toSeq, l.fromStop, l.toStop);
            counts.merge(h, 1, Integer::sum);
            examples.putIfAbsent(h, example(l, stops, distM(stops.get(l.fromStop), stops.get(l.toStop))));
            if (fullyCovered(fromSeq, toSeq)) covered++;
            if (backwards(toSeq, l.fromStop, l.toStop)) back++;
        }
        md.append("## E. Why the two ends differ\n\n");
        md.append(String.format("The %,d links whose ends sit on different StopPlaces, read against "
                + "the two legs' own stop sequences.%n%n", diff));
        md.append("| reading | links | share |\n|---|---:|---:|\n");
        for (Handover h : Handover.values()) row(md, h.label, counts.get(h), diff);
        md.append('\n');
        md.append("| of those | links | share |\n|---|---:|---:|\n");
        row(md, "one leg calls at EVERY stop of the other (redundant leg)", covered, diff);
        row(md, "the link runs BACKWARDS along the continuing leg", back, diff);
        md.append('\n');
        for (Handover h : Handover.values()) {
            String ex = examples.get(h);
            if (ex != null) md.append("- ").append(h.name()).append(" — ").append(ex).append('\n');
        }
        md.append('\n');
    }

    /// Ids in refs carry no `@version`; ids on the objects sometimes do. One form for both sides.
    public static String strip(String id) {
        if (id == null) return null;
        int at = id.lastIndexOf('@');
        return at > 0 && id.indexOf(':', at) < 0 ? id.substring(0, at) : id;
    }

    // ----------------------------------------------------------------------------------- //
    // Readers
    // ----------------------------------------------------------------------------------- //

    /// The cross-border links plus the TrainNumber values.
    static final class Links {
        int total;
        final List<Link> crossBorder = new ArrayList<>();
        final Map<String, String> trainNumbers = new HashMap<>();
    }

    static Map<String, Stop> readStops(Path zip) throws Exception {
        Map<String, Stop> out = new LinkedHashMap<>();
        forEntry(zip, "shared-02-sites.xml", r -> {
            String id = null, name = null, shortName = null, text = null;
            Double lat = null, lon = null;
            int depth = 0;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.CHARACTERS) { text = r.getText(); continue; }
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    if (r.getLocalName().equals("StopPlace")) {
                        depth++;
                        if (depth == 1) {
                            id = r.getAttributeValue(null, "id");
                            name = null; shortName = null; lat = null; lon = null;
                        }
                    }
                    continue;
                }
                if (ev != XMLStreamConstants.END_ELEMENT) continue;
                // depth == 1 throughout: a StopPlace embeds Quays, and only the outer element's
                // own name and centroid belong to it.
                switch (r.getLocalName()) {
                    case "Name" -> { if (depth == 1 && name == null) name = text; }
                    case "ShortName" -> { if (depth == 1 && shortName == null) shortName = text; }
                    case "Latitude" -> { if (depth == 1 && lat == null) lat = parse(text); }
                    case "Longitude" -> { if (depth == 1 && lon == null) lon = parse(text); }
                    case "StopPlace" -> {
                        if (depth == 1 && id != null) out.put(id, new Stop(id, name, shortName, lat, lon));
                        depth--;
                    }
                    default -> { }
                }
            }
        });
        return out;
    }

    static Map<String, Assign> readAssignments(Path zip) throws Exception {
        Map<String, Assign> out = new HashMap<>();
        forEntry(zip, "shared-03-network.xml", r -> {
            String psaId = null, ssp = null, stop = null, quay = null;
            boolean in = false;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "PassengerStopAssignment" -> {
                            in = true;
                            psaId = r.getAttributeValue(null, "id");
                            ssp = null; stop = null; quay = null;
                        }
                        case "ScheduledStopPointRef" -> { if (in) ssp = r.getAttributeValue(null, "ref"); }
                        case "StopPlaceRef" -> { if (in) stop = r.getAttributeValue(null, "ref"); }
                        case "QuayRef" -> { if (in) quay = r.getAttributeValue(null, "ref"); }
                        default -> { }
                    }
                    continue;
                }
                if (ev == XMLStreamConstants.END_ELEMENT
                        && r.getLocalName().equals("PassengerStopAssignment")) {
                    in = false;
                    if (ssp != null) {
                        // As the pipeline resolves it: lowest-id assignment wins per stop point.
                        Assign prev = out.get(ssp);
                        if (prev == null || psaId != null && psaId.compareTo(prev.psaId()) < 0) {
                            out.put(ssp, new Assign(psaId, stop, quay));
                        }
                    }
                }
            }
        });
        return out;
    }

    /// The TrainNumbers from the globally shared `shared-04-links.xml` (every group's journeys
    /// reference them, so they cannot sit in one group's file) and the interchanges from each
    /// shard's `gSS-shared.xml` (OTP keeps that indexed for the whole group, which is when it
    /// resolves an interchange's two journeys).
    ///
    /// ONE walk for both. `ZipInputStream` is sequential and `getNextEntry` has to inflate what it
    /// skips, so a walk of this archive is ~47 GB of zlib whatever it is looking for.
    static Links readLinks(Path zip) throws Exception {
        Links links = new Links();
        int matched = forEntries(zip, LINK_ENTRIES, (name, r) -> {
            if (name.equals("shared-04-links.xml")) {
                readTrainNumbers(r, links);
            } else {
                readInterchanges(r, links);
            }
        });
        // A layout mismatch must not read as a finding. The whole report is downstream of `links`,
        // so an export that stopped putting them where this reads would come out as "0 cross-border
        // links" -- the very answer the report exists to distinguish from a real zero. The check
        // sits here because `forEntries` cannot throw on an absent entry: most shards have none.
        if (matched == 0) {
            throw new IOException("no " + LINK_ENTRIES.pattern() + " entry in " + zip
                    + " -- the export layout changed and this reader did not");
        }
        return links;
    }

    /// `shared-04-links.xml` -> TrainNumber id -> its advertised value.
    private static void readTrainNumbers(XMLStreamReader r, Links links) throws Exception {
        String tnId = null, text = null;
        boolean inTn = false;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.CHARACTERS) { text = r.getText(); continue; }
            if (ev == XMLStreamConstants.START_ELEMENT) {
                if (r.getLocalName().equals("TrainNumber")) {
                    inTn = true;
                    tnId = r.getAttributeValue(null, "id");
                }
                continue;
            }
            if (ev != XMLStreamConstants.END_ELEMENT) continue;
            switch (r.getLocalName()) {
                // The export writes a TrainNumber's value as ForAdvertisement; Name/ShortName/
                // PublicCode are accepted too because the feeds are not consistent about it.
                case "ForAdvertisement", "Name", "ShortName", "PublicCode" -> {
                    if (inTn && tnId != null && trim(text) != null) {
                        links.trainNumbers.putIfAbsent(tnId, trim(text));
                    }
                }
                case "TrainNumber" -> { inTn = false; tnId = null; }
                default -> { }
            }
        }
    }

    /// One `gSS-shared.xml` -> its ServiceJourneyInterchanges; the `CrossBorder=true` ones are kept.
    private static void readInterchanges(XMLStreamReader r, Links links) throws Exception {
        Link cur = null;
        String text = null;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.CHARACTERS) { text = r.getText(); continue; }
            if (ev == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "ServiceJourneyInterchange" -> {
                        links.total++;
                        cur = new Link();
                        cur.id = r.getAttributeValue(null, "id");
                        cur.version = r.getAttributeValue(null, "version");
                    }
                    case "FromPointRef" -> { if (cur != null) cur.fromPt = r.getAttributeValue(null, "ref"); }
                    case "ToPointRef" -> { if (cur != null) cur.toPt = r.getAttributeValue(null, "ref"); }
                    case "FromJourneyRef" -> { if (cur != null) cur.fromJy = r.getAttributeValue(null, "ref"); }
                    case "ToJourneyRef" -> { if (cur != null) cur.toJy = r.getAttributeValue(null, "ref"); }
                    default -> { }
                }
                continue;
            }
            if (ev != XMLStreamConstants.END_ELEMENT) continue;
            switch (r.getLocalName()) {
                case "StaySeated" -> { if (cur != null) cur.staySeated = "true".equals(trim(text)); }
                case "CrossBorder" -> { if (cur != null) cur.crossBorder = "true".equals(trim(text)); }
                case "Planned" -> { if (cur != null) cur.planned = "true".equals(trim(text)); }
                case "ServiceJourneyInterchange" -> {
                    if (cur != null && cur.crossBorder) links.crossBorder.add(cur);
                    cur = null;
                }
                default -> { }
            }
        }
    }

    // ----------------------------------------------------------------------------------- //
    // Plumbing
    // ----------------------------------------------------------------------------------- //

    interface Body { void read(XMLStreamReader r) throws Exception; }

    /// [#forEntries] reads a SET of entries, so its body gets the name as well: which entry it is
    /// holding is the only thing that says how to read it.
    interface NamedBody { void read(String name, XMLStreamReader r) throws Exception; }

    /// Stream ONE named entry out of the zip. `ZipInputStream` is sequential, so the entry is found
    /// by walking forward; the archive is written name-sorted and the shared entries sit late, so
    /// reaching one costs a skip over the journey groups.
    static void forEntry(Path zip, String name, Body body) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (!e.getName().equals(name)) continue;
                XMLStreamReader r = factory().createXMLStreamReader(shield(zis));
                body.read(r);
                r.close();
                return;
            }
        }
        throw new IOException("no entry " + name + " in " + zip);
    }

    /// Every entry whose name matches, in zip order, on ONE walk. Unlike [#forEntry] a zero-match
    /// run is NOT an error: a shard with no ServiceLinks and no interchanges emits no shared group
    /// entry at all, and `--shards` above the component count leaves whole shards empty.
    static int forEntries(Path zip, Pattern name, NamedBody body) throws Exception {
        int matched = 0;
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (!name.matcher(e.getName()).matches()) continue;
                matched++;
                XMLStreamReader r = factory().createXMLStreamReader(shield(zis));
                body.read(e.getName(), r);
                r.close();
            }
        }
        return matched;
    }

    /// StAX closes the stream it is handed; a ZipInputStream must survive its entry.
    static InputStream shield(InputStream in) {
        return new java.io.FilterInputStream(in) {
            @Override public void close() { }
        };
    }

    static XMLInputFactory factory() {
        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
        f.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        return f;
    }

    static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    static Double parse(String s) {
        try {
            return s == null ? null : Double.valueOf(s.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
