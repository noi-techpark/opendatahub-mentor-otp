package tests;

// Operator-based dedup of republished journeys: a journey is dropped when its own operator/
// responsibility set — or its LINE's operator, resolved via the direct LineRef or the
// SJP->Route->Line chain — matches a drop rule; everything else passes through untouched.

import fix.DropForeignJourneys;
import noi.netex.model.JourneyMeeting;
import noi.netex.model.ServiceJourney;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.Runner;
import toolkit.test.TestStore;

import java.util.Set;
import java.util.TreeSet;

public class TestDropForeignJourneys {

    private static final String FRAMES = """
        <ServiceFrame id="sf" version="1">
          <lines>
            <Line id="ch:2:Line:81_VVV.B.201" version="any"><Name>201</Name>
              <OperatorRef ref="ch:1:Operator:81_VVV"/></Line>
            <Line id="ch:2:Line:805.B.12" version="any"><Name>12</Name>
              <OperatorRef ref="ch:1:Operator:805"/></Line>
            <Line id="it:apb:Line:218273:" version="any"><Name>273</Name>
              <OperatorRef ref="it:apb:Operator:070:"/></Line>
          </lines>
          <routes>
            <Route id="it:apb:Route:R273:" version="any"><LineRef ref="it:apb:Line:218273:" version="any"/></Route>
          </routes>
          <journeyPatterns>
            <ServiceJourneyPattern id="it:apb:ServiceJourneyPattern:P273:" version="1">
              <RouteRef ref="it:apb:Route:R273:" version="any"/>
            </ServiceJourneyPattern>
          </journeyPatterns>
        </ServiceFrame>
        <TimetableFrame id="tf" version="1"><vehicleJourneys>
          <ServiceJourney id="sj-vvv-import" version="1">
            <LineRef ref="ch:2:Line:81_VVV.B.201" version="any"/>
          </ServiceJourney>
          <ServiceJourney id="sj-liem-native" version="1">
            <LineRef ref="ch:2:Line:805.B.12" version="any"/>
          </ServiceJourney>
          <ServiceJourney id="sj-liem-republished" version="1" responsibilitySetRef="at:vvv:ResponsibilitySet:VA_LIEm:">
            <LineRef ref="ch:2:Line:805.B.12" version="any"/>
          </ServiceJourney>
          <ServiceJourney id="sj-sta-tlb" version="1">
            <ServiceJourneyPatternRef ref="it:apb:ServiceJourneyPattern:P273:" version="1"/>
          </ServiceJourney>
        </vehicleJourneys>
        <journeyMeetings>
          <JourneyMeeting id="jm-both-kept" version="any">
            <FromJourneyRef ref="sj-liem-native" version="1"/>
            <ToJourneyRef ref="sj-liem-native" version="1"/>
          </JourneyMeeting>
          <JourneyMeeting id="jm-from-dropped" version="any">
            <FromJourneyRef ref="sj-vvv-import" version="1"/>
            <ToJourneyRef ref="sj-liem-native" version="1"/>
          </JourneyMeeting>
          <JourneyMeeting id="jm-to-dropped" version="any">
            <FromJourneyRef ref="sj-liem-native" version="1"/>
            <ToJourneyRef ref="sj-sta-tlb" version="1"/>
          </JourneyMeeting>
          <JourneyMeeting id="jm-unknown-journey" version="any">
            <FromJourneyRef ref="sj-liem-native" version="1"/>
            <ToJourneyRef ref="sj-never-existed" version="1"/>
          </JourneyMeeting>
        </journeyMeetings>
        </TimetableFrame>
        """;

    public static void main(String[] args) {
        Runner.run(TestDropForeignJourneys.class);
    }

    public static void testRepublishedJourneysDroppedNativeKept(TestStore db) throws Exception {
        db.loadNetex(FRAMES);
        TestStore target = db.runDbToDb(DropForeignJourneys::apply);
        Set<String> kept = new TreeSet<>();
        try (Txn txn = target.store.roTxn()) {
            for (Object o : target.store.iterOnlyObjects(txn, ServiceJourney.class)) {
                kept.add(((ServiceJourney) o).getId());
            }
        }

        // dropped: the 81_VVV import (line operator via direct LineRef), the VA_LIEm
        // republication (journey responsibility set), and the STA Tiroler-Linien copy
        // (line operator via SJP->Route->Line). Kept: LIEmobil's native publication.
        Check.equals(Set.of("sj-liem-native"), kept, "kept journeys");
    }

    /// A meeting outlives its journey into the graph -- EpipToDb turns it into the
    /// ServiceJourneyInterchange OTP reads -- so a dropped journey has to take its meetings with
    /// it. A meeting naming a journey that was NEVER in the feed is a different defect and stays,
    /// so that this pass cannot quietly absorb it.
    public static void testMeetingsOnDroppedJourneysGoWithThem(TestStore db) throws Exception {
        db.loadNetex(FRAMES);
        TestStore target = db.runDbToDb(DropForeignJourneys::apply);
        Set<String> kept = new TreeSet<>();
        try (Txn txn = target.store.roTxn()) {
            for (Object o : target.store.iterOnlyObjects(txn, JourneyMeeting.class)) {
                kept.add(((JourneyMeeting) o).getId());
            }
        }

        Check.equals(Set.of("jm-both-kept", "jm-unknown-journey"), kept, "kept meetings");
    }
}
