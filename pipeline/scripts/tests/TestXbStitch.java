package tests;

// Reading the interchange graph as vehicles: which meetings are one train continuing, which are a
// join, a split or a connection, and what a stitched node is made of.
//
// The corpus shapes each case is taken from:
//
//   train 13478   Linz -> St.Margrethen and St.Margrethen -> Zürich HB, one vehicle, two journeys.
//                 Unstitched, St.Margrethen is a terminus of both and every anchor rule that needs
//                 an interior station refuses.
//   line 12-CH1   the onward half is published twice, once under ÖBB's number and once under the
//                 partner's, with a meeting to each. Both cannot be stitched, so the graph is
//                 reduced to a matching and the same-number successor wins.
//   the Nightjet  the second half departs 00:10 on the next operating day against an arrival at
//                 24:05 on this one. The raw seconds say the vehicle left a day before it arrived.
//
// Nodes are built from values, so nothing here depends on the four passes that normally feed them.

import toolkit.test.Check;
import toolkit.test.Runner;
import transformers.xb.XbCalendar.DaySet;
import transformers.xb.XbStitch;
import transformers.xb.XbTypes;
import transformers.xb.XbTypes.Node;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class TestXbStitch {

    public static void main(String[] args) {
        Runner.run(TestXbStitch.class);
    }

    // Stations, countries and publishers as the small constants a fixture supplies for itself.
    private static final int LINZ = 1;
    private static final int SALZBURG = 2;
    private static final int STM = 3;           // St.Margrethen, where ÖBB splits the publication
    private static final int WINTERTHUR = 4;
    private static final int ZRH = 5;
    private static final int AT = 1;
    private static final int OBB = 10;
    private static final int SBB = 11;
    private static final int NUM_13478 = 100;
    private static final int NUM_PARTNER = 101;

    private static final DaySet JAN1 = DaySet.ofDates("2026-01-01");
    private static final DaySet JAN2 = DaySet.ofDates("2026-01-02");
    private static final DaySet JUNE = DaySet.ofDates("2026-06-01");

    /// A node whose every stop is in one country, at the given seconds.
    private static Node node(String id, int num, int[] canon, int[] sec, DaySet days) {
        int[] cc = new int[canon.length];
        Arrays.fill(cc, AT);
        return Node.of(id, AT, OBB, num, canon, cc, sec, days);
    }

    private static final int H = 3600;

    /// The Austrian half: Linz 06:56 -> Salzburg -> St.Margrethen, arriving 11:45.
    private static Node austrianHalf() {
        return node("at:obb:ServiceJourney:10A11-OEBB-67:", NUM_13478,
                new int[] {LINZ, SALZBURG, STM}, new int[] {6 * H + 3360, 8 * H + 1800, 11 * H + 2700},
                JAN1);
    }

    /// The Swiss half, published by ÖBB under the partner's code: St.Margrethen 11:45 -> Zürich.
    private static Node swissHalf() {
        return node("at:obb:ServiceJourney:10CH1-SBB-22:", NUM_PARTNER,
                new int[] {STM, WINTERTHUR, ZRH},
                new int[] {11 * H + 2700, 12 * H + 1800, 13 * H + 1680}, JAN1);
    }

    // ------------------------------------------------------------------------------------- //

    /// The 13478 shape is read as one vehicle, and the handover is the station both name.
    public static void testTheTwoHalvesOfOneTrainAreAContinuation() {
        XbStitch.Counters counters = new XbStitch.Counters();
        XbStitch.Link link = XbStitch.continuation(austrianHalf(), swissHalf(), counters);
        Check.that(link != null, "the two halves of 13478 are one vehicle continuing");
        Check.equals(STM, link.station(), "the handover is St.Margrethen");
        Check.equals(0, link.waitSec(), "the split is administrative: the dwell is zero");
        Check.equals(List.of("2026-01-01"), link.days().toIsoDates(),
                "the vehicle is one train on the dates BOTH halves run");
    }

    /// A join, a split and an ordinary connection are all "not at the ends", and none of them is
    /// stitched.
    public static void testAJoinOrASplitIsNotAContinuation() {
        XbStitch.Counters counters = new XbStitch.Counters();
        // The second journey does not start at the handover — it was already running, which is what
        // a portion joining another train looks like.
        Node joins = node("at:obb:ServiceJourney:joins:", NUM_PARTNER,
                new int[] {WINTERTHUR, STM, ZRH},
                new int[] {10 * H, 11 * H + 2700, 13 * H + 1680}, JAN1);
        Check.isNull(XbStitch.continuation(austrianHalf(), joins, counters),
                "the departing journey was already running before the handover");

        // The first journey does not end at the handover — it runs on, which is what a portion
        // splitting off looks like from the other side.
        Node runsOn = node("at:obb:ServiceJourney:runs-on:", NUM_13478,
                new int[] {LINZ, STM, ZRH},
                new int[] {6 * H + 3360, 11 * H + 2700, 13 * H + 1680}, JAN1);
        Check.isNull(XbStitch.continuation(runsOn, swissHalf(), counters),
                "the arriving journey continues past the handover");
        Check.equals(2L, counters.refusals[2], "both land in the not-at-the-ends bucket");
    }

    /// Time and calendar refuse by default: a departure before the arrival, one an hour later, and
    /// two halves that never run on the same date.
    public static void testTheVehicleMustBeThereToContinue() {
        XbStitch.Counters counters = new XbStitch.Counters();
        Node backwards = node("at:obb:ServiceJourney:backwards:", NUM_PARTNER,
                new int[] {STM, ZRH}, new int[] {9 * H, 10 * H}, JAN1);
        Check.isNull(XbStitch.continuation(austrianHalf(), backwards, counters),
                "the second half left two and a half hours before the first arrived");

        Node muchLater = node("at:obb:ServiceJourney:later:", NUM_PARTNER,
                new int[] {STM, ZRH}, new int[] {13 * H, 14 * H}, JAN1);
        Check.isNull(XbStitch.continuation(austrianHalf(), muchLater, counters),
                "an hour and a quarter on the platform is a connection, not a through service");

        Node otherSeason = node("at:obb:ServiceJourney:summer:", NUM_PARTNER,
                new int[] {STM, ZRH}, new int[] {11 * H + 2700, 13 * H}, JUNE);
        Check.isNull(XbStitch.continuation(austrianHalf(), otherSeason, counters),
                "the two never run on one date");
        Check.equals(2L, counters.refusals[3], "two refused on the window");
        Check.equals(1L, counters.refusals[4], "one refused on the calendar");
    }

    /// One vehicle is one publisher and one mode. Two publishers describing one train is the
    /// cross-border coupling's own question, answered there with an edge and a link.
    public static void testTwoPublishersOrTwoModesAreNotOneVehicle() {
        XbStitch.Counters counters = new XbStitch.Counters();
        Node other = swissHalf();
        Node byAnotherPublisher = Node.of(other.id + "x", AT, SBB, other.num, other.canon,
                new int[] {AT, AT, AT}, other.sec, other.days);
        Check.isNull(XbStitch.continuation(austrianHalf(), byAnotherPublisher, counters),
                "two publishers are two publications, not one vehicle");

        Node asABus = Node.of(other.id + "b", AT, OBB, other.num, other.canon,
                new int[] {AT, AT, AT}, other.sec, other.days, (byte) 5);
        Check.isNull(XbStitch.continuation(austrianHalf(), asABus, counters),
                "the train does not continue as a bus");
        Check.equals(2L, counters.refusals[5], "both are refused as not-one-vehicle");
    }

    /// The night train: the halves' clocks are a day apart and the wait is five minutes.
    public static void testTheWholeDayComponentIsRemoved() {
        Check.equals(-1, XbStitch.dayShift(24 * H + 300, 600),
                "arrive 24:05 this operating day, depart 00:10 the next: one day back");
        Check.equals(0, XbStitch.dayShift(11 * H, 11 * H + 300), "an ordinary five-minute wait");

        XbStitch.Counters counters = new XbStitch.Counters();
        Node arrives = node("at:obb:ServiceJourney:night-a:", NUM_13478,
                new int[] {LINZ, STM}, new int[] {20 * H, 24 * H + 300}, JAN1);
        Node departs = node("at:obb:ServiceJourney:night-b:", NUM_PARTNER,
                new int[] {STM, ZRH}, new int[] {600, 2 * H}, JAN2);
        XbStitch.Link link = XbStitch.continuation(arrives, departs, counters);
        Check.that(link != null, "the night train continues, and the raw seconds say it does not");
        Check.equals(300, link.waitSec(), "five minutes, not minus a day");
        Check.equals(List.of("2026-01-01"), link.days().toIsoDates(),
                "the vehicle's dates are the FIRST half's, which is the space the node speaks in");
    }

    /// A journey with two candidate successors keeps the one carrying its own train number. Line
    /// 12-CH1's shape: the onward half is published as ÖBB's 464 and as the partner's 40414, with a
    /// meeting to both.
    public static void testOnlyOneSuccessorSurvives() {
        XbStitch.Counters counters = new XbStitch.Counters();
        Node from = austrianHalf();
        Node partnerCoded = swissHalf();
        Node obbCoded = node("at:obb:ServiceJourney:10CH1-OEBB-25:", NUM_13478,
                swissHalf().canon, swissHalf().sec, JAN1);

        List<XbStitch.Link> links = new ArrayList<>();
        links.add(XbStitch.continuation(from, partnerCoded, counters));
        links.add(XbStitch.continuation(from, obbCoded, counters));
        Map<Node, Node> next = XbStitch.matching(links, counters);
        Check.equals(1, next.size(), "one successor, not two");
        Check.equals(obbCoded.id, next.get(from).id,
                "the copy carrying the SAME train number wins; the partner-coded one stays an "
                        + "ordinary publication for the redundancy rule to judge");
        Check.equals(1L, counters.refusals[6], "the discarded alternative is counted, not silent");
    }

    /// A chain longer than the cap, and a cycle, are both left unstitched.
    public static void testChainsAreBoundedAndCyclesAreNotVehicles() {
        XbStitch.Counters counters = new XbStitch.Counters();
        Node a = node("a", NUM_13478, new int[] {LINZ, SALZBURG}, new int[] {0, H}, JAN1);
        Node b = node("b", NUM_13478, new int[] {SALZBURG, STM}, new int[] {H, 2 * H}, JAN1);
        Node c = node("c", NUM_13478, new int[] {STM, WINTERTHUR}, new int[] {2 * H, 3 * H}, JAN1);
        Node d = node("d", NUM_13478, new int[] {WINTERTHUR, ZRH}, new int[] {3 * H, 4 * H}, JAN1);

        List<List<Node>> chains = XbStitch.chains(Map.of(a, b, b, c), counters);
        Check.equals(1, chains.size(), "one chain");
        Check.equals(List.of("a", "b", "c"), ids(chains.get(0)), "walked from the head");

        chains = XbStitch.chains(Map.of(a, b, b, c, c, d), counters);
        Check.equals(0, chains.size(), "four parts is past the cap, so nothing is stitched");

        chains = XbStitch.chains(Map.of(a, b, b, a), counters);
        Check.equals(0, chains.size(), "a cycle is a vehicle diagram, not a train");
    }

    /// What a composite is made of: the handover carried once, the parts recoverable, and the
    /// second half's clock moved into the first's date space.
    public static void testTheCompositeIsTheVehicleAndRemembersItsJourneys() {
        Node arrives = node("at:obb:ServiceJourney:night-a:", NUM_13478,
                new int[] {LINZ, STM}, new int[] {20 * H, 24 * H + 300}, JAN1);
        Node departs = node("at:obb:ServiceJourney:night-b:", NUM_PARTNER,
                new int[] {STM, ZRH}, new int[] {600, 2 * H}, JAN2);
        Node vehicle = XbStitch.compose(List.of(arrives, departs));

        Check.that(vehicle != null, "the two halves compose");
        Check.equals(3, vehicle.size(), "LINZ, STM, ZRH — the handover carried ONCE, not twice");
        Check.equals(List.of(LINZ, STM, ZRH), ints(vehicle.canon), "in travel order");
        Check.equals(24 * H + 600, vehicle.sec[1 + 1] - (2 * H - 600),
                "the second half's clock is moved into the first half's date space");
        Check.that(vehicle.sec[0] < vehicle.sec[1] && vehicle.sec[1] < vehicle.sec[2],
                "and the vehicle's timeline is monotone, which is what every time test assumes");
        Check.equals(arrives.id, vehicle.id, "the composite carries the first part's id");
        Check.equals(List.of("2026-01-01"), vehicle.days.toIsoDates(),
                "and runs on the dates both halves do");

        Check.that(vehicle.stitched(), "it knows it is several publications");
        Check.equals(List.of(arrives.id, departs.id),
                vehicle.publications().stream().map(n -> n.id).toList(),
                "and which ones, in travel order");

        // The way back to the journeys, which is what the cut and the link need.
        Check.equals(1, vehicle.partAt[2], "Zürich came from the second half");
        Check.equals(1, vehicle.partIndex[2], "at the second half's own position 1");
        Check.equals(0, vehicle.partAt[1], "the handover is carried by the FIRST half");
    }

    /// A leg over a composite is cut per journey, and a part outside the leg is left alone.
    public static void testALegOverACompositeIsSplitBackIntoJourneys() {
        Node arrives = node("a", NUM_13478, new int[] {LINZ, SALZBURG, STM},
                new int[] {0, H, 2 * H}, JAN1);
        Node departs = node("b", NUM_PARTNER, new int[] {STM, WINTERTHUR, ZRH},
                new int[] {2 * H, 3 * H, 4 * H}, JAN1);
        Node vehicle = XbStitch.compose(List.of(arrives, departs));

        // The Austrian leg: positions 0..2 of the vehicle, which is the whole of the first journey.
        Check.equals(List.of(0), ints(XbStitch.partsInside(vehicle, 0, 2)), "one journey inside");
        Check.equals(List.of(0, 2), ints(XbStitch.partRange(vehicle, 0, 0, 2)),
                "cut on the first journey's OWN positions");
        Check.equals(List.of("b"),
                XbStitch.partsOutside(vehicle, 0, 2).stream().map(n -> n.id).toList(),
                "the Swiss half is outside the leg and is left exactly as published");

        // A leg spanning the split: both journeys are cut, each on its own positions.
        Check.equals(List.of(0, 1), ints(XbStitch.partsInside(vehicle, 1, 3)), "both journeys");
        Check.equals(List.of(1, 2), ints(XbStitch.partRange(vehicle, 0, 1, 3)),
                "the first journey from Salzburg to St.Margrethen");
        Check.equals(List.of(1, 1), ints(XbStitch.partRange(vehicle, 1, 1, 3)),
                "the second journey at Winterthur only");
        Check.equals(List.of(), XbStitch.partsOutside(vehicle, 1, 3).stream().map(n -> n.id).toList(),
                "nothing is left outside");
    }

    private static List<String> ids(List<Node> nodes) {
        return nodes.stream().map(n -> n.id).toList();
    }

    private static List<Integer> ints(int[] values) {
        List<Integer> out = new ArrayList<>();
        for (int v : values) out.add(v);
        return out;
    }
}
