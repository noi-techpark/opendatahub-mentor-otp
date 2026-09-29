package tests;

// Unit tests for the pure helpers behind `make verify-crossborder`. No NeTEx fixtures: every id
// below is copied verbatim out of the national export.

import toolkit.test.Check;
import transformers.xb.XbIds;
import toolkit.test.Runner;
import tools.VerifyCrossborder;
import tools.VerifyCrossborder.Handover;
import tools.VerifyCrossborder.Stop;

import java.util.Arrays;
import java.util.List;

public class TestVerifyCrossborder {

    public static void main(String[] args) {
        Runner.run(TestVerifyCrossborder.class);
    }

    // ------------------------------------------------------- the id defect

    /// Every ÖBB and STA journey id ends with a colon, so an identity slot taken as everything
    /// after the last colon comes out empty. These are the two shapes that produces, against real
    /// ids.
    public static void testEmptyIdentitySlotIsDetected() {
        // ÖBB on the left, Swiss on the right: the left slot collapses to "::".
        Check.that(VerifyCrossborder.hasEmptyIdentitySlot(
                        "AT-CH:ServiceJourneyInterchange:294::120083_0_12-294-6FC1-0083"),
                "an OeBB left-hand journey leaves '::'");
        // Trenitalia on the left, ÖBB on the right: the right slot leaves a trailing ":".
        Check.that(VerifyCrossborder.hasEmptyIdentitySlot(
                        "IT-AT:ServiceJourneyInterchange:294:120083_0_12-294-6FC1-0083:"),
                "an OeBB right-hand journey leaves a trailing ':'");
        Check.that(!VerifyCrossborder.hasEmptyIdentitySlot(
                        "IT-CH:ServiceJourneyInterchange:21:60083_0_6-21-4E6E-0083:21-003_9102AY.j26_359"),
                "two Trenitalia/Swiss ids fill both slots");
    }

    /// Both slots empty makes the id a pure function of corridor and train number, so two journeys
    /// of that number in that corridor overwrite each other in the store. 37 of the shipped 861
    /// look like this.
    public static void testNoIdentityAtAllIsDetected() {
        Check.that(VerifyCrossborder.hasNoIdentity("AT-CH:ServiceJourneyInterchange:1826::"),
                "STA on one side and OeBB on the other leaves nothing but the train number");
        Check.that(!VerifyCrossborder.hasNoIdentity(
                        "AT-CH:ServiceJourneyInterchange:294::120083_0_12-294-6FC1-0083"),
                "one empty slot is not both");
        Check.that(!VerifyCrossborder.hasNoIdentity(null), "a null id is not a collision");
    }

    // ------------------------------------------------------- the country defect

    /// `XbIds.feedCountry` itself resolves the lower-case `it:apb:` STA codespace, so every
    /// JOURNEY id is covered by the pipeline's own table. `feedCountryLoose` still exists for the
    /// STOP id spaces the corridor profile has no reason to carry.
    public static void testStaCodespaceNeedsTheLooseLookup() {
        Check.equals("IT", XbIds.feedCountry(
                        "it:apb:ServiceJourney:034008S-SAD_Bahn-32-13-20820:TA:"),
                "Geo resolves the STA codespace directly");
        Check.equals("IT", VerifyCrossborder.feedCountryLoose(
                        "it:apb:ServiceJourney:034008S-SAD_Bahn-32-13-20820:TA:"),
                "and the report agrees with it");
        Check.equals("DE", VerifyCrossborder.feedCountryLoose("de:05315:StopPlace:1"),
                "a stop-only id space still needs the loose lookup");
        Check.equals("IT", VerifyCrossborder.feedCountryLoose("IT:ITI3:StopPlace:1"),
                "Marche does not, any more: Geo answers for it once normalize-it-ids has run");
        Check.equals("IT", VerifyCrossborder.feedCountryLoose(
                        "IT::VehicleJourney:railTRENITALIA:10083_0_1-1840-1677-0083"),
                "national Trenitalia");
        Check.equals("AT", VerifyCrossborder.feedCountryLoose(
                        "at:obb:ServiceJourney:120I3-OEBB-18-1-65400:s9q10:"), "OeBB");
        Check.equals("CH", VerifyCrossborder.feedCountryLoose("ch:1:ServiceJourney:ch:1:sjyid:100579"), "CH");
        Check.isNull(VerifyCrossborder.feedCountryLoose("gtfs:IT-ITI3-ATMA:whatever"),
                "an unknown prefix stays null rather than guessing");
    }

    /// The corridor comes from the two JOURNEYS, not the two stops -- a correctly anchored link
    /// puts both ends on one StopPlace, so stop countries would make every good link look domestic.
    /// The bucket is sorted, so a link either way round lands in the same one.
    public static void testCorridorIsSymmetricAndJourneyDerived() {
        String at = "at:obb:ServiceJourney:120I3-OEBB-18-1-65400:s9q10:";
        String it = "IT::VehicleJourney:railTRENITALIA:120083_0_12-294-6FC1-0083";
        Check.equals("AT", VerifyCrossborder.countryOfJourney(at, "IT:ITF3:StopPlace:830008217_x"),
                "the journey decides, not the Italian stop it hands over at");
        Check.equals("IT", VerifyCrossborder.countryOfJourney(it, "IT:ITF3:StopPlace:830008217_x"), "IT");
        // The stop is only consulted when the journey id carries no known prefix.
        Check.equals("CH", VerifyCrossborder.countryOfJourney("weird:id", "ch:2:StopPlace:8509404"),
                "an unrecognised journey id falls back to its stop");
    }

    /// The `<cc>-<cc>` prefix the coupling stamped in, normalised to the same sorted bucket so the
    /// report can compare it against the resolved corridor.
    public static void testIdPrefixCorridor() {
        Check.equals("AT-IT", VerifyCrossborder.idPrefixCorridor("IT-AT:ServiceJourneyInterchange:1840:a:b"),
                "IT-AT and AT-IT are one bucket");
        Check.equals("AT-IT", VerifyCrossborder.idPrefixCorridor("AT-IT:ServiceJourneyInterchange:1840:a:b"),
                "either order");
        Check.isNull(VerifyCrossborder.idPrefixCorridor(
                        "ch:1:ServiceJourneyInterchange:92501-AAG-11936-1-85500"),
                "a domestic Swiss interchange carries no corridor prefix");
    }

    /// The Trenitalia RAP feeds put the UIC code in `Name` and the station in `ShortName`, so a
    /// report that reads `Name` alone labels Roma Tiburtina "830008217".
    public static void testStopLabelPrefersAUsableName() {
        Check.equals("ROMA TIBURTINA",
                new Stop("IT:ITF3:StopPlace:830008217_x", "830008217", "ROMA TIBURTINA", null, null).label(),
                "an all-digit Name falls through to ShortName");
        Check.equals("Buchs SG",
                new Stop("ch:2:StopPlace:8509404", "Buchs SG", null, null, null).label(), "a real name wins");
        Check.equals("at:48:1313", new Stop("at:48:1313", null, null, null, null).label(),
                "a Verbund stop publishes neither, so the id is the only label left");
    }

    // ------------------------------------------------------- the handover split

    // Real coordinates, copied out of shared-02-sites.xml of the 2026-08-26 export.
    static final Stop LIENZ = stop("at:47:3873", "Lienz Bahnhof", 46.828686, 12.771133);
    static final Stop WEITLANBRUNN = stop("at:47:3880", "Weitlanbrunn Bahnhof", 46.742535, 12.395511);
    static final Stop INNSBRUCK_AT = stop("at:47:1187", "Innsbruck Hauptbahnhof", 47.263533, 11.400277);
    static final Stop INNSBRUCK_CH = stop("ch:2:StopPlace:8101187", "Innsbruck Hbf", 47.263332, 11.40051);
    static final Stop CHIASSO = stop("ch:2:StopPlace:8505307", "Chiasso", 45.83217, 9.031446);
    static final Stop COMO = stop("IT:ITC3:StopPlace:railTRENITALIA:830001307",
            "COMO S.GIOVANNI", 45.808948517, 9.0723314285);

    private static Stop stop(String id, String name, double lat, double lon) {
        return new Stop(id, name, null, lat, lon);
    }

    /// A pair 28 m apart is two copies of one station that the rail consolidation would have
    /// folded; a pair 30 km apart is two stations no stop merge can help. The distance bucket is
    /// what tells the two apart.
    public static void testDistanceBucketSeparatesConsolidationMissesFromRealDistance() {
        // The two Innsbruck Hbf StopPlaces, both still in the shipped export.
        Check.that(VerifyCrossborder.distM(INNSBRUCK_AT, INNSBRUCK_CH) < 50,
                "the two Innsbruck copies are within 50 m of each other");
        Check.equals(VerifyCrossborder.DISTANCE_BUCKETS.get(0),
                VerifyCrossborder.distanceBucket(VerifyCrossborder.distM(INNSBRUCK_AT, INNSBRUCK_CH)),
                "and land in the consolidation-miss bucket");
        // The handover pair that carries 203 of the 572: Lienz to Weitlanbrunn, 30 km.
        Check.that(VerifyCrossborder.distM(LIENZ, WEITLANBRUNN) > 20_000,
                "Lienz to Weitlanbrunn is over 20 km");
        Check.equals(VerifyCrossborder.DISTANCE_BUCKETS.get(3),
                VerifyCrossborder.distanceBucket(VerifyCrossborder.distM(LIENZ, WEITLANBRUNN)),
                "so it cannot be a consolidation miss");
        // The border pair: Chiasso to Como S.Giovanni, 4 km.
        Check.equals(VerifyCrossborder.DISTANCE_BUCKETS.get(1),
                VerifyCrossborder.distanceBucket(VerifyCrossborder.distM(CHIASSO, COMO)),
                "the Chiasso/Como border pair is 440 m - 5 km apart");
        Check.equals(VerifyCrossborder.DISTANCE_BUCKETS.get(4),
                VerifyCrossborder.distanceBucket(
                        VerifyCrossborder.distM(LIENZ, new Stop("x", null, null, null, null))),
                "a stop with no coordinate gets its own bucket rather than a wrong number");
    }

    /// Two handover stops in ONE country mean the two legs overlap on domestic track; two
    /// countries mean complementary legs meeting at a border station pair. An unknown id space is
    /// a third answer, not "different".
    public static void testSameCountryHandover() {
        Check.equals(Boolean.TRUE,
                VerifyCrossborder.sameCountryHandover("at:47:3873", "at:47:3880"),
                "Lienz and Weitlanbrunn are both Austrian");
        Check.equals(Boolean.FALSE,
                VerifyCrossborder.sameCountryHandover("ch:2:StopPlace:8505307",
                        "IT:ITC3:StopPlace:railTRENITALIA:830001307"),
                "Chiasso and Como are the border pair");
        Check.isNull(VerifyCrossborder.sameCountryHandover("gtfs:whatever", "at:47:3873"),
                "an id space nobody has taught the report stays unknown");
    }

    /// The classification pass E reports. `selectChMatches` admits a counterpart only on two shared
    /// canonical StopPlaces, so SHARED_AVAILABLE is the expected class for a candidate-derived
    /// link -- the station to anchor on exists and the builder does not use it.
    public static void testHandoverClassification() {
        // The Lienz shape: the SAD leg runs to Lienz, the OeBB leg runs Weitlanbrunn -> Lienz, so
        // the continuing leg serves the stop the passenger leaves at.
        List<String> sad = Arrays.asList("it:sc", "at:47:3880", "at:47:3873");
        List<String> obb = Arrays.asList("at:47:3880", "at:47:3873");
        Check.equals(Handover.SHARED_AVAILABLE,
                VerifyCrossborder.classify(sad, obb, "at:47:3873", "at:47:3880"),
                "the OeBB leg serves Lienz, so the link could have anchored there");
        Check.that(VerifyCrossborder.fullyCovered(sad, obb),
                "and every OeBB stop is on the SAD leg -- the redundant-leg shape");
        Check.that(VerifyCrossborder.backwards(obb, "at:47:3873", "at:47:3880"),
                "joining at Weitlanbrunn after leaving at Lienz runs the link backwards");

        // The border pair: neither leg serves the other's handover stop, and they share nothing.
        List<String> north = Arrays.asList("ch:zh", "ch:2:StopPlace:8505307");
        List<String> south = Arrays.asList("IT:ITC3:StopPlace:railTRENITALIA:830001307", "IT:mi");
        Check.equals(Handover.DISJOINT,
                VerifyCrossborder.classify(north, south,
                        "ch:2:StopPlace:8505307", "IT:ITC3:StopPlace:railTRENITALIA:830001307"),
                "complementary legs that meet across the border share no station");
        Check.that(!VerifyCrossborder.backwards(south,
                        "ch:2:StopPlace:8505307", "IT:ITC3:StopPlace:railTRENITALIA:830001307"),
                "a leg that does not serve the leaving stop cannot be backwards");

        // Shared somewhere, but not at either handover stop.
        Check.equals(Handover.SHARED_ELSEWHERE,
                VerifyCrossborder.classify(Arrays.asList("a", "shared", "b"),
                        Arrays.asList("shared", "c"), "b", "c"),
                "a station in common that is not either end");
        Check.equals(Handover.UNRESOLVED,
                VerifyCrossborder.classify(null, obb, "at:47:3873", "at:47:3880"),
                "a leg whose pattern was not found is counted, not guessed at");
        Check.that(!VerifyCrossborder.fullyCovered(List.of(), obb),
                "an empty sequence covers nothing");
    }

    /// B2 groups by TRAIN, and the number is the interchange id's third field. The two identity
    /// slots after it are `slot()`ed journey ids -- colons replaced -- so the split cannot run into
    /// them however baroque the journey id is.
    public static void testTrainNumberIsReadOffTheInterchangeId() {
        Check.equals("1896", VerifyCrossborder.trainNumber(
                        "AT-IT:ServiceJourneyInterchange:1896:at_obb_ServiceJourney_01SO2T-OEBB-62-1-73980_uv910_"
                        + ":it_apb_ServiceJourney_064009O-Postbu-30-3-73920_TA_"),
                "the Pustertal train, between two ids that are themselves full of underscores");
        Check.equals("464", VerifyCrossborder.trainNumber(
                        "AT-CH:ServiceJourneyInterchange:464:at_obb_ServiceJourney_12CH1-OEBB-15-1-115920_dno10_"
                        + ":ch_1_ServiceJourney_ch_1_sjyid_100001_464-005_9102PY.j26_10"),
                "and the Zurich-Innsbruck one, whose counterpart id repeats the number");
        Check.equals("?", VerifyCrossborder.trainNumber("nonsense"), "an id with no number field");
    }

    /// Object ids sometimes carry `@version`; the refs pointing at them never do.
    public static void testStripVersionSuffix() {
        Check.equals("IT:ITH5:ServiceJourneyPattern:TRENITALIA-RER:620083_62-3845-472-0083_A",
                VerifyCrossborder.strip("IT:ITH5:ServiceJourneyPattern:TRENITALIA-RER:620083_62-3845-472-0083_A@1"),
                "a trailing @version is dropped");
        Check.equals("at:obb:ServiceJourneyPattern:01ST3T.j26102219:",
                VerifyCrossborder.strip("at:obb:ServiceJourneyPattern:01ST3T.j26102219:"),
                "an id with no @ is unchanged");
    }
}
