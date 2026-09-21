package tests;

// transformers.feedfix.StaServiceLinks: the join from a pattern's hops to the ServiceLinks that
// span them. Covers the ref the export depends on carrying a version, the tie-break between two
// links claiming one hop, and the three shapes that must be left alone -- a hop no link spans, a
// point that already names one, and a pattern nothing matched.

import fix.WireStaServiceLinks;
import noi.netex.model.PointInLinkSequence_VersionedChildStructure;
import noi.netex.model.ServiceJourneyPattern;
import noi.netex.model.StopPointInJourneyPattern_VersionedChildStructure;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.Runner;
import toolkit.test.TestStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TestStaServiceLinks {

    // P1 is the ordinary case: three points, two hops, a link for each. P2's middle hop has no
    // link. P3 already names one on its first point. P4 matches nothing at all.
    //
    // A->B is claimed by two links, whose ids order the opposite way to the document, so the
    // tie-break has to be the id and not the cursor.
    private static final String FRAMES = """
        <ServiceFrame id="sf" version="1">
          <serviceLinks>
            <ServiceLink id="it:apb:ServiceLink:zz_A_B:" version="any">
              <Distance>100</Distance>
              <FromPointRef ref="it:apb:ScheduledStopPoint:A:" version="any"/>
              <ToPointRef ref="it:apb:ScheduledStopPoint:B:" version="any"/>
            </ServiceLink>
            <ServiceLink id="it:apb:ServiceLink:aa_A_B:" version="any">
              <Distance>101</Distance>
              <FromPointRef ref="it:apb:ScheduledStopPoint:A:" version="any"/>
              <ToPointRef ref="it:apb:ScheduledStopPoint:B:" version="any"/>
            </ServiceLink>
            <ServiceLink id="it:apb:ServiceLink:01_B_C:" version="any">
              <Distance>200</Distance>
              <FromPointRef ref="it:apb:ScheduledStopPoint:B:" version="any"/>
              <ToPointRef ref="it:apb:ScheduledStopPoint:C:" version="any"/>
            </ServiceLink>
          </serviceLinks>
          <journeyPatterns>
            <ServiceJourneyPattern id="it:apb:ServiceJourneyPattern:P1:" version="1">
              <pointsInSequence>
                <StopPointInJourneyPattern id="it:apb:StopPointInJourneyPattern:P1-1:" version="1" order="1">
                  <ScheduledStopPointRef ref="it:apb:ScheduledStopPoint:A:" version="any"/>
                </StopPointInJourneyPattern>
                <StopPointInJourneyPattern id="it:apb:StopPointInJourneyPattern:P1-2:" version="1" order="2">
                  <ScheduledStopPointRef ref="it:apb:ScheduledStopPoint:B:" version="any"/>
                </StopPointInJourneyPattern>
                <StopPointInJourneyPattern id="it:apb:StopPointInJourneyPattern:P1-3:" version="1" order="3">
                  <ScheduledStopPointRef ref="it:apb:ScheduledStopPoint:C:" version="any"/>
                </StopPointInJourneyPattern>
              </pointsInSequence>
            </ServiceJourneyPattern>
            <ServiceJourneyPattern id="it:apb:ServiceJourneyPattern:P2:" version="1">
              <pointsInSequence>
                <StopPointInJourneyPattern id="it:apb:StopPointInJourneyPattern:P2-1:" version="1" order="1">
                  <ScheduledStopPointRef ref="it:apb:ScheduledStopPoint:A:" version="any"/>
                </StopPointInJourneyPattern>
                <StopPointInJourneyPattern id="it:apb:StopPointInJourneyPattern:P2-2:" version="1" order="2">
                  <ScheduledStopPointRef ref="it:apb:ScheduledStopPoint:Z:" version="any"/>
                </StopPointInJourneyPattern>
                <StopPointInJourneyPattern id="it:apb:StopPointInJourneyPattern:P2-3:" version="1" order="3">
                  <ScheduledStopPointRef ref="it:apb:ScheduledStopPoint:C:" version="any"/>
                </StopPointInJourneyPattern>
              </pointsInSequence>
            </ServiceJourneyPattern>
            <ServiceJourneyPattern id="it:apb:ServiceJourneyPattern:P3:" version="1">
              <pointsInSequence>
                <StopPointInJourneyPattern id="it:apb:StopPointInJourneyPattern:P3-1:" version="1" order="1">
                  <ScheduledStopPointRef ref="it:apb:ScheduledStopPoint:A:" version="any"/>
                  <OnwardServiceLinkRef ref="it:apb:ServiceLink:published:" version="any"/>
                </StopPointInJourneyPattern>
                <StopPointInJourneyPattern id="it:apb:StopPointInJourneyPattern:P3-2:" version="1" order="2">
                  <ScheduledStopPointRef ref="it:apb:ScheduledStopPoint:B:" version="any"/>
                </StopPointInJourneyPattern>
              </pointsInSequence>
            </ServiceJourneyPattern>
            <ServiceJourneyPattern id="it:apb:ServiceJourneyPattern:P4:" version="1">
              <pointsInSequence>
                <StopPointInJourneyPattern id="it:apb:StopPointInJourneyPattern:P4-1:" version="1" order="1">
                  <ScheduledStopPointRef ref="it:apb:ScheduledStopPoint:Y:" version="any"/>
                </StopPointInJourneyPattern>
                <StopPointInJourneyPattern id="it:apb:StopPointInJourneyPattern:P4-2:" version="1" order="2">
                  <ScheduledStopPointRef ref="it:apb:ScheduledStopPoint:Z:" version="any"/>
                </StopPointInJourneyPattern>
              </pointsInSequence>
            </ServiceJourneyPattern>
          </journeyPatterns>
        </ServiceFrame>
        """;

    public static void main(String[] args) {
        Runner.run(TestStaServiceLinks.class);
    }

    public static void testEveryHopButTheLastGetsItsLink(TestStore db) throws Exception {
        Map<String, List<String>> refs = run(db);

        Check.equals(Arrays.asList("it:apb:ServiceLink:aa_A_B:", "it:apb:ServiceLink:01_B_C:", null),
                refs.get("it:apb:ServiceJourneyPattern:P1:"),
                "a link per hop, and none on the terminus");
        Check.equals(Arrays.asList(null, null, null),
                refs.get("it:apb:ServiceJourneyPattern:P2:"),
                "a hop no link spans leaves the point alone");
        Check.equals(Arrays.asList("it:apb:ServiceLink:published:", null),
                refs.get("it:apb:ServiceJourneyPattern:P3:"),
                "a published ref is kept, not restated");
        Check.equals(Arrays.asList(null, null),
                refs.get("it:apb:ServiceJourneyPattern:P4:"),
                "a pattern nothing matched is untouched");
    }

    /// The ref the export depends on: a versionless one resolves to no edge, and the shard's links
    /// are selected by walking pattern -> link edges, so the pattern would ship with no geometry.
    public static void testTheRefCarriesTheLinkVersion(TestStore db) throws Exception {
        db.loadNetex(FRAMES);
        TestStore target = db.runDbToDb(WireStaServiceLinks::apply);
        try (Txn txn = target.store.roTxn()) {
            for (Object o : target.store.iterOnlyObjects(txn, ServiceJourneyPattern.class)) {
                ServiceJourneyPattern pat = (ServiceJourneyPattern) o;
                if (!"it:apb:ServiceJourneyPattern:P1:".equals(pat.getId())) continue;
                StopPointInJourneyPattern_VersionedChildStructure first =
                        (StopPointInJourneyPattern_VersionedChildStructure) points(pat).get(0);
                Check.equals("any", first.getOnwardServiceLinkRef().getVersion(),
                        "the emitted ref carries the link's version");
            }
        }
    }

    /// The pattern -> link edge the export reads, rather than the ref bean the pass wrote.
    public static void testTheLinkResolvesFromThePattern(TestStore db) throws Exception {
        db.loadNetex(FRAMES);
        TestStore target = db.runDbToDb(WireStaServiceLinks::apply);
        try (Txn txn = target.store.roTxn()) {
            long pk = target.store.lookupFullKey(txn, "it:apb:ServiceJourneyPattern:P1:", "1",
                    ServiceJourneyPattern.class);
            Check.that(pk != -1, "P1 is in the id index");
            java.util.Set<String> targets = new java.util.HashSet<>();
            for (long[] e : target.store.iterEdges(txn)) {
                if (e[0] != pk) continue;
                Object o = target.store.loadObjectByFullKey(txn, e[1]);
                if (o instanceof noi.netex.model.ServiceLink sl) targets.add(sl.getId());
            }
            Check.equals(java.util.Set.of("it:apb:ServiceLink:aa_A_B:", "it:apb:ServiceLink:01_B_C:"),
                    targets, "both links resolve as edges off the pattern's own row");
        }
    }

    /// Pattern id -> its points' onward link refs, in order, null where a point carries none.
    private static Map<String, List<String>> run(TestStore db) throws Exception {
        db.loadNetex(FRAMES);
        TestStore target = db.runDbToDb(WireStaServiceLinks::apply);
        Map<String, List<String>> out = new LinkedHashMap<>();
        try (Txn txn = target.store.roTxn()) {
            for (Object o : target.store.iterOnlyObjects(txn, ServiceJourneyPattern.class)) {
                ServiceJourneyPattern pat = (ServiceJourneyPattern) o;
                List<String> refs = new ArrayList<>();
                for (PointInLinkSequence_VersionedChildStructure p : points(pat)) {
                    StopPointInJourneyPattern_VersionedChildStructure sp =
                            (StopPointInJourneyPattern_VersionedChildStructure) p;
                    refs.add(sp.getOnwardServiceLinkRef() == null
                            ? null : sp.getOnwardServiceLinkRef().getRef());
                }
                out.put(pat.getId(), refs);
            }
        }
        return out;
    }

    private static List<PointInLinkSequence_VersionedChildStructure> points(ServiceJourneyPattern pat) {
        return pat.getPointsInSequence()
                .getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern();
    }
}
