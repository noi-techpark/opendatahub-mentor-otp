package tests;

// The passthrough link merge, and the counter-case that decides how it is gated.
//
// Coordinates are real Carinthian ones so the seam arithmetic runs at the latitude it will see, and
// the two links meet exactly -- as measured, 12,465 of 12,465 consecutive at:obb pairs do.

import noi.netex.model.LinkInLinkSequence_VersionedChildStructure;
import noi.netex.model.ServiceJourneyPattern;
import noi.netex.model.ServiceLink;
import noi.netex.model.ServiceLinkInJourneyPattern_VersionedChildStructure;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.Runner;
import toolkit.test.TestStore;
import transformers.xb.Passthrough;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TestPassthrough {

    public static void main(String[] args) {
        Runner.run(TestPassthrough.class);
    }

    static final String NETEX = """
<ServiceFrame id="sf" version="1">
  <scheduledStopPoints>
    <ScheduledStopPoint id="ssp1" version="1"><Location><Longitude>14.855584</Longitude><Latitude>46.721527</Latitude></Location></ScheduledStopPoint>
    <ScheduledStopPoint id="ssp2" version="1"><Location><Longitude>14.795055</Longitude><Latitude>46.620892</Latitude></Location></ScheduledStopPoint>
    <ScheduledStopPoint id="ssp3" version="1"><Location><Longitude>14.794148</Longitude><Latitude>46.590626</Latitude></Location></ScheduledStopPoint>
    <ScheduledStopPoint id="ssp4" version="1"><Location><Longitude>14.750319</Longitude><Latitude>46.582699</Latitude></Location></ScheduledStopPoint>
    <ScheduledStopPoint id="ssp5" version="1"><Location><Longitude>14.700000</Longitude><Latitude>46.500000</Latitude></Location></ScheduledStopPoint>
    <ScheduledStopPoint id="ssp6" version="1"><Location><Longitude>14.650000</Longitude><Latitude>46.450000</Latitude></Location></ScheduledStopPoint>
    <ScheduledStopPoint id="ssp7" version="1"><Location><Longitude>14.600000</Longitude><Latitude>46.400000</Latitude></Location></ScheduledStopPoint>
    <ScheduledStopPoint id="ssp8" version="1"><Location><Longitude>14.550000</Longitude><Latitude>46.350000</Latitude></Location></ScheduledStopPoint>
  </scheduledStopPoints>
  <journeyPatterns>
    <ServiceJourneyPattern id="Pat:at" version="1">
      <pointsInSequence>
      <StopPointInJourneyPattern id="ap0" version="1" order="1"><ScheduledStopPointRef ref="ssp1" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="ap1" version="1" order="2"><ScheduledStopPointRef ref="ssp2" version="1"/><StopUse>passthrough</StopUse></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="ap2" version="1" order="3"><ScheduledStopPointRef ref="ssp3" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="ap3" version="1" order="4"><ScheduledStopPointRef ref="ssp4" version="1"/></StopPointInJourneyPattern>
      </pointsInSequence>
      <linksInSequence>
      <ServiceLinkInJourneyPattern id="al0" version="1" order="1"><ServiceLinkRef ref="sl0" version="1"/></ServiceLinkInJourneyPattern>
      <ServiceLinkInJourneyPattern id="al1" version="1" order="2"><ServiceLinkRef ref="sl1" version="1"/></ServiceLinkInJourneyPattern>
      <ServiceLinkInJourneyPattern id="al2" version="1" order="3"><ServiceLinkRef ref="sl2" version="1"/></ServiceLinkInJourneyPattern>
      </linksInSequence>
    </ServiceJourneyPattern>
    <ServiceJourneyPattern id="Pat:ch" version="1">
      <pointsInSequence>
      <StopPointInJourneyPattern id="cp0" version="1" order="1"><ScheduledStopPointRef ref="ssp1" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="cp1" version="1" order="2"><ScheduledStopPointRef ref="ssp2" version="1"/><StopUse>passthrough</StopUse></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="cp2" version="1" order="3"><ScheduledStopPointRef ref="ssp3" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="cp3" version="1" order="4"><ScheduledStopPointRef ref="ssp4" version="1"/></StopPointInJourneyPattern>
      </pointsInSequence>
      <linksInSequence>
      <ServiceLinkInJourneyPattern id="cl0" version="1" order="1"><ServiceLinkRef ref="sl0" version="1"/></ServiceLinkInJourneyPattern>
      <ServiceLinkInJourneyPattern id="cl1" version="1" order="2"><ServiceLinkRef ref="sl1" version="1"/></ServiceLinkInJourneyPattern>
      <ServiceLinkInJourneyPattern id="cl2" version="1" order="3"><ServiceLinkRef ref="sl2" version="1"/></ServiceLinkInJourneyPattern>
      </linksInSequence>
    </ServiceJourneyPattern>
    <ServiceJourneyPattern id="Pat:it" version="1">
      <pointsInSequence>
      <StopPointInJourneyPattern id="ip0" version="1" order="1"><ScheduledStopPointRef ref="ssp1" version="1"/><OnwardServiceLinkRef ref="sl0" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="ip1" version="1" order="2"><ScheduledStopPointRef ref="ssp2" version="1"/><StopUse>passthrough</StopUse><OnwardServiceLinkRef ref="sl1" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="ip2" version="1" order="3"><ScheduledStopPointRef ref="ssp3" version="1"/><OnwardServiceLinkRef ref="sl2" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="ip3" version="1" order="4"><ScheduledStopPointRef ref="ssp4" version="1"/></StopPointInJourneyPattern>
      </pointsInSequence>
    </ServiceJourneyPattern>
    <ServiceJourneyPattern id="Pat:solo" version="1">
      <pointsInSequence>
      <StopPointInJourneyPattern id="op0" version="1" order="1"><ScheduledStopPointRef ref="ssp5" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="op1" version="1" order="2"><ScheduledStopPointRef ref="ssp6" version="1"/><StopUse>passthrough</StopUse></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="op2" version="1" order="3"><ScheduledStopPointRef ref="ssp7" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="op3" version="1" order="4"><ScheduledStopPointRef ref="ssp8" version="1"/></StopPointInJourneyPattern>
      </pointsInSequence>
      <linksInSequence>
      <ServiceLinkInJourneyPattern id="ol0" version="1" order="1"><ServiceLinkRef ref="sl3" version="1"/></ServiceLinkInJourneyPattern>
      <ServiceLinkInJourneyPattern id="ol1" version="1" order="2"><ServiceLinkRef ref="sl4" version="1"/></ServiceLinkInJourneyPattern>
      <ServiceLinkInJourneyPattern id="ol2" version="1" order="3"><ServiceLinkRef ref="sl5" version="1"/></ServiceLinkInJourneyPattern>
      </linksInSequence>
    </ServiceJourneyPattern>
  </journeyPatterns>
  <serviceLinks>
    <ServiceLink id="sl0" version="1"><Distance>12000</Distance>
      <LineString xmlns="http://www.opengis.net/gml/3.2" id="ls0"><posList srsDimension="2">46.721527 14.855584 46.670000 14.820000 46.620892 14.795055</posList></LineString>
      <FromPointRef ref="ssp1" version="1"/><ToPointRef ref="ssp2" version="1"/></ServiceLink>
    <ServiceLink id="sl1" version="1"><Distance>3400</Distance>
      <LineString xmlns="http://www.opengis.net/gml/3.2" id="ls1"><posList srsDimension="2">46.620892 14.795055 46.600000 14.794500 46.590626 14.794148</posList></LineString>
      <FromPointRef ref="ssp2" version="1"/><ToPointRef ref="ssp3" version="1"/></ServiceLink>
    <ServiceLink id="sl2" version="1"><Distance>3900</Distance>
      <LineString xmlns="http://www.opengis.net/gml/3.2" id="ls2"><posList srsDimension="2">46.590626 14.794148 46.586000 14.770000 46.582699 14.750319</posList></LineString>
      <FromPointRef ref="ssp3" version="1"/><ToPointRef ref="ssp4" version="1"/></ServiceLink>
    <ServiceLink id="sl3" version="1"><Distance>6800</Distance>
      <LineString xmlns="http://www.opengis.net/gml/3.2" id="ls3"><posList srsDimension="2">46.500000 14.700000 46.475000 14.675000 46.450000 14.650000</posList></LineString>
      <FromPointRef ref="ssp5" version="1"/><ToPointRef ref="ssp6" version="1"/></ServiceLink>
    <ServiceLink id="sl4" version="1"><Distance>6800</Distance>
      <LineString xmlns="http://www.opengis.net/gml/3.2" id="ls4"><posList srsDimension="2">46.450000 14.650000 46.425000 14.625000 46.400000 14.600000</posList></LineString>
      <FromPointRef ref="ssp6" version="1"/><ToPointRef ref="ssp7" version="1"/></ServiceLink>
    <ServiceLink id="sl5" version="1"><Distance>6800</Distance>
      <LineString xmlns="http://www.opengis.net/gml/3.2" id="ls5"><posList srsDimension="2">46.400000 14.600000 46.375000 14.575000 46.350000 14.550000</posList></LineString>
      <FromPointRef ref="ssp7" version="1"/><ToPointRef ref="ssp8" version="1"/></ServiceLink>
  </serviceLinks>
</ServiceFrame>
<TimetableFrame id="tf" version="1">
  <vehicleJourneys>
    <ServiceJourney id="Sj:at" version="1">
      <ServiceJourneyPatternRef ref="Pat:at" version="1"/>
      <passingTimes>
        <TimetabledPassingTime id="at0" version="1"><StopPointInJourneyPatternRef ref="ap0" version="1" order="1"/><DepartureTime>08:00:00</DepartureTime></TimetabledPassingTime>
        <TimetabledPassingTime id="at2" version="1"><StopPointInJourneyPatternRef ref="ap2" version="1" order="3"/><DepartureTime>08:20:00</DepartureTime></TimetabledPassingTime>
        <TimetabledPassingTime id="at3" version="1"><StopPointInJourneyPatternRef ref="ap3" version="1" order="4"/><ArrivalTime>08:30:00</ArrivalTime></TimetabledPassingTime>
      </passingTimes>
    </ServiceJourney>
    <ServiceJourney id="Sj:ch" version="1">
      <ServiceJourneyPatternRef ref="Pat:ch" version="1"/>
      <passingTimes>
        <TimetabledPassingTime id="ch0" version="1"><StopPointInJourneyPatternRef ref="cp0" version="1" order="1"/><DepartureTime>09:00:00</DepartureTime></TimetabledPassingTime>
        <TimetabledPassingTime id="ch1" version="1"><StopPointInJourneyPatternRef ref="cp1" version="1" order="2"/><DepartureTime>09:10:00</DepartureTime></TimetabledPassingTime>
        <TimetabledPassingTime id="ch2" version="1"><StopPointInJourneyPatternRef ref="cp2" version="1" order="3"/><DepartureTime>09:20:00</DepartureTime></TimetabledPassingTime>
        <TimetabledPassingTime id="ch3" version="1"><StopPointInJourneyPatternRef ref="cp3" version="1" order="4"/><ArrivalTime>09:30:00</ArrivalTime></TimetabledPassingTime>
      </passingTimes>
    </ServiceJourney>
    <ServiceJourney id="Sj:it" version="1">
      <ServiceJourneyPatternRef ref="Pat:it" version="1"/>
      <passingTimes>
        <TimetabledPassingTime id="it0" version="1"><StopPointInJourneyPatternRef ref="ip0" version="1" order="1"/><DepartureTime>10:00:00</DepartureTime></TimetabledPassingTime>
        <TimetabledPassingTime id="it2" version="1"><StopPointInJourneyPatternRef ref="ip2" version="1" order="3"/><DepartureTime>10:20:00</DepartureTime></TimetabledPassingTime>
        <TimetabledPassingTime id="it3" version="1"><StopPointInJourneyPatternRef ref="ip3" version="1" order="4"/><ArrivalTime>10:30:00</ArrivalTime></TimetabledPassingTime>
      </passingTimes>
    </ServiceJourney>
    <ServiceJourney id="Sj:solo" version="1">
      <ServiceJourneyPatternRef ref="Pat:solo" version="1"/>
      <passingTimes>
        <TimetabledPassingTime id="so0" version="1"><StopPointInJourneyPatternRef ref="op0" version="1" order="1"/><DepartureTime>11:00:00</DepartureTime></TimetabledPassingTime>
        <TimetabledPassingTime id="so2" version="1"><StopPointInJourneyPatternRef ref="op2" version="1" order="3"/><DepartureTime>11:20:00</DepartureTime></TimetabledPassingTime>
        <TimetabledPassingTime id="so3" version="1"><StopPointInJourneyPatternRef ref="op3" version="1" order="4"/><ArrivalTime>11:30:00</ArrivalTime></TimetabledPassingTime>
      </passingTimes>
    </ServiceJourney>
  </vehicleJourneys>
</TimetableFrame>
""";

    /// The Austrian shape: 4 points with one passthrough, 3 links, and a journey that omits the
    /// passthrough point's passing time. OTP sees 3 stops and needs 2 links.
    public static void testSpanningLinksAreMerged(TestStore db) throws Exception {
        Merged m = merge(db);

        List<String> refs = linkRefs(m.patterns.get("Pat:at"));
        Check.equals(2, refs.size(), "3 surviving stops need 2 links");
        Check.equals("sl2", refs.get(1), "the un-merged second hop keeps its original link");

        ServiceLink joined = m.minted.get(refs.get(0));
        Check.that(joined != null, "hop 0's merged link was minted");
        Check.equals("ssp1", joined.getFromPointRef().getRef(), "it starts at the first kept stop");
        Check.equals("ssp3", joined.getToPointRef().getRef(), "and ends at the next kept stop");
    }

    /// The geometry runs end to end and the shared seam appears once, not twice. 3 + 3 pairs with
    /// the seam de-duplicated is 5 pairs.
    public static void testMergedGeometryIsContinuous(TestStore db) throws Exception {
        Merged m = merge(db);

        ServiceLink joined = m.minted.get(linkRefs(m.patterns.get("Pat:at")).get(0));
        List<Double> pos = joined.getLineString().getPosList().getValue();
        Check.equals(10, pos.size(), "the seam coordinate is de-duplicated, not repeated");
        Check.equals(46.721527, pos.get(0), "geometry starts on ssp1");
        Check.equals(46.590626, pos.get(pos.size() - 2), "and runs all the way to ssp3");
    }

    /// THE counter-case, and the reason the merge cannot key on StopUse alone. Sj:ch TIMES its
    /// passthrough point, so OTP counts all four stops and the three published links are already
    /// right -- merging would leave the pattern one link short. That is 94,596 journeys of the
    /// OPENOV export on the national store, and OTP's own JourneyPatternSJMismatch carries the
    /// matching note.
    public static void testPatternIsNotMergedWhenTheJourneyTimesThePassthrough(TestStore db)
            throws Exception {
        Merged m = merge(db);

        List<String> refs = linkRefs(m.patterns.get("Pat:ch"));
        Check.equals(3, refs.size(), "all three links kept");
        Check.equals("sl0", refs.get(0), "and they are the published ones, unmerged");
        Check.equals("sl1", refs.get(1), "second");
        Check.equals("sl2", refs.get(2), "third");
    }

    /// The Italian passthrough shape: no linksInSequence at all, the links named only by each
    /// point's OnwardServiceLinkRef. 1,044 patterns on the national store are like this, and reading
    /// only linksInSequence would skip every one of them.
    public static void testOnwardRefsAreMergedToo(TestStore db) throws Exception {
        Merged m = merge(db);

        List<String> refs = linkRefs(m.patterns.get("Pat:it"));
        Check.equals(2, refs.size(), "3 surviving stops need 2 links, written as linksInSequence");
        Check.equals("sl2", refs.get(1), "the un-merged second hop keeps its original link");

        ServiceLink joined = m.minted.get(refs.get(0));
        Check.that(joined != null, "hop 0's merged link was minted from the onward refs");
        Check.equals("ssp1", joined.getFromPointRef().getRef(), "starts at the first kept stop");
        Check.equals("ssp3", joined.getToPointRef().getRef(), "ends at the next kept stop");
    }

    /// A minted link is yielded before the pattern that references it. Yield order is insert order,
    /// and the phase runs with no resolve after it.
    public static void testMintedLinksAreYieldedBeforeTheirPattern(TestStore db) throws Exception {
        db.loadNetex(NETEX);
        List<String> order = new ArrayList<>();
        Map<String, List<String>> refsOf = new LinkedHashMap<>();
        try (Txn txn = db.store.roTxn()) {
            var it = Passthrough.mergeLinks(db.store, txn);
            while (it.hasNext()) {
                Object o = it.next();
                if (o instanceof ServiceJourneyPattern p) {
                    order.add(p.getId());
                    refsOf.put(p.getId(), linkRefs(p));
                } else if (o instanceof ServiceLink sl) {
                    order.add(sl.getId());
                }
            }
        }
        Check.that(!refsOf.isEmpty(), "the fixture yields at least one rewritten pattern");
        for (Map.Entry<String, List<String>> e : refsOf.entrySet()) {
            int pat = order.indexOf(e.getKey());
            for (String ref : e.getValue()) {
                int link = order.indexOf(ref);
                if (link < 0) continue;                 // a published link, not one this pass minted
                Check.that(link < pat, "minted " + ref + " must be yielded before " + e.getKey()
                        + " (link at " + link + ", pattern at " + pat + ")");
            }
        }
    }

    /// With no resolve afterwards, the pattern reaches its minted link through the edge index,
    /// which is the only thing `EpipDbToZip` reads to decide which ServiceLinks to export.
    ///
    /// `Pat:solo` is here because `join` is content-hashed: `Pat:at` and `Pat:it` merge the same
    /// span into one id and each resolves the other's insert, so neither can show the ordering.
    /// `Pat:solo`'s span is unique, and only one pattern references what it mints.
    public static void testAMintedLinkBecomesAnEdgeWithoutAResolve(TestStore db) throws Exception {
        db.loadNetex(NETEX);
        // The shape of XbDbToDb phase 5: one rw txn, read and write through it, commit, no resolve.
        try (Txn txn = db.store.rwTxn()) {
            db.store.insertAnyObjects(txn, Passthrough.mergeLinks(db.store, txn));
            txn.commit();
        }
        try (Txn txn = db.store.roTxn()) {
            String hop0 = null;
            for (Object o : db.store.iterOnlyObjects(txn, ServiceJourneyPattern.class)) {
                ServiceJourneyPattern p = (ServiceJourneyPattern) o;
                if ("Pat:solo".equals(p.getId())) hop0 = linkRefs(p).get(0);
            }
            Check.that(hop0 != null, "Pat:solo is in the store");
            Check.that(!List.of("sl3", "sl4", "sl5").contains(hop0),
                    "and its first hop names a minted link, not a published one: " + hop0);

            Set<String> reachable = new LinkedHashSet<>();
            var it = db.store.fetchAllReferencesByClass(txn,
                    Set.of(ServiceJourneyPattern.class), false);
            while (it.hasNext()) {
                if (it.next() instanceof ServiceLink sl) reachable.add(sl.getId());
            }
            Check.that(reachable.contains(hop0), "the minted link " + hop0 + " is reachable from "
                    + "the pattern through the edge index; reached " + reachable);
        }
    }

    // ------------------------------------------------------------------------------- //

    record Merged(Map<String, ServiceJourneyPattern> patterns, Map<String, ServiceLink> minted) {}

    /// Drive the pass directly over a loaded store: it is read-only and yields what a caller would
    /// write back, so the yielded objects ARE the result.
    static Merged merge(TestStore db) throws Exception {
        db.loadNetex(NETEX);
        Map<String, ServiceJourneyPattern> patterns = new LinkedHashMap<>();
        Map<String, ServiceLink> minted = new LinkedHashMap<>();
        try (Txn txn = db.store.roTxn()) {
            var it = Passthrough.mergeLinks(db.store, txn);
            while (it.hasNext()) {
                Object o = it.next();
                if (o instanceof ServiceJourneyPattern p) patterns.put(p.getId(), p);
                if (o instanceof ServiceLink sl) minted.put(sl.getId(), sl);
            }
            // A pattern the pass did not change is not yielded, so read it back from the store.
            for (Object o : db.store.iterOnlyObjects(txn, ServiceJourneyPattern.class)) {
                ServiceJourneyPattern p = (ServiceJourneyPattern) o;
                patterns.putIfAbsent(p.getId(), p);
            }
        }
        return new Merged(patterns, minted);
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
}
