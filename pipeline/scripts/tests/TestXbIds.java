package tests;

// Identifier parsing.

import toolkit.test.Check;
import toolkit.test.Runner;
import transformers.xb.XbIds;

public class TestXbIds {

    public static void main(String[] args) {
        Runner.run(TestXbIds.class);
    }

    /// THE trap: "take the longest digit run and read its first two characters as a UIC country"
    /// answers for everything, including ids whose longest run is a local number.
    public static void testAUicLookupUsedAsATestMustMiss() {
        Check.equals("31_80", XbIds.canonUic("IT:ITH10:ScheduledStopPoint:1170:52:31080"),
                "the canonicaliser still answers -- that is its job");
        Check.isNull(XbIds.uicCountry("31_80"),
                "but 31 is not a country, and the lookup must say so rather than hand back '31'");
        Check.isNull(XbIds.uicCountry("00_1"), "nor is 00");
        Check.isNull(XbIds.uicCountry("60_5"), "nor 60");
        Check.equals("IT", XbIds.uicCountry("83_2001"), "83 is");
        Check.equals("CH", XbIds.uicCountry("85_5307"), "and 85");
        Check.equals("IT", XbIds.uicCountry("85_9369"),
                "Tirano is coded Swiss and is in Italy -- the one honorary override");
    }

    /// So the id-space arm is REACHABLE, which is what answers for the majority of the corpus.
    public static void testAnUnreadableIdFallsBackToItsIdSpace() {
        Check.equals("IT", XbIds.stopCountry("IT:ITH10:ScheduledStopPoint:1170:52:31080"),
                "an Italian regional authority publishes it, so the stop is in Italy");
        Check.equals("IT", XbIds.stopCountry("IT:ITI3:ScheduledStopPoint:ATMA_20820"),
                "Marche too -- normalize-it-ids strips its undeclared epd: token at load, so it "
                + "arrives here in the ordinary IT:<NUTS> space");
        Check.equals("IT", XbIds.feedCountry("IT:ITI3:ServiceJourney:1"),
                "and its 35,525 journeys are classifiable for the same reason: the id-space table "
                + "no longer has to carry a prefix the data does not declare");
        Check.equals("DE", XbIds.stopCountry("de:05315:ScheduledStopPoint:1"),
                "a genuinely stop-only space still answers only on the stop arm");
        Check.isNull(XbIds.feedCountry("de:05315:ServiceJourney:1"),
                "and must NOT answer for journeys");
    }

    /// The HAFAS infix is the only place where a feed says where a stop IS rather than who
    /// published it, so it has to be consulted FIRST.
    public static void testTheHafasInfixBeatsTheIdSpace() {
        String obbCodingOfASwissStop = "at:obb:ScheduledStopPoint:ch-23016-20302-0-2:";
        Check.equals("AT", XbIds.stopSpaceCountry(obbCodingOfASwissStop),
                "the id space says Austria, because ÖBB published it");
        Check.equals("CH", XbIds.stopCountry(obbCodingOfASwissStop),
                "but the stop is in Switzerland, and the ch- infix says so");
    }

    /// A country table missing one lower-case prefix silently removes a whole region from matching,
    /// and the output then looks like a DATA gap.
    public static void testTheStaCodespaceIsKnown() {
        Check.equals("IT", XbIds.feedCountry("it:apb:ServiceJourney:034008S-SAD_Bahn-32-13-20820:TA:"),
                "STA publishes under it:apb, lower case, and it is as Italian as IT:");
        Check.equals("IT", XbIds.feedCountry("IT::VehicleJourney:railTRENITALIA:10083_0_1"),
                "Trenitalia under IT:");
        Check.equals("AT", XbIds.feedCountry("at:obb:ServiceJourney:120I3-OEBB-18-1-65400:s9q10:"),
                "OeBB under at:");
        Check.equals("CH", XbIds.feedCountry("ch:1:ServiceJourney:464-005_9102PY.j26_10"),
                "and the Swiss feed under ch:");
        Check.isNull(XbIds.feedCountry("hr:1:ServiceJourney:1"), "and an unknown space is unknown");
    }

    /// Only the WHOLE journey id is injective.
    public static void testTheInterchangeSlotIsInjective() {
        String a = "at:obb:ServiceJourney:120I3-OEBB-18-1-65400:s9q10:";
        String b = "at:obb:ServiceJourney:120I3-OEBB-19-1-65400:s9q10:";
        Check.equals("", a.substring(a.lastIndexOf(':') + 1),
                "the part after the last colon is EMPTY for two whole feeds");
        String stripped = a.substring(0, a.length() - 1);
        Check.equals("s9q10", stripped.substring(stripped.lastIndexOf(':') + 1),
                "and the segment before it is a calendar code shared across journeys");
        Check.that(!XbIds.slot(a).equals(XbIds.slot(b)),
                "so the slot has to be the whole id, and two journeys differing mid-id differ here");
        Check.equals("at_obb_ServiceJourney_120I3-OEBB-18-1-65400_s9q10_", XbIds.slot(a),
                "which is the id with ':' replaced");
        Check.equals("", XbIds.slot(null), "a null id has an empty slot");
    }

    /// The first digit run as an integer, else the upper-cased token.
    public static void testTrainNumberNormalisation() {
        Check.equals("25", XbIds.normalizeTrainNumber("rj 25"), "the leading token is not the number");
        Check.equals("295", XbIds.normalizeTrainNumber("000295"), "leading zeros dropped");
        Check.equals("1826", XbIds.normalizeTrainNumber("R 1826 "), "and surrounding space");
        Check.equals("EC", XbIds.normalizeTrainNumber("ec"),
                "an alphanumeric number is still matchable, upper-cased");
        Check.isNull(XbIds.normalizeTrainNumber(null), "no name, no number");
        Check.isNull(XbIds.normalizeTrainNumber("   "), "nor an empty one");
    }

    /// A code arm in the station merge needs BOTH sides to carry a real code, and on the Austrian
    /// side almost nothing does.
    public static void testAbfaltersbachHasNoUsableCodeOnTheAustrianSide() {
        Check.equals("81_3877", XbIds.realUic("810003877", "IT::StopPlace:railTRENITALIA:810003877"),
                "the STA copy carries the station's true UIC");
        Check.isNull(XbIds.realUic("369", "at:47:3877"),
                "the ÖBB copy's PrivateCode is a Verbund-local 369, and its id yields 38_77 -- "
                + "neither is a country, so no code arm keyed on both sides can reach this pair");
    }
}
