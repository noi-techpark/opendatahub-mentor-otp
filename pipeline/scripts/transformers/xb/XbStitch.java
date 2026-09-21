package transformers.xb;

// The interchange graph, and the vehicle it describes.
//
// A ServiceJourneyInterchange is one vehicle continuing when the meeting station is the arriving
// journey's LAST stop and the departing journey's FIRST. Those pairs are stitched into one composite
// [XbTypes.Node], which replaces its parts in the coupler's index. The element carries three other
// shapes — a portion that JOINS another train, a portion that SPLITS off it, and an ordinary
// guaranteed connection between two vehicles — and they are counted and left alone. Of the 288,917
// interchanges the store holds, 150,913 name a journey outside the retained population; of the
// 138,004 that do, 136,896 are continuations and 132 are not.

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import noi.netex.model.JourneyRefStructure;
import noi.netex.model.ServiceJourneyInterchange;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.util.Log;
import transformers.xb.XbCalendar.DaySet;
import transformers.xb.XbTypes.Node;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class XbStitch {

    private XbStitch() {}

    /// `-Dxb.stitch=off` restores the coupler that reads no interchange at all. The measurement arm:
    /// every number this stage is judged on is a difference against that baseline.
    public static boolean enabled() {
        return !"off".equalsIgnoreCase(System.getProperty("xb.stitch", "on"));
    }

    /// How many journeys one composite may span, `-Dxb.stitch.maxParts=<n>`.
    ///
    /// A guard against the graph rather than a claim about vehicles: a Swiss through-working chain
    /// can run all day, and a composite spanning a whole vehicle diagram is not a train any route
    /// test can read. A chain longer than this is counted and left unstitched, every part of it an
    /// ordinary publication. The bound binds: 17,720 publications sit in chains past it, against
    /// 178,477 stitched.
    public static final int MAX_PARTS = Integer.getInteger("xb.stitch.maxParts", 3);

    /// How long the vehicle may stand at the handover. The same window everything else in the
    /// corridor uses; see [XbProfile#MEETING_WINDOW_S].
    public static final int MAX_WAIT_S = XbProfile.MEETING_WINDOW_S;

    // ------------------------------------------------------------------------------------- //

    /// Why an interchange was not read as one vehicle continuing. Ordered as the tests apply, so a
    /// bucket says how far a meeting got.
    public static final String[] REFUSAL = {
        "one of the two journeys is not in the retained population",
        "the two are the same journey",
        "not at the ends: a join, a split, or a connection between two vehicles",
        "the departure is before the arrival, or more than the window after it",
        "the two never run on a common date",
        "two publishers, or two transport modes: not one vehicle",
        "the journey already has a successor, or the successor already has a predecessor",
        "the chain is longer than MAX_PARTS",
    };

    public static final int READ = -1;         // accepted, not refused

    /// The counters. Every one of them is printed, at zero as at a million: a gate that disappears
    /// when it passes cannot be read as having passed.
    public static final class Counters {
        public long interchanges;
        public final long[] refusals = new long[REFUSAL.length];
        public long continuations;
        public long composites;
        public long partsStitched;
        public long longestChain;
        /// Composites whose parts do not all carry one train number — the 464 / 40414 shape.
        public long multiNumber;

        public void report(String tag) {
            Log.info("%s interchanges %,d -> %,d continuations -> %,d composites over %,d "
                    + "publications (longest chain %d, %,d spanning two train numbers)",
                    tag, interchanges, continuations, composites, partsStitched, longestChain,
                    multiNumber);
            for (int i = 0; i < refusals.length; i++) {
                Log.info("%s     %,10d  %s", tag, refusals[i], REFUSAL[i]);
            }
        }
    }

    // ------------------------------------------------------------------------------------- //

    /// Read every interchange, stitch what is one vehicle, and REPLACE the parts with the composite
    /// in `byNum`.
    ///
    /// Runs after the alias rewrite, so the parts' stations are already the ones everything
    /// downstream compares; a composite is never aliased again.
    public static void stitch(Store db, Txn txn, Int2ObjectMap<List<Node>> byNum, Counters counters,
            String tag) {
        if (!enabled()) {
            Log.info("%s stitching is OFF (-Dxb.stitch=off): the coupler reads no interchange", tag);
            return;
        }
        Object2ObjectOpenHashMap<String, Node> byId = new Object2ObjectOpenHashMap<>();
        for (List<Node> nodes : byNum.values()) {
            for (Node n : nodes) byId.put(n.id, n);
        }

        List<Link> links = read(db, txn, byId, counters);
        Map<Node, Node> next = matching(links, counters);
        List<List<Node>> chains = chains(next, counters);
        replace(byNum, chains, counters);
        counters.report(tag);
    }

    // ------------------------------------------------------------------------------------- //
    // Reading
    // ------------------------------------------------------------------------------------- //

    /// One accepted continuation: `from` ends where `to` begins, on `days` (in `from`'s date space).
    ///
    /// @param waitSec  the dwell at the handover, on the aligned clock.
    /// @param dayShift how many days `to`'s dates move to land in `from`'s space.
    public record Link(Node from, Node to, int station, int waitSec, int dayShift, DaySet days) {}

    /// The interchanges that are one vehicle continuing.
    ///
    /// The stop points are not read. The interchange names each side's own ScheduledStopPoint at the
    /// handover, but the question is whether one journey ENDS where the other BEGINS, and the nodes'
    /// own stop sequences answer that without a stop-point index.
    static List<Link> read(Store db, Txn txn, Map<String, Node> byId, Counters counters) {
        List<Link> out = new ArrayList<>();
        for (Object o : db.iterOnlyObjects(txn, ServiceJourneyInterchange.class)) {
            ServiceJourneyInterchange x = (ServiceJourneyInterchange) o;
            counters.interchanges++;
            Node from = node(byId, x.getFromJourneyRef());
            Node to = node(byId, x.getToJourneyRef());
            if (from == null || to == null) {
                counters.refusals[0]++;
                continue;
            }
            Link link = continuation(from, to, counters);
            if (link != null) {
                counters.continuations++;
                out.add(link);
            }
        }
        return out;
    }

    private static Node node(Map<String, Node> byId, JourneyRefStructure ref) {
        return ref == null || ref.getRef() == null ? null : byId.get(ref.getRef());
    }

    /// Is this pair one vehicle continuing, and on which dates? Null with a bucket incremented when
    /// it is not.
    ///
    /// The stop sequences are the only evidence: EpipToDb sets StaySeated, Planned and Advertised on
    /// EVERY meeting it converts, so all of them look identical from the flags.
    ///
    /// Every arm refuses by default. An unreadable time at either end, a station that did not
    /// resolve, a pair with no common date — all of them are "not decidably one vehicle".
    public static Link continuation(Node from, Node to, Counters counters) {
        if (from == to || from.id.equals(to.id)) {
            counters.refusals[1]++;
            return null;
        }
        int station = from.canon[from.size() - 1];
        if (station == XbTypes.NONE || station != to.canon[0]) {
            counters.refusals[2]++;
            return null;
        }
        int arrive = from.sec[from.size() - 1];
        int depart = to.sec[0];
        if (arrive == Integer.MIN_VALUE || depart == Integer.MIN_VALUE) {
            counters.refusals[3]++;
            return null;
        }
        int shift = dayShift(arrive, depart);
        int wait = depart - 86400 * shift - arrive;
        if (wait < 0 || wait > MAX_WAIT_S) {
            counters.refusals[3]++;
            return null;
        }
        DaySet days = XbCalendar.meetingDays(from.days, arrive, to.days, depart);
        if (days.isEmpty()) {
            counters.refusals[4]++;
            return null;
        }
        // ONE VEHICLE, so one publisher and one mode. Two publishers describing one train is the
        // cross-border coupling's own question and it answers it with an edge, not with a stitch.
        if (from.publisher != to.publisher || from.mode != to.mode) {
            counters.refusals[5]++;
            return null;
        }
        return new Link(from, to, station, wait, shift, days);
    }

    /// How many days `to`'s dates move to land in `from`'s date space.
    ///
    /// The two count seconds from their OWN operating-day midnight, so a half departing 00:10 the
    /// next day reads 600 against its predecessor's 86,700 and the raw difference is minus a day.
    /// The same arithmetic as [XbCalendar#meetingDays], so the two cannot disagree about which dates
    /// a stitched vehicle runs on.
    public static int dayShift(int arriveSec, int departSec) {
        return (int) Math.round(((long) departSec - arriveSec) / 86400.0);
    }

    // ------------------------------------------------------------------------------------- //
    // Graph -> matching -> chains
    // ------------------------------------------------------------------------------------- //

    /// At most one successor per journey and at most one predecessor, chosen so the answer does not
    /// depend on the order the interchanges were read in.
    ///
    /// The graph branches: ÖBB publishes the onward half TWICE, once under its own number and once
    /// under the partner's, and writes a meeting to both, so one journey has two successors that are
    /// copies of each other rather than two continuations — Line 12-CH1 does this for every one of
    /// its 40 meetings. The discarded alternative stays an ordinary member, for the redundancy rule
    /// in `XbGroups.tile` to dispose of as a duplicate publication.
    ///
    /// The rank, best first: the same train number (a continuation under one number is the same
    /// train by the publisher's own account), then the shorter wait, then the smaller journey id,
    /// which only makes the choice total.
    public static Map<Node, Node> matching(List<Link> links, Counters counters) {
        List<Link> ordered = new ArrayList<>(links);
        ordered.sort(Comparator
                .comparing((Link l) -> l.from().num == l.to().num ? 0 : 1)
                .thenComparingInt(Link::waitSec)
                .thenComparing(l -> l.from().id)
                .thenComparing(l -> l.to().id));
        Map<Node, Node> next = new HashMap<>();
        Set<Node> taken = new HashSet<>();          // journeys that already have a predecessor
        for (Link l : ordered) {
            if (next.containsKey(l.from()) || taken.contains(l.to())) {
                counters.refusals[6]++;
                continue;
            }
            next.put(l.from(), l.to());
            taken.add(l.to());
        }
        return next;
    }

    /// The matching walked into chains, longest first, starting from the journeys nothing feeds.
    ///
    /// A cycle — every journey of it has a predecessor, so no walk starts there — contributes
    /// nothing and is counted with the over-long chains: a closed loop of continuations is a vehicle
    /// diagram, not a train.
    public static List<List<Node>> chains(Map<Node, Node> next, Counters counters) {
        Set<Node> hasPredecessor = new HashSet<>(next.values());
        List<List<Node>> out = new ArrayList<>();
        for (Map.Entry<Node, Node> e : next.entrySet()) {
            Node head = e.getKey();
            if (hasPredecessor.contains(head)) continue;         // not the start of its chain
            List<Node> chain = new ArrayList<>();
            for (Node n = head; n != null; n = next.get(n)) {
                chain.add(n);
                if (chain.size() > MAX_PARTS) break;
            }
            if (chain.size() > MAX_PARTS) {
                counters.refusals[7] += chain.size();
                continue;
            }
            out.add(chain);
        }
        // Deterministic order, so the composites are built in the same order on every run over one
        // store and anything that ties on an id breaks the same way.
        out.sort(Comparator.comparing(c -> c.get(0).id));
        return out;
    }

    // ------------------------------------------------------------------------------------- //
    // The composite
    // ------------------------------------------------------------------------------------- //

    /// Replace each chain's parts with one composite, in every train number's list.
    ///
    /// The composite goes in under EVERY part's number, because a group is per train number and the
    /// two halves need not share one: ÖBB's domestic half of the St.Margrethen trains is 464 and the
    /// partner half is SBB's 40414, and a composite carried under the first alone could never meet
    /// SBB's own publication of the second. One journey is then reachable from two groups, which is
    /// what [XbCouple]'s already-mutated guard is for.
    ///
    /// ONE PASS PER AFFECTED LIST, not one removal per part: a train number's list holds every
    /// publication carrying that number, thousands of them for a low number, and `List.remove` scans
    /// it.
    static void replace(Int2ObjectMap<List<Node>> byNum, List<List<Node>> chains,
            Counters counters) {
        Set<Node> consumed = new HashSet<>();                    // Node has identity equality
        Map<Integer, List<Node>> additions = new HashMap<>();
        for (List<Node> chain : chains) {
            Node composite = compose(chain);
            if (composite == null) continue;
            Set<Integer> numbers = new java.util.LinkedHashSet<>();
            for (Node part : chain) {
                numbers.add(part.num);
                consumed.add(part);
            }
            for (int num : numbers) {
                additions.computeIfAbsent(num, k -> new ArrayList<>()).add(composite);
            }
            counters.composites++;
            counters.partsStitched += chain.size();
            counters.longestChain = Math.max(counters.longestChain, chain.size());
            if (numbers.size() > 1) counters.multiNumber++;
        }
        if (consumed.isEmpty()) return;
        for (Int2ObjectMap.Entry<List<Node>> e : byNum.int2ObjectEntrySet()) {
            List<Node> add = additions.get(e.getIntKey());
            List<Node> list = e.getValue();
            boolean touched = add != null;
            if (!touched) {
                for (Node n : list) {
                    if (consumed.contains(n)) {
                        touched = true;
                        break;
                    }
                }
            }
            if (!touched) continue;
            list.removeIf(consumed::contains);
            if (add != null) list.addAll(add);
            // The list was sorted on id and the composites were appended; restore the order the rest
            // of the stage relies on (XbScan.pass2 sorts, XbGroups walks pairwise, and the emitted
            // interchange list follows member order).
            list.sort(Comparator.comparing((Node n) -> n.id));
        }
    }

    /// The chain as one node.
    ///
    /// Positions are concatenated, each part after the first joined from its SECOND stop — the first
    /// IS the handover, already carried by the part before it, and carrying it twice would make the
    /// vehicle call at one station twice in a row.
    ///
    /// Times and dates are moved into the FIRST part's date space, which is the space the composite
    /// then speaks in: part *i*'s seconds gain `-86400 x (k1 + ... + ki)` and its dates shift by the
    /// same number of days the other way.
    ///
    /// `id` and `key` are the first part's, so downstream ordering, tie-breaks and owner rules read
    /// a composite exactly as they read a journey.
    public static Node compose(List<Node> chain) {
        Node head = chain.get(0);
        int size = head.size();
        for (int i = 1; i < chain.size(); i++) size += chain.get(i).size() - 1;

        int[] canon = new int[size];
        byte[] cc = new byte[size];
        int[] sec = new int[size];
        int[] partAt = new int[size];
        int[] partIndex = new int[size];
        DaySet days = head.days;
        int at = 0;
        long shift = 0;              // seconds to add to this part to land in the head's space
        int cumulativeDays = 0;      // days to shift this part's dates by, same reason
        for (int p = 0; p < chain.size(); p++) {
            Node part = chain.get(p);
            if (p > 0) {
                Node prev = chain.get(p - 1);
                int arrive = prev.sec[prev.size() - 1];
                int depart = part.sec[0];
                int k = dayShift(arrive, depart);
                shift -= 86400L * k;
                cumulativeDays += k;
                days = days.intersect(part.days.shifted(cumulativeDays));
                if (days.isEmpty()) return null;   // nothing runs as one vehicle: do not stitch
            }
            for (int i = p == 0 ? 0 : 1; i < part.size(); i++) {
                canon[at] = part.canon[i];
                cc[at] = part.cc[i];
                sec[at] = part.sec[i] == Integer.MIN_VALUE
                        ? Integer.MIN_VALUE : (int) (part.sec[i] + shift);
                partAt[at] = p;
                partIndex[at] = i;
                at++;
            }
        }
        Node[] parts = chain.toArray(new Node[0]);
        return new Node(head.id, head.key, head.home, head.publisher, head.num, canon, cc, sec,
                XbScan.distinctSorted(canon), days, head.mode, parts, partAt, partIndex);
    }

    // ------------------------------------------------------------------------------------- //

    /// The range of a part inside a composite's positions: `{first, last}` inclusive, or null when
    /// the part contributes nothing to `[from..to]`.
    ///
    /// What [XbCouple] cuts on. A leg over a composite spans one or more whole journeys and part of
    /// the two at its ends, and each of them is truncated on its OWN positions — the store holds
    /// journeys, and an interchange names one.
    public static int[] partRange(Node composite, int part, int from, int to) {
        int first = -1;
        int last = -1;
        for (int i = from; i <= to; i++) {
            if (composite.partAt[i] != part) continue;
            if (first < 0) first = composite.partIndex[i];
            last = composite.partIndex[i];
        }
        return first < 0 ? null : new int[] {first, last};
    }

    /// Every part of `composite` that lies wholly OUTSIDE `[from..to]`, in order.
    ///
    /// These are the publications a leg over a composite does not carry. They must NOT be truncated
    /// to nothing: a part outside the kept range is a journey the graph would simply lose, and a
    /// stop no journey serves afterwards is lost service. The caller hands them to the redundancy
    /// rule, which drops one only when a kept leg already carries every stop on every day.
    public static List<Node> partsOutside(Node composite, int from, int to) {
        if (!composite.stitched()) return List.of();
        boolean[] inside = new boolean[composite.parts.length];
        for (int i = from; i <= to; i++) inside[composite.partAt[i]] = true;
        List<Node> out = new ArrayList<>();
        for (int p = 0; p < composite.parts.length; p++) {
            if (!inside[p]) out.add(composite.parts[p]);
        }
        return out;
    }

    /// The parts a leg's kept range touches, in travel order.
    public static int[] partsInside(Node composite, int from, int to) {
        if (!composite.stitched()) return new int[] {0};
        boolean[] inside = new boolean[composite.parts.length];
        for (int i = from; i <= to; i++) inside[composite.partAt[i]] = true;
        int n = 0;
        for (boolean b : inside) {
            if (b) n++;
        }
        int[] out = new int[n];
        int at = 0;
        for (int p = 0; p < inside.length; p++) {
            if (inside[p]) out[at++] = p;
        }
        return Arrays.copyOf(out, at);
    }
}
