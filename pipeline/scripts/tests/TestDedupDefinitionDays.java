package tests;

// Phase 2 of transformers/MentzLineVersions.java: the (definition, day) dedup.
//
// Every fixture below is the coterminous shape: two line versions whose windows both carry an
// explicit ToDate and end on the same day, the later one opening inside the earlier. The producer
// re-issues the same journey under both versions; the resolve slices each version's calendar to its
// own window and both survive, so the rider sees the departure twice on every day the windows
// share. Phase 2 gives each contested day to exactly one version.

import transformers.feedfix.DedupDefinitionDays;
import transformers.feedfix.MentzLineVersions;
import noi.netex.model.DayType;
import noi.netex.model.ServiceJourney;
import noi.netex.model.UicOperatingPeriod;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.Runner;
import toolkit.test.TestStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TestDedupDefinitionDays {

    public static void main(String[] args) {
        Runner.run(TestDedupDefinitionDays.class);
    }

    // ---------------------------------------------------------------- fixtures

    /// One pattern, two stops — the stop half of a journey definition.
    private static final String PATTERN = """
              <journeyPatterns>
                <ServiceJourneyPattern id="at:ktn:ServiceJourneyPattern:P1:" version="1">
                  <pointsInSequence>
                    <StopPointInJourneyPattern id="at:ktn:StopPointInJourneyPattern:P1-1:" version="1" order="1">
                      <ScheduledStopPointRef ref="at:ktn:ScheduledStopPoint:A:" version="any"/>
                    </StopPointInJourneyPattern>
                    <StopPointInJourneyPattern id="at:ktn:StopPointInJourneyPattern:P1-2:" version="1" order="2">
                      <ScheduledStopPointRef ref="at:ktn:ScheduledStopPoint:B:" version="any"/>
                    </StopPointInJourneyPattern>
                  </pointsInSequence>
                </ServiceJourneyPattern>
              </journeyPatterns>
            """;

    /// A journey on line version `v`, departing at `dep` — the times half.
    private static String journey(String id, String v, String dep) {
        return """
                    <ServiceJourney id="at:ktn:ServiceJourney:%s:" version="1">
                      <dayTypes><DayTypeRef ref="at:ktn:DayType:DT1:" version="any"/></dayTypes>
                      <ServiceJourneyPatternRef ref="at:ktn:ServiceJourneyPattern:P1:" version="1"/>
                      <LineRef ref="at:ktn:Line:L1:" version="%s"/>
                      <passingTimes>
                        <TimetabledPassingTime id="at:ktn:TimetabledPassingTime:%s-1:" version="any">
                          <StopPointInJourneyPatternRef ref="at:ktn:StopPointInJourneyPattern:P1-1:" version="1" order="1"/>
                          <DepartureTime>%s</DepartureTime>
                        </TimetabledPassingTime>
                        <TimetabledPassingTime id="at:ktn:TimetabledPassingTime:%s-2:" version="any">
                          <StopPointInJourneyPatternRef ref="at:ktn:StopPointInJourneyPattern:P1-2:" version="1" order="2"/>
                          <ArrivalTime>%s</ArrivalTime>
                        </TimetabledPassingTime>
                      </passingTimes>
                    </ServiceJourney>
                """.formatted(id, v, id, dep, id, dep);
    }

    /// Two coterminous line versions plus whatever journeys the test names. The source DayType DT1
    /// runs every day of 2026-01-01..2026-03-31 (90 days), so after the resolve version 1's
    /// calendar is all 90 and version 2's is the days from `v2From` to 2026-03-31.
    private static String frames(String v2From, String journeys) {
        return """
                <ServiceFrame id="sf" version="1">
                  <ValidBetween><FromDate>2025-12-14T00:00:00</FromDate><ToDate>2026-12-14T23:59:59</ToDate></ValidBetween>
                  <lines>
                    <Line id="at:ktn:Line:L1:" version="1">
                      <ValidBetween>
                        <FromDate>2026-01-01T00:00:00</FromDate><ToDate>2026-03-31T23:59:59</ToDate>
                      </ValidBetween>
                      <Name>L1</Name>
                    </Line>
                    <Line id="at:ktn:Line:L1:" version="2">
                      <ValidBetween>
                        <FromDate>%sT00:00:00</FromDate><ToDate>2026-03-31T23:59:59</ToDate>
                      </ValidBetween>
                      <Name>L1</Name>
                    </Line>
                  </lines>
                %s</ServiceFrame>
                <TimetableFrame id="tf" version="1">
                  <vehicleJourneys>
                %s  </vehicleJourneys>
                </TimetableFrame>
                <ServiceCalendarFrame id="scf" version="1">
                  <ServiceCalendar id="sc" version="1">
                    <dayTypes><DayType id="at:ktn:DayType:DT1:" version="any"/></dayTypes>
                    <dayTypeAssignments>
                      <DayTypeAssignment id="at:ktn:DayTypeAssignment:DT1::001" version="any" order="1">
                        <OperatingPeriodRef ref="at:ktn:UicOperatingPeriod:P1:" version="any"/>
                        <DayTypeRef ref="at:ktn:DayType:DT1:" version="any"/>
                      </DayTypeAssignment>
                    </dayTypeAssignments>
                    <operatingPeriods>
                      <UicOperatingPeriod id="at:ktn:UicOperatingPeriod:P1:" version="any">
                        <FromDate>2026-01-01T00:00:00</FromDate>
                        <ToDate>2026-03-31T23:59:59</ToDate>
                        <ValidDayBits>%s</ValidDayBits>
                      </UicOperatingPeriod>
                    </operatingPeriods>
                  </ServiceCalendar>
                </ServiceCalendarFrame>
                """.formatted(v2From, PATTERN, journeys, "1".repeat(90));
    }

    // ---------------------------------------------------------------- readers

    private record Seen(Map<String, ServiceJourney> journeys,
                        Map<String, UicOperatingPeriod> periods,
                        List<String> dayTypeIds) {}

    private static Seen read(TestStore target) throws Exception {
        Map<String, ServiceJourney> js = new LinkedHashMap<>();
        Map<String, UicOperatingPeriod> ps = new LinkedHashMap<>();
        List<String> dts = new ArrayList<>();
        try (Txn txn = target.store.roTxn()) {
            for (Object o : target.store.iterOnlyObjects(txn, ServiceJourney.class)) {
                ServiceJourney j = (ServiceJourney) o;
                js.put(j.getId(), j);
            }
            for (Object o : target.store.iterOnlyObjects(txn, UicOperatingPeriod.class)) {
                UicOperatingPeriod p = (UicOperatingPeriod) o;
                ps.put(p.getId(), p);
            }
            for (Object o : target.store.iterOnlyObjects(txn, DayType.class)) {
                dts.add(((DayType) o).getId());
            }
        }
        return new Seen(js, ps, dts);
    }

    /// The single DayType a journey now points at.
    private static String dayTypeOf(ServiceJourney j) {
        Check.equals(1, j.getDayTypes().getDayTypeRef().size(),
                j.getId() + " carries exactly one DayTypeRef");
        return j.getDayTypes().getDayTypeRef().get(0).getValue().getRef();
    }

    private static String bits(int ones) { return "1".repeat(ones); }

    // ---------------------------------------------------------------- the pass

    /// Two versions of one line, the same journey under each, 59 shared days. Afterwards every one
    /// of the 90 days is served exactly once and none has been lost.
    public static void testTheSameJourneyUnderTwoVersionsRunsOncePerDay(TestStore db) throws Exception {
        db.loadNetex(frames("2026-02-01",
                journey("SJ1", "1", "07:53:00") + journey("SJ2", "2", "07:53:00")));
        TestStore target = db.runDbToDb(MentzLineVersions::apply);
        Seen s = read(target);

        DedupDefinitionDays.Result r = DedupDefinitionDays.lastResult();
        Check.equals(1L, r.definitions(), "the two journeys are ONE definition");
        Check.equals(149L, r.instancesBefore(), "90 (v1) + 59 (v2) (definition, day) instances");
        Check.equals(90L, r.instancesAfter(), "one instance per served day");
        Check.equals(59L, r.duplicatesBefore(), "the 59 shared days were served twice");
        Check.equals(0L, r.duplicatesAfter(), "and are served once now");
        Check.equals(59L, r.duplicatesRemoved(), "100 % of the duplication");
        Check.equals(90L, r.coverageBefore(), "union coverage before");
        Check.equals(90L, r.coverageAfter(), "union coverage after — UNCHANGED");
        Check.equals(0L, r.deleted(), "a (definition, day) dedup deletes no service");
        Check.equals(1L, r.journeysThinned(), "exactly one journey's calendar thinned");
        Check.equals(0L, r.journeysEmptied(), "and none was left with nothing");

        // The later opener keeps the contested days, so SJ2 is untouched and still points at
        // the calendar the resolve gave it.
        Check.equals("at:ktn:Line:L1::at:ktn:DayType:DT1::2_0",
                dayTypeOf(s.journeys().get("at:ktn:ServiceJourney:SJ2:")),
                "SJ2 (version 2, the later opener) keeps the resolve's own calendar");
        Check.equals(bits(59),
                s.periods().get("at:ktn:Line:L1::at:ktn:DayType:DT1::UicOperatingPeriod:2_0").getValidDayBits(),
                "and still runs all 59 days of its window");

        // SJ1 keeps January — the days version 2 does not cover — on a private calendar.
        Check.equals("at:ktn:ServiceJourney:SJ1::DayType:dedup",
                dayTypeOf(s.journeys().get("at:ktn:ServiceJourney:SJ1:")),
                "SJ1 is re-pointed at its own deduped calendar");
        UicOperatingPeriod p = s.periods().get("at:ktn:ServiceJourney:SJ1::UicOperatingPeriod:dedup");
        Check.that(p != null, "the private period exists");
        Check.equals("2026-01-01T00:00:00", p.getFromDate().toXmlFormat(), "it starts on 1 January");
        Check.equals("2026-01-31T23:59:59", p.getToDate().toXmlFormat(), "and ends on 31 January");
        Check.equals(bits(31), p.getValidDayBits(), "31 days: exactly what version 2 does not cover");

        Check.equals(2, s.journeys().size(), "NOTHING was deleted — both journeys are still here");
    }

    /// Two journeys with different times are two definitions: nothing is contested, so nothing is
    /// touched. The pass must not thin a calendar just because two versions of a line overlap.
    public static void testDifferentTimesAreDifferentJourneysAndAreLeftAlone(TestStore db) throws Exception {
        db.loadNetex(frames("2026-02-01",
                journey("SJ1", "1", "07:53:00") + journey("SJ2", "2", "08:53:00")));
        TestStore target = db.runDbToDb(MentzLineVersions::apply);
        Seen s = read(target);

        DedupDefinitionDays.Result r = DedupDefinitionDays.lastResult();
        Check.equals(2L, r.definitions(), "two definitions, not one");
        Check.equals(0L, r.duplicatesBefore(), "nothing is served twice");
        Check.equals(0L, r.journeysThinned(), "so no calendar is thinned");
        Check.equals(0L, r.deleted(), "and nothing is deleted");
        Check.equals("at:ktn:Line:L1::at:ktn:DayType:DT1::1_0",
                dayTypeOf(s.journeys().get("at:ktn:ServiceJourney:SJ1:")), "SJ1 untouched");
        Check.equals("at:ktn:Line:L1::at:ktn:DayType:DT1::2_0",
                dayTypeOf(s.journeys().get("at:ktn:ServiceJourney:SJ2:")), "SJ2 untouched");
    }

    /// Duplication within one line version is out of scope: a separate population with a different
    /// cause. Both of version 2's identical journeys keep every one of their days.
    public static void testDuplicationWithinOneVersionIsNotTouched(TestStore db) throws Exception {
        db.loadNetex(frames("2026-02-01", journey("SJ1", "1", "07:53:00")
                + journey("SJ2", "2", "07:53:00") + journey("SJ3", "2", "07:53:00")));
        TestStore target = db.runDbToDb(MentzLineVersions::apply);
        Seen s = read(target);

        DedupDefinitionDays.Result r = DedupDefinitionDays.lastResult();
        Check.equals(149L, r.instancesBefore(),
                "version 2's day set counts ONCE however many journeys carry it");
        Check.equals(59L, r.duplicatesRemoved(), "only the cross-version 59 are removed");
        Check.equals(1L, r.journeysThinned(), "only SJ1 is thinned");
        Check.equals(0L, r.deleted(), "nothing deleted");
        for (String id : List.of("SJ2", "SJ3")) {
            Check.equals("at:ktn:Line:L1::at:ktn:DayType:DT1::2_0",
                    dayTypeOf(s.journeys().get("at:ktn:ServiceJourney:" + id + ":")),
                    id + " still runs — the within-version pair is preserved");
        }
    }

    /// When the later version covers the earlier one entirely, the earlier journey keeps no day at
    /// all. It runs on no date, so it is removed rather than left with an all-zero ValidDayBits,
    /// and coverage is unchanged.
    public static void testAJourneyLeftWithNoDayIsRemoved(TestStore db) throws Exception {
        db.loadNetex(frames("2026-01-01",
                journey("SJ1", "1", "07:53:00") + journey("SJ2", "2", "07:53:00")));
        TestStore target = db.runDbToDb(MentzLineVersions::apply);
        Seen s = read(target);

        DedupDefinitionDays.Result r = DedupDefinitionDays.lastResult();
        Check.equals(180L, r.instancesBefore(), "both versions cover all 90 days");
        Check.equals(90L, r.instancesAfter(), "one of them keeps each");
        Check.equals(90L, r.coverageAfter(), "coverage unchanged");
        Check.equals(0L, r.deleted(), "and so no SERVICE is deleted — every day is still served");
        Check.equals(1L, r.journeysEmptied(), "one journey is left with no day");

        Check.equals(1, s.journeys().size(), "and it is gone: only SJ2 is left");
        Check.that(s.journeys().get("at:ktn:ServiceJourney:SJ1:") == null, "SJ1 was removed");
        Check.that(s.periods().get("at:ktn:ServiceJourney:SJ1::UicOperatingPeriod:dedup") == null,
                "no all-zero period was minted for it");
        Check.that(!s.dayTypeIds().contains("at:ktn:ServiceJourney:SJ1::DayType:dedup"),
                "and no DayType with an empty schedule: " + s.dayTypeIds());
        Check.equals("at:ktn:Line:L1::at:ktn:DayType:DT1::2_0",
                dayTypeOf(s.journeys().get("at:ktn:ServiceJourney:SJ2:")),
                "SJ2, the tie-break winner on an equal FromDate, keeps everything");
    }

    // ---------------------------------------------------------------- the guard

    /// A sabotage clears one day from the retained set of SJ1, which no other journey covers:
    /// exactly one (definition, day) instance of real service disappears. The whole transform must
    /// abort and name the number — neither the export A/B nor the OTP gate can see a loss of this
    /// shape.
    public static void testGuardFiresOnADeliberateOverDeletion(TestStore db) throws Exception {
        db.loadNetex(frames("2026-02-01",
                journey("SJ1", "1", "07:53:00") + journey("SJ2", "2", "07:53:00")));
        String message = null;
        DedupDefinitionDays.setSabotage((journeyId, days) -> {
            // SJ1 retains 1..31 January; drop 1 January, which version 2 does not cover.
            if (journeyId.contains(":SJ1:") && !days.isEmpty()) days.clear(days.nextSetBit(0));
        });
        try {
            db.runDbToDb(MentzLineVersions::apply);
        } catch (Throwable t) {
            message = rootMessage(t);
        } finally {
            DedupDefinitionDays.setSabotage(null);
        }
        Check.that(message != null, "the run ABORTED — a guard that never fires is not a guard");
        Check.that(message.contains("GUARD"), "and it was the guard that stopped it: " + message);
        Check.that(message.contains("DELETED 1"),
                "naming the exact number of (definition, day) instances lost: " + message);
    }

    /// The branch a totals-only guard would miss. This sabotage takes one day away from SJ1 and
    /// gives it a day the definition never ran on, so the totals net out exactly — deleted() is 0 —
    /// and only the per-definition set comparison can see that the calendar moved.
    public static void testGuardFiresWhenCoverageMovesButTheTotalsNetOut(TestStore db) throws Exception {
        db.loadNetex(frames("2026-02-01",
                journey("SJ1", "1", "07:53:00") + journey("SJ2", "2", "07:53:00")));
        String message = null;
        DedupDefinitionDays.setSabotage((journeyId, days) -> {
            if (!journeyId.contains(":SJ1:") || days.isEmpty()) return;
            int first = days.nextSetBit(0);
            days.clear(first);        // lose 1 January …
            days.set(first + 200);    // … and gain a July day outside the line's whole window
        });
        try {
            db.runDbToDb(MentzLineVersions::apply);
        } catch (Throwable t) {
            message = rootMessage(t);
        } finally {
            DedupDefinitionDays.setSabotage(null);
        }
        Check.that(message != null, "the run ABORTED even though the day COUNT did not change");
        Check.that(message.contains("MOVED"), "and the per-definition guard is what saw it: " + message);
        Check.that(message.contains("DELETED 0"),
                "the totals really did net out, which is the point: " + message);
    }

    /// The pass reports a coverage and a duplicate count that are both non-zero, and its three
    /// counters reconcile against each other.
    public static void testGuardCountsSomething(TestStore db) throws Exception {
        db.loadNetex(frames("2026-02-01",
                journey("SJ1", "1", "07:53:00") + journey("SJ2", "2", "07:53:00")));
        db.runDbToDb(MentzLineVersions::apply);
        DedupDefinitionDays.Result r = DedupDefinitionDays.lastResult();
        Check.equals(2L, r.journeysConsidered(), "both journeys were comparable");
        Check.equals(0L, r.journeysSkipped(), "none was skipped for want of times or a pattern");
        Check.that(r.coverageBefore() > 0, "the pass measured a coverage");
        Check.that(r.duplicatesBefore() > 0, "and found duplication to remove");
        Check.equals(1L, r.lines(), "on one line");
        Check.equals(r.duplicatesRemoved(), r.duplicatesBefore() - r.duplicatesAfter(),
                "removed == duplicates before - duplicates after");
        Check.equals(r.instancesBefore() - r.instancesAfter(), r.duplicatesRemoved(),
                "and == the drop in (definition, day) instances, because coverage did not move");
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return String.valueOf(c.getMessage());
    }
}
