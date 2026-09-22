package transformers.xb;

// Deciding which publications are the same physical train, and which part of it each one owns.
//
// The pairwise tests are read here as EDGES. The connected component they induce is one physical
// train, and the ownership decision is taken once over the whole component: every station of the
// union goes to exactly one publication, each publication is cut to the contiguous run it owns, and
// the result is checked to tile — one shared station at each junction, nothing claimed twice,
// nothing claimed by nobody. A group that does not tile is refused whole and nothing about it is
// written.
//
// The wholly-foreign publication is matched geographically. That is expressed here once, at the
// station level, as an alias between two stations the consolidation left separate, so there is one
// code path and the anchor is a station both legs serve by construction.

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import toolkit.util.Log;
import transformers.xb.XbCalendar.DaySet;
import transformers.xb.XbScan.Stations;
import transformers.xb.XbTypes.Dict;
import transformers.xb.XbTypes.Edge;
import transformers.xb.XbTypes.Group;
import transformers.xb.XbTypes.Leg;
import transformers.xb.XbTypes.Node;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class XbGroups {

    private XbGroups() {}

    // ------------------------------------------------------------------------------------- //
    // Station aliases, expressed once.
    // ------------------------------------------------------------------------------------- //

    /// Stations that are the same physical stop under two publishers' codings but that the
    /// consolidation left separate, unioned so that everything downstream can compare canons.
    ///
    /// The consolidation is rail-only and geographic, and it leaves a residue of 2,084
    /// cross-country StopPlace pairs within 50 m, Innsbruck Hbf among them at 28 m apart. A
    /// publication whose stops are all in that residue shares no canon with the publication it
    /// duplicates.
    ///
    /// Three gates:
    ///
    ///   * only stations that appear in a RETAINED journey's stop sequence are considered — a few
    ///     thousand, not the corpus's 289,187;
    ///   * only across feed namespaces;
    ///   * only MUTUAL NEAREST, the injective pairing the bus-quay merge already gates on.
    ///
    /// Nothing is written: this is a matching identity, not a merge. Read the census below before
    /// trusting it — a rule that pairs more than a handful of stations here is wrong.
    /// @param rep   station → the station it is matched as, itself when unmatched.
    /// @param blind stations the namespace gate below REFUSED to consider, but that have a neighbour
    ///              within the alias radius in a different full id space. These are pairs the alias
    ///              exists to find and structurally cannot see, because [XbProfile#feedNamespace]
    ///              stops at the first id token. Nothing is done with this set — it is carried to the
    ///              refusal census so the size of the blind spot can be read against the publications
    ///              that failed to place.
    /// @param narrow the subset of `blind` whose hidden neighbour lies in a SMALL id space. A
    ///                publisher's own regional space holds thousands of stops — `IT:ITC4:` 29,827,
    ///                `ch:2:` 35,405 — while a space holding a few dozen is somebody else's stations
    ///                copied into their own coding: `ch:23016:` (18 stops), `ch:23026:` (3),
    ///                `it:22021:` (30). The part of the blind spot a finer gate could fix.
    public record Alias(int[] rep, java.util.BitSet blind, java.util.BitSet narrow) {}

    /// Above this many stops, an id space is a region its publisher operates rather than a copy of
    /// someone else's. The corpus leaves a wide gap either side: the largest copy space has 30 stops,
    /// the smallest regional one 222.
    static final int COPY_SPACE_MAX_STOPS = 100;

    public static Alias aliasStations(Int2ObjectMap<List<Node>> byNum, Stations stations,
            java.util.BitSet railPool) {
        return aliasStations(byNum, stations, XbProfile.RAIL_PAIR_RADIUS_DEG,
                XbProfile.BUS_PAIR_RADIUS_DEG, railPool);
    }

    /// @param railPool the rail-served stations. Everything else in the pool is treated as road.
    ///
    /// Rail and road are paired separately, each at its own radius, and the two pools must stay
    /// separate: mutual-nearest is winner-takes-all — a station is aliased to exactly one other — so
    /// in one pool a station is identified with the bus stop in its own forecourt and loses the slot
    /// its real counterpart needed. Road pairs are duplicate publications of one stop, measured 0, 1,
    /// 1 and 2 m apart and often the same embedded HAFAS id under two prefixes, and want a tight
    /// radius; rail counterparts are two operators' station buildings and want a loose one.
    public static Alias aliasStations(Int2ObjectMap<List<Node>> byNum, Stations stations,
            double railRadiusDeg, double roadRadiusDeg, java.util.BitSet railPool) {
        int n = stations.ids().size();
        int[] rep = new int[n];
        for (int i = 0; i < n; i++) rep[i] = i;

        java.util.BitSet used = new java.util.BitSet(n);
        for (List<Node> nodes : byNum.values()) {
            for (Node node : nodes) {
                for (int s : node.stations) {
                    if (stations.located(s)) used.set(s);
                }
            }
        }

        // Both namespaces are interned once, so the inner loop compares ints. Id parsing must not
        // move into that loop: the pair count is quadratic in a dense bucket, the station count is
        // not.
        Dict spaces = new Dict();
        Dict namespaces = new Dict();
        int[] spaceOf = new int[n];
        int[] coarseOf = new int[n];
        IntArrayList spaceSize = new IntArrayList();
        for (int s = 0; s < n; s++) {
            String id = stations.ids().value(s);
            spaceOf[s] = spaces.intern(XbProfile.idSpace(id));
            coarseOf[s] = namespaces.intern(XbProfile.feedNamespace(id));
            while (spaceSize.size() <= spaceOf[s]) spaceSize.add(0);
            spaceSize.set(spaceOf[s], spaceSize.getInt(spaceOf[s]) + 1);
        }
        boolean[] copySpace = new boolean[spaceSize.size()];
        for (int i = 0; i < copySpace.length; i++) {
            copySpace[i] = spaceSize.getInt(i) <= COPY_SPACE_MAX_STOPS;
        }

        java.util.BitSet blind = new java.util.BitSet(n);
        java.util.BitSet narrow = new java.util.BitSet(n);
        IntArrayList rail = new IntArrayList();
        IntArrayList road = new IntArrayList();
        for (int s = used.nextSetBit(0); s >= 0; s = used.nextSetBit(s + 1)) {
            (railPool != null && railPool.get(s) ? rail : road).add(s);
        }

        int railPaired = pair(rail, railRadiusDeg, stations, coarseOf, spaceOf, copySpace, rep,
                blind, narrow);
        int roadPaired = pair(road, roadRadiusDeg, stations, coarseOf, spaceOf, copySpace, rep,
                blind, narrow);

        for (int i = 0; i < n; i++) {
            while (rep[i] != rep[rep[i]]) rep[i] = rep[rep[i]];
        }
        Log.info("[xb] station aliases: %d rail stations -> %d pairs within %.0f m; %d road stops -> "
                + "%d pairs within %.0f m; the two are matched separately and cannot take each "
                + "other's slots — a matching identity only, nothing merged",
                rail.size(), railPaired, railRadiusDeg * 111_320,
                road.size(), roadPaired, roadRadiusDeg * 111_320);
        Log.info("[xb] station aliases: %d further stations have a neighbour in range under a "
                + "different id space that the first-token namespace gate hides, %d of them next to "
                + "a space too small (<= %d stops) to be anything but a copy",
                blind.cardinality(), narrow.cardinality(), COPY_SPACE_MAX_STOPS);
        return new Alias(rep, blind, narrow);
    }

    /// Mutual-nearest cross-namespace pairing within ONE class of stations. Writes into `rep`.
    ///
    /// @return how many pairs were formed.
    private static int pair(IntArrayList pool, double radiusDeg, Stations stations, int[] coarseOf,
            int[] spaceOf, boolean[] copySpace, int[] rep, java.util.BitSet blind,
            java.util.BitSet narrow) {
        // The bucket grid must be at least as coarse as the radius, or the 3x3 neighbourhood scanned
        // below stops covering it and a wider radius finds fewer pairs than a narrow one.
        double cell = Math.max(XbProfile.BUCKET_DEG, radiusDeg);
        Map<Long, List<Integer>> buckets = new HashMap<>();
        for (int s : pool) {
            buckets.computeIfAbsent(bucket(stations.lat()[s], stations.lon()[s], cell),
                    k -> new ArrayList<>()).add(s);
        }
        Map<Integer, Integer> nearest = new HashMap<>();
        Map<Integer, Double> nearestD2 = new HashMap<>();
        double r2 = radiusDeg * radiusDeg;
        for (int s : pool) {
            int ns = coarseOf[s];
            int space = spaceOf[s];
            double lat = stations.lat()[s];
            double lon = stations.lon()[s];
            double coslat = Math.cos(Math.toRadians(lat));
            // The scan is not 1 cell in both axes. The radius is metric, so a longitude of
            // `radiusDeg / cos(lat)` lies within it -- 1.45x the radius at 46 N -- while a cell is
            // `radiusDeg` wide in raw degrees. A fixed 3x3 neighbourhood misses candidates between
            // 1.0 and 1.45 cells east or west, and which ones it misses depends on where the grid
            // boundaries happen to fall, which makes the pairing a function of the cell size.
            int spanLat = (int) Math.ceil(radiusDeg / cell);
            int spanLon = (int) Math.ceil(radiusDeg / Math.max(0.05, coslat) / cell);
            for (int di = -spanLat; di <= spanLat; di++) {
                for (int dj = -spanLon; dj <= spanLon; dj++) {
                    for (int t : buckets.getOrDefault(
                            bucket(lat + di * cell, lon + dj * cell, cell), List.of())) {
                        if (t == s) continue;
                        boolean sameNs = ns == coarseOf[t];
                        // One publisher's own two stops: the dominant case, and the only one that
                        // needs no distance at all.
                        if (sameNs && space == spaceOf[t]) continue;
                        double dlat = lat - stations.lat()[t];
                        double dlon = (lon - stations.lon()[t]) * coslat;
                        double d2 = dlat * dlat + dlon * dlon;
                        if (d2 > r2) continue;
                        if (sameNs) {
                            // Same coarse namespace, so the gate below would never consider it — but
                            // a DIFFERENT full id space, so it may well be another publisher's copy.
                            blind.set(s);
                            if (copySpace[space] || copySpace[spaceOf[t]]) narrow.set(s);
                            continue;
                        }
                        Double cur = nearestD2.get(s);
                        if (cur == null || d2 < cur) {
                            nearestD2.put(s, d2);
                            nearest.put(s, t);
                        }
                    }
                }
            }
        }
        int paired = 0;
        for (Map.Entry<Integer, Integer> e : nearest.entrySet()) {
            int a = e.getKey();
            int b = e.getValue();
            if (a > b) continue;                                   // each pair once
            if (!Integer.valueOf(a).equals(nearest.get(b))) continue;   // mutual nearest only
            rep[Math.max(a, b)] = Math.min(a, b);
            paired++;
        }
        return paired;
    }


    private static long bucket(double lat, double lon, double cell) {
        long a = (long) Math.floor(lat / cell);
        long b = (long) Math.floor(lon / cell);
        return (a << 32) ^ (b & 0xffffffffL);
    }

    /// The node with every station replaced by its alias representative. Positions, times, countries
    /// and dates are untouched — only the identity of a station moves, which is the whole point.
    ///
    /// The mode must be carried over to the rebuilt node: the constructor that omits it defaults to
    /// RAIL, and [Node#mode] is what stops [XbLinks#junction] calling a bus publication's
    /// handover STAY-SEATED.
    public static Node aliased(Node node, int[] rep) {
        int[] canon = new int[node.canon.length];
        boolean moved = false;
        for (int i = 0; i < canon.length; i++) {
            canon[i] = node.canon[i] == XbTypes.NONE ? XbTypes.NONE : rep[node.canon[i]];
            if (canon[i] != node.canon[i]) moved = true;
        }
        if (!moved) return node;
        return new Node(node.id, node.key, node.home, node.publisher, node.num, canon, node.cc,
                node.sec, XbScan.distinctSorted(canon), node.days, node.mode);
    }

    // ------------------------------------------------------------------------------------- //
    // Edges
    // ------------------------------------------------------------------------------------- //

    /// The accepted pairing between two publications, or null.
    ///
    /// Every arm refuses by default: an unreadable time, a station one of them serves at no time at
    /// all, a direction that cannot be decided — all of them return null rather than guess.
    public static Edge edge(Node a, Node b) {
        if (a.home == b.home) return null;                  // never couple a feed to itself
        int[] shared = a.sharedWith(b);
        if (shared.length == 0) return null;

        // Conjunct 2: they are at EVERY shared station within the window, on a common date. The
        // per-station meeting sets are intersected, so the survivors are the dates on which the two
        // legs are one train all the way along — which is also the set of dates the link is valid on.
        DaySet days = null;
        for (int s : shared) {
            DaySet at = meetAt(a, b, s);
            if (at.isEmpty()) return null;
            days = days == null ? at : days.intersect(at);
            if (days.isEmpty()) return null;
        }

        // Conjunct 3: same direction. Read on the two shared stations furthest apart in a's own
        // sequence, which is the longest lever available and so the least sensitive to a dwell.
        if (shared.length >= 2 && !sameDirection(a, b, shared)) return null;
        return new Edge(a, b, shared, days, false);
    }

    /// The dates on which `a` and `b` are at `station` within the meeting window, over every pairing
    /// of their calls there. The union, so a train that passes a station twice meets its counterpart
    /// if EITHER call does.
    public static DaySet meetAt(Node a, Node b, int station) {
        DaySet out = DaySet.EMPTY;
        for (int i = 0; i < a.canon.length; i++) {
            if (a.canon[i] != station || a.sec[i] == Integer.MIN_VALUE) continue;
            for (int j = 0; j < b.canon.length; j++) {
                if (b.canon[j] != station || b.sec[j] == Integer.MIN_VALUE) continue;
                out = out.union(XbCalendar.meetingDays(a.days, a.sec[i], b.days, b.sec[j]));
            }
        }
        return out;
    }

    /// Do the two run the same way? Take the first and last shared station in `a`'s travel order and
    /// ask whether `b` passes them in the same order.
    ///
    /// Refuses when either leg has no usable time at one of the two, or when either delta is zero —
    /// a pair of stations a leg reaches at the same second says nothing about direction.
    public static boolean sameDirection(Node a, Node b, int[] shared) {
        Set<Integer> set = new HashSet<>();
        for (int s : shared) set.add(s);
        int firstA = -1;
        int lastA = -1;
        for (int i = 0; i < a.canon.length; i++) {
            if (!set.contains(a.canon[i]) || a.sec[i] == Integer.MIN_VALUE) continue;
            if (firstA < 0) firstA = i;
            lastA = i;
        }
        if (firstA < 0 || firstA == lastA) return false;
        int s1 = a.canon[firstA];
        int s2 = a.canon[lastA];
        if (s1 == s2) return false;
        int i1 = indexWithTime(b, s1, false);
        int i2 = indexWithTime(b, s2, true);
        if (i1 < 0 || i2 < 0) return false;
        long da = (long) a.sec[lastA] - a.sec[firstA];
        long db = (long) b.sec[i2] - b.sec[i1];
        if (da == 0 || db == 0) return false;
        return (da > 0) == (db > 0);
    }

    private static int indexWithTime(Node n, int station, boolean last) {
        int found = -1;
        for (int i = 0; i < n.canon.length; i++) {
            if (n.canon[i] != station || n.sec[i] == Integer.MIN_VALUE) continue;
            if (!last) return i;
            found = i;
        }
        return found;
    }

    // ------------------------------------------------------------------------------------- //
    // Groups
    // ------------------------------------------------------------------------------------- //

    /// One group per physical train: the connected components of the edge graph, within one number.
    ///
    /// Components are computed per number and never across, so a coincidental reuse of a train
    /// number cannot chain two corridors together — the number is the outer key, exactly as it is
    /// in the index.
    public static List<Group> groups(Int2ObjectMap<List<Node>> byNum) {
        List<Group> out = new ArrayList<>();
        long pairs = 0;
        long accepted = 0;
        for (Int2ObjectMap.Entry<List<Node>> e : byNum.int2ObjectEntrySet()) {
            List<Node> nodes = e.getValue();
            int n = nodes.size();
            int[] parent = new int[n];
            for (int i = 0; i < n; i++) parent[i] = i;
            List<Edge> edges = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    pairs++;
                    Edge edge = edge(nodes.get(i), nodes.get(j));
                    if (edge == null) continue;
                    accepted++;
                    edges.add(edge);
                    union(parent, i, j);
                }
            }
            if (edges.isEmpty()) continue;
            Map<Integer, List<Node>> byRoot = new LinkedHashMap<>();
            Map<Integer, List<Edge>> edgesByRoot = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                byRoot.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(nodes.get(i));
            }
            for (Edge edge : edges) {
                int root = find(parent, nodes.indexOf(edge.a()));
                edgesByRoot.computeIfAbsent(root, k -> new ArrayList<>()).add(edge);
            }
            for (Map.Entry<Integer, List<Node>> g : byRoot.entrySet()) {
                List<Edge> ge = edgesByRoot.get(g.getKey());
                if (ge == null || ge.isEmpty()) continue;   // a singleton nothing paired with
                out.add(new Group(e.getIntKey(), g.getValue(), ge));
            }
        }
        Log.info("[xb] %d groups from %d accepted pairings out of %d tested", out.size(), accepted,
                pairs);
        return out;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) parent[Math.max(ra, rb)] = Math.min(ra, rb);
    }

    // ------------------------------------------------------------------------------------- //
    // Ownership and tiling
    // ------------------------------------------------------------------------------------- //

    /// One publication the tiling could not place, and why.
    ///
    /// @param refusal  an index into [XbGroups#PARALLEL_REFUSAL].
    /// @param blockedAt the station that defeated the cut, or [XbTypes#NONE].
    public record Refusal(Node node, int refusal, int blockedAt) {}

    /// What a group resolved to.
    ///
    /// @param legs    the surviving publications, in travel order, each cut to the run it owns.
    ///                Consecutive legs share exactly one station: the handover.
    /// @param dropped publications a kept leg already carries, on every day they run.
    /// @param leftAlone publications that are neither a leg, a variant, nor covered. They stay in
    ///                   the graph uncoupled, the state they were in before this stage ran.
    /// @param leftAloneCrossing of the left-alone members, how many still call in a country other
    ///                           than their publisher's — the population that shows up as "an
    ///                           Italian journey in Switzerland".
    /// @param variantsInexact of the variants attached, how many describe the leg's corridor with a
    ///                         different call list — a call skipped, a call added, or both. A run
    ///                         where this is zero is a run where the corridor test admitted nothing a
    ///                         subset test would have refused.
    /// @param blockedBy stations that defeated a parallel cut — a publication would have been
    ///                   truncated to its own country's run, but one stop it gives up is served by
    ///                   no kept leg, so the cut is forbidden and the foreign tail stays. These are
    ///                   the stations to look at when the crossing count refuses to fall.
    /// @param parallelRefusals how many publications got as far as each step of [#PARALLEL_REFUSAL]
    ///                          before failing. The station census answers only the give-up step;
    ///                          this says whether the rest are even a station problem.
    /// @param parallelRefusalsBlind the same breakdown, restricted to publications calling at a
    ///                               station the alias is blind to (see [Alias#blind]). A refusal
    ///                               bucket that is mostly blind is one a better namespace gate could
    ///                               move; a bucket that is not is a genuinely different routing, and
    ///                               no amount of station matching will couple it.
    /// @param refusals one entry per publication in `leftAlone`, in the order they were refused.
    /// @param refusal null when the group tiled; otherwise why it did not, and nothing about the
    ///                group is written — no truncation, no drop, no link.
    public record Tiling(List<Leg> legs, List<Node> dropped, long leftAlone, long leftAloneCrossing,
            long variantsInexact, IntArrayList blockedBy, long[] parallelRefusals,
            long[] parallelRefusalsBlind, long[] parallelRefusalsNarrow, List<RouteMiss> routeMisses,
            List<Refusal> refusals, String refusal) {

        static Tiling refused(String why) {
            return new Tiling(List.of(), List.of(), 0, 0, 0, new IntArrayList(),
                    new long[PARALLEL_REFUSAL.length], new long[PARALLEL_REFUSAL.length],
                    new long[PARALLEL_REFUSAL.length], List.of(), List.of(), why);
        }
    }

    /// Assign every station of the group to exactly one publication, cut each publication to what it
    /// was assigned, and check the result tiles.
    ///
    /// A station's country decides who owns it, so the answer does not depend on the order the
    /// pairings were found in. Two refinements the corpus forces:
    ///
    ///  * Unclaimed stations. A station whose country no member of the group publishes belongs to
    ///    nobody by that rule. The Nightjet is the case: ÖBB publishes the Austrian AND the German
    ///    half, and no German member exists. Such a station goes to whichever leg reaches it in
    ///    fewest stops along its own sequence, feeder first on a tie.
    ///  * The handover is shared, not split. After assignment the runs are disjoint, and a link
    ///    needs a station BOTH legs serve to anchor on. So each junction is widened by one: the
    ///    distributor picks the feeder's last station up, if it serves it — and if neither can, the
    ///    group is refused rather than linked at two different stations.
    public static Tiling tile(Group group) {
        return tile(group, new java.util.BitSet(), new java.util.BitSet());
    }

    /// @param blind stations the alias could not see, from [#aliasStations]. Read-only, and used for
    ///              nothing but the refusal census: the tiling behaves identically without it.
    public static Tiling tile(Group group, java.util.BitSet blind, java.util.BitSet narrow) {
        List<Node> members = group.members();
        Set<Integer> claimed = new LinkedHashSet<>();
        for (Node m : members) claimed.add(m.home);

        // ---- 1. each member's claimed run: the positions whose country is its publisher's.
        Map<Node, int[]> runs = new LinkedHashMap<>();
        for (Node m : members) {
            int first = -1;
            int last = -1;
            int count = 0;
            for (int i = 0; i < m.canon.length; i++) {
                if (m.cc[i] != m.home) continue;
                if (first < 0) first = i;
                last = i;
                count++;
            }
            if (first < 0) continue;                        // owns nothing: the foreign-leg shape
            // Not a refusal: the contiguity requirement is about this publication, not about the
            // train. It is simply not an owner candidate, and step 6 decides what becomes of it.
            if (last - first + 1 != count) continue;
            runs.put(m, new int[] {first, last});
        }
        if (runs.isEmpty()) return Tiling.refused("no member owns a stop");

        // ---- 2. one owner per country: the member holding the most of its country's stops.
        // A tie goes to the smaller id, so the choice does not depend on iteration order.
        Map<Integer, Node> owner = new LinkedHashMap<>();
        for (Map.Entry<Node, int[]> e : runs.entrySet()) {
            Node m = e.getKey();
            int span = e.getValue()[1] - e.getValue()[0] + 1;
            Node cur = owner.get(m.home);
            if (cur == null) {
                owner.put(m.home, m);
                continue;
            }
            int[] cr = runs.get(cur);
            int curSpan = cr[1] - cr[0] + 1;
            if (span > curSpan || (span == curSpan && m.id.compareTo(cur.id) < 0)) {
                owner.put(m.home, m);
            }
        }

        // ---- 3. extend each owner over the unclaimed stations it reaches soonest.
        // A station whose country IS claimed is assigned by that rule and never moves; only the
        // unclaimed ones are competed for, and the competition is "fewest stops away along the
        // claiming leg's own sequence", ties to the smaller id so the answer does not depend on map
        // iteration order.
        Map<Integer, Node> hard = new HashMap<>();         // station -> owner, by country
        for (Node m : owner.values()) {
            for (int i = 0; i < m.canon.length; i++) {
                if (m.canon[i] != XbTypes.NONE && m.cc[i] == m.home) hard.put(m.canon[i], m);
            }
        }
        Map<Integer, Node> soft = new HashMap<>();         // station -> owner, by reach
        Map<Integer, Integer> reach = new HashMap<>();
        for (Node m : owner.values()) {
            int[] run = runs.get(m);
            for (int dir = -1; dir <= 1; dir += 2) {
                int steps = 0;
                for (int i = dir < 0 ? run[0] - 1 : run[1] + 1; i >= 0 && i < m.canon.length; i += dir) {
                    steps++;
                    int s = m.canon[i];
                    if (s != XbTypes.NONE && hard.containsKey(s)) break;   // someone's territory
                    if (claimed.contains(m.cc[i])) break;                  // someone's country
                    if (s == XbTypes.NONE) continue;
                    Integer cur = reach.get(s);
                    Node held = soft.get(s);
                    if (cur == null || steps < cur
                            || (steps == cur && m.id.compareTo(held.id) < 0)) {
                        reach.put(s, steps);
                        soft.put(s, m);
                    }
                }
            }
        }
        Map<Integer, Node> assignedTo = new HashMap<>(soft);
        assignedTo.putAll(hard);

        // ---- 4. cut each owner to what it was assigned, and require the cut to be contiguous.
        List<Leg> legs = new ArrayList<>();
        for (Node m : owner.values()) {
            int first = -1;
            int last = -1;
            int count = 0;
            for (int i = 0; i < m.canon.length; i++) {
                if (m.canon[i] == XbTypes.NONE || assignedTo.get(m.canon[i]) != m) continue;
                if (first < 0) first = i;
                last = i;
                count++;
            }
            // An owner whose cut does not survive is DEMOTED, not a reason to refuse the group: it
            // goes to step 6, which drops it when a kept leg already carries it and otherwise leaves
            // it alone, and the other legs still tile and still link. The single-stop cut is the
            // degenerate case. The one station such an owner was holding goes to nobody.
            if (first < 0 || last == first || last - first + 1 != count) continue;
            legs.add(new Leg(m, first, last, m.home, new ArrayList<>()));
        }
        if (legs.isEmpty()) return Tiling.refused("no owner survived the cut");

        // ---- 5. order the legs and widen each junction to the shared handover station.
        //
        // One leg is a legitimate outcome: it is what is left when every other publication of the
        // train was demoted above, typically because its owned run collapsed to a degenerate
        // single border station. There is nothing to chain and no link to emit, but step 6 still has
        // work — the demoted publications are the ones the surviving leg may already carry, and
        // dropping them is the dedup.
        if (legs.size() == 1) {
            // Nothing to hand over to, so nothing to cut back for: the leg is restored to its full
            // published extent.
            Leg only = legs.get(0);
            legs = new ArrayList<>(List.of(new Leg(only.node(), 0, only.node().size() - 1,
                    only.country(), only.variants())));
        } else {
            Map<String, Long> shift = align(group);
            if (shift == null) return Tiling.refused("the members' clocks do not agree");
            legs = order(legs, shift);
            if (legs == null) return Tiling.refused("the legs do not form a single chain");
            Widened widened = widen(legs, members);
            if (widened.legs() == null) {
                return Tiling.refused("two consecutive legs share no station to hand over at ("
                        + widened.diagnostic() + ")");
            }
            legs = widened.legs();

            String overlap = overlap(legs);
            if (overlap != null) return Tiling.refused("the legs overlap: " + overlap);
        }

        // ---- 6. every other member is redundant, a parallel publication of a leg, or unplaceable.
        //
        // Redundancy is decided FIRST, against the OWNER legs only, so the set it tests against is
        // fixed before the walk starts and the answer does not depend on member order. Without it a
        // second publication of the whole train — ÖBB and STA both publish R 1826-1829 — is kept as
        // a "variant" of the first, and the links only join a train to itself.
        legs = new ArrayList<>(legs);
        List<Node> dropped = new ArrayList<>();
        List<Node> unplaced = new ArrayList<>();
        for (Node m : members) {
            if (legFor(legs, m) != null) continue;
            if (coveredBy(legs, m)) {
                dropped.add(m);
            } else {
                unplaced.add(m);
            }
        }

        // What is left survived the redundancy test, so it is a parallel publication of a leg rather
        // than a duplicate, and it is cut like one: attaching it to the leg as a variant makes cutLeg
        // truncate it to the leg's own first and last station and junction emit a link per variant,
        // so it keeps its through-service. Left at its full published extent it leaves an Italian
        // journey standing in Switzerland, the duplication this stage exists to remove. There is no
        // publisher test: a different publisher on the same route is a parallel leg.
        long leftAlone = 0;
        long leftAloneCrossing = 0;
        IntArrayList blocked = new IntArrayList();
        long[] parallelRefusals = new long[PARALLEL_REFUSAL.length];
        long[] parallelRefusalsBlind = new long[PARALLEL_REFUSAL.length];
        long[] parallelRefusalsNarrow = new long[PARALLEL_REFUSAL.length];
        List<RouteMiss> misses = new ArrayList<>();
        List<Refusal> refusals = new ArrayList<>();
        long variantsInexact = 0;
        for (Node m : unplaced) {
            ParallelMatch match = parallelTo(legs, m, blocked, misses);
            if (match.leg() != null) {
                match.leg().variants().add(m);
                if (match.inexact()) variantsInexact++;
                continue;
            }
            leftAlone++;
            refusals.add(new Refusal(m, match.refusal(), match.blockedAt()));
            parallelRefusals[match.refusal()]++;
            if (callsAtBlindStation(m, blind)) parallelRefusalsBlind[match.refusal()]++;
            if (callsAtBlindStation(m, narrow)) parallelRefusalsNarrow[match.refusal()]++;
            if (crossesABorder(m)) leftAloneCrossing++;
        }
        for (Leg leg : legs) {
            leg.variants().sort(Comparator.comparing((Node n) -> n.id));
        }
        return new Tiling(legs, dropped, leftAlone, leftAloneCrossing, variantsInexact, blocked,
                parallelRefusals, parallelRefusalsBlind, parallelRefusalsNarrow, misses, refusals,
                null);
    }

    /// Does this publication call anywhere the alias is blind? One such station is enough: it names
    /// a station under an id the matcher cannot connect to the other publisher's id for the same
    /// place, which is what defeats the endpoint tests.
    private static boolean callsAtBlindStation(Node m, java.util.BitSet blind) {
        for (int s : m.stations) {
            if (s != XbTypes.NONE && blind.get(s)) return true;
        }
        return false;
    }

    /// Put every member's clock on ONE timeline, keyed by journey id, or null when they cannot be.
    ///
    /// Each publication counts seconds from its OWN operating-day midnight, and two publishers that
    /// split one journey at midnight do not mean the same midnight, so raw `sec` may not be compared
    /// across members. The edges carry the fix: two members that meet at a station are there
    /// at the same instant, so the difference of their readings at that station IS the offset between
    /// their clocks.
    ///
    /// The offsets are propagated over a spanning tree of the group's edges. An edge that closes a
    /// cycle is a CHECK, not a no-op: if it implies an offset more than the meeting window away from
    /// the one already assigned, the members disagree about when they are where and the group is
    /// refused.
    static Map<String, Long> align(Group group) {
        Map<String, Long> shift = new HashMap<>();
        Map<String, List<Edge>> incident = new HashMap<>();
        for (Edge e : group.edges()) {
            incident.computeIfAbsent(e.a().id, k -> new ArrayList<>()).add(e);
            incident.computeIfAbsent(e.b().id, k -> new ArrayList<>()).add(e);
        }
        List<Node> members = new ArrayList<>(group.members());
        members.sort(Comparator.comparing(n -> n.id));
        Deque<Node> queue = new ArrayDeque<>();
        shift.put(members.get(0).id, 0L);
        queue.add(members.get(0));
        while (!queue.isEmpty()) {
            Node cur = queue.poll();
            for (Edge e : incident.getOrDefault(cur.id, List.of())) {
                Node other = e.a() == cur ? e.b() : e.a();
                Long delta = clockDelta(cur, other, e.shared());
                if (delta == null) return null;
                long want = shift.get(cur.id) + delta;
                Long had = shift.get(other.id);
                if (had == null) {
                    shift.put(other.id, want);
                    queue.add(other);
                } else if (Math.abs(had - want) > XbProfile.MEETING_WINDOW_S) {
                    return null;
                }
            }
        }
        return shift.size() == group.members().size() ? shift : null;
    }

    /// How far `other`'s clock runs behind `cur`'s: `cur.sec - other.sec` at a station they share.
    /// Null when no shared station gives both a readable time.
    private static Long clockDelta(Node cur, Node other, int[] shared) {
        for (int s : shared) {
            int i = indexWithTime(cur, s, false);
            int j = indexWithTime(other, s, false);
            if (i >= 0 && j >= 0) return (long) cur.sec[i] - other.sec[j];
        }
        return null;
    }

    /// The legs as a single chain, in travel order, or null when they do not form one.
    ///
    /// Sorted on the ALIGNED clock, so a leg that runs after midnight sorts after the leg that hands
    /// over to it rather than before it. Two legs whose spans nest — one entirely inside the other —
    /// are not a chain but a duplicate that the ownership step should already have resolved, so that
    /// is a refusal rather than an ordering.
    static List<Leg> order(List<Leg> legs, Map<String, Long> shift) {
        if (legs.size() < 2) return null;
        List<Leg> sorted = new ArrayList<>(legs);
        sorted.sort(Comparator.comparingLong((Leg l) -> l.startSec() + shift.get(l.node().id))
                .thenComparing(l -> l.node().id));
        for (int i = 0; i + 1 < sorted.size(); i++) {
            long endHere = sorted.get(i).endSec() + shift.get(sorted.get(i).node().id);
            long endNext = sorted.get(i + 1).endSec() + shift.get(sorted.get(i + 1).node().id);
            if (endHere > endNext) return null;
        }
        return sorted;
    }

    /// Widen each junction so the two legs share the handover station: the distributor picks up the
    /// feeder's last station where it serves it, else the feeder runs on to the distributor's first.
    /// Null when neither is possible — the two publications hand over at two different stations, and
    /// linking them would tell a passenger to leave at one and board at the other.
    /// The widened legs, or the reason there are none.
    record Widened(List<Leg> legs, String diagnostic) {}

    static Widened widen(List<Leg> legs, List<Node> members) {
        List<Leg> out = new ArrayList<>(legs);
        for (int i = 0; i + 1 < out.size(); i++) {
            Leg feeder = out.get(i);
            Leg dist = out.get(i + 1);
            int[] at = handover(feeder, dist, members);
            if (at == null) return new Widened(null, handoverDiagnostic(feeder, dist));
            out.set(i, new Leg(feeder.node(), feeder.from(), at[0], feeder.country(),
                    feeder.variants()));
            out.set(i + 1, new Leg(dist.node(), at[1], dist.to(), dist.country(), dist.variants()));
        }
        return new Widened(out, null);
    }

    /// Where two consecutive legs hand over: `{position in the feeder, position in the distributor}`,
    /// or null when no station serves.
    ///
    /// Three attempts. The first is [#overlapAnchor], which asks where the two PUBLICATIONS stop
    /// coinciding; the other two are the ownership boundary itself — the feeder's own last owned stop
    /// when the distributor calls there, then the distributor's first owned stop when the feeder runs
    /// that far.
    ///
    /// The publication anchor must stay first. The ownership boundary is not symmetric — attempt 2
    /// reads the FEEDER's last owned stop, and which leg is the feeder is decided by travel direction
    /// — so on attempts 2 and 3 alone the same corridor hands over in two different places depending
    /// on which way the train runs.
    private static int[] handover(Leg feeder, Leg dist, List<Node> members) {
        int[] overlap = overlapAnchor(feeder, dist, members);
        if (overlap != null) return overlap;

        int at = dist.node().firstIndexOf(feeder.lastStation());
        if (at >= 0 && at <= dist.to()) return new int[] {feeder.to(), at};

        int atFeeder = feeder.node().lastIndexOf(dist.firstStation());
        if (atFeeder >= feeder.from()) return new int[] {atFeeder, dist.from()};
        return null;
    }

    /// The best station to hand over at inside the CROSS-BORDER OVERLAP: the stretch the distributor
    /// publishes up to and including its first owned stop. Null when nothing in that window serves.
    ///
    /// The window has TWO ends, both properties of the pair rather than of the travel direction. One
    /// end is the ownership boundary. The other is the interior-facing endpoint of the SHORTER
    /// publication — where ÖBB's S.CANDIDO – Lienz stops coinciding with Trenitalia's FORTEZZA –
    /// Lienz, where SBB's Como S. Giovanni – Zürich HB stops coinciding with Trenitalia's Milano
    /// Centrale – Zürich HB. Bounded by the ownership boundary alone the window would span the whole
    /// foreign section, since the Italian publication carries the whole Austrian one.
    ///
    /// Inside the window the station the most publications of the group serve wins: Chiasso is served
    /// by every publication of trains 10, 12, 13 and 126, while the shorter publication's endpoint
    /// there is Como S. Giovanni, which a Trenitalia skip-stop working omits.
    ///
    /// Ties go to the station furthest from the ownership boundary — the shorter publication's own
    /// endpoint — and then to the smaller station id, so the answer never depends on iteration order.
    ///
    /// Loss-free is a PRECONDITION, not a consequence. Moving the anchor makes one leg give up stops
    /// it owned, so [#lossFree] checks the other leg serves every one of them; a candidate that does
    /// not is discarded and the ownership boundary answers instead.
    public static int[] overlapAnchor(Leg feeder, Leg dist, List<Node> members) {
        Node f = feeder.node();
        Node d = dist.node();
        // The shorter publication is the one whose endpoint sits inside the other. Equal lengths
        // break on the id, so the answer cannot depend on which leg the caller passed first.
        boolean distIsShorter = d.size() < f.size()
                || (d.size() == f.size() && d.id.compareTo(f.id) < 0);
        int endpoint = distIsShorter ? d.canon[0] : f.canon[f.size() - 1];
        if (endpoint == XbTypes.NONE) return null;
        // The endpoint must be INTERIOR to the other publication, or there is no publication boundary
        // to anchor on and this must not answer. When both carry the WHOLE route the "shorter" one is
        // decided by an id tie-break, its endpoint is the shared TERMINUS, and the window below then
        // spans the entire foreign section — the anchor walks to the far end of the line instead of
        // to the border.
        Node other = distIsShorter ? f : d;
        if (other.firstIndexOf(endpoint) <= 0
                || other.lastIndexOf(endpoint) >= other.size() - 1) {
            return null;
        }
        // The window spans the boundary, so it has to be read off BOTH nodes. Whichever way the train
        // runs, the endpoint lies on one side of the ownership boundary and the boundary station on
        // the other, and only one of the two is indexable in any single node.
        IntArrayList candidates = new IntArrayList();
        java.util.BitSet seen = new java.util.BitSet();
        collect(candidates, seen, f, f.lastIndexOf(endpoint), feeder.to());
        collect(candidates, seen, d, d.firstIndexOf(endpoint), dist.from());

        int[] best = null;
        int bestServers = -1;
        int bestFromBoundary = -1;
        int bestStation = Integer.MAX_VALUE;
        for (int s : candidates) {
            int j = d.firstIndexOf(s);
            if (j < 0) continue;
            // The arriving leg hands over on its LAST call there, the continuing leg picks it up on
            // its FIRST — the direction rule XbLinks.End.refAt applies.
            int i = f.lastIndexOf(s);
            if (i < feeder.from()) continue;            // -1 included: the feeder never calls there
            if (j > dist.to()) continue;
            int[] at = {i, j};
            if (!lossFree(feeder, dist, at)) continue;
            int servers = servedBy(members, s);
            int fromBoundary = Math.abs(dist.from() - j);
            if (servers > bestServers
                    || (servers == bestServers && fromBoundary > bestFromBoundary)
                    || (servers == bestServers && fromBoundary == bestFromBoundary
                            && s < bestStation)) {
                best = at;
                bestServers = servers;
                bestFromBoundary = fromBoundary;
                bestStation = s;
            }
        }
        return best;
    }

    /// The stations one node carries between two of its own positions, inclusive, appended in order
    /// and without repeats. Either bound may be -1, in which case there is nothing to collect.
    private static void collect(IntArrayList out, java.util.BitSet seen, Node n, int a, int b) {
        if (a < 0 || b < 0) return;
        for (int i = Math.min(a, b); i <= Math.max(a, b) && i < n.size(); i++) {
            int s = n.canon[i];
            if (s == XbTypes.NONE || seen.get(s)) continue;
            seen.set(s);
            out.add(s);
        }
    }

    /// How many publications of the group call at `station`. Makes the anchor a property of the GROUP
    /// rather than of whichever leg the direction made the feeder.
    private static int servedBy(List<Node> members, int station) {
        int n = 0;
        for (Node m : members) {
            if (m.serves(station)) n++;
        }
        return n;
    }

    /// Would moving the handover to `at` leave a stop that no leg of this group serves?
    ///
    /// The feeder keeps `[from, at[0]]` and the distributor `[at[1], to]`, so the feeder gives up
    /// everything after `at[0]` in its own owned run and the distributor everything before `at[1]` in
    /// its own. Each of those has to be served by the OTHER publication, which after widening is the
    /// leg that covers that stretch. Refuses by default: an unresolved station counts as unserved.
    public static boolean lossFree(Leg feeder, Leg dist, int[] at) {
        for (int i = at[0] + 1; i <= feeder.to(); i++) {
            int s = feeder.node().canon[i];
            if (s == XbTypes.NONE || !dist.node().serves(s)) return false;
        }
        for (int j = dist.from(); j < at[1]; j++) {
            int s = dist.node().canon[j];
            if (s == XbTypes.NONE || !feeder.node().serves(s)) return false;
        }
        return true;
    }

    /// What the two legs looked like when no handover station could be found — the numbers needed to
    /// tell "these publications genuinely diverge" from "the search is looking in the wrong place".
    private static String handoverDiagnostic(Leg feeder, Leg dist) {
        int shared = feeder.node().sharedWith(dist.node()).length;
        int sharedInRuns = 0;
        for (int i = feeder.from(); i <= feeder.to(); i++) {
            int d = dist.node().firstIndexOf(feeder.node().canon[i]);
            if (d >= dist.from() && d <= dist.to()) sharedInRuns++;
        }
        return String.format(
                "feeder run %d of %d stops, distributor run %d of %d; the publications share %d "
                + "stations, %d of them inside both runs",
                feeder.to() - feeder.from() + 1, feeder.node().size(),
                dist.to() - dist.from() + 1, dist.node().size(), shared, sharedInRuns);
    }

    /// Do the legs tile? Consecutive legs must share exactly ONE station, the handover; every other
    /// pair must share none. Returns a description of the first violation, or null when they tile.
    public static String overlap(List<Leg> legs) {
        for (int i = 0; i < legs.size(); i++) {
            for (int j = i + 1; j < legs.size(); j++) {
                List<Integer> shared = sharedRunStations(legs.get(i), legs.get(j));
                int allowed = j == i + 1 ? 1 : 0;
                if (shared.size() != allowed) {
                    return legs.get(i).node().id + " and " + legs.get(j).node().id + " share "
                            + shared.size() + " stations where " + allowed + " is allowed";
                }
            }
        }
        return null;
    }

    /// The stations two legs' KEPT runs have in common.
    private static List<Integer> sharedRunStations(Leg a, Leg b) {
        Set<Integer> inB = new LinkedHashSet<>();
        for (int j = b.from(); j <= b.to(); j++) {
            if (b.node().canon[j] != XbTypes.NONE) inB.add(b.node().canon[j]);
        }
        Set<Integer> shared = new LinkedHashSet<>();
        for (int i = a.from(); i <= a.to(); i++) {
            if (inB.contains(a.node().canon[i])) shared.add(a.node().canon[i]);
        }
        return new ArrayList<>(shared);
    }

    private static Leg legFor(List<Leg> legs, Node m) {
        for (Leg l : legs) {
            if (l.node() == m) return l;
        }
        return null;
    }

    /// The leg this publication runs parallel to: same first and last station as the leg's kept run,
    /// and its own run between them stays on the leg's corridor — it may skip any of the leg's calls,
    /// but it may not go where the leg does not, and what they share must be in the same order.
    ///
    /// Two shapes arrive here and both want the same treatment. One logical train is published as
    /// many journeys, one per calendar variant, and a link has to be emitted per variant or it is
    /// missing on every date the others serve. And two FEEDS may publish the same section —
    /// Trenitalia and STA on the Brenner — in which case the one that is NOT redundant is a second
    /// publication of the same leg. Either way it is truncated to the leg's run and linked with it.
    /// There is no publisher test; redundancy is decided first, in step 6 of [#tile].
    /// Why a publication could not be attached to a leg, ordered by how FAR the test got. A member
    /// is tested against every leg, so it is recorded at its best attempt: "it got as far as X"
    /// answers "how close is this to being fixable", which a first-failure tally does not.
    public static final String[] PARALLEL_REFUSAL = {
        "no leg of its own country",
        "does not serve the leg's FIRST station",
        "does not serve the leg's LAST station",
        "serves both endpoints, but in the wrong order",
        "runs the stations it shares with the leg in a different order",
        "goes somewhere between the endpoints the leg never goes",
        "gives up a stop no kept leg serves",
        "would keep another leg's territory",
    };

    /// The same ladder, short enough to draw. [XbCoupleAtlas] labels a refused publication with one
    /// of these inside a ~32-character gap beside its band. Same length as [#PARALLEL_REFUSAL], same
    /// order, and nothing may be appended to one without the other.
    public static final String[] PARALLEL_REFUSAL_SHORT = {
        "no leg of its own country",
        "misses the leg's first stop",
        "misses the leg's last stop",
        "serves the ends out of order",
        "shared stops out of order",
        "a different route between the ends",
        "gives up an unserved stop",
        "would keep a leg's territory",
    };

    /// One publication that missed a leg on route, kept so the shape can be read rather than
    /// guessed at. Stations are ints; [XbCouple] resolves them to ids when it prints.
    public record RouteMiss(String member, String leg, int legFirst, int legLast, int[] memberRun,
            int[] legRun, int refusal) {}

    /// The leg a publication was attached to, and how far it got when it was not.
    /// @param blockedAt the station whose loss defeated the cut, when the refusal was the "gives up
    ///                   a stop no kept leg serves" one; [XbTypes#NONE] otherwise. The same fact the
    ///                   group's `blockedBy` list counts in aggregate.
    /// @param inexact the match was accepted with a call list that is not the leg's — a skipped call,
    ///                 an added one, or both.
    record ParallelMatch(Leg leg, int refusal, int blockedAt, boolean inexact) {}

    /// Record how far one attempt got, and keep a worked example of the route miss.
    ///
    /// ONE PER RUNG, never the first n overall: the endpoint rungs hold roughly half the uncoupled
    /// publications, so a first-come cap spends every slot on them and a rung further down the ladder
    /// ships with nothing to look at. XbCouple caps again globally, the same way.
    private static int note(int furthest, int level, List<RouteMiss> misses, Node m, Leg l) {
        for (RouteMiss rm : misses) {
            if (rm.refusal() == level) return Math.max(furthest, level);
        }
        misses.add(new RouteMiss(m.id, l.node().id, l.firstStation(), l.lastStation(),
                m.canon.clone(),
                java.util.Arrays.copyOfRange(l.node().canon, l.from(), l.to() + 1), level));
        return Math.max(furthest, level);
    }

    private static ParallelMatch parallelTo(List<Leg> legs, Node m, IntArrayList blocked,
            List<RouteMiss> misses) {
        int furthest = 0;
        int blockedAt = XbTypes.NONE;
        for (int li = 0; li < legs.size(); li++) {
            Leg l = legs.get(li);
            // A leg IS a country's section of the train, so a publication can only run parallel to
            // the leg of its own country. Without this an Italian publication matches the AUSTRIAN
            // leg on route — it serves all of it — and is cut to Austria's section and linked as
            // though it were Austria's, which emits an IT-IT interchange.
            if (l.country() != m.home) continue;
            // A short working is still this train. A leg's first station is a JUNCTION only when
            // another leg hands over to it, and its last only when it hands over onward; at the two
            // ends of the chain there is no junction, and nothing downstream needs the publication
            // to reach them. XbLinks.junction asks only that a variant serve the junction station
            // and meet there in time: 1868's Lienz - BRUNICO workings all call at the S.CANDIDO
            // junction and stop four stations short of the leg's FORTEZZA, and 25133's publications
            // start at Bellinzona rather than at the leg's Biasca.
            //
            // The relaxation is bounded by the CORRIDOR test below, not by these two rungs: a
            // publication clamped to its own extent that reaches beyond the leg's run adds stations
            // the leg never serves, and PARALLEL_MAX_ADDED still refuses it.
            boolean junctionAtStart = li > 0;
            boolean junctionAtEnd = li + 1 < legs.size();
            int from = m.firstIndexOf(l.firstStation());
            if (from < 0) {
                if (junctionAtStart) {
                    furthest = note(furthest, 1, misses, m, l);
                    continue;
                }
                from = 0;                             // starts late at the head of the chain
            }
            int to = m.lastIndexOf(l.lastStation());
            if (to < 0) {
                if (junctionAtEnd) {
                    furthest = note(furthest, 2, misses, m, l);
                    continue;
                }
                to = m.size() - 1;                    // finishes early at the tail of the chain
            }
            if (from >= to) {
                furthest = note(furthest, 3, misses, m, l);
                continue;
            }
            // The corridor, not the stop list. A publication that SKIPS one of the leg's calls is
            // the same train stopping differently on a different day, which is what a variant IS:
            // two Trenitalia publications of train 23 Zürich HB – Milano Centrale serve Chiasso and
            // Milano Centrale and omit only the Monza call between them.
            //
            // So the two axes are separated and only ONE of them refuses: a skipped call costs
            // nothing, a station the leg never serves is bounded by PARALLEL_MAX_ADDED. The order
            // test is the third, and it is the only thing between a divergent routing and a
            // stay-seated link across a border it never crosses — junction asks only about the
            // handover station, and keepsNoOtherLegsTerritory refuses only a detour through a
            // station some OTHER leg holds, so a detour through country no leg covers passes it.
            Corridor corridor = corridor(l, m, from, to);
            if (!corridor.inOrder()) {
                furthest = note(furthest, 4, misses, m, l);
                continue;
            }
            if (corridor.added() > XbProfile.PARALLEL_MAX_ADDED) {
                furthest = note(furthest, 5, misses, m, l);
                continue;
            }
            int unserved = firstUnservedGiveUp(legs, m, from, to);
            if (unserved != XbTypes.NONE) {
                // Which station defeats the cut, named. The consolidation residue is the leading
                // suspect: Abfaltersbach is 1,378 m apart between the ÖBB and STA publications,
                // outside both the 440 m merge and the 1 km alias.
                blocked.add(unserved);
                // Keep the station only while this is still the FURTHEST any leg got: a later leg
                // that fails earlier must not overwrite the explanation with a blank one, and a
                // later leg that also reaches this rung is blocked by its own station, which is just
                // as good an answer.
                if (furthest <= 6) blockedAt = unserved;
                furthest = Math.max(furthest, 6);
                continue;
            }
            furthest = Math.max(furthest, 7);
            if (keepsNoOtherLegsTerritory(legs, l, m, from, to)) {
                return new ParallelMatch(l, -1, XbTypes.NONE,
                        corridor.skipped() + corridor.added() > 0);
            }
        }
        return new ParallelMatch(null, furthest, furthest == 6 ? blockedAt : XbTypes.NONE, false);
    }

    /// Is every station this publication serves already served by a kept leg, on every day it runs?
    /// Then it adds nothing and a link to it would be a handover from a train to itself.
    ///
    /// Every arm refuses by default — an unresolved station, an empty date set, one uncovered date —
    /// because the one outcome this must not produce is a stop that no journey serves afterwards. An
    /// edge requires an OVERLAPPING day, not every day, so without the date half a publication
    /// running on days its counterpart does not would vanish.
    public static boolean coveredBy(List<Leg> legs, Node m) {
        return coveredBy(legs, m, false);
    }

    /// @param withVariantDays union the legs' VARIANTS' days too. Always false on the live path —
    ///        step 6 decides redundancy before it assigns any variant, precisely so the set is fixed
    ///        before the walk starts. True is the MEASUREMENT arm: how much of the "no leg of its
    ///        own country" population would be disposed of as redundant if the rule counted the
    ///        days a leg's variants cover, which is what train 25017's three groups differ on.
    ///        Nothing on the live path may pass true without making step 6 two-pass.
    public static boolean coveredBy(List<Leg> legs, Node m, boolean withVariantDays) {
        if (m.days.isEmpty()) return false;
        for (int i = 0; i < m.canon.length; i++) {
            if (m.canon[i] == XbTypes.NONE) return false;
            // PER STATION. Asking "is every station served by some leg" and "is every date covered
            // by some leg" as two separate questions accepts a publication that no SINGLE leg
            // replaces: leg X serves its stations, leg Y runs on its dates, and on the day it
            // actually runs nothing goes where it went. The rule is one clause, not two, so the days
            // are unioned only over the legs that serve THIS station.
            DaySet here = DaySet.EMPTY;
            for (Leg l : legs) {
                for (int j = l.from(); j <= l.to(); j++) {
                    if (l.node().canon[j] == m.canon[i]) {
                        // The LEG's days only, never its variants': step 6 decides redundancy before
                        // it assigns any variant, so this set is fixed before the walk starts.
                        here = here.union(l.node().days);
                        if (withVariantDays) {
                            for (Node v : l.variants()) here = here.union(v.days);
                        }
                        break;
                    }
                }
            }
            if (!here.containsAll(m.days)) return false;
        }
        return true;
    }

    /// Does the range this publication would KEEP stay out of another leg's territory?
    ///
    /// The kept range is `[firstIndexOf(leg's first station) .. lastIndexOf(leg's last station)]`,
    /// and nothing so far constrains what lies BETWEEN those two positions in this publication's own
    /// sequence. A publication that reaches the leg's last station only after passing through the
    /// neighbouring country keeps those stops when it is cut — so the graph gets the neighbour's
    /// section twice, which is the duplication this whole file exists to remove.
    ///
    /// The leg's own run is exempt, because after `widen` consecutive legs deliberately share their
    /// handover station and a parallel publication has to keep it to be linked there.
    private static boolean keepsNoOtherLegsTerritory(List<Leg> legs, Leg own, Node m, int from,
            int to) {
        for (int i = from; i <= to; i++) {
            int s = m.canon[i];
            if (s == XbTypes.NONE || inRun(own, s)) continue;
            for (Leg other : legs) {
                if (other != own && inRun(other, s)) return false;
            }
        }
        return true;
    }

    /// How this publication's own run between the leg's two endpoints compares with the leg's run.
    ///
    /// @param skipped calls the LEG makes that the member does not, between the same two endpoints.
    ///                REPORTED, NEVER REFUSED — one train stopping differently on different days is
    ///                one train.
    /// @param added   calls the MEMBER makes there that the leg never makes. The only signal in the
    ///                data that it reaches the far endpoint by another route, and the only axis here
    ///                that refuses.
    /// @param inOrder the stations the two have in common appear in the same relative order in both.
    record Corridor(int skipped, int added, boolean inOrder) {}

    /// Does the member's run between the leg's endpoints stay on the leg's corridor?
    ///
    /// Read over a RANGE on both sides, not over the whole publication. Everything outside
    /// `[from..to]` is cut away by [XbCouple], so a leg station the member serves only outside that
    /// range is not a station the coupled journey calls at, and must not count as served.
    ///
    /// [XbTypes#NONE] is ignored on BOTH sides. An unresolved stop is evidence of neither a shared
    /// route nor a divergent one, and making it count either way would make this gate a function of
    /// how well the stop resolution ran rather than of the two routes.
    ///
    /// Adjacent repeats collapse — a dwell published as two calls is one call. A station the
    /// publication RETURNS to after calling elsewhere does not collapse, and surfaces as an order
    /// violation, which is what it is.
    static Corridor corridor(Leg l, Node m, int from, int to) {
        int[] legRun = Arrays.copyOfRange(l.node().canon, l.from(), l.to() + 1);
        int[] memRun = Arrays.copyOfRange(m.canon, from, to + 1);
        return new Corridor(missing(legRun, memRun), missing(memRun, legRun),
                Arrays.equals(inCommon(legRun, memRun), inCommon(memRun, legRun)));
    }

    /// How many DISTINCT stations of `run` are absent from `other`. Distinct, so a run that lists one
    /// station twice cannot count it twice.
    private static int missing(int[] run, int[] other) {
        int n = 0;
        for (int i = 0; i < run.length; i++) {
            int s = run[i];
            if (s == XbTypes.NONE || firstAt(run, s) != i) continue;
            if (!has(other, s)) n++;
        }
        return n;
    }

    /// `run` restricted to the stations `other` also serves, in `run`'s OWN travel order, with
    /// adjacent repeats collapsed. Two of these are equal exactly when the two runs agree about the
    /// order of everything they have in common.
    private static int[] inCommon(int[] run, int[] other) {
        int[] out = new int[run.length];
        int n = 0;
        for (int s : run) {
            if (s == XbTypes.NONE || !has(other, s)) continue;
            if (n > 0 && out[n - 1] == s) continue;
            out[n++] = s;
        }
        return Arrays.copyOf(out, n);
    }

    private static boolean has(int[] run, int station) {
        for (int s : run) {
            if (s == station) return true;
        }
        return false;
    }

    private static int firstAt(int[] run, int station) {
        for (int i = 0; i < run.length; i++) {
            if (run[i] == station) return i;
        }
        return -1;
    }

    private static boolean inRun(Leg l, int station) {
        for (int i = l.from(); i <= l.to(); i++) {
            if (l.node().canon[i] == station) return true;
        }
        return false;
    }

    /// The first stop this publication would GIVE UP that no kept leg serves, or [XbTypes#NONE]
    /// when every one of them is covered.
    ///
    /// Cutting a parallel publication to the leg's run removes everything outside `[from..to]`, and
    /// the one outcome that must not produce is unambiguous: "A stop that no journey serves
    /// afterwards is lost service". So a publication that reaches into territory no kept leg covers
    /// is NOT cut, even though leaving it whole means leaving a foreign tail in the graph.
    ///
    /// That is why the left-alone population never reaches zero: between losing a stop and keeping a
    /// duplicated one, the duplicate is chosen, and `leftAloneCrossing` counts the residue.
    ///
    /// Refuses by default: an unresolved station is treated as unserved.
    private static int firstUnservedGiveUp(List<Leg> legs, Node m, int from, int to) {
        for (int i = 0; i < m.canon.length; i++) {
            if (i >= from && i <= to) continue;              // kept
            int s = m.canon[i];
            if (s == XbTypes.NONE) return XbTypes.NONE;   // unresolved: refuse, but nothing to name
            boolean served = false;
            for (Leg l : legs) {
                for (int j = l.from(); j <= l.to() && !served; j++) {
                    if (l.node().canon[j] == s) served = true;
                }
                if (served) break;
            }
            if (!served) return s;
        }
        return XbTypes.NONE;
    }

    /// Does this publication call in a country other than its publisher's?
    static boolean crossesABorder(Node m) {
        for (byte cc : m.cc) {
            if (cc != XbTypes.NONE && cc != m.home) return true;
        }
        return false;
    }

    /// The codespace a journey id belongs to — its first two colon-separated segments, which is what
    /// distinguishes `at:obb` from `at:vor` and `IT:ITH10` from `it:apb`. A proxy for "the same
    /// publisher".
    public static String publisher(String id) {
        if (id == null) return "";
        int first = id.indexOf(':');
        if (first < 0) return id;
        int second = id.indexOf(':', first + 1);
        return second < 0 ? id : id.substring(0, second);
    }

    /// The stations of a tiling, for the overlap assertion the tests make.
    public static List<int[]> runs(Tiling tiling) {
        List<int[]> out = new ArrayList<>();
        for (Leg l : tiling.legs()) {
            out.add(Arrays.copyOfRange(l.node().canon, l.from(), l.to() + 1));
        }
        return out;
    }
}
