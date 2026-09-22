package tests;

// The three conjuncts of an edge, each tested in isolation from the four passes that normally feed
// it. The end-to-end fixture in TestXbTiling asserts the outcome and can reach it for more than one
// reason — a return working is refused by the tiling's own chain check too — so each rule is pinned
// here, where nothing else can answer for it.

import toolkit.test.Check;
import toolkit.test.Runner;
import transformers.xb.XbCalendar;
import transformers.xb.XbCalendar.DaySet;
import transformers.xb.XbGroups;

import java.util.ArrayList;
import java.util.List;
import transformers.xb.XbTypes.Edge;
import transformers.xb.XbTypes.Group;
import transformers.xb.XbTypes.Leg;
import transformers.xb.XbTypes.Node;

public class TestXbEdges {

    public static void main(String[] args) {
        Runner.run(TestXbEdges.class);
    }

    // Interned ids, chosen so nothing depends on which int a Dict would have handed out.
    static final int IT = 0;
    static final int AT = 1;
    static final int CH_CC = 2;
    static final int NUM = 7;
    // Publisher codespaces, interned. Two Italian feeds are two publishers, which is what decides
    // whether a second publication is a calendar variant or a duplicate to choose between.
    static final int IT_TRENITALIA = 0;
    static final int IT_STA = 1;
    static final int AT_OBB = 2;
    static final int CH_SBB = 3;
    // Stations, in the order a route visits them.
    static final int FORTEZZA = 10;
    static final int BRENNERO = 11;
    static final int GRIES = 12;
    static final int INNSBRUCK = 13;
    static final int STEINACH = 14;
    // Off the Brenner line entirely -- stations no leg of these groups ever serves, which is what a
    // publication taking a different way between the same two endpoints calls at.
    static final int MATREI = 15;
    static final int ST_JODOK = 16;

    static final DaySet ONE_DAY = DaySet.ofDates("2026-01-05");
    // A day the legs do not run, so the coverage test cannot dispose of a variant before parallelTo
    // sees it -- which is what a calendar variant is.
    static final DaySet OTHER_DAY = DaySet.ofDates("2026-01-06");

    static int hm(int h, int m) {
        return h * 3600 + m * 60;
    }

    /// The ordinary case: an Italian leg running to Innsbruck and an Austrian one starting at
    /// Brennero, three stations in common, minutes apart at each.
    public static void testTheOrdinaryPairIsAnEdge() {
        Node it = Node.of("IT:Sj:1", IT, IT_TRENITALIA, NUM,
                new int[] {FORTEZZA, BRENNERO, GRIES, INNSBRUCK},
                new int[] {IT, IT, AT, AT},
                new int[] {hm(20, 0), hm(20, 30), hm(20, 45), hm(21, 20)}, ONE_DAY);
        Node at = Node.of("at:obb:Sj:1", AT, AT_OBB, NUM,
                new int[] {BRENNERO, GRIES, INNSBRUCK},
                new int[] {IT, AT, AT},
                new int[] {hm(20, 32), hm(20, 47), hm(21, 22)}, ONE_DAY);

        Edge e = XbGroups.edge(it, at);
        Check.that(e != null, "the two publications of one train are an edge");
        Check.equals(3, e.shared().length, "sharing Brennero, Gries and Innsbruck");
        Check.equals(1, e.days().size(), "meeting on the one day they both run");
    }

    /// Conjunct 1: never couple a feed to itself. Two Italian publications of one train are a
    /// deduplication question, not a border crossing.
    public static void testTwoPublicationsOfOneCountryAreNotAnEdge() {
        Node a = Node.of("IT:Sj:1", IT, IT_TRENITALIA, NUM,
                new int[] {FORTEZZA, BRENNERO}, new int[] {IT, IT},
                new int[] {hm(20, 0), hm(20, 30)}, ONE_DAY);
        Node b = Node.of("it:apb:Sj:1", IT, IT_STA, NUM,
                new int[] {FORTEZZA, BRENNERO}, new int[] {IT, IT},
                new int[] {hm(20, 1), hm(20, 31)}, ONE_DAY);

        Check.isNull(XbGroups.edge(a, b), "same publisher country, so no edge");
    }

    /// Conjunct 2, the time half: the same number, the same stations, the same day, twelve hours
    /// apart.
    public static void testAMorningAndAnEveningWorkingAreNotAnEdge() {
        Node morning = Node.of("IT:Sj:1", IT, IT_TRENITALIA, NUM,
                new int[] {FORTEZZA, BRENNERO, GRIES}, new int[] {IT, IT, AT},
                new int[] {hm(8, 0), hm(8, 30), hm(8, 45)}, ONE_DAY);
        Node evening = Node.of("at:obb:Sj:1", AT, AT_OBB, NUM,
                new int[] {BRENNERO, GRIES, INNSBRUCK}, new int[] {IT, AT, AT},
                new int[] {hm(20, 30), hm(20, 45), hm(21, 20)}, ONE_DAY);

        Check.isNull(XbGroups.edge(morning, evening), "twelve hours apart is not one vehicle");
    }

    /// Conjunct 2, the calendar half: coincident times, disjoint calendars.
    public static void testDisjointCalendarsAreNotAnEdge() {
        Node a = Node.of("IT:Sj:1", IT, IT_TRENITALIA, NUM,
                new int[] {FORTEZZA, BRENNERO, GRIES}, new int[] {IT, IT, AT},
                new int[] {hm(20, 0), hm(20, 30), hm(20, 45)}, DaySet.ofDates("2026-01-05"));
        Node b = Node.of("at:obb:Sj:1", AT, AT_OBB, NUM,
                new int[] {BRENNERO, GRIES, INNSBRUCK}, new int[] {IT, AT, AT},
                new int[] {hm(20, 32), hm(20, 47), hm(21, 22)}, DaySet.ofDates("2026-01-06"));

        Check.isNull(XbGroups.edge(a, b), "they are never both running");
    }

    /// Conjunct 2 asks about every shared station, not any of them: two legs that agree at the
    /// border and diverge by hours inland are not one vehicle.
    public static void testAgreementAtOneSharedStationIsNotEnough() {
        Node a = Node.of("IT:Sj:1", IT, IT_TRENITALIA, NUM,
                new int[] {FORTEZZA, BRENNERO, GRIES}, new int[] {IT, IT, AT},
                new int[] {hm(20, 0), hm(20, 30), hm(20, 45)}, ONE_DAY);
        Node b = Node.of("at:obb:Sj:1", AT, AT_OBB, NUM,
                new int[] {BRENNERO, GRIES}, new int[] {IT, AT},
                new int[] {hm(20, 32), hm(23, 15)}, ONE_DAY);

        Check.isNull(XbGroups.edge(a, b), "coincident at Brennero, three hours out at Gries");
    }

    /// Conjunct 3: the two run in opposite directions. The times are within twenty minutes at every
    /// shared station, so conjunct 2 is satisfied and only direction separates them.
    public static void testAReturnWorkingIsNotAnEdge() {
        Node north = Node.of("IT:Sj:1", IT, IT_TRENITALIA, NUM,
                new int[] {FORTEZZA, BRENNERO, GRIES, INNSBRUCK},
                new int[] {IT, IT, AT, AT},
                new int[] {hm(10, 0), hm(10, 20), hm(10, 30), hm(10, 40)}, ONE_DAY);
        Node south = Node.of("at:obb:Sj:1", AT, AT_OBB, NUM,
                new int[] {INNSBRUCK, GRIES, BRENNERO},
                new int[] {AT, AT, IT},
                new int[] {hm(10, 20), hm(10, 30), hm(10, 40)}, ONE_DAY);

        // The premise: conjunct 2 passes, so it cannot be what rejects the pair.
        Check.that(!XbGroups.meetAt(north, south, BRENNERO).isEmpty(), "they meet at Brennero");
        Check.that(!XbGroups.meetAt(north, south, GRIES).isEmpty(), "and at Gries");
        Check.that(!XbGroups.meetAt(north, south, INNSBRUCK).isEmpty(), "and at Innsbruck");
        Check.that(!XbGroups.sameDirection(north, south, north.sharedWith(south)),
                "but they run opposite ways");
        Check.isNull(XbGroups.edge(north, south), "so they are not an edge");
    }

    /// A single shared station cannot decide direction, and the single-border-stop topology is
    /// exactly that shape — a truncated foreign leg touching one common station. Coincident time is
    /// what validates it there, and the direction conjunct must not veto it for want of a lever.
    public static void testASingleSharedStationStillCouplesOnTime() {
        Node a = Node.of("IT:Sj:1", IT, IT_TRENITALIA, NUM,
                new int[] {FORTEZZA, BRENNERO}, new int[] {IT, IT},
                new int[] {hm(20, 0), hm(20, 30)}, ONE_DAY);
        Node b = Node.of("at:obb:Sj:1", AT, AT_OBB, NUM,
                new int[] {BRENNERO, GRIES}, new int[] {IT, AT},
                new int[] {hm(20, 35), hm(20, 50)}, ONE_DAY);

        Edge e = XbGroups.edge(a, b);
        Check.that(e != null, "one shared station plus a coincident time is an edge");
        Check.equals(1, e.shared().length, "and it is the border station");
    }

    /// One publisher writes the departure as 24:20 on the 5th; the other, having split the journey
    /// at midnight, writes 00:25 on the 6th. Intersecting the raw date sets compares the 5th with
    /// the 6th and finds nothing. Read with the day offset, the two are five minutes apart at the
    /// same station on the same night.
    public static void testAMidnightSplitStillMeets() {
        Node before = Node.of("IT:Sj:1", IT, IT_TRENITALIA, NUM,
                new int[] {FORTEZZA, BRENNERO}, new int[] {IT, IT},
                // 24:20 normalised: 00:20 with DepartureDayOffset 1, i.e. 86400 + 20 minutes.
                new int[] {hm(23, 50), 86400 + hm(0, 20)}, DaySet.ofDates("2026-01-05"));
        Node after = Node.of("at:obb:Sj:1", AT, AT_OBB, NUM,
                new int[] {BRENNERO, GRIES}, new int[] {IT, AT},
                new int[] {hm(0, 25), hm(0, 45)}, DaySet.ofDates("2026-01-06"));

        Check.that(before.days.intersect(after.days).isEmpty(),
                "the raw date sets are disjoint -- which is what a naive overlap test would see");
        Edge e = XbGroups.edge(before, after);
        Check.that(e != null, "but read with the day offset they are the same train");
        Check.equals(java.util.List.of("2026-01-05"), e.days().toIsoDates(),
                "and the link is valid on the day the FEEDER runs");
    }

    /// The tiling assertion. Step 4 of `tile` hands each station to exactly one leg, but step 5
    /// widens each junction by pulling the distributor back to its first call at the handover, and a
    /// publication that calls there before stations the feeder owns drags those along. Consecutive
    /// legs may share exactly the handover; any other sharing is an overlap, and an overlap means
    /// two publications of one train both claiming a stretch of it.
    public static void testOverlappingLegsAreDetected() {
        Node itNode = Node.of("IT:Sj:1", IT, IT_TRENITALIA, NUM,
                new int[] {FORTEZZA, BRENNERO}, new int[] {IT, IT},
                new int[] {hm(20, 0), hm(20, 30)}, ONE_DAY);
        Node atNode = Node.of("at:obb:Sj:1", AT, AT_OBB, NUM,
                new int[] {FORTEZZA, BRENNERO, GRIES}, new int[] {IT, IT, AT},
                new int[] {hm(20, 2), hm(20, 32), hm(20, 47)}, ONE_DAY);

        List<Leg> tiled = List.of(
                new Leg(itNode, 0, 1, IT, new ArrayList<>()),
                new Leg(atNode, 1, 2, AT, new ArrayList<>()));
        Check.isNull(XbGroups.overlap(tiled),
                "sharing exactly the handover station is what a junction IS");

        List<Leg> overlapping = List.of(
                new Leg(itNode, 0, 1, IT, new ArrayList<>()),
                // The Austrian publication pulled back to position 0 takes Fortezza with it.
                new Leg(atNode, 0, 2, AT, new ArrayList<>()));
        String why = XbGroups.overlap(overlapping);
        Check.that(why != null, "two legs claiming Fortezza AND Brennero is an overlap");
        Check.that(why.contains("share 2 stations"), "and it says how many: " + why);
    }

    /// A parallel publication must not keep the neighbour's section when it is cut.
    ///
    /// `parallelTo` cuts a second publication of a country to `[first index of the leg's first
    /// station .. last index of its last station]`, and nothing about that range constrains what
    /// lies between those two positions in the publication's own sequence. Here the STA journey
    /// reaches Brennero only after running out to Gries, so the naive range keeps Gries — which the
    /// Austrian leg owns — and the graph gets Austria's section twice.
    ///
    /// The group is handed to `tile` directly rather than discovered through `edge`: the direction
    /// conjunct rejects every shape that reaches this by the normal route.
    public static void testAParallelPublicationCannotKeepTheNeighboursSection() {
        Node it = Node.of("IT:Sj:1", IT, IT_TRENITALIA, NUM,
                new int[] {FORTEZZA, BRENNERO, GRIES, INNSBRUCK},
                new int[] {IT, IT, AT, AT},
                new int[] {hm(20, 0), hm(20, 30), hm(20, 45), hm(21, 20)}, ONE_DAY);
        Node at = Node.of("at:obb:Sj:1", AT, AT_OBB, NUM,
                new int[] {BRENNERO, GRIES, INNSBRUCK},
                new int[] {IT, AT, AT},
                new int[] {hm(20, 32), hm(20, 47), hm(21, 22)}, ONE_DAY);
        // Same country as the Italian leg, same endpoints, but it passes through Gries on the way —
        // and it runs on a day the legs do not, so the redundancy test cannot dispose of it first.
        DaySet otherDay = DaySet.ofDates("2026-01-06");
        Node sta = Node.of("it:apb:Sj:1", IT, IT_STA, NUM,
                new int[] {FORTEZZA, GRIES, BRENNERO},
                new int[] {IT, AT, IT},
                new int[] {hm(20, 1), hm(20, 46), hm(20, 31)}, otherDay);

        int[] shared = it.sharedWith(at);
        List<Edge> edges = List.of(
                new Edge(it, at, shared, ONE_DAY, false),
                new Edge(sta, at, sta.sharedWith(at), otherDay, false));
        XbGroups.Tiling t = XbGroups.tile(new Group(NUM, List.of(it, at, sta), edges));

        Check.isNull(t.refusal(), "the two real legs still tile: " + t.refusal());
        for (Leg l : t.legs()) {
            for (Node v : l.variants()) {
                Check.that(!"it:apb:Sj:1".equals(v.id),
                        "the STA publication must NOT be cut as a parallel leg -- its kept range "
                        + "would span Gries, which the Austrian leg owns");
            }
        }
        Check.equals(1L, t.leftAloneCrossing(),
                "it is left alone instead, and counted as still crossing a border");
    }

    /// The corridor test's permissive half, in isolation: a skipped call costs nothing.
    public static void testAParallelPublicationMaySkipOneOfTheLegsCalls() {
        Node it = Node.of("IT:Sj:1", IT, IT_TRENITALIA, NUM,
                new int[] {FORTEZZA, BRENNERO},
                new int[] {IT, IT},
                new int[] {hm(20, 0), hm(20, 30)}, ONE_DAY);
        Node at = Node.of("at:obb:Sj:1", AT, AT_OBB, NUM,
                new int[] {BRENNERO, GRIES, STEINACH, INNSBRUCK},
                new int[] {IT, AT, AT, AT},
                new int[] {hm(20, 32), hm(20, 47), hm(21, 0), hm(21, 22)}, ONE_DAY);
        // Austria's section again on another day, without the Gries call.
        DaySet otherDay = DaySet.ofDates("2026-01-06");
        Node vor = Node.of("at:vor:Sj:1", AT, AT_OBB + 1, NUM,
                new int[] {BRENNERO, STEINACH, INNSBRUCK},
                new int[] {IT, AT, AT},
                new int[] {hm(20, 33), hm(21, 1), hm(21, 23)}, otherDay);

        XbGroups.Tiling t = tiled(it, at, vor);
        Check.isNull(t.refusal(), "the two real legs still tile: " + t.refusal());
        Check.equals(List.of("at:vor:Sj:1"), variantIds(t),
                "the publication that skips Gries is a calendar variant of the Austrian leg, "
                + "not an unplaceable member -- a train stopping differently on a different day "
                + "is the same train");
        Check.equals(0L, t.leftAlone(), "so nothing was left uncoupled");
        Check.equals(1L, t.variantsInexact(),
                "and it is counted as carrying a call list of its own, so the population this "
                + "rule admits can be read rather than inferred from a bucket that emptied");
    }

    /// The corridor test's refusing half: a publication that gets between the leg's endpoints a way
    /// the leg never goes is not that leg's train.
    ///
    /// Nothing else can catch it. `XbLinks.junction` asks only about the handover station, and
    /// `keepsNoOtherLegsTerritory` refuses only a detour through a station some other leg holds, so
    /// a detour through country no leg covers walks straight past it and the passenger is handed a
    /// stay-seated interchange onto a train they are not on.
    public static void testAParallelPublicationOnADifferentCorridorIsRefused() {
        Node it = Node.of("IT:Sj:1", IT, IT_TRENITALIA, NUM,
                new int[] {FORTEZZA, BRENNERO},
                new int[] {IT, IT},
                new int[] {hm(20, 0), hm(20, 30)}, ONE_DAY);
        Node at = Node.of("at:obb:Sj:1", AT, AT_OBB, NUM,
                new int[] {BRENNERO, GRIES, STEINACH, INNSBRUCK},
                new int[] {IT, AT, AT, AT},
                new int[] {hm(20, 32), hm(20, 47), hm(21, 0), hm(21, 22)}, ONE_DAY);
        // Both endpoints, in order, in the right direction -- but two stations of its own between
        // them and none of the leg's. Its Austrian run must stay the same length as OBB's, so that
        // the ownership vote ties and the smaller id keeps OBB as the leg; a longer one would win
        // the vote and make this a test of the tiling rather than of the corridor.
        DaySet otherDay = DaySet.ofDates("2026-01-06");
        Node vor = Node.of("at:vor:Sj:1", AT, AT_OBB + 1, NUM,
                new int[] {BRENNERO, MATREI, ST_JODOK, INNSBRUCK},
                new int[] {IT, AT, AT, AT},
                new int[] {hm(20, 33), hm(20, 50), hm(21, 5), hm(21, 23)}, otherDay);

        XbGroups.Tiling t = tiled(it, at, vor);
        Check.isNull(t.refusal(), "the two real legs still tile: " + t.refusal());
        Check.equals(List.of(), variantIds(t), "it is not attached to the Austrian leg");
        Check.equals(1, t.refusals().size(), "one publication was refused");
        Check.equals(5, t.refusals().get(0).refusal(),
                "at the corridor rung, not an endpoint one: it serves both ends and runs what it "
                + "shares with the leg in the right order, but it gets between them a way the leg "
                + "never goes");
    }

    /// The order rung, pinned by index. The index is the contract: XbLinks.Counters sizes three
    /// arrays on PARALLEL_REFUSAL.length and XbCoupleAtlas labels a band from PARALLEL_REFUSAL_SHORT
    /// at the same offset, so a rung that moves without its label moves silently.
    public static void testAPublicationThatRunsTheSharedStopsBackwardsIsRefused() {
        Node it = Node.of("IT:Sj:1", IT, IT_TRENITALIA, NUM,
                new int[] {FORTEZZA, BRENNERO},
                new int[] {IT, IT},
                new int[] {hm(20, 0), hm(20, 30)}, ONE_DAY);
        Node at = Node.of("at:obb:Sj:1", AT, AT_OBB, NUM,
                new int[] {BRENNERO, GRIES, STEINACH, INNSBRUCK},
                new int[] {IT, AT, AT, AT},
                new int[] {hm(20, 32), hm(20, 47), hm(21, 0), hm(21, 22)}, ONE_DAY);
        // Steinach before Gries: the leg's own stations, in the other order.
        DaySet otherDay = DaySet.ofDates("2026-01-06");
        Node vor = Node.of("at:vor:Sj:1", AT, AT_OBB + 1, NUM,
                new int[] {BRENNERO, STEINACH, GRIES, INNSBRUCK},
                new int[] {IT, AT, AT, AT},
                new int[] {hm(20, 33), hm(20, 48), hm(21, 1), hm(21, 23)}, otherDay);

        XbGroups.Tiling t = tiled(it, at, vor);
        Check.isNull(t.refusal(), "the two real legs still tile: " + t.refusal());
        Check.equals(List.of(), variantIds(t), "it is not attached to the Austrian leg");
        Check.equals(1, t.refusals().size(), "one publication was refused");
        Check.equals(4, t.refusals().get(0).refusal(),
                "at the ORDER rung -- it adds nothing of its own, so only the sequence gives it "
                + "away");
    }

    /// A group of three, handed to `tile` directly. The edges are built by hand because the
    /// direction conjunct will not discover a same-country third publication, and the rules under
    /// test are downstream of that.
    static XbGroups.Tiling tiled(Node it, Node at, Node third) {
        List<Edge> edges = List.of(
                new Edge(it, at, it.sharedWith(at), ONE_DAY, false),
                new Edge(it, third, it.sharedWith(third), third.days, false));
        return XbGroups.tile(new Group(NUM, List.of(it, at, third), edges));
    }

    static List<String> variantIds(XbGroups.Tiling t) {
        List<String> out = new ArrayList<>();
        for (Leg l : t.legs()) {
            for (Node v : l.variants()) out.add(v.id);
        }
        out.sort(java.util.Comparator.naturalOrder());
        return out;
    }

    // ------------------------------------------------------------------------------------- //
    // The handover anchor.
    // ------------------------------------------------------------------------------------- //

    // The Pustertal, in the shape the corpus has it. Trenitalia publishes FORTEZZA - Lienz whole;
    // ÖBB publishes only S.CANDIDO - Lienz. The Italian stations run to Versciaco and the Austrian
    // ones from Weitlanbrunn, so the ownership boundary is Versciaco / Weitlanbrunn while the two
    // publications part company at S.CANDIDO, three stops earlier.
    static final int DOBBIACO = 20;
    static final int SAN_CANDIDO = 21;
    static final int VERSCIACO = 22;
    static final int WEITLANBRUNN = 23;
    static final int SILLIAN = 24;
    static final int LIENZ = 25;

    /// Eastbound: the anchor is where ÖBB's publication begins, not where Italy stops owning stops.
    public static void testTheHandoverIsWhereTheShorterPublicationBegins() {
        Node it = Node.of("IT:Sj:1863", IT, IT_TRENITALIA, NUM,
                new int[] {DOBBIACO, SAN_CANDIDO, VERSCIACO, WEITLANBRUNN, SILLIAN, LIENZ},
                new int[] {IT, IT, IT, IT, AT, AT},
                new int[] {hm(6, 0), hm(6, 12), hm(6, 18), hm(6, 24), hm(6, 30), hm(7, 0)}, ONE_DAY);
        Node at = Node.of("at:obb:Sj:1863", AT, AT_OBB, NUM,
                new int[] {SAN_CANDIDO, VERSCIACO, WEITLANBRUNN, SILLIAN, LIENZ},
                new int[] {IT, IT, AT, AT, AT},
                new int[] {hm(6, 14), hm(6, 20), hm(6, 26), hm(6, 32), hm(7, 2)}, ONE_DAY);

        XbGroups.Tiling t = XbGroups.tile(
                new Group(NUM, List.of(it, at), List.of(new Edge(it, at, it.sharedWith(at),
                        ONE_DAY, false))));
        Check.isNull(t.refusal(), "the group tiles");
        Check.equals(2, t.legs().size(), "one leg per publication");
        Check.equals(SAN_CANDIDO, t.legs().get(0).lastStation(),
                "the Italian leg is cut where the Austrian publication starts, not at Versciaco");
        Check.equals(SAN_CANDIDO, t.legs().get(1).firstStation(),
                "and the Austrian leg picks it up on the same station");
    }

    /// Westbound, the same physical service: the anchor must not move. Under the ownership boundary
    /// alone it does, because the feeder is now the Austrian publication and its last owned stop is
    /// Weitlanbrunn.
    public static void testTheHandoverDoesNotDependOnTravelDirection() {
        Node at = Node.of("at:obb:Sj:1892", AT, AT_OBB, NUM,
                new int[] {LIENZ, SILLIAN, WEITLANBRUNN, VERSCIACO, SAN_CANDIDO},
                new int[] {AT, AT, AT, IT, IT},
                new int[] {hm(8, 0), hm(8, 28), hm(8, 34), hm(8, 40), hm(8, 46)}, ONE_DAY);
        Node it = Node.of("IT:Sj:1892", IT, IT_TRENITALIA, NUM,
                new int[] {LIENZ, SILLIAN, WEITLANBRUNN, VERSCIACO, SAN_CANDIDO, DOBBIACO},
                new int[] {AT, AT, AT, IT, IT, IT},
                new int[] {hm(8, 2), hm(8, 30), hm(8, 36), hm(8, 42), hm(8, 48), hm(9, 0)}, ONE_DAY);

        XbGroups.Tiling t = XbGroups.tile(
                new Group(NUM, List.of(it, at), List.of(new Edge(it, at, it.sharedWith(at),
                        ONE_DAY, false))));
        Check.isNull(t.refusal(), "the group tiles");
        Check.equals(SAN_CANDIDO, t.legs().get(0).lastStation(),
                "the same station as eastbound, not Weitlanbrunn");
        Check.equals(SAN_CANDIDO, t.legs().get(1).firstStation(),
                "on both sides of the junction");
    }

    /// The loss-free precondition. The Italian publication calls at ARNBACH, which the Austrian one
    /// does not serve at all. Moving the anchor back to S.CANDIDO would strip Arnbach from the only
    /// leg that serves it, so the candidate is discarded and the ownership boundary answers instead.
    public static void testAnAnchorThatWouldStripAStopIsRefused() {
        int arnbach = 26;
        Node it = Node.of("IT:Sj:1831", IT, IT_TRENITALIA, NUM,
                new int[] {SAN_CANDIDO, VERSCIACO, arnbach, SILLIAN, LIENZ},
                new int[] {IT, IT, IT, AT, AT},
                new int[] {hm(6, 0), hm(6, 6), hm(6, 12), hm(6, 30), hm(7, 0)}, ONE_DAY);
        Node at = Node.of("at:obb:Sj:1831", AT, AT_OBB, NUM,
                new int[] {SAN_CANDIDO, VERSCIACO, WEITLANBRUNN, SILLIAN, LIENZ},
                new int[] {IT, IT, AT, AT, AT},
                new int[] {hm(6, 2), hm(6, 8), hm(6, 20), hm(6, 32), hm(7, 2)}, ONE_DAY);

        Leg feeder = new Leg(it, 0, 2, IT, new ArrayList<>());
        Leg dist = new Leg(at, 2, 4, AT, new ArrayList<>());
        Check.isNull(XbGroups.overlapAnchor(feeder, dist, List.of(it, at)),
                "S.CANDIDO would strip Arnbach, which the Austrian publication never serves");
        Check.that(!XbGroups.lossFree(feeder, dist, new int[] {0, 0}),
                "and lossFree says so directly");
    }

    // The Gotthard, in the shape trains 10, 12, 13 and 126 have in the corpus. SBB publishes from
    // Como S. Giovanni — one stop INTO Italy — where Trenitalia publishes the whole run from Milano
    // Centrale, so the shorter publication's endpoint is Como S. Giovanni and the ownership boundary
    // is Chiasso. The third publication is Trenitalia's skip-stop working, which omits Como S.
    // Giovanni and so cannot attach to a leg that ends there.
    static final int MILANO = 30;
    static final int MONZA = 31;
    static final int COMO_SG = 32;
    static final int CHIASSO = 33;
    static final int LUGANO = 34;
    static final int ZURICH = 35;

    /// Inside the window, the station the most publications serve wins — which is Chiasso, not the
    /// shorter publication's endpoint. Anchoring at Como S. Giovanni strands the skip-stop working at
    /// `parallelTo`'s "does not serve the leg's LAST station"; anchoring at Chiasso attaches it.
    public static void testTheAnchorPrefersTheStationMostPublicationsServe() {
        Node it = Node.of("IT:Sj:126a", IT, IT_TRENITALIA, NUM,
                new int[] {MILANO, MONZA, COMO_SG, CHIASSO, LUGANO, ZURICH},
                new int[] {IT, IT, IT, CH_CC, CH_CC, CH_CC},
                new int[] {hm(7, 0), hm(7, 12), hm(7, 40), hm(7, 55), hm(8, 25), hm(9, 30)}, ONE_DAY);
        Node ch = Node.of("ch:1:Sj:126", CH_CC, CH_SBB, NUM,
                new int[] {COMO_SG, CHIASSO, LUGANO, ZURICH},
                new int[] {IT, CH_CC, CH_CC, CH_CC},
                new int[] {hm(7, 42), hm(7, 57), hm(8, 27), hm(9, 32)}, ONE_DAY);
        // The skip-stop working: Milano Centrale to Chiasso direct, no Monza, no Como S. Giovanni.
        Node skip = Node.of("IT:Sj:126b", IT, IT_TRENITALIA, NUM,
                new int[] {MILANO, CHIASSO, LUGANO, ZURICH},
                new int[] {IT, CH_CC, CH_CC, CH_CC},
                new int[] {hm(7, 2), hm(7, 57), hm(8, 27), hm(9, 32)}, OTHER_DAY);

        // Edges by hand, as testAParallelPublicationCannotKeepTheNeighboursSection does it: `edge`
        // will not pair two publications of one country, and `align` needs every member reachable.
        XbGroups.Tiling t = XbGroups.tile(new Group(NUM, List.of(it, ch, skip),
                List.of(new Edge(it, ch, it.sharedWith(ch), ONE_DAY, false),
                        new Edge(it, skip, it.sharedWith(skip), OTHER_DAY, false))));
        Check.isNull(t.refusal(), "the group tiles");
        Check.equals(CHIASSO, t.legs().get(0).lastStation(),
                "Chiasso is served by all three; Como S. Giovanni only by two");
        Check.equals(CHIASSO, t.legs().get(1).firstStation(), "on both sides of the junction");
        Check.equals(List.of("IT:Sj:126b"), variantIds(t),
                "and the skip-stop working attaches instead of being left uncoupled");
    }

    /// A publication that stops SHORT of the leg's far end still attaches, because that end is the
    /// end of the chain and not a junction. Train 1868's shape: the Austrian leg hands over at
    /// S.CANDIDO, the Italian leg runs on to FORTEZZA, and a SAD working turns back at DOBBIACO.
    public static void testAShortWorkingAttachesWhenTheEndIsNotAJunction() {
        Node at = Node.of("at:obb:Sj:1868", AT, AT_OBB, NUM,
                new int[] {LIENZ, SILLIAN, WEITLANBRUNN, VERSCIACO, SAN_CANDIDO},
                new int[] {AT, AT, AT, IT, IT},
                new int[] {hm(8, 0), hm(8, 28), hm(8, 34), hm(8, 40), hm(8, 46)}, ONE_DAY);
        Node it = Node.of("IT:Sj:1868", IT, IT_TRENITALIA, NUM,
                new int[] {LIENZ, SILLIAN, WEITLANBRUNN, VERSCIACO, SAN_CANDIDO, DOBBIACO, FORTEZZA},
                new int[] {AT, AT, AT, IT, IT, IT, IT},
                new int[] {hm(8, 2), hm(8, 30), hm(8, 36), hm(8, 42), hm(8, 48), hm(9, 0),
                        hm(9, 30)}, ONE_DAY);
        // Turns back at DOBBIACO: it serves the S.CANDIDO junction but never reaches FORTEZZA.
        Node shortWorking = Node.of("IT:Sj:1868b", IT, IT_TRENITALIA, NUM,
                new int[] {LIENZ, SILLIAN, WEITLANBRUNN, VERSCIACO, SAN_CANDIDO, DOBBIACO},
                new int[] {AT, AT, AT, IT, IT, IT},
                new int[] {hm(8, 4), hm(8, 32), hm(8, 38), hm(8, 44), hm(8, 50), hm(9, 2)},
                OTHER_DAY);

        XbGroups.Tiling t = XbGroups.tile(new Group(NUM, List.of(at, it, shortWorking),
                List.of(new Edge(at, it, at.sharedWith(it), ONE_DAY, false),
                        new Edge(it, shortWorking, it.sharedWith(shortWorking), OTHER_DAY, false))));
        Check.isNull(t.refusal(), "the group tiles");
        Check.equals(List.of("IT:Sj:1868b"), variantIds(t),
                "the short working attaches: FORTEZZA is the end of the chain, not a junction");
    }

    /// The same shape REFUSED, because the end it misses IS a junction. Three countries, so the
    /// middle leg has a junction at BOTH ends: a publication that stops short of the second one
    /// would be linked at a station it never calls at.
    static final int P1 = 40, P2 = 41, P3 = 42, P4 = 43, P5 = 44, P6 = 45, P7 = 46;

    public static void testAShortWorkingIsRefusedWhenTheEndIsAJunction() {
        Node itN = Node.of("IT:Sj:m", IT, IT_TRENITALIA, NUM,
                new int[] {P1, P2, P3}, new int[] {IT, IT, AT},
                new int[] {hm(6, 0), hm(6, 10), hm(6, 20)}, ONE_DAY);
        Node atN = Node.of("at:obb:Sj:m", AT, AT_OBB, NUM,
                new int[] {P2, P3, P4, P5, P6}, new int[] {IT, AT, AT, AT, CH_CC},
                new int[] {hm(6, 12), hm(6, 22), hm(6, 32), hm(6, 42), hm(6, 52)}, ONE_DAY);
        Node chN = Node.of("ch:1:Sj:m", CH_CC, CH_SBB, NUM,
                new int[] {P5, P6, P7}, new int[] {AT, CH_CC, CH_CC},
                new int[] {hm(6, 44), hm(6, 54), hm(7, 4)}, ONE_DAY);
        // Serves the middle leg's first junction (P3) but turns back at P4, short of its onward one.
        Node shortWorking = Node.of("at:obb:Sj:short", AT, AT_OBB, NUM,
                new int[] {P2, P3, P4}, new int[] {IT, AT, AT},
                new int[] {hm(6, 14), hm(6, 24), hm(6, 34)}, OTHER_DAY);

        XbGroups.Tiling t = XbGroups.tile(new Group(NUM, List.of(itN, atN, chN, shortWorking),
                List.of(new Edge(itN, atN, itN.sharedWith(atN), ONE_DAY, false),
                        new Edge(atN, chN, atN.sharedWith(chN), ONE_DAY, false),
                        new Edge(atN, shortWorking, atN.sharedWith(shortWorking),
                                OTHER_DAY, false))));
        Check.isNull(t.refusal(), "the three-country chain tiles: " + t.refusal());
        Check.equals(3, t.legs().size(), "one leg per country");
        Check.that(!variantIds(t).contains("at:obb:Sj:short"),
                "a publication missing the middle leg's onward JUNCTION must not attach");
    }

    /// The meeting arithmetic on its own, since everything above rests on it.
    public static void testMeetingDaysAcrossMidnight() {
        DaySet fifth = DaySet.ofDates("2026-01-05");
        DaySet sixth = DaySet.ofDates("2026-01-06");
        // 24:30 on the 5th against 00:30 on the 6th: the same instant, one day apart on paper.
        Check.equals(java.util.List.of("2026-01-05"),
                XbCalendar.meetingDays(fifth, 86400 + hm(0, 30), sixth, hm(0, 30)).toIsoDates(),
                "the offset cancels the date difference exactly");
        // The same clock times, but both dated the 5th: now they really are 24 hours apart.
        Check.that(XbCalendar.meetingDays(fifth, 86400 + hm(0, 30), fifth, hm(0, 30)).isEmpty(),
                "a whole day apart is not a meeting");
        Check.that(XbCalendar.meetingDays(fifth, hm(12, 0), fifth, hm(12, 29)).isEmpty()
                        == false,
                "29 minutes is inside the window");
        Check.that(XbCalendar.meetingDays(fifth, hm(12, 0), fifth, hm(12, 31)).isEmpty(),
                "31 minutes is outside it");
    }
}
