package tests;

// Mentz line versions: a journey on a multi-version line gets a per-version composite DayType
// whose operating days are sliced from the source DayType's UicOperatingPeriod (ValidDayBits).
// The source DayTypeAssignment + UicOperatingPeriod arrive first-class (the loader treats
// <ServiceCalendar> as a transparent container), so the composite period is a UicOperatingPeriod
// with real ValidDayBits, not a day-less OperatingPeriod fallback.

import transformers.feedfix.MentzLineVersions;
import noi.netex.model.DayTypeAssignment;
import noi.netex.model.EntityStructure;
import noi.netex.model.JourneyMeeting;
import noi.netex.model.Line;
import noi.netex.model.OperatingPeriod;
import noi.netex.model.Route;
import noi.netex.model.ServiceJourney;
import noi.netex.model.ServiceJourneyPattern;
import noi.netex.model.UicOperatingPeriod;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.Runner;
import toolkit.test.TestStore;
import toolkit.test.Xml;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public class TestResolveMentzLineVersions {

    public static void main(String[] args) {
        Runner.run(TestResolveMentzLineVersions.class);
    }

    // A two-version line (Mentz writes journey variants as new Line *versions*): v1 from
    // 2026-01-01, v2 from 2026-02-01. The single journey (on v1's route) references DayType DT1,
    // whose UicOperatingPeriod P1 spans 2026-01-01..2026-03-31 with all days active.
    static final String FRAMES = """
            <ServiceFrame id="sf" version="1">
              <ValidBetween><FromDate>2025-12-14T00:00:00</FromDate><ToDate>2026-12-14T23:59:59</ToDate></ValidBetween>
              <lines>
                <Line id="it:apb:Line:L1:" version="1">
                  <ValidBetween><FromDate>2026-01-01T00:00:00</FromDate></ValidBetween>
                  <Name>L1</Name>
                </Line>
                <Line id="it:apb:Line:L1:" version="2">
                  <ValidBetween><FromDate>2026-02-01T00:00:00</FromDate></ValidBetween>
                  <Name>L1</Name>
                </Line>
              </lines>
              <routes>
                <Route id="it:apb:Route:R1:" version="any">
                  <LineRef ref="it:apb:Line:L1:" version="1"/>
                </Route>
              </routes>
              <journeyPatterns>
                <ServiceJourneyPattern id="it:apb:ServiceJourneyPattern:SJP1:" version="1">
                  <RouteRef ref="it:apb:Route:R1:" version="any"/>
                </ServiceJourneyPattern>
              </journeyPatterns>
            </ServiceFrame>
            <TimetableFrame id="tf" version="1">
              <vehicleJourneys>
                <ServiceJourney id="it:apb:ServiceJourney:SJ1:" version="1">
                  <dayTypes><DayTypeRef ref="it:apb:DayType:DT1:" version="any"/></dayTypes>
                  <ServiceJourneyPatternRef ref="it:apb:ServiceJourneyPattern:SJP1:" version="1"/>
                </ServiceJourney>
              </vehicleJourneys>
            </TimetableFrame>
            <ServiceCalendarFrame id="scf" version="1">
              <ServiceCalendar id="sc" version="1">
                <dayTypes>
                  <DayType id="it:apb:DayType:DT1:" version="any"/>
                </dayTypes>
                <dayTypeAssignments>
                  <DayTypeAssignment id="it:apb:DayTypeAssignment:DT1::001" version="any" order="1">
                    <OperatingPeriodRef ref="it:apb:UicOperatingPeriod:P1:" version="any"/>
                    <DayTypeRef ref="it:apb:DayType:DT1:" version="any"/>
                  </DayTypeAssignment>
                </dayTypeAssignments>
                <operatingPeriods>
                  <UicOperatingPeriod id="it:apb:UicOperatingPeriod:P1:" version="any">
                    <FromDate>2026-01-01T00:00:00</FromDate>
                    <ToDate>2026-03-31T23:59:59</ToDate>
                    <ValidDayBits>%s</ValidDayBits>
                  </UicOperatingPeriod>
                </operatingPeriods>
              </ServiceCalendar>
            </ServiceCalendarFrame>
            """.formatted("1".repeat(90));

    // The version-scoped (composite) period carries ValidDayBits sliced from the source period:
    // v1's window 01-01..01-31 -> the first 31 days.
    static final String EXPECTED_PERIOD = """
            <UicOperatingPeriod xmlns="http://www.netex.org.uk/netex" xmlns:gml="http://www.opengis.net/gml/3.2" id="it:apb:Line:L1::it:apb:DayType:DT1::UicOperatingPeriod:1_0" version="1">
              <FromDate>2026-01-01T00:00:00</FromDate>
              <ToDate>2026-01-31T23:59:59</ToDate>
              <ValidDayBits>1111111111111111111111111111111</ValidDayBits>
            </UicOperatingPeriod>""";

    static final String EXPECTED_ASSIGNMENT = """
            <DayTypeAssignment xmlns="http://www.netex.org.uk/netex" xmlns:gml="http://www.opengis.net/gml/3.2" id="it:apb:Line:L1::it:apb:DayType:DT1::DayTypeAssignment:1_0" version="1">
              <UicOperatingPeriodRef version="1" ref="it:apb:Line:L1::it:apb:DayType:DT1::UicOperatingPeriod:1_0"/>
              <DayTypeRef version="1" ref="it:apb:Line:L1::it:apb:DayType:DT1::1_0"/>
            </DayTypeAssignment>""";

    static final String EXPECTED_JOURNEY = """
            <ServiceJourney xmlns="http://www.netex.org.uk/netex" xmlns:gml="http://www.opengis.net/gml/3.2" id="it:apb:ServiceJourney:SJ1:" version="1">
              <dayTypes>
                <DayTypeRef version="1" ref="it:apb:Line:L1::it:apb:DayType:DT1::1_0"/>
              </dayTypes>
              <ServiceJourneyPatternRef version="1" ref="it:apb:ServiceJourneyPattern:SJP1:"/>
            </ServiceJourney>""";

    // Verbund shape (VOR/SVV/VVT/...): the export ships NO Route objects at all -- the journey
    // carries the line association DIRECTLY as a versioned LineRef. Same two-version line, same
    // calendar.
    static final String FRAMES_DIRECT = """
            <ServiceFrame id="sf" version="1">
              <ValidBetween><FromDate>2025-12-14T00:00:00</FromDate><ToDate>2026-12-14T23:59:59</ToDate></ValidBetween>
              <lines>
                <Line id="at:svv:Line:L1:" version="1">
                  <ValidBetween><FromDate>2026-01-01T00:00:00</FromDate></ValidBetween>
                  <Name>L1</Name>
                </Line>
                <Line id="at:svv:Line:L1:" version="2">
                  <ValidBetween><FromDate>2026-02-01T00:00:00</FromDate></ValidBetween>
                  <Name>L1</Name>
                </Line>
              </lines>
            </ServiceFrame>
            <TimetableFrame id="tf" version="1">
              <vehicleJourneys>
                <ServiceJourney id="at:svv:ServiceJourney:SJ1:" version="1">
                  <LineRef ref="at:svv:Line:L1:" version="1"/>
                  <dayTypes><DayTypeRef ref="at:svv:DayType:DT1:" version="any"/></dayTypes>
                </ServiceJourney>
              </vehicleJourneys>
            </TimetableFrame>
            <ServiceCalendarFrame id="scf" version="1">
              <ServiceCalendar id="sc" version="1">
                <dayTypes>
                  <DayType id="at:svv:DayType:DT1:" version="any"/>
                </dayTypes>
                <dayTypeAssignments>
                  <DayTypeAssignment id="at:svv:DayTypeAssignment:DT1::001" version="any" order="1">
                    <OperatingPeriodRef ref="at:svv:UicOperatingPeriod:P1:" version="any"/>
                    <DayTypeRef ref="at:svv:DayType:DT1:" version="any"/>
                  </DayTypeAssignment>
                </dayTypeAssignments>
                <operatingPeriods>
                  <UicOperatingPeriod id="at:svv:UicOperatingPeriod:P1:" version="any">
                    <FromDate>2026-01-01T00:00:00</FromDate>
                    <ToDate>2026-03-31T23:59:59</ToDate>
                    <ValidDayBits>%s</ValidDayBits>
                  </UicOperatingPeriod>
                </operatingPeriods>
              </ServiceCalendar>
            </ServiceCalendarFrame>
            """.formatted("1".repeat(90));

    static final String EXPECTED_PERIOD_DIRECT = """
            <UicOperatingPeriod xmlns="http://www.netex.org.uk/netex" xmlns:gml="http://www.opengis.net/gml/3.2" id="at:svv:Line:L1::at:svv:DayType:DT1::UicOperatingPeriod:1_0" version="1">
              <FromDate>2026-01-01T00:00:00</FromDate>
              <ToDate>2026-01-31T23:59:59</ToDate>
              <ValidDayBits>1111111111111111111111111111111</ValidDayBits>
            </UicOperatingPeriod>""";

    static final String EXPECTED_JOURNEY_DIRECT = """
            <ServiceJourney xmlns="http://www.netex.org.uk/netex" xmlns:gml="http://www.opengis.net/gml/3.2" id="at:svv:ServiceJourney:SJ1:" version="1">
              <dayTypes>
                <DayTypeRef version="1" ref="at:svv:Line:L1::at:svv:DayType:DT1::1_0"/>
              </dayTypes>
              <LineRef version="1" ref="at:svv:Line:L1:"/>
            </ServiceJourney>""";

    /// \_composite: the synthesised (line-scoped) objects only, id-sorted.
    static List<Object> composite(List<? extends EntityStructure> objs) {
        List<EntityStructure> found = new ArrayList<>();
        for (EntityStructure o : objs) {
            if (o.getId() != null && o.getId().contains(":Line:")) found.add(o);
        }
        found.sort(Comparator.comparing(EntityStructure::getId));
        return new ArrayList<>(found);
    }

    public static void testCompositeDaytypeInheritsOperatingDaysFromSourcePeriod(TestStore db) throws Exception {
        db.loadNetex(FRAMES);

        TestStore target = db.runDbToDb(MentzLineVersions::apply);

        List<UicOperatingPeriod> periods = new ArrayList<>();
        List<DayTypeAssignment> assignments = new ArrayList<>();
        List<OperatingPeriod> operatingPeriods = new ArrayList<>();
        List<ServiceJourney> journey = new ArrayList<>();
        try (Txn txn = target.store.roTxn()) {
            for (Object o : target.store.iterOnlyObjects(txn, UicOperatingPeriod.class)) periods.add((UicOperatingPeriod) o);
            for (Object o : target.store.iterOnlyObjects(txn, DayTypeAssignment.class)) assignments.add((DayTypeAssignment) o);
            for (Object o : target.store.iterOnlyObjects(txn, OperatingPeriod.class)) operatingPeriods.add((OperatingPeriod) o);
            for (Object o : target.store.iterOnlyObjects(txn, ServiceJourney.class)) {
                ServiceJourney j = (ServiceJourney) o;
                if ("it:apb:ServiceJourney:SJ1:".equals(j.getId())) journey.add(j);
            }
        }

        Xml.assertXmlEqual(Xml.toXmlAll(composite(periods)), EXPECTED_PERIOD);
        Xml.assertXmlEqual(Xml.toXmlAll(composite(assignments)), EXPECTED_ASSIGNMENT);
        Xml.assertXmlEqual(Xml.toXmlAll(journey), EXPECTED_JOURNEY);
        Check.equals("", Xml.toXmlAll(composite(operatingPeriods)), "no composite OperatingPeriod");
    }

    /// Verbund exports carry no Routes: the journey's own versioned LineRef is the association.
    /// It must get the same per-version composite calendar treatment.
    public static void testRouteLessJourneyWithDirectLineRefIsResolved(TestStore db) throws Exception {
        db.loadNetex(FRAMES_DIRECT);

        TestStore target = db.runDbToDb(MentzLineVersions::apply);

        List<UicOperatingPeriod> periods = new ArrayList<>();
        List<ServiceJourney> journey = new ArrayList<>();
        try (Txn txn = target.store.roTxn()) {
            for (Object o : target.store.iterOnlyObjects(txn, UicOperatingPeriod.class)) periods.add((UicOperatingPeriod) o);
            for (Object o : target.store.iterOnlyObjects(txn, ServiceJourney.class)) {
                ServiceJourney j = (ServiceJourney) o;
                if ("at:svv:ServiceJourney:SJ1:".equals(j.getId())) journey.add(j);
            }
        }

        Xml.assertXmlEqual(Xml.toXmlAll(composite(periods)), EXPECTED_PERIOD_DIRECT);
        Xml.assertXmlEqual(Xml.toXmlAll(journey), EXPECTED_JOURNEY_DIRECT);
    }

    // ---------------------------------------------------------------- the frame end

    /// The frame ToDate in [#FRAMES], and the composite period the DEFAULT version (v2, which
    /// carries no ToDate) is given when the run ends there.
    private static final String FRAME_TO = "2026-12-14T23:59:59";

    /// Run [#FRAMES] with its frame validity rewritten and its Route pointed at version 2, and
    /// return every synthesised period.
    ///
    /// The Route must point at v2 for the frame end to be observable: in [#FRAMES] both versions
    /// are ToDate-less, so v1's window is closed by the next default's FromDate and only the last
    /// default reaches the frame end.
    private static List<UicOperatingPeriod> periodsWithFrameTo(TestStore db, String frameTo)
            throws Exception {
        db.loadNetex(FRAMES.replace(FRAME_TO, frameTo)
                .replace("<LineRef ref=\"it:apb:Line:L1:\" version=\"1\"/>",
                         "<LineRef ref=\"it:apb:Line:L1:\" version=\"2\"/>"));
        TestStore target = db.runDbToDb(MentzLineVersions::apply);
        List<UicOperatingPeriod> periods = new ArrayList<>();
        try (Txn txn = target.store.roTxn()) {
            for (Object o : target.store.iterOnlyObjects(txn, UicOperatingPeriod.class)) {
                periods.add((UicOperatingPeriod) o);
            }
        }
        return periods;
    }

    /// The id of v2's composite period — the DEFAULT version, whose window has no ToDate of its
    /// own and so ends wherever the feed's frame does.
    private static final String V2_PERIOD =
            "it:apb:Line:L1::it:apb:DayType:DT1::UicOperatingPeriod:2_0";

    private static UicOperatingPeriod byId(List<UicOperatingPeriod> periods, String id) {
        for (UicOperatingPeriod p : periods) if (id.equals(p.getId())) return p;
        return null;
    }

    /// A ToDate-less version's window ends where the feed's frame ends: move the frame end and the
    /// synthesised calendar moves with it.
    ///
    /// Both dates below sit inside the source period (2026-01-01..2026-03-31), so the clip is the
    /// frame's doing and not the source period's.
    public static void testTheDefaultVersionsWindowEndsWhereTheFEEDSaysItDoes(TestStore db) throws Exception {
        UicOperatingPeriod wide = byId(periodsWithFrameTo(db, "2026-03-31T23:59:59"), V2_PERIOD);
        Check.that(wide != null, "v2 got a composite period");
        // 2026-02-01..2026-03-31 = 59 days, all active in the source.
        Check.equals(59, wide.getValidDayBits().length(), "v2 runs to the frame end: " + wide.getValidDayBits());

        UicOperatingPeriod narrow = byId(periodsWithFrameTo(db, "2026-02-10T23:59:59"), V2_PERIOD);
        Check.that(narrow != null, "v2 got a composite period with the earlier frame end too");
        // 2026-02-01..2026-02-10 = 10 days.
        Check.equals(10, narrow.getValidDayBits().length(),
                "and moving the frame end moved it: " + narrow.getValidDayBits());
    }

    /// A store with no frame validity at all cannot say when its calendar ends, so the run refuses
    /// rather than assuming a date.
    public static void testAFeedWithNoFrameValidityIsRefused(TestStore db) throws Exception {
        String message = refusalFrom(db, FRAMES.replace(
                "<ValidBetween><FromDate>2025-12-14T00:00:00</FromDate><ToDate>"
                        + FRAME_TO + "</ToDate></ValidBetween>", ""));
        Check.that(message.contains("frame end is unknown"),
                "the run refused rather than inventing an end date: " + message);
    }

    /// The rollover shape: a line version that opens at or after the frame end resolves to no
    /// window at all. Here v2 opens 2026-02-01, after a frame that ends in January, and the guard
    /// stops the run.
    public static void testAVersionOpeningPastTheFrameEndIsRefused(TestStore db) throws Exception {
        String message = refusalFrom(db, FRAMES.replace(FRAME_TO, "2026-01-15T23:59:59"));
        Check.that(message.contains("GUARD"), "the rollover guard stopped the run: " + message);
        Check.that(message.contains("open at or after"), "naming the cause: " + message);
    }

    /// A source period that does not meet the line version's window means the journey runs on no
    /// day of it. The calendar is refused rather than emitted all-zero, and the journey is removed.
    ///
    /// Both line versions are moved past P1, which stays 2026-01-01..2026-03-31. v1 then runs
    /// 2026-05-01..2026-05-31 — the next default's FromDate closes it — and P1 has long ended.
    public static void testADisjointSourcePeriodDropsTheJourneyAndEmitsNoCalendar(TestStore db) throws Exception {
        db.loadNetex(disjoint(FRAMES));

        TestStore target = db.runDbToDb(MentzLineVersions::apply);

        List<UicOperatingPeriod> periods = new ArrayList<>();
        List<OperatingPeriod> dayless = new ArrayList<>();
        List<String> journeys = new ArrayList<>();
        try (Txn txn = target.store.roTxn()) {
            for (Object o : target.store.iterOnlyObjects(txn, UicOperatingPeriod.class)) {
                periods.add((UicOperatingPeriod) o);
            }
            for (Object o : target.store.iterOnlyObjects(txn, OperatingPeriod.class)) {
                dayless.add((OperatingPeriod) o);
            }
            for (Object o : target.store.iterOnlyObjects(txn, ServiceJourney.class)) {
                journeys.add(((ServiceJourney) o).getId());
            }
        }

        Check.that(byId(periods, "it:apb:Line:L1::it:apb:DayType:DT1::UicOperatingPeriod:1_0") == null,
                "the disjoint case gets NO composite period, not an all-zero one");
        Check.equals(List.of(), journeys, "and the journey it would have been for is gone");
        Check.equals("", Xml.toXmlAll(composite(dayless)),
                "no day-less OperatingPeriod is emitted any more");
    }

    /// What the drop leaves behind goes with it. The journey was the pattern's only reason to
    /// exist, the pattern was the route's, and the route was v1's.
    ///
    /// v2 of the line stays: nothing referenced it to begin with, and the peel only removes what
    /// had a referrer.
    public static void testTheDropPeelsWhatItLeavesUnreferenced(TestStore db) throws Exception {
        db.loadNetex(disjoint(FRAMES));

        TestStore target = db.runDbToDb(MentzLineVersions::apply);

        List<String> patterns = new ArrayList<>();
        List<String> routes = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        try (Txn txn = target.store.roTxn()) {
            for (Object o : target.store.iterOnlyObjects(txn, ServiceJourneyPattern.class)) {
                patterns.add(((ServiceJourneyPattern) o).getId());
            }
            for (Object o : target.store.iterOnlyObjects(txn, Route.class)) {
                routes.add(((Route) o).getId());
            }
            for (Object o : target.store.iterOnlyObjects(txn, Line.class)) {
                lines.add(((Line) o).getVersion());
            }
        }

        Check.equals(List.of(), patterns, "the pattern the dropped journey was the last user of");
        Check.equals(List.of(), routes, "and the route that pattern was the last user of");
        Check.equals(List.of("2"), lines,
                "v1 went with its route; v2, which nothing ever referenced, stays");
    }

    /// Both line versions moved past P1 (2026-01-01..2026-03-31), so v1's window
    /// 2026-05-01..2026-05-31 cannot meet it.
    private static String disjoint(String frames) {
        return frames
                .replace("<ValidBetween><FromDate>2026-01-01T00:00:00</FromDate></ValidBetween>",
                         "<ValidBetween><FromDate>2026-05-01T00:00:00</FromDate></ValidBetween>")
                .replace("<ValidBetween><FromDate>2026-02-01T00:00:00</FromDate></ValidBetween>",
                         "<ValidBetween><FromDate>2026-06-01T00:00:00</FromDate></ValidBetween>");
    }

    /// The journey keeps the DayTypes that do resolve to days. Only a journey with nothing left at
    /// all is removed, so a second, disjoint DayType costs it its ref and nothing else — an
    /// all-zero calendar contributes no day to the union either way.
    public static void testAJourneyKeepsTheDayTypesThatDoResolve(TestStore db) throws Exception {
        // DT2's period runs in June, outside v1's 2026-01-01..2026-01-31 window; DT1's meets it.
        db.loadNetex(FRAMES
                .replace("<dayTypes><DayTypeRef ref=\"it:apb:DayType:DT1:\" version=\"any\"/></dayTypes>",
                        "<dayTypes><DayTypeRef ref=\"it:apb:DayType:DT1:\" version=\"any\"/>"
                                + "<DayTypeRef ref=\"it:apb:DayType:DT2:\" version=\"any\"/></dayTypes>")
                .replace("<DayType id=\"it:apb:DayType:DT1:\" version=\"any\"/>",
                        "<DayType id=\"it:apb:DayType:DT1:\" version=\"any\"/>"
                                + "<DayType id=\"it:apb:DayType:DT2:\" version=\"any\"/>")
                .replace("</dayTypeAssignments>", """
                          <DayTypeAssignment id="it:apb:DayTypeAssignment:DT2::001" version="any" order="1">
                            <OperatingPeriodRef ref="it:apb:UicOperatingPeriod:P2:" version="any"/>
                            <DayTypeRef ref="it:apb:DayType:DT2:" version="any"/>
                          </DayTypeAssignment>
                        </dayTypeAssignments>""")
                .replace("</operatingPeriods>", """
                          <UicOperatingPeriod id="it:apb:UicOperatingPeriod:P2:" version="any">
                            <FromDate>2026-06-01T00:00:00</FromDate>
                            <ToDate>2026-06-30T23:59:59</ToDate>
                            <ValidDayBits>%s</ValidDayBits>
                          </UicOperatingPeriod>
                        </operatingPeriods>""".formatted("1".repeat(30))));

        TestStore target = db.runDbToDb(MentzLineVersions::apply);

        List<UicOperatingPeriod> periods = new ArrayList<>();
        List<ServiceJourney> journeys = new ArrayList<>();
        try (Txn txn = target.store.roTxn()) {
            for (Object o : target.store.iterOnlyObjects(txn, UicOperatingPeriod.class)) {
                periods.add((UicOperatingPeriod) o);
            }
            for (Object o : target.store.iterOnlyObjects(txn, ServiceJourney.class)) {
                journeys.add((ServiceJourney) o);
            }
        }

        Check.equals(1, journeys.size(), "the journey survives — one of its DayTypes has days");
        Xml.assertXmlEqual(Xml.toXmlAll(journeys), EXPECTED_JOURNEY);
        Check.that(byId(periods, "it:apb:Line:L1::it:apb:DayType:DT1::UicOperatingPeriod:1_0") != null,
                "DT1's composite period is there");
        Check.that(byId(periods, "it:apb:Line:L1::it:apb:DayType:DT2::UicOperatingPeriod:1_0") == null,
                "DT2's is not — it would carry no day");
    }

    /// A JourneyMeeting on a dropped journey goes with it, on the same rule as
    /// `DropForeignJourneys` — including its exception: a meeting naming a journey the feed never
    /// shipped is a different defect and stays visible.
    public static void testAMeetingOnADroppedJourneyIsRemoved(TestStore db) throws Exception {
        db.loadNetex(disjoint(FRAMES).replace("</vehicleJourneys>", """
                </vehicleJourneys>
                <journeyMeetings>
                  <JourneyMeeting id="jm-on-dropped" version="any">
                    <FromJourneyRef ref="it:apb:ServiceJourney:SJ1:" version="1"/>
                    <ToJourneyRef ref="it:apb:ServiceJourney:SJ9:" version="1"/>
                  </JourneyMeeting>
                  <JourneyMeeting id="jm-unknown-journey" version="any">
                    <FromJourneyRef ref="it:apb:ServiceJourney:SJ8:" version="1"/>
                    <ToJourneyRef ref="it:apb:ServiceJourney:SJ9:" version="1"/>
                  </JourneyMeeting>
                </journeyMeetings>"""));

        TestStore target = db.runDbToDb(MentzLineVersions::apply);

        Set<String> kept = new TreeSet<>();
        try (Txn txn = target.store.roTxn()) {
            for (Object o : target.store.iterOnlyObjects(txn, JourneyMeeting.class)) {
                kept.add(((JourneyMeeting) o).getId());
            }
        }
        Check.equals(Set.of("jm-unknown-journey"), kept, "kept meetings");
    }

    // ---------------------------------------------------------------- shape refusals
    //
    // Each feeds one malformed shape and asserts the run stops. None of these shapes occurs in
    // either Mentz feed today.

    /// B1: NeTEx allows many assignments per DayType; this pass keeps one.
    public static void testASecondDayTypeAssignmentForOneDayTypeIsRefused(TestStore db) throws Exception {
        String message = refusalFrom(db, FRAMES.replace("</dayTypeAssignments>", """
                  <DayTypeAssignment id="it:apb:DayTypeAssignment:DT1::002" version="any" order="2">
                    <OperatingPeriodRef ref="it:apb:UicOperatingPeriod:P1:" version="any"/>
                    <DayTypeRef ref="it:apb:DayType:DT1:" version="any"/>
                  </DayTypeAssignment>
                </dayTypeAssignments>"""));
        Check.that(message.contains("more than one DayTypeAssignment"),
                "the second assignment was refused, not silently dropped: " + message);
    }

    /// B2: an OperatingDayRef names a single day, which this pass cannot read as a period.
    public static void testAnOperatingDayRefIsRefused(TestStore db) throws Exception {
        String message = refusalFrom(db, FRAMES.replace(
                "<OperatingPeriodRef ref=\"it:apb:UicOperatingPeriod:P1:\" version=\"any\"/>",
                "<OperatingDayRef ref=\"it:apb:OperatingDay:OD1:\" version=\"any\"/>"));
        Check.that(message.contains("resolves to no UicOperatingPeriod"),
                "a period ref this pass cannot read stopped the run: " + message);
    }

    /// B3: isAvailable=false is an EXCLUSION. Nothing in this pass subtracts days.
    public static void testAnUnavailableAssignmentIsRefused(TestStore db) throws Exception {
        // isAvailable is LAST in the DayTypeAssignment content model, so it goes immediately
        // before the closing tag or JAXB will not bind it.
        String message = refusalFrom(db, FRAMES.replace("</DayTypeAssignment>",
                "<isAvailable>false</isAvailable></DayTypeAssignment>"));
        Check.that(message.contains("isAvailable=false"),
                "an exclusion was refused rather than read as service: " + message);
    }

    /// B3: the bare-<Date> arm yields no period ref, so the assignment leaves its DayType with no
    /// calendar.
    public static void testABareDateAssignmentIsRefused(TestStore db) throws Exception {
        String message = refusalFrom(db, FRAMES.replace(
                "<OperatingPeriodRef ref=\"it:apb:UicOperatingPeriod:P1:\" version=\"any\"/>",
                "<Date>2026-01-05T00:00:00</Date>"));
        Check.that(message.contains("neither an operating period nor an operating day"),
                "an assignment with only a date was refused: " + message);
    }

    /// B4: a DayType a journey names but the feed never assigns.
    public static void testAJourneyDayTypeWithNoAssignmentIsRefused(TestStore db) throws Exception {
        // Point the JOURNEY at a DayType nothing assigns; the <dayTypes> wrapper makes this the
        // one occurrence of the ref that is not the assignment's own.
        String message = refusalFrom(db, FRAMES.replace(
                "<dayTypes><DayTypeRef ref=\"it:apb:DayType:DT1:\" version=\"any\"/></dayTypes>",
                "<dayTypes><DayTypeRef ref=\"it:apb:DayType:DT9:\" version=\"any\"/></dayTypes>"));
        Check.that(message.contains("has no DayTypeAssignment"),
                "a DayType with no calendar at all stopped the run: " + message);
    }

    /// B5: the one lossy line in the pass right-pads a short bit string with '0', which reads as
    /// "does not run". The source here declares 90 days and carries 30 bits.
    public static void testValidDayBitsShorterThanTheirWindowAreRefused(TestStore db) throws Exception {
        String message = refusalFrom(db,
                FRAMES.replace("<ValidDayBits>" + "1".repeat(90) + "</ValidDayBits>",
                        "<ValidDayBits>" + "1".repeat(30) + "</ValidDayBits>"));
        Check.that(message.contains("ValidDayBits"),
                "a period that cannot cover its own window was refused: " + message);
    }

    /// B6: the pass takes the FIRST dated ValidBetween per Line and drops the rest.
    public static void testASecondDatedValidBetweenOnALineIsRefused(TestStore db) throws Exception {
        String message = refusalFrom(db, FRAMES.replace(
                "<ValidBetween><FromDate>2026-01-01T00:00:00</FromDate></ValidBetween>",
                "<ValidBetween><FromDate>2026-01-01T00:00:00</FromDate></ValidBetween>"
                        + "<ValidBetween><FromDate>2026-05-01T00:00:00</FromDate></ValidBetween>"));
        Check.that(message.contains("more than one dated ValidBetween"),
                "the dropped second window stopped the run: " + message);
    }

    /// Load `frames`, run the pass, and return the message it refused with. Fails the test if the
    /// run succeeds.
    private static String refusalFrom(TestStore db, String frames) throws Exception {
        db.loadNetex(frames);
        String message = null;
        try {
            db.runDbToDb(MentzLineVersions::apply);
        } catch (Throwable t) {
            Throwable c = t;
            while (c.getCause() != null) c = c.getCause();
            message = String.valueOf(c.getMessage());
        }
        Check.that(message != null, "the run ABORTED rather than silently accepting the shape");
        return message;
    }
}
