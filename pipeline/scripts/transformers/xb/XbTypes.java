package transformers.xb;

// The shared types of the cross-border stage, and the memory budget they hold to.
//
// Nothing O(journeys) may be retained. A station and a country are ints from [Dict]; a journey's
// own ScheduledStopPoint refs, its coordinates and its version string are not held here at all,
// and are re-loaded or looked up by the passes that need them.

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import noi.netex.model.PassengerStopAssignment;
import noi.netex.model.ServiceJourney;
import noi.netex.model.ServiceJourneyInterchange;
import noi.netex.model.StopPlace;
import transformers.xb.XbCalendar.DaySet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class XbTypes {

    private XbTypes() {}

    /// "no such station" / "no such country" — the int that is never a dictionary entry.
    public static final int NONE = -1;

    /// The mode a journey gets when its own resolves to nothing. [XbStations#modeByte] is the rule.
    public static final byte RAIL_MODE =
            (byte) noi.netex.model.AllVehicleModesOfTransportEnumeration.RAIL.ordinal();

    // ------------------------------------------------------------------------------------- //

    /// String → dense int, and back. Insertion-ordered, so an id's int is a function of the order
    /// the scan first met it — which is the class-map cursor order, and therefore stable across runs
    /// over one store. Nothing order-dependent keys on the int itself; it is compared and sorted,
    /// never ranked.
    public static final class Dict {

        private final Map<String, Integer> index = new HashMap<>();
        private final List<String> values = new ArrayList<>();

        /// The int for `s`, assigning one if this is the first sighting. [#NONE] for null/empty.
        public int intern(String s) {
            if (s == null || s.isEmpty()) return NONE;
            Integer i = index.get(s);
            if (i != null) return i;
            int next = values.size();
            values.add(s);
            index.put(s, next);
            return next;
        }

        /// The int for `s` WITHOUT assigning one — [#NONE] when unseen.
        public int lookup(String s) {
            if (s == null || s.isEmpty()) return NONE;
            return index.getOrDefault(s, NONE);
        }

        public String value(int i) {
            return i == NONE ? null : values.get(i);
        }

        public int size() {
            return values.size();
        }
    }

    // ------------------------------------------------------------------------------------- //

    /// One journey, reduced to what matching reads. Built by [XbScan]; never mutated afterwards.
    ///
    /// `canon`, `cc` and `sec` are aligned 1:1 with the journey's passing times, so position *i*
    /// means the same stop in all of them.
    public static final class Node {

        /// The journey's id — the WHOLE id; nothing shortens it.
        public final String id;
        /// The row's full key in the SOURCE store, which `loadObjectByFullKey` takes directly.
        public final long key;
        /// The publishing feed's country, interned. Never [#NONE] — the scan skips a journey it
        /// cannot classify.
        public final int home;
        /// The codespace that published it — the first two colon-separated segments, interned. What
        /// distinguishes `at:obb` from `at:vor` when deciding whether two publications are calendar
        /// variants of one another or two publications to choose between.
        public final int publisher;
        /// The normalised train number, interned.
        public final int num;
        /// Consolidated StopPlace per position; [#NONE] where the stop resolved to no station.
        public final int[] canon;
        /// The country each stop sits in, interned — the station's own country where the stop
        /// resolved, else the stop id's. [#NONE] where neither answered. Derived once, HERE, and
        /// read everywhere: a stop whose country is derived twice and differently is a trap.
        public final byte[] cc;
        /// Seconds since the journey's operating-day midnight per position, day offset folded in.
        /// [Integer#MIN_VALUE] where the journey carries no time at that stop.
        public final int[] sec;
        /// The distinct non-[#NONE] entries of [#canon], SORTED — the intersection key.
        public final int[] stations;
        /// The dates the journey runs, keyed on its FIRST stop's operating day.
        public final DaySet days;
        /// The journey's transport mode as an [AllVehicleModesOfTransportEnumeration] ordinal,
        /// resolved by [XbStations#modeByte]. Read for one question: [XbLinks] may only call a
        /// handover stay-seated when the two journeys are the same mode.
        public final byte mode;
        /// The publications this node was STITCHED from, in travel order, or null when it is one
        /// journey. The node is the vehicle; these are the ServiceJourneys a publisher split it into
        /// and that the store holds ([XbStitch]). [#partAt] says which part each position came from
        /// and [#partIndex] where it sat in that part's own sequence.
        public final Node[] parts;
        /// Per position, the index into [#parts]. Null exactly when `parts` is.
        public final int[] partAt;
        /// Per position, the position it occupies in ITS OWN part. Null exactly when `parts` is.
        public final int[] partIndex;

        Node(String id, long key, int home, int publisher, int num, int[] canon, byte[] cc,
                int[] sec, int[] stations, DaySet days) {
            this(id, key, home, publisher, num, canon, cc, sec, stations, days, RAIL_MODE);
        }

        Node(String id, long key, int home, int publisher, int num, int[] canon, byte[] cc,
                int[] sec, int[] stations, DaySet days, byte mode) {
            this(id, key, home, publisher, num, canon, cc, sec, stations, days, mode, null, null,
                    null);
        }

        Node(String id, long key, int home, int publisher, int num, int[] canon, byte[] cc,
                int[] sec, int[] stations, DaySet days, byte mode, Node[] parts, int[] partAt,
                int[] partIndex) {
            this.mode = mode;
            this.id = id;
            this.key = key;
            this.home = home;
            this.publisher = publisher;
            this.num = num;
            this.canon = canon;
            this.cc = cc;
            this.sec = sec;
            this.stations = stations;
            this.days = days;
            this.parts = parts;
            this.partAt = partAt;
            this.partIndex = partIndex;
        }

        /// Is this node several publications of one vehicle?
        public boolean stitched() {
            return parts != null;
        }

        /// The publications this node stands for: its parts when it is stitched, itself when it is
        /// not. What every consumer that has to name a JOURNEY rather than a vehicle reads.
        public List<Node> publications() {
            return parts == null ? List.of(this) : List.of(parts);
        }

        /// A node built from values rather than from a store — the test-fixture route. Every
        /// interned value is PASSED, never derived from a dictionary this class holds; the live path
        /// interns into the dictionaries [XbCouple] creates and threads through. `stations` is
        /// derived, so a fixture cannot make a node the scan could never produce.
        public static Node of(String id, int home, int publisher, int num, int[] canon, int[] cc,
                int[] sec, DaySet days) {
            return of(id, home, publisher, num, canon, cc, sec, days, RAIL_MODE);
        }

        /// The same, with an explicit mode — for the fixtures that exist to test the mode rule.
        public static Node of(String id, int home, int publisher, int num, int[] canon, int[] cc,
                int[] sec, DaySet days, byte mode) {
            byte[] bytes = new byte[cc.length];
            for (int i = 0; i < cc.length; i++) bytes[i] = (byte) cc[i];
            return new Node(id, 0L, home, publisher, num, canon, bytes, sec,
                    XbScan.distinctSorted(canon), days, mode);
        }

        public int size() {
            return canon.length;
        }

        /// The FIRST position calling at `station`, or -1. What the leg that CONTINUES from a
        /// handover is anchored on.
        public int firstIndexOf(int station) {
            for (int i = 0; i < canon.length; i++) {
                if (canon[i] == station) return i;
            }
            return -1;
        }

        /// The LAST position calling at `station`, or -1. What the leg that ARRIVES at a handover is
        /// anchored on.
        public int lastIndexOf(int station) {
            for (int i = canon.length - 1; i >= 0; i--) {
                if (canon[i] == station) return i;
            }
            return -1;
        }

        public boolean serves(int station) {
            return firstIndexOf(station) >= 0;
        }

        /// The stations this node and `other` have in common, sorted.
        public int[] sharedWith(Node other) {
            int[] out = new int[Math.min(stations.length, other.stations.length)];
            int n = 0;
            int i = 0;
            int j = 0;
            while (i < stations.length && j < other.stations.length) {
                if (stations[i] < other.stations[j]) {
                    i++;
                } else if (stations[i] > other.stations[j]) {
                    j++;
                } else {
                    out[n++] = stations[i];
                    i++;
                    j++;
                }
            }
            return Arrays.copyOf(out, n);
        }

        @Override
        public String toString() {
            return id + " (" + canon.length + " stops)";
        }
    }

    // ------------------------------------------------------------------------------------- //

    /// One accepted pairing between two nodes: the stations they share and the dates on which they
    /// are at ALL of them within the meeting window. Built by [XbGroups].
    ///
    /// @param shared  the shared stations, sorted.
    /// @param days    the meeting dates, in `a`'s date space.
    /// @param viaGeo  the shared stations were found by coordinate rather than by canon — the
    ///                foreign-leg arm.
    public record Edge(Node a, Node b, int[] shared, DaySet days, boolean viaGeo) {

        /// The strength of the pairing: more meeting dates first, then more shared stations. Used to
        /// pick a group's owner when two members of one country tie on stop count.
        public int weightDays() {
            return days.size();
        }
    }

    // ------------------------------------------------------------------------------------- //

    /// One physical train: every publication of it, from every feed, with the ownership decision
    /// taken over the whole set rather than pairwise. Each station of the union belongs to one
    /// country and each country to one publication, and the tiling is checked before anything is
    /// written.
    ///
    /// @param num     the normalised train number, interned.
    /// @param members every publication, in canonical (id, version) order.
    /// @param edges   the accepted pairings among them.
    public record Group(int num, List<Node> members, List<Edge> edges) {}

    /// One publication cut to the stops its country owns, ready to be truncated and linked.
    ///
    /// @param node    the publication.
    /// @param from    first position of the owned run, inclusive.
    /// @param to      last position of the owned run, inclusive.
    /// @param country the country whose stops it keeps, interned.
    /// @param variants the other publications of the same country and route — same first and last
    ///                 owned station — that this leg stands for. One logical train is published as
    ///                 many journeys, one per calendar variant, and a link is emitted per variant.
    public record Leg(Node node, int from, int to, int country, List<Node> variants) {

        public int firstStation() {
            return node.canon[from];
        }

        public int lastStation() {
            return node.canon[to];
        }

        /// When this leg is at its first owned stop, in seconds since its operating-day midnight.
        public int startSec() {
            return node.sec[from];
        }

        public int endSec() {
            return node.sec[to];
        }
    }

    // ------------------------------------------------------------------------------------- //

    /// Everything the read/couple phase produces; the write phase consumes it.
    ///
    /// @param survivorStops  the consolidated StopPlaces, re-emitted (Stage A only).
    /// @param psas           assignments the quay-sharing touched.
    /// @param modifiedSj     journey id → its truncated / renumbered bean.
    /// @param modifiedPat    pattern id → its sliced or forked bean.
    /// @param links          the new stay-seated ServiceJourneyInterchanges.
    /// @param droppedKeys    SOURCE-store full keys of the publications removed entirely because a
    ///                       kept leg already carries them.
    /// @param orphanTn       TrainNumber ids no journey's final state still references.

    public record Result(
            List<StopPlace> survivorStops,
            List<PassengerStopAssignment> psas,
            Map<String, ServiceJourney> modifiedSj,
            Map<String, Object> modifiedPat,
            List<ServiceJourneyInterchange> links,
            LongOpenHashSet droppedKeys,
            Set<String> orphanTn) {}
}
