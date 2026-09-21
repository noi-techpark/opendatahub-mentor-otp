package tests;

// The synthesised JourneyMeetings: one train published as two ServiceJourneys because it was split
// at a border, and nothing in the feed says so.
//
// The fixture is train 13478's shape, read out of the ÖBB feed and reduced to two stops a side: an
// Austrian half ending at St.Margrethen and a Swiss half starting there, one train number, one
// DayType, zero dwell, two different Lines — which is why the exporter writes no meeting — and the
// two halves naming the station with different ScheduledStopPoints, which is why the join is on the
// StopPlace and never on the stop point.
//
// Every other journey pair here exists to be refused, one per conjunct:
//
//   turnback   the vehicle leaves the way it came — a real continuation, not one train
//   calendar   the two never run on the same date
//   wait       an hour standing at the platform is a connection, not a through service
//   published  the feed already joins this pair, and a second copy would be a duplicate id
//   midnight   the pre-EPIP after-midnight form (24:05) against the next operating day
//
// The day-offset rewrite is EpipToDb phase D and has not run against this fixture, so a night
// train's arrival is written an hour past the one xs:time admits and the halves' operating days
// differ by one.

import fix.StitchSplitJourneys;
import noi.netex.model.JourneyMeeting;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.Runner;
import toolkit.test.TestStore;
import transformers.feedfix.SplitJourneys;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class TestSplitJourneys {

    public static void main(String[] args) {
        Runner.run(TestSplitJourneys.class);
    }

    // ------------------------------------------------------------------------------------- //
    // The fixture
    // ------------------------------------------------------------------------------------- //

    /// One two-stop journey and the pattern it runs on. The two are generated together because a
    /// journey's stops are only readable through its pattern's points, which is the join
    /// [transformers.xb.XbScan#stopRefs] makes.
    private static String journey(String id, String line, String num, String dayType,
            String fromSsp, String departure, String toSsp, String arrival) {
        return """
            <ServiceJourneyPattern id="at:obb:ServiceJourneyPattern:%1$s:" version="1">
              <pointsInSequence>
                <StopPointInJourneyPattern id="at:obb:StopPointInJourneyPattern:%1$s-1:" version="1" order="1">
                  <ScheduledStopPointRef ref="%5$s" version="any"/>
                </StopPointInJourneyPattern>
                <StopPointInJourneyPattern id="at:obb:StopPointInJourneyPattern:%1$s-2:" version="1" order="2">
                  <ScheduledStopPointRef ref="%7$s" version="any"/>
                </StopPointInJourneyPattern>
              </pointsInSequence>
            </ServiceJourneyPattern>
            <ServiceJourney id="at:obb:ServiceJourney:%1$s:" version="1">
              <dayTypes><DayTypeRef ref="%4$s" version="any"/></dayTypes>
              <ServiceJourneyPatternRef ref="at:obb:ServiceJourneyPattern:%1$s:" version="1"/>
              <LineRef ref="%2$s" version="1"/>
              <trainNumbers><TrainNumberRef ref="%3$s" version="any"/></trainNumbers>
              <passingTimes>
                <TimetabledPassingTime version="any">
                  <StopPointInJourneyPatternRef ref="at:obb:StopPointInJourneyPattern:%1$s-1:" version="1"/>
                  <DepartureTime>%6$s</DepartureTime>
                </TimetabledPassingTime>
                <TimetabledPassingTime version="any">
                  <StopPointInJourneyPatternRef ref="at:obb:StopPointInJourneyPattern:%1$s-2:" version="1"/>
                  <ArrivalTime>%8$s</ArrivalTime>
                </TimetabledPassingTime>
              </passingTimes>
            </ServiceJourney>
            """.formatted(id, line, num, dayType, fromSsp, departure, toSsp, arrival);
    }

    private static final String LINZ = "at:obb:ScheduledStopPoint:linz:";
    /// St.Margrethen under two codings, as the ÖBB feed writes it: the arriving half names it one
    /// way and the departing half the other, and only their StopPlace says they are one place.
    private static final String SG_ARRIVE = "ch:23016:20309-0-Gen";
    private static final String SG_DEPART = "at:obb:ScheduledStopPoint:ch-23016-20309-90-1:";
    private static final String ZRH = "at:obb:ScheduledStopPoint:zrh:";
    private static final String P = "at:obb:ScheduledStopPoint:p:";

    private static final String DT1 = "at:obb:DayType:65710_6:";
    private static final String DT_NEXT = "at:obb:DayType:next:";   // DT1 shifted one day later
    private static final String DT_OTHER = "at:obb:DayType:other:"; // no date in common with DT1
    private static final String NUM = "at:obb:TrainNumber:13478:";
    private static final String NUM2 = "at:obb:TrainNumber:99:";


    /// Every journey of the fixture. The patterns and the journeys are generated together and then
    /// split into the two frames they belong in.
    private static final String[] JOURNEYS = {
        // The 13478 shape: the two halves of one train, on two Lines, meeting at St.Margrethen.
        journey("A", "at:obb:Line:10A11:", NUM, DT1, LINZ, "06:56:00", SG_ARRIVE, "11:45:00"),
        journey("B", "at:obb:Line:10CH1:", NUM, DT1, SG_DEPART, "11:45:00", ZRH, "13:28:00"),
        // A turnback: same vehicle, back the way it came.
        journey("C", "at:obb:Line:10A11:", NUM2, DT1, P, "08:00:00", SG_ARRIVE, "09:00:00"),
        journey("D", "at:obb:Line:10A11:", NUM2, DT1, SG_DEPART, "09:05:00", P, "10:00:00"),
        // Two halves that never run on the same date.
        journey("E", "at:obb:Line:10A11:", NUM2, DT1, LINZ, "13:00:00", SG_ARRIVE, "14:00:00"),
        journey("F", "at:obb:Line:10CH1:", NUM2, DT_OTHER, SG_DEPART, "14:05:00", ZRH, "15:00:00"),
        // An hour on the platform: a connection, not a through service.
        journey("G", "at:obb:Line:10A11:", NUM2, DT1, LINZ, "15:00:00", SG_ARRIVE, "16:00:00"),
        journey("H", "at:obb:Line:10CH1:", NUM2, DT1, SG_DEPART, "17:00:00", ZRH, "18:00:00"),
        // A pair the feed already joins itself.
        journey("I", "at:obb:Line:10A11:", NUM2, DT1, LINZ, "17:00:00", SG_ARRIVE, "18:00:00"),
        journey("J", "at:obb:Line:10CH1:", NUM2, DT1, SG_DEPART, "18:05:00", ZRH, "19:00:00"),
        // After midnight: the arrival is written in the form xs:time does not admit, and the
        // departing half is dated the next operating day.
        journey("K", "at:obb:Line:10A11:", NUM2, DT1, LINZ, "20:00:00", SG_ARRIVE, "24:05:00"),
        journey("L", "at:obb:Line:10CH1:", NUM2, DT_NEXT, SG_DEPART, "00:10:00", ZRH, "01:30:00"),
    };

    private static final String FRAMES = ("""
        <SiteFrame id="site" version="1"><stopPlaces>
          <StopPlace id="at:obb:StopPlace:linz" version="1"><Name>Linz Hbf</Name></StopPlace>
          <StopPlace id="ch:23016:20309" version="1"><Name>St.Margrethen Bahnhof</Name></StopPlace>
          <StopPlace id="at:obb:StopPlace:zrh" version="1"><Name>Zuerich HB</Name></StopPlace>
          <StopPlace id="at:obb:StopPlace:p" version="1"><Name>Turnback end</Name></StopPlace>
        </stopPlaces></SiteFrame>
        <ServiceFrame id="sf" version="1">
          <stopAssignments>
            <PassengerStopAssignment id="psa-linz" version="1" order="1"><ScheduledStopPointRef ref="%1$s"/><StopPlaceRef ref="at:obb:StopPlace:linz"/></PassengerStopAssignment>
            <PassengerStopAssignment id="psa-sg-a" version="1" order="1"><ScheduledStopPointRef ref="%2$s"/><StopPlaceRef ref="ch:23016:20309"/></PassengerStopAssignment>
            <PassengerStopAssignment id="psa-sg-b" version="1" order="1"><ScheduledStopPointRef ref="%3$s"/><StopPlaceRef ref="ch:23016:20309"/></PassengerStopAssignment>
            <PassengerStopAssignment id="psa-zrh" version="1" order="1"><ScheduledStopPointRef ref="%4$s"/><StopPlaceRef ref="at:obb:StopPlace:zrh"/></PassengerStopAssignment>
            <PassengerStopAssignment id="psa-p" version="1" order="1"><ScheduledStopPointRef ref="%5$s"/><StopPlaceRef ref="at:obb:StopPlace:p"/></PassengerStopAssignment>
          </stopAssignments>
          <journeyPatterns>
        %6$s
          </journeyPatterns>
        </ServiceFrame>
        <TimetableFrame id="tf" version="1">
          <vehicleJourneys>
        %7$s
          </vehicleJourneys>
          <trainNumbers>
            <TrainNumber id="%11$s" version="any"><ForAdvertisement>13478</ForAdvertisement></TrainNumber>
            <TrainNumber id="%12$s" version="any"><ForAdvertisement>99</ForAdvertisement></TrainNumber>
          </trainNumbers>
          <journeyMeetings>
            <JourneyMeeting id="at:obb:JourneyMeeting:published:" version="any">
              <AtStopPointRef ref="%2$s" version="any"/>
              <FromJourneyRef ref="at:obb:ServiceJourney:I:" version="1"/>
              <ToJourneyRef ref="at:obb:ServiceJourney:J:" version="1"/>
            </JourneyMeeting>
          </journeyMeetings>
        </TimetableFrame>
        <ServiceCalendarFrame id="scf" version="1"><ServiceCalendar id="sc" version="1">
          <dayTypes>
            <DayType id="%8$s" version="any"/>
            <DayType id="%9$s" version="any"/>
            <DayType id="%10$s" version="any"/>
          </dayTypes>
          <dayTypeAssignments>
            <DayTypeAssignment id="dta-1" version="any" order="1"><OperatingPeriodRef ref="at:obb:UicOperatingPeriod:P1:" version="any"/><DayTypeRef ref="%8$s" version="any"/></DayTypeAssignment>
            <DayTypeAssignment id="dta-2" version="any" order="1"><OperatingPeriodRef ref="at:obb:UicOperatingPeriod:PNEXT:" version="any"/><DayTypeRef ref="%9$s" version="any"/></DayTypeAssignment>
            <DayTypeAssignment id="dta-3" version="any" order="1"><OperatingPeriodRef ref="at:obb:UicOperatingPeriod:POTHER:" version="any"/><DayTypeRef ref="%10$s" version="any"/></DayTypeAssignment>
          </dayTypeAssignments>
          <operatingPeriods>
            <UicOperatingPeriod id="at:obb:UicOperatingPeriod:P1:" version="any">
              <FromDate>2026-01-01T00:00:00</FromDate><ValidDayBits>1111111111</ValidDayBits>
            </UicOperatingPeriod>
            <UicOperatingPeriod id="at:obb:UicOperatingPeriod:PNEXT:" version="any">
              <FromDate>2026-01-02T00:00:00</FromDate><ValidDayBits>1111111111</ValidDayBits>
            </UicOperatingPeriod>
            <UicOperatingPeriod id="at:obb:UicOperatingPeriod:POTHER:" version="any">
              <FromDate>2026-06-01T00:00:00</FromDate><ValidDayBits>1111111111</ValidDayBits>
            </UicOperatingPeriod>
          </operatingPeriods>
        </ServiceCalendar></ServiceCalendarFrame>
        """).formatted(LINZ, SG_ARRIVE, SG_DEPART, ZRH, P, patterns(), journeys(), DT1, DT_NEXT,
                DT_OTHER, NUM, NUM2);

    private static String patterns() {
        return part(0);
    }

    private static String journeys() {
        return part(1);
    }

    /// Half of each generated block: 0 is the pattern, 1 is the journey.
    private static String part(int which) {
        StringBuilder b = new StringBuilder();
        for (String block : JOURNEYS) {
            int at = block.indexOf("<ServiceJourney id=");
            b.append(which == 0 ? block.substring(0, at) : block.substring(at));
        }
        return b.toString();
    }

    // ------------------------------------------------------------------------------------- //

    /// One meeting, for the one pair that is a split train — and nothing for any of the five
    /// shapes that are not.
    public static void testOnlyTheSplitTrainIsStitched(TestStore db) throws Exception {
        db.loadNetex(FRAMES);
        TestStore target = db.runDbToDb(StitchSplitJourneys::apply);

        Map<String, JourneyMeeting> meetings = new LinkedHashMap<>();
        int rows = 0;
        try (Txn txn = target.store.roTxn()) {
            for (Object o : target.store.iterOnlyObjects(txn, JourneyMeeting.class)) {
                JourneyMeeting jm = (JourneyMeeting) o;
                rows++;
                meetings.put(jm.getFromJourneyRef().getRef() + " -> " + jm.getToJourneyRef().getRef(),
                        jm);
            }
        }
        // Counted as well as keyed: a second copy of the pair the feed publishes would collapse
        // into the map and be invisible.
        Check.equals(3, rows, "three meetings in the store, no duplicate of the published pair");
        Check.equals(
                Set.of("at:obb:ServiceJourney:A: -> at:obb:ServiceJourney:B:",
                        "at:obb:ServiceJourney:I: -> at:obb:ServiceJourney:J:",
                        "at:obb:ServiceJourney:K: -> at:obb:ServiceJourney:L:"),
                new TreeSet<>(meetings.keySet()),
                "the split train and the night train are stitched; the pair the feed already "
                        + "publishes is kept once and not duplicated; turnback, disjoint calendars "
                        + "and the hour-long wait are all refused");

        // The anchor is the arriving half's own stop point. EpipToDb resolves the other side's own
        // point at the same StopPlace, which is why this emits a meeting rather than an
        // interchange: the two halves do not agree on how St.Margrethen is coded.
        JourneyMeeting stitched = meetings.get(
                "at:obb:ServiceJourney:A: -> at:obb:ServiceJourney:B:");
        Check.equals(SG_ARRIVE, stitched.getAtStopPointRef().getRef(),
                "anchored on the arriving half's own coding of St.Margrethen");
        Check.equals("ServiceJourney", stitched.getFromJourneyRef().getNameOfRefClass(),
                "the ref names the class the id names, or the store files it under Journey");
        Check.equals("1", stitched.getToJourneyRef().getVersion(),
                "the ref carries the journey's own version");
    }

    /// The whole-day component of the wait is removed, or a night train reads as arriving a day
    /// after it departs. The times are the pre-EPIP forms, before the day-offset rewrite.
    public static void testTheWaitIsMeasuredOnOneTimeline() {
        Check.equals(300, SplitJourneys.alignedWait(11 * 3600, 11 * 3600 + 300),
                "an ordinary five-minute wait");
        Check.equals(300, SplitJourneys.alignedWait(24 * 3600 + 300, 24 * 3600 + 600),
                "both halves in the hour-24 form the feed writes before the rewrite");
        Check.equals(300, SplitJourneys.alignedWait(24 * 3600 + 300, 600),
                "the arriving half at 24:05, the departing half at 00:10 the NEXT operating day");
        Check.equals(-300, SplitJourneys.alignedWait(600, 24 * 3600 + 300),
                "and backwards is negative, not a day and a bit");
    }
}
