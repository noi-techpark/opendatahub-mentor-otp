package tests;

// The fixture for one train published three ways, asserting that the resulting legs tile the route
// without overlap or gap.
//
// The Brenner is the shape it is drawn from: Trenitalia and STA both publish the Italian section
// and ÖBB the Austrian one. The group model decides once over all three — every station goes to the
// country it sits in, each country to one publication — and the tiling is checked before anything
// is written.
//
// The fixture is post-EPIP in shape: passing times and a pattern on every journey, no Calls, one
// DayType per journey through a UicOperatingPeriod, and one StopPlace per physical station with
// each publisher's own stop point assigned to it, which is the state the consolidation leaves
// behind.

import noi.netex.model.ServiceJourneyInterchange;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.Runner;
import toolkit.test.TestStore;
import transformers.xb.XbCouple;
import transformers.xb.XbTypes.Result;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TestXbTiling {

    public static void main(String[] args) {
        Runner.run(TestXbTiling.class);
    }

    // One StopPlace per physical station, with each publisher's own stop point assigned to it.
    // Fortezza and Brennero are Italian, Gries am Brenner and Innsbruck Austrian, and the station's
    // country is voted from the stop points -- which is why ÖBB's HAFAS coding of Brennero
    // (`it-22021-1389`) matters: it says Italy in as many words, against the `at:` id space it sits
    // in, and the vote has to prefer it.
    static final String STOPS = """
<SiteFrame id="site" version="1">
  <stopPlaces>
    <StopPlace id="SP:Fortezza" version="1"><Name>Fortezza</Name><Centroid><Location><Latitude>46.788</Latitude><Longitude>11.612</Longitude></Location></Centroid>
      <quays><Quay id="Q:Fortezza" version="1"><Centroid><Location><Latitude>46.788</Latitude><Longitude>11.612</Longitude></Location></Centroid></Quay></quays></StopPlace>
    <StopPlace id="SP:Brennero" version="1"><Name>Brennero</Name><Centroid><Location><Latitude>47.003</Latitude><Longitude>11.506</Longitude></Location></Centroid>
      <quays><Quay id="Q:Brennero" version="1"><Centroid><Location><Latitude>47.003</Latitude><Longitude>11.506</Longitude></Location></Centroid></Quay></quays></StopPlace>
    <StopPlace id="SP:Gries" version="1"><Name>Gries am Brenner</Name><Centroid><Location><Latitude>47.041</Latitude><Longitude>11.487</Longitude></Location></Centroid>
      <quays><Quay id="Q:Gries" version="1"><Centroid><Location><Latitude>47.041</Latitude><Longitude>11.487</Longitude></Location></Centroid></Quay></quays></StopPlace>
    <StopPlace id="SP:Steinach" version="1"><Name>Steinach am Brenner</Name><Centroid><Location><Latitude>47.090</Latitude><Longitude>11.466</Longitude></Location></Centroid>
      <quays><Quay id="Q:Steinach" version="1"><Centroid><Location><Latitude>47.090</Latitude><Longitude>11.466</Longitude></Location></Centroid></Quay></quays></StopPlace>
    <StopPlace id="SP:Innsbruck" version="1"><Name>Innsbruck Hbf</Name><Centroid><Location><Latitude>47.263</Latitude><Longitude>11.401</Longitude></Location></Centroid>
      <quays><Quay id="Q:Innsbruck" version="1"><Centroid><Location><Latitude>47.263</Latitude><Longitude>11.401</Longitude></Location></Centroid></Quay></quays></StopPlace>
  </stopPlaces>
</SiteFrame>
""";

    static final String ASSIGNMENTS = """
  <stopAssignments>
    <PassengerStopAssignment id="psa:it:f" version="1" order="1"><ScheduledStopPointRef ref="IT:ITH10:ScheduledStopPoint:830003400"/><StopPlaceRef ref="SP:Fortezza"/><QuayRef ref="Q:Fortezza"/></PassengerStopAssignment>
    <PassengerStopAssignment id="psa:it:b" version="1" order="2"><ScheduledStopPointRef ref="IT:ITH10:ScheduledStopPoint:830002001"/><StopPlaceRef ref="SP:Brennero"/><QuayRef ref="Q:Brennero"/></PassengerStopAssignment>
    <PassengerStopAssignment id="psa:it:g" version="1" order="3"><ScheduledStopPointRef ref="IT:ITH10:ScheduledStopPoint:810000300"/><StopPlaceRef ref="SP:Gries"/></PassengerStopAssignment>
    <PassengerStopAssignment id="psa:it:i" version="1" order="4"><ScheduledStopPointRef ref="IT:ITH10:ScheduledStopPoint:810001187"/><StopPlaceRef ref="SP:Innsbruck"/></PassengerStopAssignment>
    <PassengerStopAssignment id="psa:it:s" version="1" order="12"><ScheduledStopPointRef ref="IT:ITH10:ScheduledStopPoint:810000900"/><StopPlaceRef ref="SP:Steinach"/></PassengerStopAssignment>
    <PassengerStopAssignment id="psa:sta:f" version="1" order="5"><ScheduledStopPointRef ref="it:apb:ScheduledStopPoint:830003400s"/><StopPlaceRef ref="SP:Fortezza"/></PassengerStopAssignment>
    <PassengerStopAssignment id="psa:sta:b" version="1" order="6"><ScheduledStopPointRef ref="it:apb:ScheduledStopPoint:830002001s"/><StopPlaceRef ref="SP:Brennero"/></PassengerStopAssignment>
    <PassengerStopAssignment id="psa:sta:g" version="1" order="7"><ScheduledStopPointRef ref="it:apb:ScheduledStopPoint:810000300s"/><StopPlaceRef ref="SP:Gries"/></PassengerStopAssignment>
    <PassengerStopAssignment id="psa:sta:i" version="1" order="8"><ScheduledStopPointRef ref="it:apb:ScheduledStopPoint:810001187s"/><StopPlaceRef ref="SP:Innsbruck"/></PassengerStopAssignment>
    <PassengerStopAssignment id="psa:sta:s" version="1" order="16"><ScheduledStopPointRef ref="it:apb:ScheduledStopPoint:810000900s"/><StopPlaceRef ref="SP:Steinach"/></PassengerStopAssignment>
    <PassengerStopAssignment id="psa:vor:b" version="1" order="13"><ScheduledStopPointRef ref="at:vor:ScheduledStopPoint:830002001v"/><StopPlaceRef ref="SP:Brennero"/></PassengerStopAssignment>
    <PassengerStopAssignment id="psa:vor:g" version="1" order="14"><ScheduledStopPointRef ref="at:vor:ScheduledStopPoint:810000300v"/><StopPlaceRef ref="SP:Gries"/></PassengerStopAssignment>
    <PassengerStopAssignment id="psa:vor:i" version="1" order="15"><ScheduledStopPointRef ref="at:vor:ScheduledStopPoint:810001187v"/><StopPlaceRef ref="SP:Innsbruck"/></PassengerStopAssignment>
    <PassengerStopAssignment id="psa:at:b" version="1" order="9"><ScheduledStopPointRef ref="at:obb:ScheduledStopPoint:it-22021-1389-0-1:"/><StopPlaceRef ref="SP:Brennero"/><QuayRef ref="Q:Brennero"/></PassengerStopAssignment>
    <PassengerStopAssignment id="psa:at:g" version="1" order="10"><ScheduledStopPointRef ref="at:obb:ScheduledStopPoint:810000300a"/><StopPlaceRef ref="SP:Gries"/></PassengerStopAssignment>
    <PassengerStopAssignment id="psa:at:i" version="1" order="11"><ScheduledStopPointRef ref="at:obb:ScheduledStopPoint:810001187a"/><StopPlaceRef ref="SP:Innsbruck"/></PassengerStopAssignment>
    <PassengerStopAssignment id="psa:at:s" version="1" order="17"><ScheduledStopPointRef ref="at:obb:ScheduledStopPoint:810000900a"/><StopPlaceRef ref="SP:Steinach"/></PassengerStopAssignment>
    <PassengerStopAssignment id="psa:vor:f" version="1" order="18"><ScheduledStopPointRef ref="at:vor:ScheduledStopPoint:830003400v"/><StopPlaceRef ref="SP:Fortezza"/></PassengerStopAssignment>
    <PassengerStopAssignment id="psa:vor:s" version="1" order="19"><ScheduledStopPointRef ref="at:vor:ScheduledStopPoint:810000900v"/><StopPlaceRef ref="SP:Steinach"/></PassengerStopAssignment>
  </stopAssignments>
""";

    static final String CALENDAR = """
<ServiceCalendarFrame id="scf" version="1">
  <dayTypes><DayType id="dt1" version="1"/></dayTypes>
  <operatingPeriods><UicOperatingPeriod id="op1" version="1"><FromDate>2026-01-05T00:00:00</FromDate><ToDate>2026-01-05T00:00:00</ToDate><ValidDayBits>1</ValidDayBits></UicOperatingPeriod></operatingPeriods>
  <dayTypeAssignments><DayTypeAssignment id="dta1" version="1" order="1"><OperatingPeriodRef ref="op1" version="1"/><DayTypeRef ref="dt1" version="1"/></DayTypeAssignment></dayTypeAssignments>
</ServiceCalendarFrame>
""";

    static final String CALENDAR2 = """
<ServiceCalendarFrame id="scf2" version="1">
  <dayTypes><DayType id="dt2" version="1"/></dayTypes>
  <operatingPeriods><UicOperatingPeriod id="op2" version="1"><FromDate>2026-01-06T00:00:00</FromDate><ToDate>2026-01-06T00:00:00</ToDate><ValidDayBits>1</ValidDayBits></UicOperatingPeriod></operatingPeriods>
  <dayTypeAssignments><DayTypeAssignment id="dta2" version="1" order="1"><OperatingPeriodRef ref="op2" version="1"/><DayTypeRef ref="dt2" version="1"/></DayTypeAssignment></dayTypeAssignments>
</ServiceCalendarFrame>
""";

    /// Both days, for the leg that spans them.
    static final String CALENDAR_BOTH = """
<ServiceCalendarFrame id="scf3" version="1">
  <dayTypes><DayType id="dt3" version="1"/></dayTypes>
  <operatingPeriods><UicOperatingPeriod id="op3" version="1"><FromDate>2026-01-05T00:00:00</FromDate><ToDate>2026-01-06T00:00:00</ToDate><ValidDayBits>11</ValidDayBits></UicOperatingPeriod></operatingPeriods>
  <dayTypeAssignments><DayTypeAssignment id="dta3" version="1" order="1"><OperatingPeriodRef ref="op3" version="1"/><DayTypeRef ref="dt3" version="1"/></DayTypeAssignment></dayTypeAssignments>
</ServiceCalendarFrame>
""";

    /// A pattern plus a journey on it. `stops` are the ScheduledStopPoint refs and `times` the
    /// departure times, one per stop.
    static String journey(String pat, String sj, String[] stops, String[] times) {
        return journey(pat, sj, stops, times, "dt1");
    }

    static String journey(String pat, String sj, String[] stops, String[] times, String dayType) {
        StringBuilder points = new StringBuilder();
        StringBuilder passing = new StringBuilder();
        for (int i = 0; i < stops.length; i++) {
            points.append(String.format(
                    "<StopPointInJourneyPattern id=\"%s-p%d\" version=\"1\" order=\"%d\">"
                    + "<ScheduledStopPointRef ref=\"%s\" version=\"1\"/></StopPointInJourneyPattern>",
                    pat, i, i + 1, stops[i]));
            passing.append(String.format(
                    "<TimetabledPassingTime id=\"%s-t%d\" version=\"1\">"
                    + "<StopPointInJourneyPatternRef ref=\"%s-p%d\" version=\"1\" order=\"%d\"/>"
                    + "<DepartureTime>%s</DepartureTime></TimetabledPassingTime>",
                    sj, i, pat, i, i + 1, times[i]));
        }
        return String.format("""
<ServiceFrame id="sf%s" version="1">
  <journeyPatterns>
    <ServiceJourneyPattern id="%s" version="1"><pointsInSequence>%s</pointsInSequence></ServiceJourneyPattern>
  </journeyPatterns>
</ServiceFrame>
<TimetableFrame id="tf%s" version="1">
  <vehicleJourneys>
    <ServiceJourney id="%s" version="1">
      <Name>1826</Name>
      <dayTypes><DayTypeRef ref="%s" version="1"/></dayTypes>
      <ServiceJourneyPatternRef ref="%s" version="1"/>
      <passingTimes>%s</passingTimes>
    </ServiceJourney>
  </vehicleJourneys>
</TimetableFrame>
""", pat, pat, points, sj, sj, dayType, pat, passing);
    }

    /// A second journey on an already-declared pattern, so the pattern is shared. Its own train
    /// number is carried by one country only, so pass 1 prunes it and it never joins a group.
    static String journeyOnExistingPattern(String pat, String sj, String number, String[] times) {
        StringBuilder passing = new StringBuilder();
        for (int i = 0; i < times.length; i++) {
            passing.append(String.format(
                    "<TimetabledPassingTime id=\"%s-t%d\" version=\"1\">"
                    + "<StopPointInJourneyPatternRef ref=\"%s-p%d\" version=\"1\" order=\"%d\"/>"
                    + "<DepartureTime>%s</DepartureTime></TimetabledPassingTime>",
                    sj, i, pat, i, i + 1, times[i]));
        }
        return String.format("""
<TimetableFrame id="tf%s" version="1">
  <vehicleJourneys>
    <ServiceJourney id="%s" version="1">
      <Name>%s</Name>
      <dayTypes><DayTypeRef ref="dt1" version="1"/></dayTypes>
      <ServiceJourneyPatternRef ref="%s" version="1"/>
      <passingTimes>%s</passingTimes>
    </ServiceJourney>
  </vehicleJourneys>
</TimetableFrame>
""", sj, sj, number, pat, passing);
    }

    static final String IT_F = "IT:ITH10:ScheduledStopPoint:830003400";
    static final String IT_B = "IT:ITH10:ScheduledStopPoint:830002001";
    static final String IT_G = "IT:ITH10:ScheduledStopPoint:810000300";
    static final String IT_I = "IT:ITH10:ScheduledStopPoint:810001187";
    static final String IT_S = "IT:ITH10:ScheduledStopPoint:810000900";
    static final String AT_B = "at:obb:ScheduledStopPoint:it-22021-1389-0-1:";
    static final String AT_G = "at:obb:ScheduledStopPoint:810000300a";
    static final String AT_S = "at:obb:ScheduledStopPoint:810000900a";
    static final String AT_I = "at:obb:ScheduledStopPoint:810001187a";
    // A second Austrian publisher, for the cases where the leg's own country needs a parallel
    // publication with a call list of its own.
    static final String VOR_F = "at:vor:ScheduledStopPoint:830003400v";
    static final String VOR_B = "at:vor:ScheduledStopPoint:830002001v";
    static final String VOR_G = "at:vor:ScheduledStopPoint:810000300v";
    static final String VOR_S = "at:vor:ScheduledStopPoint:810000900v";
    static final String VOR_I = "at:vor:ScheduledStopPoint:810001187v";

    /// Trenitalia and ÖBB publish the train; Trenitalia runs the whole route, ÖBB only the Austrian
    /// part. Two legs, one handover, and the handover is the border station both of them serve.
    public static void testTwoPublishersTileAtTheBorder(TestStore db) throws Exception {
        db.loadNetex(STOPS);
        db.loadNetex("<ServiceFrame id=\"psa\" version=\"1\">" + ASSIGNMENTS + "</ServiceFrame>");
        db.loadNetex(CALENDAR);
        db.loadNetex(journey("IT:Pat:1", "IT:Sj:1826",
                new String[] {IT_F, IT_B, IT_G, IT_I},
                new String[] {"20:00:00", "20:30:00", "20:45:00", "21:20:00"}));
        db.loadNetex(journey("at:obb:Pat:1", "at:obb:Sj:1826",
                new String[] {AT_B, AT_G, AT_I},
                new String[] {"20:32:00", "20:47:00", "21:22:00"}));

        Result r = couple(db);
        Map<String, ServiceJourneyInterchange> links = byId(r);
        Check.equals(1, links.size(), "one junction, one link");
        ServiceJourneyInterchange x = links.values().iterator().next();
        Check.equals(IT_B, x.getFromPointRef().getRef(),
                "the feeder hands over at ITS OWN coding of the border station");
        Check.equals(AT_B, x.getToPointRef().getRef(),
                "and the distributor picks up at its own coding of the SAME station");
        Check.equals("IT:Sj:1826", x.getFromJourneyRef().getRef(), "feeder is the Italian leg");
        Check.equals("at:obb:Sj:1826", x.getToJourneyRef().getRef(), "distributor is the Austrian one");
        Check.equals(Boolean.TRUE, x.isCrossBorder(), "flagged cross-border");
        Check.equals(Boolean.TRUE, x.isStaySeated(), "and stay-seated");

        var sj = r.modifiedSj().get("IT:Sj:1826");
        Check.that(sj != null, "the Italian leg was truncated");
        Check.equals(2, sj.getPassingTimes().getTimetabledPassingTime().size(),
                "truncated to Fortezza + Brennero");
        Check.isNull(r.modifiedSj().get("at:obb:Sj:1826"),
                "the Austrian leg owns everything it publishes, so nothing cut it");
    }

    /// The full fixture: three publishers, two of them describing the same Italian section.
    ///
    /// The legs must still tile — Italy from Fortezza to Brennero, Austria from Brennero on — and
    /// the second Italian publication must not survive as a third leg.
    public static void testThreePublishersTileWithoutOverlapOrGap(TestStore db) throws Exception {
        db.loadNetex(STOPS);
        db.loadNetex("<ServiceFrame id=\"psa\" version=\"1\">" + ASSIGNMENTS + "</ServiceFrame>");
        db.loadNetex(CALENDAR);
        db.loadNetex(journey("IT:Pat:1", "IT:Sj:1826",
                new String[] {IT_F, IT_B, IT_G, IT_I},
                new String[] {"20:00:00", "20:30:00", "20:45:00", "21:20:00"}));
        db.loadNetex(journey("it:apb:Pat:1", "it:apb:Sj:1826",
                new String[] {"it:apb:ScheduledStopPoint:830003400s", "it:apb:ScheduledStopPoint:830002001s",
                        "it:apb:ScheduledStopPoint:810000300s", "it:apb:ScheduledStopPoint:810001187s"},
                new String[] {"20:01:00", "20:31:00", "20:46:00", "21:21:00"}));
        db.loadNetex(journey("at:obb:Pat:1", "at:obb:Sj:1826",
                new String[] {AT_B, AT_G, AT_I},
                new String[] {"20:32:00", "20:47:00", "21:22:00"}));

        Result r = couple(db);

        List<String> droppedIds = droppedIds(db, r);
        Check.equals(1, droppedIds.size(),
                "one of the two Italian publications is dropped as redundant, got " + droppedIds);
        Check.equals("it:apb:Sj:1826", droppedIds.get(0),
                "the loser is the one whose id sorts second on an equal stop count");

        Map<String, ServiceJourneyInterchange> links = byId(r);
        Check.equals(1, links.size(), "still ONE junction: three publishers, two legs, one handover");
        ServiceJourneyInterchange x = links.values().iterator().next();
        Check.equals("IT:Sj:1826", x.getFromJourneyRef().getRef(), "the surviving Italian leg feeds");
        Check.equals("at:obb:Sj:1826", x.getToJourneyRef().getRef(), "into the Austrian one");
        Check.equals(IT_B, x.getFromPointRef().getRef(), "handover at Brennero, Italian coding");
        Check.equals(AT_B, x.getToPointRef().getRef(), "and Austrian coding of the same station");

        Check.equals(2, r.modifiedSj().get("IT:Sj:1826").getPassingTimes()
                .getTimetabledPassingTime().size(), "the Italian leg is cut once, to its two stops");
        Check.isNull(r.modifiedSj().get("at:obb:Sj:1826"), "the Austrian leg is not cut at all");
    }

    /// A group is refused whole or not at all, and "whole" has to include the legs already cut.
    ///
    /// The tiling here is fine and the first leg cuts cleanly; the second leg's calendar variant is
    /// then uncuttable, which abandons the group. The variant is uncuttable because it lists the
    /// leg's first and last station in the other order: it shares only the border station with the
    /// Italian leg, so the direction conjunct never gets two stations to compare and cannot keep it
    /// out of the group.
    public static void testAnAbandonedGroupLeavesNothingBehind(TestStore db) throws Exception {
        db.loadNetex(STOPS);
        db.loadNetex("<ServiceFrame id=\"psa\" version=\"1\">" + ASSIGNMENTS + "</ServiceFrame>");
        db.loadNetex(CALENDAR);
        db.loadNetex(journey("IT:Pat:1", "IT:Sj:1826",
                new String[] {IT_F, IT_B, IT_S},
                new String[] {"20:00:00", "20:30:00", "20:40:00"}));
        db.loadNetex(journey("at:obb:Pat:a", "at:obb:Sj:1826a",
                new String[] {AT_B, AT_G, AT_I},
                new String[] {"20:32:00", "20:47:00", "21:22:00"}));
        db.loadNetex(journey("at:obb:Pat:b", "at:obb:Sj:1826b",
                new String[] {AT_I, AT_G, AT_B},
                new String[] {"20:20:00", "20:25:00", "20:31:00"}));

        Result r = couple(db);
        Check.equals(0, r.links().size(), "the group produced no link");
        Check.equals(0, r.modifiedSj().size(),
                "and no journey was left truncated: " + r.modifiedSj().keySet());
        Check.equals(0, r.modifiedPat().size(), "nor any pattern");
        Check.equals(0, r.droppedKeys().size(), "nor was anything dropped");
    }

    /// A pattern shared with another journey must be forked, never sliced in place — slicing it
    /// would truncate every other journey on it, and the consumer's first check is that a journey's
    /// passing-time count equals its pattern's point count, with "skip the journey" as its action.
    public static void testASharedPatternIsForkedNotSliced(TestStore db) throws Exception {
        db.loadNetex(STOPS);
        db.loadNetex("<ServiceFrame id=\"psa\" version=\"1\">" + ASSIGNMENTS + "</ServiceFrame>");
        db.loadNetex(CALENDAR);
        db.loadNetex(journey("IT:Pat:1", "IT:Sj:1826",
                new String[] {IT_F, IT_B, IT_G, IT_I},
                new String[] {"20:00:00", "20:30:00", "20:45:00", "21:20:00"}));
        // Same pattern, a number no other country carries, so it is never coupled or cut.
        db.loadNetex(journeyOnExistingPattern("IT:Pat:1", "IT:Sj:9999", "9999",
                new String[] {"08:00:00", "08:30:00", "08:45:00", "09:20:00"}));
        db.loadNetex(journey("at:obb:Pat:1", "at:obb:Sj:1826",
                new String[] {AT_B, AT_G, AT_I},
                new String[] {"20:32:00", "20:47:00", "21:22:00"}));

        Result r = couple(db);
        var cut = r.modifiedSj().get("IT:Sj:1826");
        Check.that(cut != null, "the Italian leg was truncated");
        Check.equals(2, cut.getPassingTimes().getTimetabledPassingTime().size(),
                "down to its two Italian stops");
        String forked = cut.getJourneyPatternRef().getValue().getRef();
        Check.that(forked.startsWith("IT:Pat:1:xb:"),
                "and it points at a FORKED pattern, not the shared original: " + forked);
        Check.that(r.modifiedPat().containsKey(forked), "the fork is emitted");
        Check.that(!r.modifiedPat().containsKey("IT:Pat:1"),
                "and the shared original is not rewritten");
        Check.isNull(r.modifiedSj().get("IT:Sj:9999"),
                "so the other journey on it is untouched -- which is the whole point of forking");
    }

    /// The two legs must resolve to ONE Quay at the handover, or the consumer -- which matches
    /// stay-seated transfers by physical stop -- creates no transfer.
    public static void testHandoverQuayIsShared(TestStore db) throws Exception {
        db.loadNetex(STOPS);
        db.loadNetex("<ServiceFrame id=\"psa\" version=\"1\">" + ASSIGNMENTS + "</ServiceFrame>");
        db.loadNetex(CALENDAR);
        db.loadNetex(journey("IT:Pat:1", "IT:Sj:1826",
                new String[] {IT_F, IT_B, IT_G, IT_I},
                new String[] {"20:00:00", "20:30:00", "20:45:00", "21:20:00"}));
        db.loadNetex(journey("at:obb:Pat:1", "at:obb:Sj:1826",
                new String[] {AT_B, AT_G, AT_I},
                new String[] {"20:32:00", "20:47:00", "21:22:00"}));

        Result r = couple(db);
        // Both sides already name Q:Brennero in this fixture, so nothing has to move.
        Check.equals(0, r.psas().size(),
                "both assignments already share the handover quay, so none is rewritten");
    }

    /// A publication that is neither a leg, a variant, nor fully covered must not cost the group its
    /// links. It is left alone, exactly where it was before this stage ran. Here STA serves
    /// Steinach, which neither kept leg does.
    public static void testAnUnplaceablePublicationDoesNotCostTheGroupItsLinks(TestStore db)
            throws Exception {
        db.loadNetex(STOPS);
        db.loadNetex("<ServiceFrame id=\"psa\" version=\"1\">" + ASSIGNMENTS + "</ServiceFrame>");
        db.loadNetex(CALENDAR);
        db.loadNetex(journey("IT:Pat:1", "IT:Sj:1826",
                new String[] {IT_F, IT_B, IT_G, IT_I},
                new String[] {"20:00:00", "20:30:00", "20:45:00", "21:20:00"}));
        db.loadNetex(journey("it:apb:Pat:1", "it:apb:Sj:1826",
                new String[] {"it:apb:ScheduledStopPoint:830003400s", "it:apb:ScheduledStopPoint:830002001s",
                        "it:apb:ScheduledStopPoint:810000300s", "it:apb:ScheduledStopPoint:810000900s",
                        "it:apb:ScheduledStopPoint:810001187s"},
                new String[] {"20:01:00", "20:31:00", "20:46:00", "21:00:00", "21:21:00"}));
        db.loadNetex(journey("at:obb:Pat:1", "at:obb:Sj:1826",
                new String[] {AT_B, AT_G, AT_I},
                new String[] {"20:32:00", "20:47:00", "21:22:00"}));

        Result r = couple(db);
        Check.equals(1, r.links().size(),
                "the Italian and Austrian legs still hand over at the border");
        ServiceJourneyInterchange x = r.links().get(0);
        Check.equals("IT:Sj:1826", x.getFromJourneyRef().getRef(), "Trenitalia feeds");
        Check.equals("at:obb:Sj:1826", x.getToJourneyRef().getRef(), "into OeBB");
        Check.that(!droppedIds(db, r).contains("it:apb:Sj:1826"),
                "the STA publication is not dropped -- it serves a stop nothing else does");
        Check.isNull(r.modifiedSj().get("it:apb:Sj:1826"),
                "and it is left exactly as published, which is where it was before this stage ran");
    }

    /// A matched publication keeps its home block and gives up the rest; being a second publisher is
    /// not a reason to keep the rest.
    ///
    /// STA publishes the same section as Trenitalia and is not redundant — it runs on a day
    /// Trenitalia does not — so it survives the drop. Everything it gives up here, Gries and
    /// Innsbruck, is served by the Austrian leg, so cutting it loses no service.
    public static void testAParallelPublicationIsCutNotLeftInTheOtherCountry(TestStore db)
            throws Exception {
        db.loadNetex(STOPS);
        db.loadNetex("<ServiceFrame id=\"psa\" version=\"1\">" + ASSIGNMENTS + "</ServiceFrame>");
        db.loadNetex(CALENDAR);
        db.loadNetex(CALENDAR2);
        db.loadNetex(CALENDAR_BOTH);
        db.loadNetex(journey("IT:Pat:1", "IT:Sj:1826",
                new String[] {IT_F, IT_B, IT_G, IT_I},
                new String[] {"20:00:00", "20:30:00", "20:45:00", "21:20:00"}, "dt1"));
        // Same route, same country, DIFFERENT publisher, and a day Trenitalia does not run.
        db.loadNetex(journey("it:apb:Pat:1", "it:apb:Sj:1826",
                new String[] {"it:apb:ScheduledStopPoint:830003400s", "it:apb:ScheduledStopPoint:830002001s",
                        "it:apb:ScheduledStopPoint:810000300s", "it:apb:ScheduledStopPoint:810001187s"},
                new String[] {"20:01:00", "20:31:00", "20:46:00", "21:21:00"}, "dt2"));
        db.loadNetex(journey("at:obb:Pat:1", "at:obb:Sj:1826",
                new String[] {AT_B, AT_G, AT_I},
                new String[] {"20:32:00", "20:47:00", "21:22:00"}, "dt3"));

        Result r = couple(db);
        var sta = r.modifiedSj().get("it:apb:Sj:1826");
        Check.that(sta != null, "the STA publication was truncated, not left whole");
        Check.equals(2, sta.getPassingTimes().getTimetabledPassingTime().size(),
                "down to its two ITALIAN stops -- no Austrian tail left standing");
        Check.that(!droppedIds(db, r).contains("it:apb:Sj:1826"),
                "and it is not dropped: it runs on a day the other Italian leg does not");
        // Both Italian publications hand over to the Austrian leg, each on its own day.
        List<String> feeders = new ArrayList<>();
        for (ServiceJourneyInterchange x : r.links()) feeders.add(x.getFromJourneyRef().getRef());
        java.util.Collections.sort(feeders);
        Check.equals(List.of("IT:Sj:1826", "it:apb:Sj:1826"), feeders,
                "so each keeps its through-service rather than losing it to the cut");
    }

    /// One link per calendar variant.
    ///
    /// A logical train is published once per set of dates it runs, so the Italian half of this one
    /// arrives as two journeys with identical stops and identical times that differ only in their
    /// DayType. The tiling makes one of them the leg and attaches the other as its variant, and the
    /// coupling then owes a link for each: a passenger booked on the 6th cannot be handed a link
    /// that only exists on the 5th.
    public static void testEveryCalendarVariantOfALegGetsItsOwnLink(TestStore db) throws Exception {
        db.loadNetex(STOPS);
        db.loadNetex("<ServiceFrame id=\"psa\" version=\"1\">" + ASSIGNMENTS + "</ServiceFrame>");
        db.loadNetex(CALENDAR);        // dt1: the 5th
        db.loadNetex(CALENDAR2);       // dt2: the 6th
        db.loadNetex(CALENDAR_BOTH);   // dt3: both
        String[] itStops = {IT_F, IT_B, IT_G, IT_I};
        String[] itTimes = {"20:00:00", "20:30:00", "20:45:00", "21:20:00"};
        db.loadNetex(journey("IT:Pat:1", "IT:Sj:1826", itStops, itTimes, "dt1"));
        db.loadNetex(journey("IT:Pat:2", "IT:Sj:1826b", itStops, itTimes, "dt2"));
        // The Austrian leg runs on BOTH days, so it meets each Italian publication on its own.
        db.loadNetex(journey("at:obb:Pat:1", "at:obb:Sj:1826",
                new String[] {AT_B, AT_G, AT_I},
                new String[] {"20:32:00", "20:47:00", "21:22:00"}, "dt3"));

        Result r = couple(db);
        Check.equals(2, r.links().size(),
                "one junction, but TWO links: the Italian leg and its calendar variant each get one");
        Check.equals(List.of(), droppedIds(db, r),
                "neither Italian publication is redundant — each runs on a day the other does not");

        List<String> feeders = new ArrayList<>();
        Set<String> distributors = new java.util.LinkedHashSet<>();
        for (ServiceJourneyInterchange x : r.links()) {
            feeders.add(x.getFromJourneyRef().getRef());
            distributors.add(x.getToJourneyRef().getRef());
        }
        feeders.sort(java.util.Comparator.naturalOrder());
        Check.equals(List.of("IT:Sj:1826", "IT:Sj:1826b"), feeders,
                "one link per Italian publication, not two links for the leg");
        Check.equals(Set.of("at:obb:Sj:1826"), distributors,
                "both hand over to the one Austrian leg");
        Check.equals(2, byId(r).size(), "and the two links have distinct ids");
    }

    /// A train that skips a call on some days is still that train.
    ///
    /// Here VOR publishes the Austrian section on a day ÖBB does not and skips Gries, which the ÖBB
    /// leg calls at. Everything it gives up is Italian and the Italian leg serves it, so the
    /// coverage rule is satisfied; everything it keeps is inside its own leg's run, so nothing
    /// else's territory is at stake.
    public static void testAVariantThatSkipsOneOfTheLegsCallsIsStillCutAndLinked(TestStore db)
            throws Exception {
        db.loadNetex(STOPS);
        db.loadNetex("<ServiceFrame id=\"psa\" version=\"1\">" + ASSIGNMENTS + "</ServiceFrame>");
        db.loadNetex(CALENDAR);        // dt1: the 5th
        db.loadNetex(CALENDAR2);       // dt2: the 6th
        db.loadNetex(CALENDAR_BOTH);   // dt3: both
        // The Italian leg runs both days, so it meets each Austrian publication on its own.
        db.loadNetex(journey("IT:Pat:1", "IT:Sj:1826",
                new String[] {IT_F, IT_B},
                new String[] {"20:00:00", "20:30:00"}, "dt3"));
        db.loadNetex(journey("at:obb:Pat:1", "at:obb:Sj:1826",
                new String[] {AT_B, AT_G, AT_S, AT_I},
                new String[] {"20:32:00", "20:47:00", "21:00:00", "21:22:00"}, "dt1"));
        // Same country, same corridor, a day ÖBB does not run -- and NO CALL AT GRIES.
        db.loadNetex(journey("at:vor:Pat:1", "at:vor:Sj:1826",
                new String[] {VOR_F, VOR_B, VOR_S, VOR_I},
                new String[] {"20:01:00", "20:33:00", "21:01:00", "21:23:00"}, "dt2"));

        Result r = couple(db);
        Check.equals(2, r.links().size(),
                "one junction, but TWO links: the OBB leg and the publication that skips Gries "
                + "each hand over at Brennero, on its own day");
        Set<String> distributors = new java.util.LinkedHashSet<>();
        for (ServiceJourneyInterchange x : r.links()) distributors.add(x.getToJourneyRef().getRef());
        Check.equals(Set.of("at:obb:Sj:1826", "at:vor:Sj:1826"), distributors,
                "a call the leg makes and this one does not is a different stopping pattern, "
                + "not a different train");

        var vor = r.modifiedSj().get("at:vor:Sj:1826");
        Check.that(vor != null, "and it was CUT, not left standing at full length");
        Check.equals(3, vor.getPassingTimes().getTimetabledPassingTime().size(),
                "down to its three Austrian-leg stops -- no Italian tail left behind");
        Check.that(!droppedIds(db, r).contains("at:vor:Sj:1826"),
                "it is not dropped either: it runs on a day the OBB leg does not");
    }

    /// The other half of the corridor test: the order of what the two share still has to agree.
    ///
    /// This publication reaches Innsbruck via Steinach before Gries, which is not the way the leg
    /// goes, so it is not the same working and must not be cut to the leg or linked alongside it.
    public static void testAPublicationThatDoublesBackIsNotAttachedToTheLeg(TestStore db)
            throws Exception {
        db.loadNetex(STOPS);
        db.loadNetex("<ServiceFrame id=\"psa\" version=\"1\">" + ASSIGNMENTS + "</ServiceFrame>");
        db.loadNetex(CALENDAR);
        db.loadNetex(CALENDAR2);
        db.loadNetex(CALENDAR_BOTH);
        db.loadNetex(journey("IT:Pat:1", "IT:Sj:1826",
                new String[] {IT_F, IT_B},
                new String[] {"20:00:00", "20:30:00"}, "dt3"));
        db.loadNetex(journey("at:obb:Pat:1", "at:obb:Sj:1826",
                new String[] {AT_B, AT_G, AT_S, AT_I},
                new String[] {"20:32:00", "20:47:00", "21:00:00", "21:22:00"}, "dt1"));
        // Steinach BEFORE Gries: the same stations as the leg, in the other order.
        db.loadNetex(journey("at:vor:Pat:1", "at:vor:Sj:1826",
                new String[] {VOR_F, VOR_B, VOR_S, VOR_G, VOR_I},
                new String[] {"20:01:00", "20:33:00", "20:48:00", "21:01:00", "21:23:00"}, "dt2"));

        Result r = couple(db);
        Check.equals(1, r.links().size(),
                "only the OBB leg hands over -- the publication that runs Steinach before Gries "
                + "is not on the leg's corridor");
        Check.isNull(r.modifiedSj().get("at:vor:Sj:1826"),
                "and it is left exactly as published rather than cut to a route it does not run");
    }

    public static void testASingleStopOwnerIsDroppedNotRefused(TestStore db) throws Exception {
        db.loadNetex(STOPS);
        db.loadNetex("<ServiceFrame id=\"psa\" version=\"1\">" + ASSIGNMENTS + "</ServiceFrame>");
        db.loadNetex(CALENDAR);
        db.loadNetex(journey("IT:Pat:1", "IT:Sj:1826",
                new String[] {IT_B},
                new String[] {"20:30:00"}));
        db.loadNetex(journey("at:obb:Pat:1", "at:obb:Sj:1826",
                new String[] {AT_B, AT_G, AT_I},
                new String[] {"20:32:00", "20:47:00", "21:22:00"}));

        Result r = couple(db);
        Check.equals(0, r.links().size(),
                "one leg means no junction, so no link -- but that is not a refusal");
        Check.equals(List.of("IT:Sj:1826"), droppedIds(db, r),
                "the single-stop publication is DROPPED: its only stop is the border station the "
                + "Austrian leg serves anyway");
        Check.isNull(r.modifiedSj().get("at:obb:Sj:1826"),
                "and the Austrian leg keeps everything it publishes");
    }

    /// The rule is one clause, not two: the counterpart serves every station of the block on every
    /// day the leg runs. Asking "is every station served by some leg" and "is every date covered by
    /// some leg" separately accepts a publication that no single leg replaces.
    ///
    /// Here a second Austrian publisher runs the Austrian section on the 6th, when the ÖBB leg does
    /// not; the Italian leg runs both days. Station-wise, everything it serves is served by someone.
    /// Date-wise, the 6th is covered by someone. But at Gries on the 6th, nothing runs at all if this
    /// publication is dropped — so it must not be, and the conservative outcome is that the group is
    /// refused rather than silently losing a day of Austrian service.
    public static void testAPublicationIsNotDroppedOnAnotherLegsDates(TestStore db) throws Exception {
        db.loadNetex(STOPS);
        db.loadNetex("<ServiceFrame id=\"psa\" version=\"1\">" + ASSIGNMENTS + "</ServiceFrame>");
        db.loadNetex(CALENDAR);
        db.loadNetex(CALENDAR2);
        db.loadNetex(CALENDAR_BOTH);
        db.loadNetex(journey("IT:Pat:1", "IT:Sj:1826",
                new String[] {IT_F, IT_B},
                new String[] {"20:00:00", "20:30:00"}, "dt3"));
        db.loadNetex(journey("at:obb:Pat:1", "at:obb:Sj:1826",
                new String[] {AT_B, AT_G, AT_I},
                new String[] {"20:32:00", "20:47:00", "21:22:00"}, "dt1"));
        db.loadNetex(journey("at:vor:Pat:1", "at:vor:Sj:1826",
                new String[] {"at:vor:ScheduledStopPoint:830002001v",
                        "at:vor:ScheduledStopPoint:810000300v",
                        "at:vor:ScheduledStopPoint:810001187v"},
                new String[] {"20:33:00", "20:48:00", "21:23:00"}, "dt2"));

        Result r = couple(db);
        Check.that(!droppedIds(db, r).contains("at:vor:Sj:1826"),
                "the only Austrian service on the 6th must not be dropped because the ITALIAN "
                + "leg happens to run that day");
    }

    /// A return working shares the number, the stations and the date, and differs only in direction.
    public static void testAReturnWorkingIsNotCoupled(TestStore db) throws Exception {
        db.loadNetex(STOPS);
        db.loadNetex("<ServiceFrame id=\"psa\" version=\"1\">" + ASSIGNMENTS + "</ServiceFrame>");
        db.loadNetex(CALENDAR);
        // The times are chosen so the time test cannot do this job: the two are within 20 minutes of
        // each other at every station they share -- Brennero, Gries and Innsbruck -- and the
        // Austrian leg keeps two Austrian stops, so the tiling has no reason to refuse it either.
        db.loadNetex(journey("IT:Pat:1", "IT:Sj:1826",
                new String[] {IT_F, IT_B, IT_G, IT_I},
                new String[] {"10:00:00", "10:20:00", "10:30:00", "10:40:00"}));
        db.loadNetex(journey("at:obb:Pat:1", "at:obb:Sj:1826",
                new String[] {AT_I, AT_G, AT_B},
                new String[] {"10:20:00", "10:30:00", "10:40:00"}));

        Result r = couple(db);
        Check.equals(0, r.links().size(), "a leg running the other way is not the same train");
        Check.equals(0, r.modifiedSj().size(), "and nothing is truncated for it");
    }

    /// The same two stations and the same date, six hours apart. The train number is the only thing
    /// they share, and at low values a train number carries almost no identity: 3,411 journeys in
    /// the corpus carry 80-89/294/295, many of them cable cars.
    public static void testAMorningAndAnEveningWorkingAreNotCoupled(TestStore db) throws Exception {
        db.loadNetex(STOPS);
        db.loadNetex("<ServiceFrame id=\"psa\" version=\"1\">" + ASSIGNMENTS + "</ServiceFrame>");
        db.loadNetex(CALENDAR);
        db.loadNetex(journey("IT:Pat:1", "IT:Sj:1826",
                new String[] {IT_F, IT_B, IT_G, IT_I},
                new String[] {"08:00:00", "08:30:00", "08:45:00", "09:20:00"}));
        db.loadNetex(journey("at:obb:Pat:1", "at:obb:Sj:1826",
                new String[] {AT_B, AT_G, AT_I},
                new String[] {"20:32:00", "20:47:00", "21:22:00"}));

        Result r = couple(db);
        Check.equals(0, r.links().size(), "six hours apart at every shared station is not one train");
    }

    // ------------------------------------------------------------------------------- //

    /// The dropped publications' ids. The coupling hands back source full keys, the store's own
    /// primary key, so the test resolves them the same way.
    static List<String> droppedIds(TestStore db, Result r) throws Exception {
        List<String> out = new ArrayList<>();
        try (Txn txn = db.store.roTxn()) {
            for (long fk : r.droppedKeys()) {
                Object o = db.store.loadObjectByFullKey(txn, fk);
                if (o instanceof noi.netex.model.ServiceJourney sj) out.add(sj.getId());
            }
        }
        out.sort(java.util.Comparator.naturalOrder());
        return out;
    }

    static Result couple(TestStore db) throws Exception {
        try (Txn txn = db.store.roTxn()) {
            return XbCouple.couple(db.store, txn, "[test]");
        }
    }

    static Map<String, ServiceJourneyInterchange> byId(Result r) {
        Map<String, ServiceJourneyInterchange> out = new LinkedHashMap<>();
        for (ServiceJourneyInterchange x : r.links()) out.put(x.getId(), x);
        return out;
    }
}
