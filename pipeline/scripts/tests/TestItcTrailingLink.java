package tests;

// GTT's one-link-per-point-including-the-terminus, and the two shapes that must NOT be touched.
//
// The recognition test is `links == points` AND the last two refs equal -- a shape test, not a
// count threshold. Assertions here are on the surviving REFS, never on the list length.

import fix.FixItcTrailingLink;
import noi.netex.model.LinkInLinkSequence_VersionedChildStructure;
import noi.netex.model.PointInLinkSequence_VersionedChildStructure;
import noi.netex.model.ServiceJourneyPattern;
import noi.netex.model.ServiceLinkInJourneyPattern_VersionedChildStructure;
import noi.netex.model.StopPointInJourneyPattern_VersionedChildStructure;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.Runner;
import toolkit.test.TestStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TestItcTrailingLink {

    public static void main(String[] args) {
        Runner.run(TestItcTrailingLink.class);
    }

    // Pat:dup  -- the defect: 3 points, 3 links, the last repeating sl1, and the terminus repeating
    //             it again in its own OnwardServiceLinkRef.
    // Pat:ok   -- already correct: 3 points, 2 links.
    // Pat:wrong-- 3 points, 3 links whose last two DIFFER: the right number of the wrong links, and
    //             not this pass's business.
    static final String NETEX = """
<ServiceFrame id="sf" version="1">
  <journeyPatterns>
    <ServiceJourneyPattern id="Pat:dup" version="1">
      <pointsInSequence>
      <StopPointInJourneyPattern id="dp0" version="1" order="1"><ScheduledStopPointRef ref="ssp1" version="1"/><OnwardServiceLinkRef ref="sl0" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="dp1" version="1" order="2"><ScheduledStopPointRef ref="ssp2" version="1"/><OnwardServiceLinkRef ref="sl1" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="dp2" version="1" order="3"><ScheduledStopPointRef ref="ssp3" version="1"/><OnwardServiceLinkRef ref="sl1" version="1"/></StopPointInJourneyPattern>
      </pointsInSequence>
      <linksInSequence>
      <ServiceLinkInJourneyPattern id="dl0" version="1" order="1"><ServiceLinkRef ref="sl0" version="1"/></ServiceLinkInJourneyPattern>
      <ServiceLinkInJourneyPattern id="dl1" version="1" order="2"><ServiceLinkRef ref="sl1" version="1"/></ServiceLinkInJourneyPattern>
      <ServiceLinkInJourneyPattern id="dl2" version="1" order="3"><ServiceLinkRef ref="sl1" version="1"/></ServiceLinkInJourneyPattern>
      </linksInSequence>
    </ServiceJourneyPattern>
    <ServiceJourneyPattern id="Pat:ok" version="1">
      <pointsInSequence>
      <StopPointInJourneyPattern id="op0" version="1" order="1"><ScheduledStopPointRef ref="ssp1" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="op1" version="1" order="2"><ScheduledStopPointRef ref="ssp2" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="op2" version="1" order="3"><ScheduledStopPointRef ref="ssp3" version="1"/></StopPointInJourneyPattern>
      </pointsInSequence>
      <linksInSequence>
      <ServiceLinkInJourneyPattern id="ol0" version="1" order="1"><ServiceLinkRef ref="sl0" version="1"/></ServiceLinkInJourneyPattern>
      <ServiceLinkInJourneyPattern id="ol1" version="1" order="2"><ServiceLinkRef ref="sl1" version="1"/></ServiceLinkInJourneyPattern>
      </linksInSequence>
    </ServiceJourneyPattern>
    <ServiceJourneyPattern id="Pat:wrong" version="1">
      <pointsInSequence>
      <StopPointInJourneyPattern id="wp0" version="1" order="1"><ScheduledStopPointRef ref="ssp1" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="wp1" version="1" order="2"><ScheduledStopPointRef ref="ssp2" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="wp2" version="1" order="3"><ScheduledStopPointRef ref="ssp3" version="1"/></StopPointInJourneyPattern>
      </pointsInSequence>
      <linksInSequence>
      <ServiceLinkInJourneyPattern id="wl0" version="1" order="1"><ServiceLinkRef ref="sl0" version="1"/></ServiceLinkInJourneyPattern>
      <ServiceLinkInJourneyPattern id="wl1" version="1" order="2"><ServiceLinkRef ref="sl1" version="1"/></ServiceLinkInJourneyPattern>
      <ServiceLinkInJourneyPattern id="wl2" version="1" order="3"><ServiceLinkRef ref="sl2" version="1"/></ServiceLinkInJourneyPattern>
      </linksInSequence>
    </ServiceJourneyPattern>
  </journeyPatterns>
  <serviceLinks>
    <ServiceLink id="sl0" version="1"><FromPointRef ref="ssp1" version="1"/><ToPointRef ref="ssp2" version="1"/></ServiceLink>
    <ServiceLink id="sl1" version="1"><FromPointRef ref="ssp2" version="1"/><ToPointRef ref="ssp3" version="1"/></ServiceLink>
    <ServiceLink id="sl2" version="1"><FromPointRef ref="ssp3" version="1"/><ToPointRef ref="ssp1" version="1"/></ServiceLink>
  </serviceLinks>
</ServiceFrame>
""";

    /// The repair, asserted on the surviving refs.
    public static void testTrailingDuplicateIsDropped(TestStore db) throws Exception {
        Map<String, ServiceJourneyPattern> pats = fix(db);

        List<String> refs = linkRefs(pats.get("Pat:dup"));
        Check.equals(2, refs.size(), "three points now carry two links");
        Check.equals("sl0", refs.get(0), "the survivors are the first two, by ref");
        Check.equals("sl1", refs.get(1), "second survivor");
    }

    /// The duplicate is published on both sides; a repair that fixes only the container leaves the
    /// pattern contradicting itself, and a consumer reading the onward refs still sees the surplus.
    public static void testTerminusOnwardRefIsCleared(TestStore db) throws Exception {
        Map<String, ServiceJourneyPattern> pats = fix(db);

        List<PointInLinkSequence_VersionedChildStructure> pts = points(pats.get("Pat:dup"));
        Check.isNull(onwardRef(pts.get(2)), "the terminus no longer repeats the last link");
        Check.equals("sl0", onwardRef(pts.get(0)), "and the interior points keep theirs");
        Check.equals("sl1", onwardRef(pts.get(1)), "second interior point");
    }

    /// A pattern that is already right must come out byte-identical.
    public static void testCorrectPatternIsLeftAlone(TestStore db) throws Exception {
        Map<String, ServiceJourneyPattern> pats = fix(db);

        List<String> refs = linkRefs(pats.get("Pat:ok"));
        Check.equals(2, refs.size(), "untouched");
        Check.equals("sl0", refs.get(0), "first ref");
        Check.equals("sl1", refs.get(1), "second ref");
    }

    /// THE test that makes the shape rule worth having: Pat:wrong has links == points, so a
    /// count-only rule would drop its last link.
    public static void testRightNumberOfWrongLinksIsLeftAlone(TestStore db) throws Exception {
        Map<String, ServiceJourneyPattern> pats = fix(db);

        List<String> refs = linkRefs(pats.get("Pat:wrong"));
        Check.equals(3, refs.size(), "not this pass's defect, so not this pass's business");
        Check.equals("sl2", refs.get(2), "the distinct last link is still there");
    }

    // ------------------------------------------------------------------------------- //

    static Map<String, ServiceJourneyPattern> fix(TestStore db) throws Exception {
        db.loadNetex(NETEX);
        TestStore target = db.runDbToDb(FixItcTrailingLink::apply);
        Map<String, ServiceJourneyPattern> pats = new LinkedHashMap<>();
        try (Txn txn = target.store.roTxn()) {
            for (Object o : target.store.iterOnlyObjects(txn, ServiceJourneyPattern.class)) {
                ServiceJourneyPattern p = (ServiceJourneyPattern) o;
                pats.put(p.getId(), p);
            }
        }
        return pats;
    }

    static List<PointInLinkSequence_VersionedChildStructure> points(ServiceJourneyPattern p) {
        Check.that(p != null, "pattern present");
        var seq = p.getPointsInSequence();
        return seq == null ? new ArrayList<>()
                : seq.getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern();
    }

    static List<String> linkRefs(ServiceJourneyPattern p) {
        Check.that(p != null, "pattern present");
        var seq = p.getLinksInSequence();
        List<String> out = new ArrayList<>();
        if (seq == null) return out;
        for (LinkInLinkSequence_VersionedChildStructure l
                : seq.getServiceLinkInJourneyPatternOrTimingLinkInJourneyPattern()) {
            out.add(l instanceof ServiceLinkInJourneyPattern_VersionedChildStructure sl
                    && sl.getServiceLinkRef() != null ? sl.getServiceLinkRef().getRef() : null);
        }
        return out;
    }

    static String onwardRef(PointInLinkSequence_VersionedChildStructure p) {
        if (!(p instanceof StopPointInJourneyPattern_VersionedChildStructure sp)) return null;
        return sp.getOnwardServiceLinkRef() == null ? null : sp.getOnwardServiceLinkRef().getRef();
    }
}
