package tests;

// conv.ItRapDbToDb: the RAP-readiness overlays over a raw clone. Covers the clone-path edge cases
// on top of the plain overlay behavior:
//   * a duplicate StopPlace stored in TWO versions keeps both rows;
//   * version collapse: an authority-less Line stored in two versions is substituted BY ID by
//     the object-level generator, so exactly one row (the overlay object's version) survives;
//   * a referrer outside every re-emitted class (GroupOfStopPlaces) keeps its reference state;
//   * quay-centroid backfill applies.
//
// Then the Name/ShortName swap the NAP's GTFS-derived assets publish, including the stop that needs
// the centroid backfill and the name repair at once.
//
// Then the authority backfill's id-token handling, on its own fixtures: an Operator id spelling the
// class token in a case the canonical one does not use is still recognised, and a feed that already
// ships one Authority gets its Network repaired onto that one instead of a freshly minted twin.

import jakarta.xml.bind.JAXBElement;
import noi.netex.model.Authority;
import noi.netex.model.AuthorityRefStructure;
import noi.netex.model.EntityStructure;
import noi.netex.model.GroupOfStopPlaces;
import noi.netex.model.Line;
import noi.netex.model.Network;
import noi.netex.model.ObjectFactory;
import noi.netex.model.PassengerStopAssignment;
import noi.netex.model.Quay;
import noi.netex.model.StopPlace;
import noi.netex.model.StopPlaceRefStructure;
import noi.netex.model.StopPlaceRefs_RelStructure;
import noi.netex.text.Mls;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.store.UnresolvedRow;
import toolkit.test.Check;
import toolkit.test.Runner;
import toolkit.test.TestStore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import conv.ItRapDbToDb;

public class TestItRapDbToDb {

    private static final ObjectFactory FACTORY = new ObjectFactory();

    static final String NETEX = """
        <SiteFrame id="site" version="1"><stopPlaces>
          <StopPlace id="IT:ITF3:StopPlace:dup1" version="1"><Name>Piazza Roma</Name>
            <Centroid><Location><Latitude>40.85000</Latitude><Longitude>14.25000</Longitude></Location></Centroid>
            <quays><Quay id="IT:ITF3:Quay:dup1a" version="1"/></quays></StopPlace>
          <StopPlace id="IT:ITF3:StopPlace:dup1" version="2"><Name>Piazza Roma</Name>
            <Centroid><Location><Latitude>40.85001</Latitude><Longitude>14.25001</Longitude></Location></Centroid>
            <quays><Quay id="IT:ITF3:Quay:dup1a" version="2"/></quays></StopPlace>
          <StopPlace id="IT:ITF3:StopPlace:dup2" version="1"><Name>Piazza Roma</Name>
            <Centroid><Location><Latitude>40.85005</Latitude><Longitude>14.25005</Longitude></Location></Centroid>
            <quays><Quay id="IT:ITF3:Quay:dup2a" version="1"/><Quay id="IT:ITF3:Quay:dup2b" version="1"/></quays></StopPlace>
          <StopPlace id="IT:ITF3:StopPlace:nocent" version="1"><Name>Via Milano</Name>
            <quays><Quay id="IT:ITF3:Quay:nocent1" version="1"><Centroid><Location><Latitude>40.90000</Latitude><Longitude>14.30000</Longitude></Location></Centroid></Quay></quays></StopPlace>
        </stopPlaces></SiteFrame>
        <ServiceFrame id="sf" version="1">
          <lines>
            <Line id="IT:ITF3:Line:noauth" version="1"><Name>NoAuth v1</Name><TransportMode>bus</TransportMode><OperatorRef ref="IT:ITF3:Operator:01234:AV1"/></Line>
            <Line id="IT:ITF3:Line:noauth" version="2"><Name>NoAuth v2</Name><TransportMode>bus</TransportMode><OperatorRef ref="IT:ITF3:Operator:01234:AV1"/></Line>
          </lines>
          <scheduledStopPoints>
            <ScheduledStopPoint id="IT:reg:ScheduledStopPoint:lone1" version="1"><Name>Lone One</Name><Location><Latitude>43.1</Latitude><Longitude>11.1</Longitude></Location></ScheduledStopPoint>
          </scheduledStopPoints>
          <stopAssignments>
            <PassengerStopAssignment id="pdup-1" version="1" order="1"><ScheduledStopPointRef ref="s-x"/><StopPlaceRef ref="IT:ITF3:StopPlace:dup1"/></PassengerStopAssignment>
          </stopAssignments>
        </ServiceFrame>
        <ResourceFrame id="rf" version="1"><organisations>
          <Operator id="IT:ITF3:Operator:01234:AV1" version="1"><Name>Op AV1</Name></Operator>
        </organisations></ResourceFrame>
        """;

    // An Operator whose id spells the class token in upper case, no Authority anywhere: the mint
    // path has to fire and produce the canonical token rather than the source's.
    static final String UPPER_OPERATOR = """
        <ServiceFrame id="sf" version="1"><lines>
          <Line id="IT:ITC4:Line:1" version="1"><Name>Upper</Name><TransportMode>bus</TransportMode><OperatorRef ref="IT:ITC4:OPERATOR:LOM-1"/></Line>
        </lines></ServiceFrame>
        <ResourceFrame id="rf" version="1"><organisations>
          <Operator id="IT:ITC4:OPERATOR:LOM-1" version="1"><Name>Op LOM</Name></Operator>
        </organisations></ResourceFrame>
        """;

    // The Name/ShortName swap, in the five shapes the corpus holds it in. `bothfixes` is the one
    // that needs both StopPlace overlays: no centroid AND a code for a name.
    static final String CODE_NAMES = """
        <SiteFrame id="site" version="1"><stopPlaces>
          <StopPlace id="IT:ITF4:StopPlace:830001700_ReteTrenitalia_GTFS" version="1"><Name>830001700</Name><ShortName>MILANO CENTRALE</ShortName><PrivateCode>830001700</PrivateCode><PublicCode>830001700</PublicCode>
            <Centroid><Location><Latitude>45.486341</Latitude><Longitude>9.204544</Longitude></Location></Centroid>
            <quays><Quay id="IT:ITF4:Quay:830001700_ReteTrenitalia_GTFS" version="1"><Name>830001700</Name><ShortName>MILANO CENTRALE</ShortName>
              <Centroid><Location><Latitude>45.486341</Latitude><Longitude>9.204544</Longitude></Location></Centroid></Quay></quays></StopPlace>
          <StopPlace id="IT:ITF3:StopPlace:bothfixes" version="1"><Name>5450</Name><ShortName>Piazza Mazzini</ShortName>
            <quays><Quay id="IT:ITF3:Quay:bothfixes" version="1"><Name>5450</Name><ShortName>Piazza Mazzini</ShortName>
              <Centroid><Location><Latitude>40.97341</Latitude><Longitude>14.21725</Longitude></Location></Centroid></Quay></quays></StopPlace>
          <StopPlace id="IT:ITF3:StopPlace:codeshort" version="1"><Name>7085</Name><ShortName>7085</ShortName>
            <Centroid><Location><Latitude>40.90</Latitude><Longitude>14.30</Longitude></Location></Centroid></StopPlace>
          <StopPlace id="IT:ITF3:StopPlace:noshort" version="1"><Name>0986</Name>
            <Centroid><Location><Latitude>40.91</Latitude><Longitude>14.31</Longitude></Location></Centroid></StopPlace>
          <StopPlace id="IT:ITF3:StopPlace:named" version="1"><Name>Via Stazione</Name><ShortName>0452</ShortName>
            <Centroid><Location><Latitude>40.92</Latitude><Longitude>14.32</Longitude></Location></Centroid></StopPlace>
        </stopPlaces></SiteFrame>
        """;

    // Lower-case token, and the feed ships its own single Authority whose last id segment does not
    // match the Operator's. The Network's org ref is Operator-typed, so the Network arm runs.
    static final String LOWER_OPERATOR_WITH_AUTHORITY = """
        <ServiceFrame id="sf" version="1">
          <Network id="IT:ITI1:network:1" version="1"><Name>Net</Name><OperatorRef ref="IT:ITI1:operator:AT"/></Network>
          <lines>
            <Line id="IT:ITI1:Line:1" version="1"><Name>Lower</Name><TransportMode>bus</TransportMode><OperatorRef ref="IT:ITI1:operator:AT"/></Line>
          </lines>
        </ServiceFrame>
        <ResourceFrame id="rf" version="1"><organisations>
          <Authority id="IT:ITI1:authority:RT" version="1"><Name>Regione</Name></Authority>
          <Operator id="IT:ITI1:operator:AT" version="1"><Name>Op AT</Name></Operator>
        </organisations></ResourceFrame>
        """;

    public static void main(String[] args) {
        Runner.run(TestItRapDbToDb.class);
    }

    /// Load the fixture, add the GroupOfStopPlaces referrer, run the transform.
    private static TestStore run(TestStore db) throws Exception {
        db.loadNetex(NETEX);
        GroupOfStopPlaces group = new GroupOfStopPlaces();
        group.setId("grp1");
        group.setVersion("1");
        StopPlaceRefs_RelStructure members = new StopPlaceRefs_RelStructure();
        members.getStopPlaceRef().add(FACTORY.createStopPlaceRef(
                new StopPlaceRefStructure().withRef("IT:ITF3:StopPlace:dup1").withVersion("1")));
        members.getStopPlaceRef().add(FACTORY.createStopPlaceRef(
                new StopPlaceRefStructure().withRef("IT:ITF3:StopPlace:dup2").withVersion("1")));
        group.setMembers(members);
        try (Txn txn = db.store.rwTxn()) {
            db.store.insertAnyObjects(txn, List.of(group));
            txn.commit();
        }
        return db.runDbToDb(ItRapDbToDb::applyItRap);
    }

    public static void testOverlaysAndCloneEdgeCases(TestStore db) throws Exception {
        TestStore target = run(db);
        try (Txn txn = target.store.roTxn()) {
            Map<String, List<StopPlace>> stops = new LinkedHashMap<>();
            for (Object o : target.store.iterOnlyObjects(txn, StopPlace.class)) {
                StopPlace sp = (StopPlace) o;
                stops.computeIfAbsent(sp.getId(), k -> new ArrayList<>()).add(sp);
            }

            // The duplicate-stop merge no longer runs at this tier -- it runs once nationally, so
            // dup1 and dup2 must BOTH still be here, each keeping its own quays and its own PSA.
            // TestItMergeDbToDb.testNationalMergeCollapsesDuplicateStops covers the merge itself.
            Check.that(stops.containsKey("IT:ITF3:StopPlace:dup1"), "dup1 survives -- no merge here");
            Check.equals(2, stops.get("IT:ITF3:StopPlace:dup1").size(), "both stored versions of dup1");
            List<StopPlace> dup2Rows = stops.get("IT:ITF3:StopPlace:dup2");
            Check.equals(1, dup2Rows.size(), "exactly one dup2 row");
            Set<String> quayIds = new HashSet<>();
            for (JAXBElement<?> el : dup2Rows.get(0).getQuays().getQuayRefOrQuay()) {
                if (el.getValue() instanceof Quay q) quayIds.add(q.getId());
            }
            Check.equals(Set.of("IT:ITF3:Quay:dup2a", "IT:ITF3:Quay:dup2b"), quayIds,
                    "dup2 keeps its own quays and absorbs none");
            Map<String, PassengerStopAssignment> psas = new LinkedHashMap<>();
            for (Object o : target.store.iterOnlyObjects(txn, PassengerStopAssignment.class)) {
                PassengerStopAssignment p = (PassengerStopAssignment) o;
                psas.put(p.getId(), p);
            }
            Check.equals("IT:ITF3:StopPlace:dup1",
                    psas.get("pdup-1").getStopPlaceRef().getValue().getRef(), "PSA left pointing at dup1");

            // Version collapse: the authority-less Line survives as ONE row (the overlay's version).
            List<Line> lines = new ArrayList<>();
            for (Object o : target.store.iterOnlyObjects(txn, Line.class)) lines.add((Line) o);
            List<String> idVersions = new ArrayList<>();
            for (Line ln : lines) idVersions.add(ln.getId() + "|" + ln.getVersion());
            Check.equals(List.of("IT:ITF3:Line:noauth|2"), idVersions, "single collapsed Line row");
            Check.equals("IT:ITF3:Authority:01234:AV1", lines.get(0).getAuthorityRef().getRef(),
                    "Line authority backfilled");
            Set<String> authorityIds = new HashSet<>();
            for (Object o : target.store.iterOnlyObjects(txn, Authority.class)) {
                authorityIds.add(((Authority) o).getId());
            }
            Check.that(authorityIds.contains("IT:ITF3:Authority:01234:AV1"), "synthesised Authority present");

            // SSP synthesis no longer runs at this tier -- it moved to ItMerge's plan phase, after
            // id qualification, so that the Quay ids it derives cannot collide across operators.
            // TestSspSynthesis covers what it mints; TestItMergeDbToDb covers it running there.
            Check.that(!stops.containsKey("IT:reg:StopPlace:lone1"),
                    "the lone SSP is left for the national synthesis, not minted per operator");

            // Centroid backfill, which DOES stay per operator (it reads feed-published quays).
            List<StopPlace> nocentRows = stops.get("IT:ITF3:StopPlace:nocent");
            Check.equals(1, nocentRows.size(), "exactly one nocent row");
            Check.that(nocentRows.get(0).getCentroid() != null, "nocent centroid backfilled");
            Check.that(nocentRows.get(0).getCentroid().getLocation() != null, "nocent centroid location set");

            // The referrer survived the clone. With no stop drops at this tier BOTH its edges
            // resolve; the drop-and-dangle case moved to the national merge along with the merge.
            int groups = 0;
            for (Object ignored : target.store.iterOnlyObjects(txn, GroupOfStopPlaces.class)) groups++;
            Check.equals(1, groups, "exactly one GroupOfStopPlaces");
            long groupFk = target.store.lookupFullKey(txn, "grp1", "1", GroupOfStopPlaces.class);
            Check.that(groupFk != -1, "group present in the id index");
            // Outward edges only.
            Set<String> resolved = new HashSet<>();
            for (long[] e : target.store.iterEdges(txn)) {
                if (e[0] != groupFk) continue;
                resolved.add(((EntityStructure) target.store.loadObjectByFullKey(txn, e[1])).getId());
            }
            Check.that(resolved.contains("IT:ITF3:StopPlace:dup2"), "group -> dup2 edge resolved");
            Check.that(resolved.contains("IT:ITF3:StopPlace:dup1"), "group -> dup1 edge resolved too");
            Check.equals(Set.of(), unresolvedRefIds(target.store, txn, groupFk),
                    "and nothing is left dangling");
        }
    }

    /// Upper-case token, no Authority in the store: the Operator has to be recognised anyway, and
    /// the id minted from it carries the canonical `:Authority:` token, not the source's spelling.
    public static void testUpperCaseOperatorTokenMintsCanonicalAuthority(TestStore db) throws Exception {
        db.loadNetex(UPPER_OPERATOR);
        TestStore target = db.runDbToDb(ItRapDbToDb::applyItRap);
        try (Txn txn = target.store.roTxn()) {
            Line line = (Line) target.store.iterOnlyObjects(txn, Line.class).iterator().next();
            Check.equals("IT:ITC4:Authority:LOM-1", line.getAuthorityRef().getRef(),
                    "upper-case Operator token still yields an authorityRef");
            Set<String> authorityIds = new HashSet<>();
            for (Object o : target.store.iterOnlyObjects(txn, Authority.class)) {
                authorityIds.add(((Authority) o).getId());
            }
            Check.equals(Set.of("IT:ITC4:Authority:LOM-1"), authorityIds,
                    "the minted id carries the canonical token, not :OPERATOR:'s case");
        }
    }

    /// Lower-case token, and the feed already ships one Authority. The Network's Operator-typed org
    /// ref is repaired onto that Authority rather than a freshly minted one — the suffix match
    /// cannot see it (operator `AT` vs authority `RT`), so this is the soleAuthority fallback.
    public static void testLowerCaseOperatorTokenReusesTheSoleAuthority(TestStore db) throws Exception {
        db.loadNetex(LOWER_OPERATOR_WITH_AUTHORITY);
        TestStore target = db.runDbToDb(ItRapDbToDb::applyItRap);
        try (Txn txn = target.store.roTxn()) {
            Network net = (Network) target.store.iterOnlyObjects(txn, Network.class).iterator().next();
            var org = net.getTransportOrganisationRef().getValue();
            Check.that(org instanceof AuthorityRefStructure, "Network org ref is now Authority-typed");
            Check.equals("IT:ITI1:authority:RT", org.getRef(), "and names the feed's own Authority");

            Line line = (Line) target.store.iterOnlyObjects(txn, Line.class).iterator().next();
            Check.equals("IT:ITI1:authority:RT", line.getAuthorityRef().getRef(),
                    "the Line agrees with the Network");
            Set<String> authorityIds = new HashSet<>();
            for (Object o : target.store.iterOnlyObjects(txn, Authority.class)) {
                authorityIds.add(((Authority) o).getId());
            }
            Check.equals(Set.of("IT:ITI1:authority:RT"), authorityIds, "no redundant Authority minted");
        }
    }

    /// The swap is undone on the StopPlace and on its embedded Quay, and only where ShortName
    /// actually holds a name. `bothfixes` also pins the composition of the two StopPlace overlays:
    /// the centroid backfill runs first, and the name repair has to land on ITS object.
    public static void testCodeNamedStopsTakeTheirNameFromShortName(TestStore db) throws Exception {
        db.loadNetex(CODE_NAMES);
        TestStore target = db.runDbToDb(ItRapDbToDb::applyItRap);
        try (Txn txn = target.store.roTxn()) {
            Map<String, StopPlace> stops = new LinkedHashMap<>();
            for (Object o : target.store.iterOnlyObjects(txn, StopPlace.class)) {
                StopPlace sp = (StopPlace) o;
                stops.put(sp.getId(), sp);
            }

            StopPlace milano = stops.get("IT:ITF4:StopPlace:830001700_ReteTrenitalia_GTFS");
            Check.equals("MILANO CENTRALE", Mls.text(milano.getName()), "the station is named");
            Check.equals("MILANO CENTRALE", Mls.text(milano.getShortName()), "ShortName is left alone");
            Check.equals("830001700", milano.getPrivateCode().getValue(), "PrivateCode is left alone");
            Check.equals("830001700", milano.getPublicCode().getValue(), "PublicCode is left alone");
            Check.equals("MILANO CENTRALE", Mls.text(onlyQuay(milano).getName()), "the quay too");

            StopPlace both = stops.get("IT:ITF3:StopPlace:bothfixes");
            Check.equals("Piazza Mazzini", Mls.text(both.getName()), "named");
            Check.that(both.getCentroid() != null && both.getCentroid().getLocation() != null,
                    "and the centroid backfill this object also needed survived the name repair");

            Check.equals("7085", Mls.text(stops.get("IT:ITF3:StopPlace:codeshort").getName()),
                    "a ShortName that is itself a code promotes nothing");
            Check.equals("0986", Mls.text(stops.get("IT:ITF3:StopPlace:noshort").getName()),
                    "nor does an absent one");
            Check.equals("Via Stazione", Mls.text(stops.get("IT:ITF3:StopPlace:named").getName()),
                    "a stop that already has a name keeps it");
        }
    }

    /// The one Quay a fixture stop embeds.
    private static Quay onlyQuay(StopPlace sp) {
        for (JAXBElement<?> el : sp.getQuays().getQuayRefOrQuay()) {
            if (el.getValue() instanceof Quay q) return q;
        }
        throw new IllegalStateException("no embedded quay on " + sp.getId());
    }

    /// The ids of an object's UNRESOLVED-map refs.
    private static Set<String> unresolvedRefIds(Store store, Txn txn, long fullKey) {
        Set<String> out = new HashSet<>();
        for (UnresolvedRow row : store.iterUnresolved(txn)) {
            if (row.referrerFullKey() != fullKey) continue;
            byte[] enc = row.encodedTarget();
            int nul = 0;
            while (nul < enc.length && enc[nul] != 0) nul++;
            out.add(new String(enc, 0, nul, StandardCharsets.UTF_8));
        }
        return out;
    }
}
