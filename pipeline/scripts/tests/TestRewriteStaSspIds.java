package tests;

// STA SSP id rewrite: ScheduledStopPoints move to the SIRI id
// format, every reference follows, and the stale original ids do not survive the db-to-db copy.

import fix.RewriteStaSspIds;
import noi.netex.model.ScheduledStopPoint;
import noi.netex.model.ServiceJourneyPattern;
import noi.netex.model.ServiceLink;
import noi.netex.model.StopPointInJourneyPattern;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.Runner;
import toolkit.test.TestStore;

import java.util.HashSet;
import java.util.Set;

public class TestRewriteStaSspIds {

    private static final String FRAMES = """
        <ServiceFrame id="sf" version="1">
          <scheduledStopPoints>
            <ScheduledStopPoint id="IT:ITH1:ScheduledStopPoint:it-22021-7010-51-32073:" version="any">
              <Name>Bozen Bahnhof</Name>
            </ScheduledStopPoint>
            <ScheduledStopPoint id="IT:ITH1:ScheduledStopPoint:other-stop:" version="any">
              <Name>Untouched</Name>
            </ScheduledStopPoint>
          </scheduledStopPoints>
          <serviceLinks>
            <ServiceLink id="IT:ITH1:ServiceLink:L1:" version="any">
              <Distance>100</Distance>
              <FromPointRef ref="IT:ITH1:ScheduledStopPoint:it-22021-7010-51-32073:" version="any"/>
              <ToPointRef ref="IT:ITH1:ScheduledStopPoint:other-stop:" version="any"/>
            </ServiceLink>
          </serviceLinks>
          <journeyPatterns>
            <ServiceJourneyPattern id="IT:ITH1:ServiceJourneyPattern:P1:" version="1">
              <pointsInSequence>
                <StopPointInJourneyPattern id="IT:ITH1:StopPointInJourneyPattern:P1-1:" version="1" order="1">
                  <ScheduledStopPointRef ref="IT:ITH1:ScheduledStopPoint:it-22021-7010-51-32073:" version="any"/>
                </StopPointInJourneyPattern>
              </pointsInSequence>
            </ServiceJourneyPattern>
          </journeyPatterns>
        </ServiceFrame>
        """;

    public static void main(String[] args) {
        Runner.run(TestRewriteStaSspIds.class);
    }

    public static void testSspRenamedRefsFollowAndOriginalDropped(TestStore db) throws Exception {
        db.loadNetex(FRAMES);

        TestStore target = db.runDbToDb(RewriteStaSspIds::apply);

        Set<String> sspIds = new HashSet<>();
        ServiceJourneyPattern sjp = null;
        try (Txn txn = target.store.roTxn()) {
            for (Object o : target.store.iterOnlyObjects(txn, ScheduledStopPoint.class)) {
                sspIds.add(((ScheduledStopPoint) o).getId());
            }
            for (Object o : target.store.iterOnlyObjects(txn, ServiceJourneyPattern.class)) {
                sjp = (ServiceJourneyPattern) o;
                break;
            }
        }

        Check.equals(
                Set.of("IT:ITH10:ScheduledStopPoint:7010:51:32073", "IT:ITH1:ScheduledStopPoint:other-stop:"),
                sspIds, "ScheduledStopPoint ids after rewrite");
        StopPointInJourneyPattern point = (StopPointInJourneyPattern) sjp.getPointsInSequence()
                .getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern().get(0);
        Check.equals("IT:ITH10:ScheduledStopPoint:7010:51:32073",
                point.getScheduledStopPointRef().getValue().getRef(), "SJP point ref follows the rename");
    }

    /// A ServiceLink's From/ToPointRef are ScheduledStopPointRefStructures reached through the base
    /// type rather than a concrete `...Ref` element, and they name the same hop key the link wiring
    /// matches a pattern on. Left behind by the rename, that join would find nothing.
    public static void testServiceLinkPointRefsFollowTheRename(TestStore db) throws Exception {
        db.loadNetex(FRAMES);

        TestStore target = db.runDbToDb(RewriteStaSspIds::apply);

        ServiceLink link = null;
        try (Txn txn = target.store.roTxn()) {
            for (Object o : target.store.iterOnlyObjects(txn, ServiceLink.class)) {
                link = (ServiceLink) o;
                break;
            }
        }
        Check.equals("IT:ITH10:ScheduledStopPoint:7010:51:32073",
                link.getFromPointRef().getRef(), "ServiceLink FromPointRef follows the rename");
        Check.equals("IT:ITH1:ScheduledStopPoint:other-stop:",
                link.getToPointRef().getRef(), "a ref the pattern does not match is left alone");
    }
}
