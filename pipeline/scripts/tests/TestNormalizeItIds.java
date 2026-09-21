package tests;

// Italian RAP id-space correction: the deviating spaces move onto `IT:<NUTS>:`, every reference and
// every embedded id follows, the target of a rule that needs one is the feed's OWN region, and a
// conforming feed comes through untouched.

import fix.NormalizeItIds;
import jakarta.xml.bind.JAXBElement;
import noi.netex.model.Line;
import noi.netex.model.Operator;
import noi.netex.model.PassengerStopAssignment;
import noi.netex.model.Quay;
import noi.netex.model.ScheduledStopPoint;
import noi.netex.model.ServiceJourneyPattern;
import noi.netex.model.StopPlace;
import noi.netex.model.StopPointInJourneyPattern;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.Runner;
import toolkit.test.TestStore;
import transformers.feedfix.ItIdSpaces;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

public class TestNormalizeItIds {

    /// Marche in miniature: every id and every ref under the undeclared `epd:` token, one embedded
    /// Quay, and the two refs that must NOT move — `epip:` (a TypeOfFrameRef) and the feed's own
    /// declared codespace.
    private static final String MARCHE = """
        <SiteFrame id="epd:IT:ITI3:SiteFrame-EU_PI_STOP" version="1"><stopPlaces>
          <StopPlace id="epd:IT:ITI3:StopPlace:ATMA_20820" version="1"><Name>Ancona</Name>
            <quays>
              <Quay id="epd:IT:ITI3:Quay:ATMA_20820" version="1"><Name>Ancona</Name></Quay>
            </quays></StopPlace>
        </stopPlaces></SiteFrame>
        <ServiceFrame id="epd:IT:ITI3:ServiceFrame-EU_PI_NETWORK" version="1">
          <scheduledStopPoints>
            <ScheduledStopPoint id="epd:IT:ITI3:ScheduledStopPoint:ATMA_20820" version="any">
              <Name>Ancona</Name>
            </ScheduledStopPoint>
          </scheduledStopPoints>
          <stopAssignments>
            <PassengerStopAssignment id="epd:IT:ITI3:PassengerStopAssignment:ATMA_20820" version="1" order="1">
              <ScheduledStopPointRef ref="epd:IT:ITI3:ScheduledStopPoint:ATMA_20820"/>
              <StopPlaceRef ref="epd:IT:ITI3:StopPlace:ATMA_20820"/>
              <QuayRef ref="epd:IT:ITI3:Quay:ATMA_20820"/>
            </PassengerStopAssignment>
          </stopAssignments>
          <journeyPatterns>
            <ServiceJourneyPattern id="epd:IT:ITI3:ServiceJourneyPattern:CONTRAM_0002" version="1">
              <TypeOfJourneyPatternRef ref="epip:EU_PI_NETWORK_OFFER" versionRef="any"/>
              <RouteView>
                <LineRef ref="epd:IT:ITI3:Line:ADRIABUS_13" version="any"/>
              </RouteView>
              <pointsInSequence>
                <StopPointInJourneyPattern id="epd:IT:ITI3:StopPointInJourneyPattern:CONTRAM_0002-1" version="1" order="1">
                  <ScheduledStopPointRef ref="epd:IT:ITI3:ScheduledStopPoint:ATMA_20820" version="any"/>
                </StopPointInJourneyPattern>
              </pointsInSequence>
            </ServiceJourneyPattern>
          </journeyPatterns>
        </ServiceFrame>
        """;

    /// A Lazio feed: the region's own space on everything but the one Operator, which says `IT14`,
    /// plus the Line whose OperatorRef points at it — the ~3.8k-per-feed reference arm in miniature.
    private static final String LAZIO = """
        <ResourceFrame id="rf" version="1"><organisations>
          <Operator id="IT:IT14:Operator:06043731006:Cotral:0" version="1"><Name>Cotral</Name></Operator>
        </organisations></ResourceFrame>
        <ServiceFrame id="sf" version="1">
          <scheduledStopPoints>
            <ScheduledStopPoint id="IT:ITI4:ScheduledStopPoint:1" version="any"><Name>Roma</Name></ScheduledStopPoint>
            <ScheduledStopPoint id="IT:ITI4:ScheduledStopPoint:2" version="any"><Name>Tivoli</Name></ScheduledStopPoint>
          </scheduledStopPoints>
          <lines>
            <Line id="IT:ITI4:Line:L1" version="1"><Name>Roma-Tivoli</Name>
              <OperatorRef ref="IT:IT14:Operator:06043731006:Cotral:0" version="1"/>
            </Line>
          </lines>
        </ServiceFrame>
        """;

    /// `IT::Operator:x` — an empty NUTS token — in a store whose own space is `%s`. The same input
    /// id, so the two instantiations differ only in what the surrounding feed votes for.
    private static final String EMPTY_NUTS = """
        <ResourceFrame id="rf" version="1"><organisations>
          <Operator id="IT::Operator:00057190258" version="1"><Name>Dolomitibus</Name></Operator>
        </organisations></ResourceFrame>
        <ServiceFrame id="sf" version="1"><scheduledStopPoints>
          <ScheduledStopPoint id="IT:%1$s:ScheduledStopPoint:1" version="any"><Name>A</Name></ScheduledStopPoint>
          <ScheduledStopPoint id="IT:%1$s:ScheduledStopPoint:2" version="any"><Name>B</Name></ScheduledStopPoint>
          <ScheduledStopPoint id="IT:%1$s:ScheduledStopPoint:3" version="any"><Name>C</Name></ScheduledStopPoint>
        </scheduledStopPoints></ServiceFrame>
        """;

    /// A feed that already conforms — the fast path's input.
    private static final String CONFORMING = """
        <ServiceFrame id="sf" version="1"><scheduledStopPoints>
          <ScheduledStopPoint id="IT:ITC4:ScheduledStopPoint:1" version="any"><Name>Milano</Name></ScheduledStopPoint>
          <ScheduledStopPoint id="IT:ITC4:ScheduledStopPoint:2" version="any"><Name>Monza</Name></ScheduledStopPoint>
        </scheduledStopPoints></ServiceFrame>
        """;

    public static void main(String[] args) {
        Runner.run(TestNormalizeItIds.class);
    }

    public static void testEpdStrippedAcrossIdsRefsAndEmbeddedQuays(TestStore db) throws Exception {
        db.loadNetex(MARCHE);
        TestStore target = db.runDbToDb(NormalizeItIds::apply);

        Set<String> ids = new TreeSet<>();
        StopPlace sp = null;
        PassengerStopAssignment psa = null;
        ServiceJourneyPattern sjp = null;
        try (Txn txn = target.store.roTxn()) {
            for (Class<?> clazz : target.store.dbNames(txn)) {
                for (Object o : target.store.iterOnlyObjects(txn, clazz)) {
                    if (o instanceof StopPlace s) { sp = s; ids.add(s.getId()); }
                    else if (o instanceof PassengerStopAssignment p) { psa = p; ids.add(p.getId()); }
                    else if (o instanceof ServiceJourneyPattern j) { sjp = j; ids.add(j.getId()); }
                    else if (o instanceof ScheduledStopPoint s) ids.add(s.getId());
                }
            }
        }

        for (String id : ids) {
            Check.that(id.startsWith("IT:ITI3:"), "top-level id moved to IT:ITI3: -- " + id);
        }
        Check.equals(4, ids.size(), "four top-level objects survive the correction");

        Quay quay = (Quay) ((JAXBElement<?>) sp.getQuays().getQuayRefOrQuay().get(0)).getValue();
        Check.equals("IT:ITI3:Quay:ATMA_20820", quay.getId(),
                "the EMBEDDED quay id moves too -- it is neither a top-level id nor a reference");

        Check.equals("IT:ITI3:ScheduledStopPoint:ATMA_20820",
                psa.getScheduledStopPointRef().getValue().getRef(), "assignment SSP ref follows");
        Check.equals("IT:ITI3:StopPlace:ATMA_20820",
                psa.getStopPlaceRef().getValue().getRef(), "assignment stop-place ref follows");
        Check.equals("IT:ITI3:Quay:ATMA_20820",
                psa.getQuayRef().getValue().getRef(), "assignment quay ref follows");

        StopPointInJourneyPattern point = (StopPointInJourneyPattern) sjp.getPointsInSequence()
                .getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern().get(0);
        Check.equals("IT:ITI3:ScheduledStopPoint:ATMA_20820",
                point.getScheduledStopPointRef().getValue().getRef(), "pattern point ref follows");
        Check.equals("IT:ITI3:StopPointInJourneyPattern:CONTRAM_0002-1", point.getId(),
                "the embedded point's own id moves");
        Check.equals("epip:EU_PI_NETWORK_OFFER", sjp.getTypeOfJourneyPatternRef().getRef(),
                "a ref outside the Italian spaces is left alone");

        // RouteView is id-less in the source, so the loader mints its id from the enclosing pattern
        // -- and RouteView is a DerivedViewStructure, NOT an EntityStructure, so an
        // `instanceof EntityStructure` arm walks straight past it.
        Check.equals("IT:ITI3:RouteView:CONTRAM_0002", sjp.getRouteView().getId(),
                "an id-bearing embedded object outside the EntityStructure hierarchy moves too");
        Check.equals("IT:ITI3:Line:ADRIABUS_13", sjp.getRouteView().getLineRef().getValue().getRef(),
                "and the ref it holds");
    }

    public static void testIT14OperatorAndItsRefsMoveToTheRegionSpace(TestStore db) throws Exception {
        db.loadNetex(LAZIO);
        TestStore target = db.runDbToDb(NormalizeItIds::apply);

        Operator op;
        Line line;
        try (Txn txn = target.store.roTxn()) {
            op = (Operator) target.store.iterOnlyObjects(txn, Operator.class).iterator().next();
            line = (Line) target.store.iterOnlyObjects(txn, Line.class).iterator().next();
        }
        Check.equals("IT:ITI4:Operator:06043731006:Cotral:0", op.getId(), "IT14 -> the feed's ITI4");
        Check.equals("IT:ITI4:Operator:06043731006:Cotral:0", line.getOperatorRef().getRef(),
                "the OperatorRef follows");
    }

    public static void testEmptyNutsTokenTakesTheFeedsOwnRegion(TestStore db) throws Exception {
        Check.equals("IT:ITH3:Operator:00057190258", operatorAfterFix(db, "ITH3"),
                "IT:: resolves to ITH3 in an ITH3 feed");
        Check.equals("IT:ITH1:Operator:00057190258", operatorAfterFix(db, "ITH1"),
                "and to ITH1 in an ITH1 feed -- from the identical input id");
    }

    /// Load one instantiation of EMPTY_NUTS into its own store, correct it, return the Operator id.
    private static String operatorAfterFix(TestStore parent, String nuts) throws Exception {
        try (TestStore db = TestStore.create(parent.format)) {
            db.loadNetex(EMPTY_NUTS.formatted(nuts));
            TestStore target = db.runDbToDb(NormalizeItIds::apply);
            try (Txn txn = target.store.roTxn()) {
                return ((Operator) target.store.iterOnlyObjects(txn, Operator.class)
                        .iterator().next()).getId();
            }
        }
    }

    public static void testConformingFeedComesThroughUnchanged(TestStore db) throws Exception {
        db.loadNetex(CONFORMING);
        TestStore target = db.runDbToDb(NormalizeItIds::apply);
        Check.equals(db.exportNetex(), target.exportNetex(),
                "the fast path is a verbatim clone, object for object");
    }

    public static void testCollidingCorrectionRefuses(TestStore db) throws Exception {
        db.loadNetex("""
            <ResourceFrame id="rf" version="1"><organisations>
              <Operator id="IT:IT14:Operator:1" version="1"><Name>A</Name></Operator>
              <Operator id="IT:ITI4:Operator:1" version="1"><Name>B</Name></Operator>
              <Operator id="IT:ITI4:Operator:2" version="1"><Name>C</Name></Operator>
              <Operator id="IT:ITI4:Operator:3" version="1"><Name>D</Name></Operator>
            </organisations></ResourceFrame>
            """);
        String message = null;
        try {
            db.runDbToDb(NormalizeItIds::apply);
        } catch (IllegalStateException e) {
            message = e.getMessage();
        }
        Check.that(message != null && message.contains("would collide"),
                "correcting onto an id the store already holds refuses instead of merging: " + message);
    }

    /// The rules as pure string functions, including the two the store fixtures above do not reach.
    public static void testRuleTable(TestStore db) {
        Check.equals("IT:ITI3:Line:CONTRAM_0002",
                ItIdSpaces.rewrite("epd:IT:ITI3:Line:CONTRAM_0002", "IT:ITI3"),
                "the epd: strip needs no home space");
        Check.equals("IT:ITC4:ValidBetween_EU_PI_STOP_OFFER:ita",
                ItIdSpaces.rewrite("epd:it:ValidBetween_EU_PI_STOP_OFFER:ita", "IT:ITC4"),
                "the lower-case epd: form carries no NUTS token, so BOTH tokens are replaced "
                + "rather than the first one dropped");
        Check.equals("IT:ITH1:Operator:005:", ItIdSpaces.rewrite("it:apb:Operator:005:", "IT:ITH1"),
                "the undeclared apb space");
        Check.equals("IT:ITF3:VehicleModel:001", ItIdSpaces.rewrite("IT-ITF3:VM:001", "IT:ITF3"),
                "the hyphen form, matched against the feed's OWN space");
        Check.equals("IT:ITF3:VehicleType:001", ItIdSpaces.rewrite("IT-ITF3:VT:001", "IT:ITF3"),
                "and its VehicleType half");
        Check.equals("IT-ITF3:VM:001", ItIdSpaces.rewrite("IT-ITF3:VM:001", "IT:ITC4"),
                "which is why an ITC4 feed does not touch an ITF3-hyphen id");
        Check.equals("IT:ITC4:ScheduledStopPoint:1",
                ItIdSpaces.rewrite("IT:ITC4:ScheduledStopPoint:1", "IT:ITC4"),
                "a conforming id is returned unchanged");
        Check.equals("it:apb:Operator:005:", ItIdSpaces.rewrite("it:apb:Operator:005:", null),
                "with no home space the rules that need one are skipped, not guessed");
        Set<String> distinct = new LinkedHashSet<>(Set.of(ItIdSpaces.RULES));
        Check.equals(ItIdSpaces.RULES.length, distinct.size(), "rule names are distinct");
    }
}
