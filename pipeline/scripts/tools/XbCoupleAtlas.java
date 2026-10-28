package tools;

// Draw the cross-border coupling: what was coupled, what was refused, where the cuts fell, which
// segments survived and which were dropped.
//
// READ-ONLY, and it re-runs the coupling rather than reading its output. epip-xb.lmdb cannot answer
// "what was refused", because a refused group is by definition the one that left no trace: nothing
// truncated, nothing dropped, nothing linked. So this opens the SOURCE store and runs the read/couple
// front half again -- every phase of it is public -- keeping the per-group Tiling that
// XbCouple.couple itself throws away.
//
// USE:
//   java -Xmx4g $JVM -cp netex-toolkit-shaded.jar scripts/tools/XbCoupleAtlas.java \
//        data/epip.lmdb [--out graph/report/xb] [--per-page 100] [--num 1084]
//
// Two things it does not claim, both stated again in the legend it draws.
//
// It marks junctions, not link counts. Where a link is anchored is reproduced exactly -- it is
// legs.get(i).lastStation(), the same expression XbCouple passes to XbLinks.junction. How many
// links hang there is XbLinks.junction's answer, which this tool does not run; the bar is
// labelled "<=n" from the variant counts.
//
// And a refused group has no chain to order its stations by, so column order comes from the same
// fallback every group uses, the mean normalised position.

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import noi.netex.model.StopPlace;
import toolkit.store.Store;
import toolkit.store.Stores;
import toolkit.store.Txn;
import toolkit.util.Log;
import transformers.xb.XbCalendar.DaySet;
import transformers.xb.XbGroups;
import transformers.xb.XbProfile;
import transformers.xb.XbScan;
import transformers.xb.XbStitch;
import transformers.xb.XbTypes;
import transformers.xb.XbTypes.Dict;
import transformers.xb.XbTypes.Group;
import transformers.xb.XbTypes.Leg;
import transformers.xb.XbTypes.Node;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class XbCoupleAtlas {

    // What became of one publication. The order is the drawing order and the legend's order.
    public static final int LEG = 0;
    public static final int VARIANT = 1;
    public static final int DROPPED = 2;
    public static final int UNCOUPLED = 3;
    /// A member of a group that was refused whole. Not the same fact as uncoupled: nothing about a
    /// refused group was decided, so its publications were never offered to a leg and never turned
    /// down.
    public static final int REFUSED = 4;

    /// One publication's row. `from`/`to` are positions in `node.canon` bounding the run it keeps;
    /// -1/-1 when it keeps nothing, which is every dropped and every uncoupled publication.
    /// @param country the country this leg owns, interned in [Atlas#countries] — [XbTypes#NONE]
    ///                 for a row that owns nothing. Read from the tiling rather than from the id
    ///                 space of the first kept stop: a leg widened to share a handover begins at a
    ///                 station in the other country.
    /// @param refusal  index into [XbGroups#PARALLEL_REFUSAL] for an uncoupled row, else -1.
    /// @param blockedAt the station that defeated the cut, or [XbTypes#NONE].
    public record Row(Node node, int kind, int legIndex, int from, int to, int country, int refusal,
            int blockedAt, String note) {}

    /// A publication's calendar in the width a band row has for it: how many days it runs and the
    /// first of them. Empty stays empty rather than reading as "runs on day zero".
    static String calendar(Node node) {
        int n = node.days.size();
        if (n == 0) return "no days";
        java.time.LocalDate first = java.time.LocalDate.ofEpochDay(node.days.firstDay());
        return String.format("%dd %02d-%02d", n, first.getMonthValue(), first.getDayOfMonth());
    }

    /// One group, ready to draw.
    ///
    /// @param columns  the group's stations, in the order the ladder puts them left to right.
    /// @param junctions the handover stations, one per consecutive leg pair, in leg order.
    public record Band(String num, String refusal, List<Row> rows, int[] columns, int[] junctions,
            int[] junctionCap) {}

    public static void main(String[] args) throws Exception {
        Path source = null;
        Path out = Path.of("graph/report/xb");
        int perPage = 100;
        String only = null;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--out" -> out = Path.of(args[++i]);
                    case "--per-page" -> perPage = Integer.parseInt(args[++i]);
                    case "--num" -> only = args[++i];
                    default -> {
                        if (args[i].startsWith("--")) {
                            throw new IllegalArgumentException("unknown flag " + args[i]);
                        }
                        source = Path.of(args[i]);
                    }
                }
            }
            if (source == null) throw new IllegalArgumentException("no database given");
        } catch (RuntimeException e) {
            Log.error("usage: XbCoupleAtlas <epip_db> [--out DIR] [--per-page N] [--num TRAIN]");
            Log.error("  %s", e.getMessage());
            System.exit(1);
            return;
        }
        if (!Stores.exists(source)) {
            Log.error("%s is not an existing database.", source);
            System.exit(1);
        }

        Atlas atlas;
        try (Store db = Stores.open(source, true); Txn txn = db.roTxn()) {
            atlas = read(db, txn, only);
        } catch (IllegalStateException e) {
            // The self-check in band(), fatal rather than a warning.
            Log.error("[atlas] %s", e.getMessage());
            System.exit(1);
            return;
        }
        int pages = write(out, atlas, perPage);
        Log.info("[atlas] wrote %s: xb-map.svg, %d group sheet(s), xb-index.md", out, pages);
    }

    /// Everything drawable, derived from one store. Read-only; the caller owns the transaction.
    /// @param alias    the station identity used, so a probe can report which stations were paired
    ///                  rather than only how many.
    /// @param railPool  the rail-served stations, always computed, so a pair can be reported as
    ///                  rail-to-rail or bus-to-bus rather than only as paired.
    public record Atlas(List<Band> bands, Totals totals, Map<Integer, String> names,
            XbScan.Stations stations, Dict countries, XbGroups.Alias alias,
            java.util.BitSet railPool) {}

    /// Re-run the read/couple front half, exactly as [transformers.xb.XbCouple] composes it, and keep
    /// the per-group tilings that `couple` itself throws away.
    public static Atlas read(Store db, Txn txn, String only) {
        return read(db, txn, only, XbProfile.RAIL_PAIR_RADIUS_DEG,
                XbProfile.BUS_PAIR_RADIUS_DEG);
    }

    /// @param railRadiusDeg,roadRadiusDeg the two alias radii, parameters only so a probe can
    ///        compare several in one process; the pipeline uses the [XbProfile] constants.
    public static Atlas read(Store db, Txn txn, String only, double railRadiusDeg,
            double roadRadiusDeg) {
        List<Band> bands = new ArrayList<>();
        Totals totals = new Totals();
        Map<Integer, String> names = new HashMap<>();

        Dict countries = new Dict();
        Dict publishers = new Dict();
        Map<String, DaySet> dayTypeDates = transformers.xb.XbCalendar.readDayTypeDates(db, txn);
        XbScan.Stations stations = XbScan.readStations(db, txn, countries);
        transformers.xb.XbLines.LineMaps<noi.netex.model.AllVehicleModesOfTransportEnumeration> modes =
                transformers.xb.XbLines.lineValueMaps(db, txn,
                        noi.netex.model.Line::getTransportMode);
        XbScan.Pass1 p1 = XbScan.pass1(db, txn, dayTypeDates, modes);
        Int2ObjectMap<List<Node>> byNum =
                XbScan.pass2(db, txn, p1, stations, dayTypeDates, countries, publishers, modes);
        dayTypeDates = null;
        stations.releaseStopPointIndex();

        List<noi.netex.model.PassengerStopAssignment> psas = new ArrayList<>();
        for (Object o : db.iterOnlyObjects(txn, noi.netex.model.PassengerStopAssignment.class)) {
            psas.add((noi.netex.model.PassengerStopAssignment) o);
        }
        java.util.BitSet railPool = new java.util.BitSet(stations.ids().size());
        for (String id : transformers.xb.XbStations.railServed(db, txn, psas, p1.rail())) {
            int s = stations.ids().lookup(id);
            if (s != XbTypes.NONE) railPool.set(s);
        }
        psas.clear();
        Log.info("[atlas] %,d of %,d stations are rail-served", railPool.cardinality(),
                stations.ids().size());
        XbGroups.Alias alias = XbGroups.aliasStations(byNum, stations, railRadiusDeg, roadRadiusDeg,
                railPool);
        for (List<Node> ns : byNum.values()) {
            for (int i = 0; i < ns.size(); i++) ns.set(i, XbGroups.aliased(ns.get(i), alias.rep()));
        }
        // The same stitch the coupling does, at the same point in the sequence.
        XbStitch.stitch(db, txn, byNum, new XbStitch.Counters(), "[atlas]");
        List<Group> groups = XbGroups.groups(byNum);
        byNum.clear();

        // The same ordering XbCouple uses, so a train's band lands in the same place run to run.
        groups.sort(Comparator.<Group, String>comparing(g -> p1.numbers().value(g.num()))
                .thenComparing(g -> g.members().get(0).id));

        for (Group group : groups) {
            String num = p1.numbers().value(group.num());
            XbGroups.Tiling tiling = XbGroups.tile(group, alias.blind(), alias.narrow());
            Band band = band(num, group, tiling, totals);
            if (only == null || only.equals(num)) bands.add(band);
        }
        report(totals, bands.size(), only);

        // Names, for the stations that actually appear -- a few thousand, not the corpus's 275,625.
        Set<Integer> wanted = new HashSet<>();
        for (Band b : bands) {
            for (int s : b.columns()) wanted.add(s);
        }
        for (Object o : db.iterOnlyObjects(txn, StopPlace.class)) {
            StopPlace sp = (StopPlace) o;
            int s = stations.ids().lookup(sp.getId());
            if (s == XbTypes.NONE || !wanted.contains(s) || names.containsKey(s)) continue;
            String name = readable(nameOf(sp.getName()), sp);
            if (name != null) names.put(s, name);
        }
        Log.info("[atlas] %,d station names resolved of %,d drawn", names.size(), wanted.size());
        return new Atlas(bands, totals, names, stations, countries, alias, railPool);
    }

    /// @return how many group sheets were written.
    public static int write(Path out, Atlas atlas, int perPage) throws Exception {
        Files.createDirectories(out);
        writeMap(out.resolve("xb-map.svg"), atlas.bands(), atlas.stations(), atlas.names(),
                atlas.countries(), atlas.totals());
        int pages = writePages(out, atlas.bands(), atlas.stations(), atlas.names(),
                atlas.countries(), perPage);
        writeIndex(out.resolve("xb-index.md"), atlas.bands(), atlas.totals(), perPage, pages);
        return pages;
    }

    /// A station's name, preferring one a reader can place.
    ///
    /// Trenitalia names a good many StopPlaces with their own numeric code -- `830001645` is the
    /// Name element, not a fallback. The consolidation moved every merged-away copy's Quays onto
    /// the survivor, so where a publisher had a real name for the place it is usually still here,
    /// one level down. Falls back to the number when nothing better exists.
    static String readable(String spName, StopPlace sp) {
        if (spName != null && !isCode(spName)) return spName;
        if (sp.getQuays() != null) {
            for (jakarta.xml.bind.JAXBElement<?> el : sp.getQuays().getQuayRefOrQuay()) {
                if (!(el.getValue() instanceof noi.netex.model.Quay q)) continue;
                String qn = nameOf(q.getName());
                if (qn != null && !isCode(qn)) return qn;
            }
        }
        return spName;
    }

    private static boolean isCode(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    /// A NeTEx MultilingualString is a mixed content list, not a string, so the name is its text
    /// nodes joined.
    static String nameOf(noi.netex.model.MultilingualString ms) {
        if (ms == null || ms.getContent() == null) return null;
        StringBuilder b = new StringBuilder();
        for (Object o : ms.getContent()) {
            if (o instanceof String str) b.append(str);
        }
        String out = b.toString().trim();
        return out.isEmpty() ? null : out;
    }

    // ------------------------------------------------------------------------------------- //
    // Deriving what to draw
    // ------------------------------------------------------------------------------------- //

    public static final class Totals {
        public long groups;
        public long refused;
        public long legs;
        public long variants;
        public long dropped;
        public long uncoupled;
        /// Publications sitting in a group that was refused whole. See [XbCoupleAtlas#REFUSED].
        public long inRefused;
        public long junctions;
        /// How many times a band's column order had to break a cycle in the members' stop sequences.
        public long orderingCycles;
        /// Uncoupled publications by [XbGroups#PARALLEL_REFUSAL] index.
        public final long[] refusals = new long[XbGroups.PARALLEL_REFUSAL.length];
        /// A measurement: of the refusals in each bucket, how many would have been dropped as
        /// redundant had the coverage test counted the legs' variants' days. Never acted on.
        public final long[] coveredWithVariantDays = new long[XbGroups.PARALLEL_REFUSAL.length];
    }

    /// Turn one tiling into a band, or throw saying why it cannot be trusted.
    ///
    /// `Tiling` reports the uncoupled publications as a count, so their identity is recovered by
    /// subtraction: a member that is not a leg, not a variant of one and not dropped is a member
    /// the tiling left alone. Those four are the only outcomes `tile` has. Being a derivation
    /// rather than a reading, it is checked against the count the tiling reports and the whole run
    /// is abandoned if the two ever disagree.
    static Band band(String num, Group group, XbGroups.Tiling tiling, Totals totals) {
        List<Row> rows = new ArrayList<>();
        Set<Node> placed = new LinkedHashSet<>();

        List<Leg> legs = tiling.legs();
        for (int i = 0; i < legs.size(); i++) {
            Leg leg = legs.get(i);
            rows.add(new Row(leg.node(), LEG, i, leg.from(), leg.to(), leg.country(), -1,
                    XbTypes.NONE, null));
            placed.add(leg.node());
            for (Node v : leg.variants()) {
                // cutLeg truncates a variant to the leg's first and last station, not to its own
                // owned run, so the kept run is derived the same way it is there -- including the
                // short-working clamp: at the ends of the chain there is no junction, so cutLeg
                // cuts such a variant to its own extent rather than refusing it.
                boolean junctionAtStart = i > 0;
                boolean junctionAtEnd = i + 1 < legs.size();
                int from = v.firstIndexOf(leg.firstStation());
                if (from < 0 && !junctionAtStart) from = 0;
                int to = v.lastIndexOf(leg.lastStation());
                if (to < 0 && !junctionAtEnd) to = v.size() - 1;
                boolean cuttable = from >= 0 && to >= 0 && from < to;
                rows.add(new Row(v, VARIANT, i, cuttable ? from : -1, cuttable ? to : -1,
                        leg.country(), -1, XbTypes.NONE,
                        cuttable ? null : "cannot be cut to the leg"));
                placed.add(v);
            }
        }
        for (Node d : tiling.dropped()) {
            // No note: `note` is for the exceptions only. The gap it is right-aligned into holds
            // about 32 characters before it lands on top of the journey id.
            rows.add(new Row(d, DROPPED, -1, -1, -1, XbTypes.NONE, -1, XbTypes.NONE, null));
            placed.add(d);
        }
        boolean refused = tiling.refusal() != null;
        Map<Node, XbGroups.Refusal> why = new LinkedHashMap<>();
        for (XbGroups.Refusal f : tiling.refusals()) why.put(f.node(), f);
        long uncoupled = 0;
        for (Node m : group.members()) {
            if (placed.contains(m)) continue;
            XbGroups.Refusal f = why.get(m);
            rows.add(new Row(m, refused ? REFUSED : UNCOUPLED, -1, -1, -1, XbTypes.NONE,
                    f == null ? -1 : f.refusal(), f == null ? XbTypes.NONE : f.blockedAt(), null));
            if (!refused) uncoupled++;
            if (f != null) totals.refusals[f.refusal()]++;
            if (!refused && f != null && XbGroups.coveredBy(legs, m, true)) {
                totals.coveredWithVariantDays[f.refusal()]++;
            }
        }
        // A refused group reports leftAlone 0 and contributes no uncoupled rows, so the identity
        // holds for every group.
        if (uncoupled != tiling.leftAlone()) {
            throw new IllegalStateException(String.format(
                    "train %s: derived %d uncoupled members but the tiling reports %d. The "
                    + "subtraction is wrong, so nothing here can be trusted -- refusing to draw.",
                    num, uncoupled, tiling.leftAlone()));
        }

        int[] junctions = new int[Math.max(0, legs.size() - 1)];
        int[] cap = new int[junctions.length];
        for (int i = 0; i + 1 < legs.size(); i++) {
            junctions[i] = legs.get(i).lastStation();      // XbCouple's own anchor expression
            cap[i] = (1 + legs.get(i).variants().size()) * (1 + legs.get(i + 1).variants().size());
        }

        totals.groups++;
        if (refused) totals.refused++;
        totals.junctions += junctions.length;
        for (Row r : rows) {
            switch (r.kind()) {
                case LEG -> totals.legs++;
                case VARIANT -> totals.variants++;
                case DROPPED -> totals.dropped++;
                case UNCOUPLED -> totals.uncoupled++;
                default -> totals.inRefused++;
            }
        }
        return new Band(num, tiling.refusal(), rows, columns(rows, totals), junctions, cap);
    }

    /// The group's stations, left to right.
    ///
    /// The order has to respect every member's own sequence, or a line doubles back on itself and
    /// the picture lies about where the cut fell. A handover station is often also the first stop
    /// of the next leg and of its calendar variants, so a plain mean of normalised positions puts
    /// it left of stations the earlier leg reaches first.
    ///
    /// So: a topological order over "some member visits a immediately before b", with the mean
    /// normalised position kept only to choose among stations the constraints leave free. That
    /// tie-break is what keeps the result deterministic.
    ///
    /// Cycles are possible — two members can traverse the same pair in opposite directions, which
    /// is a return working the direction test should have kept out of the group — so a blocked
    /// queue releases its best remaining candidate and counts the break.
    static int[] columns(List<Row> rows, Totals totals) {
        Int2ObjectOpenHashMap<double[]> acc = new Int2ObjectOpenHashMap<>();   // {sumNorm, sumAbs, n}
        Int2ObjectOpenHashMap<java.util.LinkedHashSet<Integer>> next = new Int2ObjectOpenHashMap<>();
        Int2IntOpenHashMap indegree = new Int2IntOpenHashMap();

        for (Row r : rows) {
            Node node = r.node();
            int len = node.canon.length;
            int prev = XbTypes.NONE;
            for (int i = 0; i < len; i++) {
                int s = node.canon[i];
                if (s == XbTypes.NONE) continue;
                double[] a = acc.computeIfAbsent(s, k -> new double[3]);
                a[0] += len > 1 ? (double) i / (len - 1) : 0.0;
                a[1] += i;
                a[2] += 1;
                indegree.putIfAbsent(s, 0);
                if (prev != XbTypes.NONE && prev != s
                        && next.computeIfAbsent(prev, k -> new java.util.LinkedHashSet<>()).add(s)) {
                    indegree.addTo(s, 1);
                }
                prev = s;
            }
        }

        Comparator<Integer> byMean = (x, y) -> {
            double[] a = acc.get((int) x);
            double[] b = acc.get((int) y);
            int c = Double.compare(a[0] / a[2], b[0] / b[2]);
            if (c != 0) return c;
            c = Double.compare(a[1] / a[2], b[1] / b[2]);
            return c != 0 ? c : Integer.compare(x, y);
        };
        java.util.PriorityQueue<Integer> ready = new java.util.PriorityQueue<>(byMean);
        java.util.TreeSet<Integer> pending = new java.util.TreeSet<>(byMean);
        for (Int2IntOpenHashMap.Entry e : indegree.int2IntEntrySet()) {
            if (e.getIntValue() == 0) ready.add(e.getIntKey());
            else pending.add(e.getIntKey());
        }

        IntArrayList out = new IntArrayList(indegree.size());
        while (out.size() < indegree.size()) {
            Integer s = ready.poll();
            if (s == null) {
                // Cycle. Release the best remaining candidate and say so.
                s = pending.pollFirst();
                if (s == null) break;
                totals.orderingCycles++;
            } else {
                pending.remove(s);
            }
            out.add((int) s);
            for (int t : next.getOrDefault((int) s, new java.util.LinkedHashSet<>())) {
                if (indegree.addTo(t, -1) - 1 == 0 && pending.remove(t)) ready.add(t);
            }
        }
        return out.toIntArray();
    }

    static void report(Totals t, int drawn, String only) {
        Log.info("[atlas] %,d groups (%,d refused, holding %,d publications), %,d legs, "
                + "%,d variants, %,d dropped, %,d uncoupled, %,d junctions", t.groups, t.refused,
                t.inRefused, t.legs, t.variants, t.dropped, t.uncoupled, t.junctions);
        long tot = 0;
        for (long n : t.refusals) tot += n;
        if (tot > 0) {
            Log.info("[atlas] why the %,d uncoupled publications were refused:", tot);
            long wouldDrop = 0;
            for (int i = 0; i < t.refusals.length; i++) {
                Log.info("[atlas]     %,6d  %s  (%,d would be dropped as covered if a leg's "
                        + "VARIANTS' days counted)", t.refusals[i], XbGroups.PARALLEL_REFUSAL[i],
                        t.coveredWithVariantDays[i]);
                wouldDrop += t.coveredWithVariantDays[i];
            }
            Log.info("[atlas] measurement: %,d of the %,d uncoupled would become redundancy drops if "
                    + "coveredBy counted variant days — MEASURED ONLY, the live rule is legs-only",
                    wouldDrop, tot);
        }
        if (t.orderingCycles > 0) {
            Log.warn("[atlas] %,d station orderings broke a cycle: two publications traverse the "
                    + "same pair of stations in opposite directions, so no left-to-right order "
                    + "satisfies both and those bands have one kink each", t.orderingCycles);
        }
        if (only != null) Log.info("[atlas] --num %s selected %d of them to draw", only, drawn);
    }

    // ------------------------------------------------------------------------------------- //
    // Drawing
    // ------------------------------------------------------------------------------------- //

    private static final int PAGE_W = 1800;
    private static final int LABEL_W = 460;
    private static final int ROW_H = 17;
    private static final int HEAD_H = 22;
    // The rotated station-label strip. A 22-character label at font 8 is ~97 px long and hangs at
    // 60°, so it needs sin(60) × 97 ≈ 84 px of clear space beneath the last row.
    private static final int TICK_H = 92;
    private static final int TICK_CHARS = 22;
    private static final int BAND_GAP = 16;

    /// What the picture does not say, one line each, below the key.
    ///
    /// Held here rather than inline so [#LEGEND_H] is derived from how many there are; a note added
    /// at a hard-coded baseline would be drawn on top of the first band.
    private static final String[] NOTES = {
        "The first column holds the country a LEG owns. A blank there with a ↳ is a calendar variant "
        + "of the leg above — the same train published again for other dates, cut to that leg and "
        + "linked alongside it.",
        "× ? and ! mark publications that own no country at all, so a country would be a fiction: "
        + "they are dropped, uncoupled, or inside a group that was refused whole. A refused group "
        + "has no legs to draw, so only its header says what went wrong.",
        "Columns are the group's stations in mean route order; a hollow marker is a stop the "
        + "publication does not serve, and the station names take the colour of the country the "
        + "consolidation elected for the station.",
        "\"+n stitched\" is ONE VEHICLE published as n+1 ServiceJourneys and joined on the "
        + "publishers' own JourneyMeetings; the label names the first. ◇ marks each station where "
        + "two were joined — hover it for the pair — and each was a terminus of both halves before.",
    };
    private static final int NOTES_Y = 90;
    private static final int NOTE_H = 16;
    private static final int LEGEND_H = NOTES_Y + NOTE_H * (NOTES.length - 1) + 12;
    /// The two label columns: the owning country, then the journey id.
    private static final int CC_X = 16;
    private static final int ID_X = 38;

    static String colourOf(String cc) {
        return switch (cc == null ? "" : cc) {
            case "CH" -> "#c0392b";
            case "AT" -> "#2c6fbb";
            case "IT" -> "#1f8f5f";
            case "DE" -> "#7d5bbe";
            default -> "#666666";
        };
    }

    /// A station's colour: the country the consolidation elected for it.
    ///
    /// Not the id space of its StopPlace, which is who published the survivor rather than where the
    /// place is -- Como's survivor is an Italian id and Brennero's an Austrian one.
    /// `XbScan.readStations` votes this per station from the stop points assigned there, preferring
    /// a HAFAS infix or a real UIC code over an id space, and that vote is what the tiling itself
    /// used to decide ownership.
    static String stationColour(int station, XbScan.Stations st, Dict countries) {
        if (station == XbTypes.NONE) return "#555555";
        int cc = st.country()[station];
        return cc == XbTypes.NONE ? "#555555" : colourOf(countries.value(cc));
    }

    static String kindColour(int kind, String cc) {
        return switch (kind) {
            case LEG, VARIANT -> colourOf(cc);
            case DROPPED -> "#9aa0a6";
            case UNCOUPLED -> "#d98218";
            default -> "#c0392b";
        };
    }

    static int writePages(Path dir, List<Band> bands, XbScan.Stations st, Map<Integer, String> names,
            Dict countries, int perPage) throws Exception {
        int pages = 0;
        for (int start = 0; start < bands.size(); start += perPage) {
            List<Band> page = bands.subList(start, Math.min(bands.size(), start + perPage));
            pages++;
            Files.writeString(dir.resolve(String.format("xb-groups-%03d.svg", pages)),
                    page(page, st, names, countries, pages));
        }
        return pages;
    }

    static String page(List<Band> page, XbScan.Stations st, Map<Integer, String> names,
            Dict countries, int no) {
        StringBuilder b = new StringBuilder(1 << 20);
        int y = LEGEND_H;
        StringBuilder body = new StringBuilder(1 << 20);
        for (Band band : page) {
            y = band(body, band, st, names, countries, y);
        }
        int h = y + 30;
        b.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        b.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(PAGE_W)
                .append("\" height=\"").append(h).append("\" viewBox=\"0 0 ").append(PAGE_W)
                .append(' ').append(h).append("\" font-family=\"DejaVu Sans, Helvetica, sans-serif\">\n");
        b.append("<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>\n");
        b.append(text(16, 24, 14, "#111", "bold", "Cross-border coupling — group sheet " + no));
        sheetLegend(b);
        b.append(body);
        b.append("</svg>\n");
        return b.toString();
    }

    /// What the marks mean, repeated on every sheet rather than held once in the index.
    static void sheetLegend(StringBuilder b) {
        // Row 1: what a line is.
        Key k = new Key(b, 46);
        k.swatch("#1f8f5f", 2.6, null);
        k.says("the run this publication KEPT, coloured by the country that owns it");
        k.swatch("#b7bcc2", 1.2, "2,3");
        k.says("cut away from a leg");
        k.cross();
        k.says("where the cut falls");
        k.bar();
        k.says("junction: spans every row it joins, ticked on each; ≤n = variant pairs linked");

        // Row 2: what a row is. The country swatches stay bare; what the colours also mean for the
        // station names is in the notes below.
        k = new Key(b, 66);
        k.swatch("#c0392b", 2.6, null);
        k.says("CH");
        k.swatch("#2c6fbb", 2.6, null);
        k.says("AT");
        k.swatch("#1f8f5f", 2.6, null);
        k.says("IT");
        k.swatch("#7d5bbe", 2.6, null);
        k.says("DE");
        k.swatch("#9aa0a6", 2.6, null);
        k.swatch("#9aa0a6", 1.2, "7,3");
        k.says("× dropped: deleted from the output, a kept leg carries it");
        k.swatch("#d98218", 2.6, null);
        k.swatch("#d98218", 1.2, "1,4");
        k.says("? uncoupled: left in the graph whole, joined to nothing");
        k.swatch("#c0392b", 2.6, null);
        k.swatch("#c0392b", 1.2, "4,2,1,2");
        k.says("! in a group refused whole: nothing cut, dropped or linked");

        for (int i = 0; i < NOTES.length; i++) {
            b.append(text(16, NOTES_Y + NOTE_H * i, 10, "#777", "italic", NOTES[i]));
        }
    }

    /// One row of the key, laid out left to right by a cursor, so an entry that grows pushes the
    /// ones after it along instead of overrunning them.
    private static final class Key {

        /// Between one entry and the next. Generous, because [#textWidth] is an estimate.
        private static final double GAP = 26;
        /// Between a mark and the words it explains.
        private static final double LABEL_GAP = 8;
        private static final int SWATCH_W = 38;

        private final StringBuilder b;
        private final int y;
        private double x = 16;

        Key(StringBuilder b, int y) {
            this.b = b;
            this.y = y;
        }

        /// A line in the weight and dash pattern it is being explained.
        void swatch(String colour, double w, String dash) {
            b.append(String.format("<line x1=\"%.0f\" y1=\"%d\" x2=\"%.0f\" y2=\"%d\" stroke=\"%s\" "
                    + "stroke-width=\"%.1f\"%s/>%n", x, y, x + SWATCH_W, y, colour, w,
                    dash == null ? "" : " stroke-dasharray=\"" + dash + "\""));
            x += SWATCH_W + LABEL_GAP;
        }

        /// The mark for where a cut falls.
        void cross() {
            b.append(String.format("<path d=\"M%.0f %d l7 7 M%.0f %d l-7 7\" stroke=\"#c0392b\" "
                    + "stroke-width=\"1.4\" fill=\"none\"/>%n", x, y - 3, x + 7, y - 3));
            x += 14 + LABEL_GAP;
        }

        /// The junction bar.
        void bar() {
            b.append(String.format("<line x1=\"%.0f\" y1=\"%d\" x2=\"%.0f\" y2=\"%d\" stroke=\"#111\" "
                    + "stroke-width=\"2.4\"/>%n", x + 1, y - 7, x + 1, y + 7));
            x += 3 + LABEL_GAP;
        }

        /// What the marks just placed mean, and on to the next entry.
        void says(String label) {
            b.append(text((int) Math.round(x), y + 4, 10, "#333", "normal", label));
            x += textWidth(label, 10) + GAP;
        }
    }

    /// Roughly how wide `s` is at `size` px in DejaVu Sans, the sheet's first-choice font.
    ///
    /// An estimate: there is no font metric available here, so this leans wide and [Key#GAP]
    /// absorbs the rest. Per character class rather than a flat average, because the legend mixes
    /// prose with the ≤ × ↳ marks.
    static double textWidth(String s, double size) {
        double em = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            em += switch (c) {
                case ' ' -> 0.34;
                case 'i', 'j', 'l', 't', 'I', '.', ',', ':', ';', '\'', '!', '|' -> 0.34;
                case 'f', 'r' -> 0.42;
                case 'm', 'w', 'M', 'W', '—', '≤', '×', '↳' -> 1.00;
                default -> Character.isUpperCase(c) || Character.isDigit(c) ? 0.72 : 0.64;
            };
        }
        return em * size;
    }

    /// One group's band. Returns the y the next band starts at.
    static int band(StringBuilder b, Band band, XbScan.Stations st, Map<Integer, String> names,
            Dict countries, int y) {
        int[] cols = band.columns();
        int k = Math.max(1, cols.length);
        double plotW = PAGE_W - LABEL_W - 40.0;
        double dx = plotW / k;

        int legs = count(band, LEG);
        boolean refused = band.refusal() != null;
        String head = refused
                ? String.format("REFUSED (%d publications, nothing cut, dropped or linked) — %s",
                        count(band, REFUSED), band.refusal())
                : String.format("tiled: %d legs, %d junctions, %d dropped, %d uncoupled",
                        legs, band.junctions().length, count(band, DROPPED), count(band, UNCOUPLED));
        String route = "";
        if (cols.length > 0) {
            String a = label(cols[0], names, st, 26);
            String z = label(cols[cols.length - 1], names, st, 26);
            // The group's whole span, not one member's: the point of a group is that no single
            // publication runs the length of it.
            route = cols.length == 1 ? ": " + a : ": " + a + " – " + z;
        }
        b.append(text(16, y + 14, 12, "#111", "bold", "Train " + band.num() + route));
        b.append(text(LABEL_W, y + 14, 11, refused ? "#c0392b" : "#444", "normal", head));
        y += HEAD_H;

        // Map station -> column, once per band.
        Int2IntOpenHashMap col = new Int2IntOpenHashMap();
        col.defaultReturnValue(-1);
        for (int i = 0; i < cols.length; i++) col.put(cols[i], i);

        // One very light rule per column, before anything else in the band so every marker, bar and
        // tick paints over it. It reaches from the first row down to where the rotated station
        // label is anchored, so a dot and its name are on one line, and it is fainter than
        // `cut away` (#b7bcc2) so the grid never reads as data.
        int gridTop = y;
        int gridBottom = y + band.rows().size() * ROW_H;
        for (int i = 0; i < cols.length; i++) {
            double x = LABEL_W + (i + 0.5) * dx;
            b.append(String.format(
                    "<line x1=\"%.1f\" y1=\"%d\" x2=\"%.1f\" y2=\"%d\" stroke=\"#e7e9ec\" "
                    + "stroke-width=\"1\"/>%n", x, gridTop, x, gridBottom + 8));
        }

        // leg index -> the y of every row that leg stands for: its own, then its calendar variants.
        Map<Integer, List<Integer>> legRows = new HashMap<>();
        for (int ri = 0; ri < band.rows().size(); ri++) {
            Row r = band.rows().get(ri);
            int cy = y + ROW_H / 2;
            if (r.kind() == LEG || r.kind() == VARIANT) {
                legRows.computeIfAbsent(r.legIndex(), leg -> new ArrayList<>()).add(cy);
            }
            row(b, r, countries, col, dx, cy, st, names);
            y += ROW_H;
        }

        // Junction bars at the handover column.
        //
        // The bar spans every row on both sides, not just the two legs, because a link is emitted
        // per pair of calendar variants -- which is what junctionCap counts. The tick on each row
        // says "this publication is one of the ones joined here".
        for (int i = 0; i < band.junctions().length; i++) {
            List<Integer> feeder = legRows.get(i);
            List<Integer> distributor = legRows.get(i + 1);
            int c = col.get(band.junctions()[i]);
            if (feeder == null || distributor == null || c < 0) continue;
            double x = LABEL_W + (c + 0.5) * dx;
            int top = Integer.MAX_VALUE;
            int bottom = Integer.MIN_VALUE;
            for (int cy : feeder) top = Math.min(top, cy);
            for (int cy : distributor) bottom = Math.max(bottom, cy);
            b.append(String.format(
                    "<line x1=\"%.1f\" y1=\"%d\" x2=\"%.1f\" y2=\"%d\" stroke=\"#111\" "
                    + "stroke-width=\"2.4\"/>%n", x, top, x, bottom));
            for (List<Integer> side : List.of(feeder, distributor)) {
                for (int cy : side) {
                    b.append(String.format(
                            "<line x1=\"%.1f\" y1=\"%d\" x2=\"%.1f\" y2=\"%d\" "
                            + "stroke=\"#111\" stroke-width=\"2.0\"/>%n", x - 4.5, cy, x + 4.5, cy));
                }
            }
            b.append(text(x + 6, (top + bottom) / 2.0 - 1, 8, "#111", "normal",
                    "≤" + band.junctionCap()[i]));
        }

        // The station strip. Rotated so a 40-column group stays legible; every second one above 60.
        int step = cols.length > 60 ? 2 : 1;
        for (int i = 0; i < cols.length; i += step) {
            double x = LABEL_W + (i + 0.5) * dx;
            String name = label(cols[i], names, st, TICK_CHARS);
            // text-anchor=end so the rotation carries the label down-left from its tick rather than
            // up-left across the rows.
            b.append(String.format(
                    "<text x=\"%.1f\" y=\"%d\" font-size=\"8\" fill=\"%s\" "
                    + "text-anchor=\"end\" transform=\"rotate(-60 %.1f %d)\">%s</text>%n",
                    x, y + 8, stationColour(cols[i], st, countries), x, y + 8, esc(name)));
        }
        return y + TICK_H + BAND_GAP;
    }

    /// A station's display name, truncated, falling back to its id when the feed gave no name.
    static String label(int station, Map<Integer, String> names, XbScan.Stations st, int max) {
        String name = names.get(station);
        if (name == null) name = st.ids().value(station);
        return name.length() > max ? name.substring(0, max - 1) + "…" : name;
    }

    static int count(Band band, int kind) {
        int n = 0;
        for (Row r : band.rows()) {
            if (r.kind() == kind) n++;
        }
        return n;
    }

    /// Why this publication was left uncoupled, in the space of a right-aligned label. The
    /// redundancy case names the station it gives up, since that is what identifies the pair of
    /// stops behind the refusal.
    static String reason(Row r, XbScan.Stations st, Map<Integer, String> names) {
        if (r.refusal() < 0) return "uncoupled — not joined";
        if (r.blockedAt() != XbTypes.NONE) {
            return "gives up " + label(r.blockedAt(), names, st, 20);
        }
        // From the ladder itself, not a switch over its indices, so a rung appended to
        // XbGroups.PARALLEL_REFUSAL_SHORT cannot silently mislabel a refusal here.
        String[] labels = XbGroups.PARALLEL_REFUSAL_SHORT;
        return r.refusal() < labels.length ? labels[r.refusal()] : labels[labels.length - 1];
    }

    static void row(StringBuilder b, Row r, Dict countries, Int2IntOpenHashMap col, double dx,
            int cy, XbScan.Stations st, Map<Integer, String> names) {
        Node node = r.node();
        String cc = r.country() == XbTypes.NONE ? null : countries.value(r.country());
        String colour = kindColour(r.kind(), cc);

        // The country and the id are two elements at fixed x, not one string: leading spaces in an
        // SVG <text> are collapsed away, so padding one string would not hold a column, and the
        // first slot has to be blank without the ids sliding left to fill it.
        //
        // A calendar variant leaves that slot empty, which reads as "ditto" against the leg above.
        // The other three states each get a mark instead, named in the legend.
        String slot = switch (r.kind()) {
            case LEG -> cc;
            case VARIANT -> null;
            case DROPPED -> "×";
            case UNCOUPLED -> "?";
            default -> "!";
        };
        if (slot != null) {
            b.append(text(CC_X, cy + 3, 9, colour, "normal", slot));
        }
        // A stitched vehicle is several journeys and the row shows one id — the first part's, which
        // is what the node carries.
        String label = (r.kind() == VARIANT ? "↳ " : "") + node.id;
        if (label.length() > 52) label = label.substring(0, 51) + "…";
        // After the truncation, not before: an ÖBB journey id is longer than the label fits and the
        // split trains are ÖBB's, so a marker appended first is the part that gets cut.
        if (node.stitched()) label += " +" + (node.parts.length - 1) + " stitched";
        b.append(text(ID_X, cy + 3, 9, colour, "normal", label));

        // The calendar, size plus first day. Rows of a group can differ only by calendar: train
        // 25133 has one dropped publication and three uncoupled ones with byte-identical stop
        // lists, and the days they run are the only thing separating them.
        b.append(String.format("<text x=\"%d\" y=\"%d\" font-size=\"8\" fill=\"%s\" "
                + "text-anchor=\"end\">%s</text>%n",
                LABEL_W - 142, cy + 3, colour, esc(calendar(node))));

        // What became of it, spelled out, right-aligned into the gap the shortened id leaves.
        String state = r.note() != null ? r.note() : switch (r.kind()) {
            case DROPPED -> "dropped — a leg covers it";
            case UNCOUPLED -> reason(r, st, names);
            case REFUSED -> "in a refused group";
            default -> null;
        };
        if (state != null) {
            b.append(String.format("<text x=\"%d\" y=\"%d\" font-size=\"8\" fill=\"%s\" "
                    + "font-style=\"italic\" text-anchor=\"end\">%s</text>%n",
                    LABEL_W - 10, cy + 3, colour, esc(state)));
        }

        // A row that keeps nothing is drawn in its own colour rather than the neutral cut-away
        // grey. The grey means "this part was cut off", so it stays for the tail of a leg, on a row
        // that does keep something.
        boolean owns = r.from() >= 0;
        String idle = owns ? "#b7bcc2" : colour;
        String dash = switch (owns ? LEG : r.kind()) {
            case DROPPED -> " stroke-dasharray=\"7,3\"";       // long dashes: struck out
            case UNCOUPLED -> " stroke-dasharray=\"1,4\"";      // fine dots: untouched, unjoined
            case REFUSED -> " stroke-dasharray=\"4,2,1,2\"";    // dash-dot: nothing was decided
            default -> " stroke-dasharray=\"2,3\"";
        };

        // The composite positions where one part hands over to the next — the last stop of a part.
        // Empty for an ordinary journey.
        java.util.BitSet stitchAt = new java.util.BitSet();
        if (node.stitched()) {
            for (int i = 1; i < node.canon.length; i++) {
                if (node.partAt[i] != node.partAt[i - 1]) stitchAt.set(i - 1);
            }
        }

        // Walk the node's own sequence, mapping each stop to a column, and join consecutive stops.
        int prevCol = -1;
        boolean prevKept = false;
        for (int i = 0; i < node.canon.length; i++) {
            int s = node.canon[i];
            if (s == XbTypes.NONE) continue;
            int c = col.get(s);
            if (c < 0) continue;
            boolean kept = r.from() >= 0 && i >= r.from() && i <= r.to();
            double x = (c + 0.5) * dx;
            if (prevCol >= 0) {
                double px = (prevCol + 0.5) * dx;
                boolean both = kept && prevKept;
                b.append(String.format(
                        "<line x1=\"%.1f\" y1=\"%d\" x2=\"%.1f\" y2=\"%d\" stroke=\"%s\" "
                        + "stroke-width=\"%s\"%s/>%n",
                        LABEL_W + px, cy, LABEL_W + x, cy, both ? colour : idle,
                        both ? "2.6" : "1.2", both ? "" : dash));
                // The cut itself: the boundary between a kept stop and one given up.
                if (kept != prevKept) {
                    double mx = LABEL_W + (px + x) / 2.0;
                    b.append(String.format(
                            "<path d=\"M%.1f %d l7 7 M%.1f %d l-7 7\" stroke=\"#c0392b\" "
                            + "stroke-width=\"1.4\" fill=\"none\"/>%n",
                            mx - 3.5, cy - 3, mx + 3.5, cy - 3));
                }
            }
            b.append(String.format("<circle cx=\"%.1f\" cy=\"%d\" r=\"%s\" fill=\"%s\"/>%n",
                    LABEL_W + x, cy, kept ? "3.0" : "1.8", kept ? colour : idle));
            // The diamond marks the handover station, which a stitched row otherwise draws as one
            // continuous band under one journey id; its tooltip names the two journeys.
            if (stitchAt.get(i)) {
                // Hollow, so the circle underneath still shows through -- that circle is what says
                // whether the station is kept or cut away.
                b.append(String.format("<g><title>%s</title>"
                        + "<path d=\"M%.1f %d l5 5 l-5 5 l-5 -5 Z\" fill=\"none\" "
                        + "stroke=\"#7d3c98\" stroke-width=\"1.6\"/></g>%n",
                        esc(stitchTitle(node, i)), LABEL_W + x, cy - 5));
            }
            prevCol = c;
            prevKept = kept;
        }
    }

    /// The two journeys a stitch at composite position `i` joined, for the marker's tooltip.
    ///
    /// Position `i` is the last stop of the earlier part — the handover station itself, which
    /// [XbStitch#compose] carries once rather than twice — so the journey continuing from it is the
    /// part at `i + 1`.
    static String stitchTitle(Node node, int i) {
        Node ends = node.parts[node.partAt[i]];
        Node begins = node.parts[node.partAt[i + 1]];
        return "stitched here: " + ends.id + "  ->  " + begins.id;
    }

    // ------------------------------------------------------------------------------------- //
    // The map
    // ------------------------------------------------------------------------------------- //

    /// Every drawn run, on the ground. Three rules keep it legible:
    ///
    ///   * Segments are deduplicated by unordered station pair, with width scaled by how many runs
    ///     use them.
    ///   * The frame is the handover stations, not every stop of every journey: coupling is
    ///     confined to a thin Alpine band, while an uncoupled Trenitalia publication runs the
    ///     length of Italy. Runs leave the frame, and how many segments fall outside it entirely is
    ///     logged rather than quietly dropped.
    ///   * Only the handover stations are named, plus whatever busy stations there is room for
    ///     after them, laid out against an occupancy grid; the ones that collide are not drawn.
    static void writeMap(Path file, List<Band> bands, XbScan.Stations st,
            Map<Integer, String> names, Dict countries, Totals totals) throws Exception {
        Long2IntOpenHashMap kept = new Long2IntOpenHashMap();
        Long2IntOpenHashMap loose = new Long2IntOpenHashMap();
        Long2IntOpenHashMap bad = new Long2IntOpenHashMap();
        Int2IntOpenHashMap anchors = new Int2IntOpenHashMap();
        Int2IntOpenHashMap traffic = new Int2IntOpenHashMap();
        Map<Long, String> keptColour = new HashMap<>();

        for (Band band : bands) {
            for (Row r : band.rows()) {
                Long2IntOpenHashMap into = band.refusal() != null ? bad
                        : r.from() >= 0 ? kept : loose;
                int lo = r.from() >= 0 ? r.from() : 0;
                int hi = r.from() >= 0 ? r.to() : r.node().canon.length - 1;
                String colour = r.country() == XbTypes.NONE ? null
                        : colourOf(countries.value(r.country()));
                int prev = XbTypes.NONE;
                for (int i = lo; i <= hi; i++) {
                    int s = r.node().canon[i];
                    if (s == XbTypes.NONE || !st.located(s)) continue;
                    traffic.addTo(s, 1);
                    if (prev != XbTypes.NONE && prev != s) {
                        long key = ((long) Math.min(prev, s) << 32) | Math.max(prev, s);
                        into.addTo(key, 1);
                        if (colour != null) keptColour.putIfAbsent(key, colour);
                    }
                    prev = s;
                }
            }
            for (int j : band.junctions()) {
                if (st.located(j)) anchors.addTo(j, 1);
            }
        }

        // ---- the frame: the handover stations, trimmed of outliers, plus a margin -------------
        Box box = frame(anchors, kept, st);
        if (box == null) {
            Files.writeString(file, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"400\" height=\"60\">"
                    + "<text x=\"10\" y=\"30\">nothing located to draw</text></svg>\n");
            return;
        }

        int width = 2400;
        int margin = 46;
        int legendH = 108;
        double coslat = Math.cos(Math.toRadians((box.minLat + box.maxLat) / 2));
        double scale = (width - 2 * margin) / ((box.maxLon - box.minLon) * coslat);
        int plotH = (int) ((box.maxLat - box.minLat) * scale);
        int height = plotH + 2 * margin + legendH;
        Proj proj = new Proj(box.minLon, box.maxLat, coslat, scale, margin, width, plotH);

        StringBuilder b = new StringBuilder(1 << 20);
        b.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        b.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(width)
                .append("\" height=\"").append(height).append("\" viewBox=\"0 0 ").append(width)
                .append(' ').append(height)
                .append("\" font-family=\"DejaVu Sans, Helvetica, sans-serif\">\n");
        b.append("<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>\n");
        b.append(String.format("<clipPath id=\"frame\"><rect x=\"%d\" y=\"%d\" width=\"%d\" "
                + "height=\"%d\"/></clipPath>%n", margin, margin, width - 2 * margin, plotH));
        b.append(String.format("<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"none\" "
                + "stroke=\"#dcdfe3\"/>%n", margin, margin, width - 2 * margin, plotH));

        graticule(b, box, proj, width, margin, plotH);

        b.append("<g clip-path=\"url(#frame)\">\n");
        // Bottom to top: the refusals first, so a coupled corridor is never hidden by one.
        int outside = 0;
        outside += layer(b, bad, null, "#c0392b", 0.6, 0.30, st, proj);
        outside += layer(b, loose, null, "#d98218", 0.6, 0.34, st, proj);
        outside += layer(b, kept, keptColour, null, 1.5, 0.85, st, proj);
        b.append("</g>\n");

        // ---- handover stations, then their names ----------------------------------------------
        List<Integer> byRank = new ArrayList<>();
        for (Int2IntOpenHashMap.Entry e : anchors.int2IntEntrySet()) byRank.add(e.getIntKey());
        byRank.sort((x, y) -> {
            int c = Integer.compare(anchors.get((int) y), anchors.get((int) x));
            return c != 0 ? c : st.ids().value(x).compareTo(st.ids().value(y));
        });
        for (int s : byRank) {
            double x = proj.x(st.lon()[s]);
            double y = proj.y(st.lat()[s]);
            if (!proj.inside(x, y)) continue;
            double r = 2.2 + 1.7 * Math.log(1 + anchors.get(s));
            b.append(String.format("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"#111\" "
                    + "fill-opacity=\"0.8\"/>%n", x, y, r));
        }
        labels(b, byRank, anchors, traffic, st, names, countries, proj);

        legend(b, totals, scale, width, margin, plotH, outside, anchors.size());
        b.append("</svg>\n");
        Files.writeString(file, b.toString());
        Log.info("[atlas] map: %d handover stations frame lat %.2f..%.2f lon %.2f..%.2f; "
                + "%d segments lie wholly outside it and are not drawn", anchors.size(),
                box.minLat, box.maxLat, box.minLon, box.maxLon, outside);
    }

    record Box(double minLat, double maxLat, double minLon, double maxLon) {}

    /// Where to point the camera.
    ///
    /// The handover stations are the subject, so they set the frame, but a single outlying anchor
    /// would stretch it back out. The box is the 2nd to 98th percentile of the anchors in each
    /// axis, padded by 12 % and then by a floor of ~25 km so a corridor that happens to be straight
    /// still has depth. Falls back to the kept legs when there are too few anchors to take a
    /// percentile of.
    static Box frame(Int2IntOpenHashMap anchors, Long2IntOpenHashMap kept, XbScan.Stations st) {
        List<Double> lats = new ArrayList<>();
        List<Double> lons = new ArrayList<>();
        for (Int2IntOpenHashMap.Entry e : anchors.int2IntEntrySet()) {
            int s = e.getIntKey();
            lats.add(st.lat()[s]);
            lons.add(st.lon()[s]);
        }
        if (lats.size() < 8) {
            lats.clear();
            lons.clear();
            for (Long2IntOpenHashMap.Entry e : kept.long2IntEntrySet()) {
                for (int s : new int[] {(int) (e.getLongKey() >>> 32), (int) e.getLongKey()}) {
                    if (!st.located(s)) continue;
                    lats.add(st.lat()[s]);
                    lons.add(st.lon()[s]);
                }
            }
        }
        if (lats.isEmpty()) return null;
        lats.sort(null);
        lons.sort(null);
        double minLat = pct(lats, 0.02);
        double maxLat = pct(lats, 0.98);
        double minLon = pct(lons, 0.02);
        double maxLon = pct(lons, 0.98);
        double padLat = Math.max(0.12 * (maxLat - minLat), 0.22);
        double padLon = Math.max(0.12 * (maxLon - minLon), 0.32);
        return new Box(minLat - padLat, maxLat + padLat, minLon - padLon, maxLon + padLon);
    }

    private static double pct(List<Double> sorted, double p) {
        int i = (int) Math.round(p * (sorted.size() - 1));
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, i)));
    }

    /// lon/lat -> px, with the frame's origin folded in.
    record Proj(double minLon, double maxLat, double coslat, double scale, int margin, int width,
            int plotH) {
        double x(double lon) {
            return margin + (lon - minLon) * coslat * scale;
        }

        double y(double lat) {
            return margin + (maxLat - lat) * scale;
        }

        double right() {
            return width - margin;
        }

        double bottom() {
            return margin + plotH;
        }

        boolean inside(double x, double y) {
            return x >= margin && x <= right() && y >= margin && y <= bottom();
        }

        /// True when BOTH ends sit beyond the same edge, which is the only case where a straight
        /// segment cannot cross the frame at all. Anything else is drawn and left to the clip path.
        boolean offFrame(double x1, double y1, double x2, double y2) {
            return (x1 < margin && x2 < margin) || (x1 > right() && x2 > right())
                    || (y1 < margin && y2 < margin) || (y1 > bottom() && y2 > bottom());
        }
    }

    /// A 1° grid, so a reader can place the picture without recognising a single station name.
    static void graticule(StringBuilder b, Box box, Proj proj, int width, int margin, int plotH) {
        for (int lat = (int) Math.ceil(box.minLat); lat <= box.maxLat; lat++) {
            double y = proj.y(lat);
            b.append(String.format("<line x1=\"%d\" y1=\"%.1f\" x2=\"%d\" y2=\"%.1f\" "
                    + "stroke=\"#eef0f2\"/>%n", margin, y, width - margin, y));
            b.append(text(margin + 3, y - 3, 9, "#aab0b6", "normal", lat + "°N"));
        }
        for (int lon = (int) Math.ceil(box.minLon); lon <= box.maxLon; lon++) {
            double x = proj.x(lon);
            b.append(String.format("<line x1=\"%.1f\" y1=\"%d\" x2=\"%.1f\" y2=\"%d\" "
                    + "stroke=\"#eef0f2\"/>%n", x, margin, x, margin + plotH));
            b.append(text(x + 3, margin + plotH - 5, 9, "#aab0b6", "normal", lon + "°E"));
        }
    }

    /// Names, in priority order, skipping any that would land on one already placed.
    static void labels(StringBuilder b, List<Integer> anchorRank, Int2IntOpenHashMap anchors,
            Int2IntOpenHashMap traffic, XbScan.Stations st, Map<Integer, String> names,
            Dict countries, Proj proj) {
        // Handover stations first -- they are the subject -- then the busiest of the rest.
        List<Integer> order = new ArrayList<>(anchorRank);
        List<Integer> rest = new ArrayList<>();
        for (Int2IntOpenHashMap.Entry e : traffic.int2IntEntrySet()) {
            if (!anchors.containsKey(e.getIntKey())) rest.add(e.getIntKey());
        }
        rest.sort((x, y) -> {
            int c = Integer.compare(traffic.get((int) y), traffic.get((int) x));
            return c != 0 ? c : st.ids().value(x).compareTo(st.ids().value(y));
        });
        order.addAll(rest);

        Set<Long> taken = new HashSet<>();
        int placed = 0;
        for (int s : order) {
            if (placed >= 90) break;
            double x = proj.x(st.lon()[s]);
            double y = proj.y(st.lat()[s]);
            if (!proj.inside(x, y)) continue;
            long cell = ((long) (int) (x / 108) << 32) | (int) (y / 15) & 0xffffffffL;
            if (!taken.add(cell)) continue;
            boolean anchor = anchors.containsKey(s);
            String name = names.get(s);
            if (name == null) continue;
            if (name.length() > 24) name = name.substring(0, 23) + "…";
            b.append(String.format("<text x=\"%.1f\" y=\"%.1f\" font-size=\"%d\" fill=\"%s\" "
                    + "fill-opacity=\"%s\" stroke=\"#ffffff\" stroke-width=\"3\" "
                    + "paint-order=\"stroke\"%s>%s</text>%n",
                    x + 5, y - 4, anchor ? 11 : 9, stationColour(s, st, countries),
                    anchor ? "1" : "0.75", anchor ? " font-weight=\"bold\"" : "", esc(name)));
            placed++;
        }
    }

    static void legend(StringBuilder b, Totals totals, double scale, int width, int margin,
            int plotH, int outside, int anchors) {
        int ly = margin + plotH + 24;
        b.append(text(margin, ly, 15, "#111", "bold", "Cross-border coupling — where"));
        b.append(text(margin, ly + 20, 11, "#444", "normal", String.format(
                "%,d groups · %,d legs · %,d variants · %,d dropped · %,d uncoupled · %,d junctions "
                + "over %d stations · %,d groups refused whole, holding %,d publications",
                totals.groups, totals.legs, totals.variants, totals.dropped, totals.uncoupled,
                totals.junctions, anchors, totals.refused, totals.inRefused)));
        b.append(text(margin, ly + 38, 11, "#444", "normal",
                "thick, by country = the run a leg kept   ·   amber = a publication left uncoupled"
                + "   ·   red = a publication in a group refused whole   ·   ● = handover station, "
                + "named in bold"));
        b.append(text(margin, ly + 56, 10, "#777", "italic", String.format(
                "Framed on the handover stations, so long runs leave the picture: %,d segments lie "
                + "wholly outside and are not drawn. Segments are deduplicated by station pair and "
                + "widen with use; a dot marks where a link is anchored, not how many hang there.",
                outside)));

        double km = 50;
        double px = km / 111.0 * scale;
        double x0 = width - margin - px;
        double y0 = ly + 50;
        b.append(String.format("<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%d\" y2=\"%.1f\" stroke=\"#111\" "
                + "stroke-width=\"2\"/>%n", x0, y0, width - margin, y0));
        b.append(text(x0, y0 - 6, 10, "#111", "normal", "50 km"));
    }

    /// @return how many segments fell wholly outside the frame and were not drawn.
    private static int layer(StringBuilder b, Long2IntOpenHashMap seg, Map<Long, String> colours,
            String flat, double base, double opacity, XbScan.Stations st, Proj proj) {
        int outside = 0;
        for (Long2IntOpenHashMap.Entry e : seg.long2IntEntrySet()) {
            long key = e.getLongKey();
            int a = (int) (key >>> 32);
            int c = (int) key;
            double x1 = proj.x(st.lon()[a]);
            double y1 = proj.y(st.lat()[a]);
            double x2 = proj.x(st.lon()[c]);
            double y2 = proj.y(st.lat()[c]);
            // The clip path hides what leaves the frame; skipping it here also gives the legend a
            // number to print.
            if (proj.offFrame(x1, y1, x2, y2)) {
                outside++;
                continue;
            }
            double wid = base + 0.9 * Math.log(1 + e.getIntValue());
            String col = flat != null ? flat : colours.getOrDefault(key, "#666666");
            b.append(String.format(
                    "<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" "
                    + "stroke-width=\"%.1f\" stroke-opacity=\"%.2f\" stroke-linecap=\"round\"/>%n",
                    x1, y1, x2, y2, col, wid, opacity));
        }
        return outside;
    }

    // ------------------------------------------------------------------------------------- //

    static void writeIndex(Path file, List<Band> bands, Totals t, int perPage, int pages)
            throws Exception {
        StringBuilder b = new StringBuilder();
        b.append("# Cross-border coupling atlas\n\n");
        b.append(String.format("%,d groups · %,d legs · %,d variants · %,d dropped · %,d uncoupled "
                + "· %,d junctions%n%n", t.groups, t.legs, t.variants, t.dropped, t.uncoupled,
                t.junctions));
        b.append(String.format("%,d groups were refused whole, holding %,d publications between "
                + "them: nothing about those was cut, dropped or linked, so they are NOT counted "
                + "among the uncoupled.%n%n", t.refused, t.inRefused));
        b.append("`xb-map.svg` is the overview. The sheets below hold one band per group: solid is "
                + "the run a publication kept, dotted what was cut away, ✕ the cut, ‖ a junction.\n\n");
        b.append("A junction bar is labelled `≤n`, the number of variant PAIRS that could be "
                + "linked there. The number actually emitted is decided by the meeting test in "
                + "`XbLinks.junction`, which this tool does not run.\n\n");
        b.append("| train | outcome | legs | dropped | uncoupled | in refused group | sheet |\n");
        b.append("|---|---|---:|---:|---:|---:|---|\n");
        for (int i = 0; i < bands.size(); i++) {
            Band band = bands.get(i);
            b.append(String.format("| %s | %s | %d | %d | %d | %d | xb-groups-%03d.svg |%n",
                    band.num(), band.refusal() == null ? "tiled" : "REFUSED: " + band.refusal(),
                    count(band, LEG), count(band, DROPPED), count(band, UNCOUPLED),
                    count(band, REFUSED), i / perPage + 1));
        }
        b.append(String.format("%n%d sheet(s), %d groups each.%n", pages, perPage));
        Files.writeString(file, b.toString());
    }

    static String text(double x, double y, int size, String fill, String style, String s) {
        return String.format("<text x=\"%.1f\" y=\"%.1f\" font-size=\"%d\" fill=\"%s\"%s>%s</text>%n",
                x, y, size, fill,
                "italic".equals(style) ? " font-style=\"italic\""
                        : "bold".equals(style) ? " font-weight=\"bold\"" : "",
                esc(s));
    }

    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}
