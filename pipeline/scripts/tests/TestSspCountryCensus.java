package tests;

// The census that measures what `XbIds.stopCountry` cannot read, and what fixing it would move.
//
// The two things that could make its numbers silently wrong are pinned here: the ids it calls
// unreadable really are (against ids copied out of the national store), and its
// candidate/stub/neither rule is the one `scanOneSource` applies.

import toolkit.test.Check;
import transformers.xb.XbIds;
import toolkit.test.Runner;
import tools.SspCountryCensus;

import java.util.LinkedHashSet;
import java.util.Set;

public class TestSspCountryCensus {

    public static void main(String[] args) {
        Runner.run(TestSspCountryCensus.class);
    }

    /// `canonUic` takes the LONGEST digit run, so an Italian local number is UIC-shaped and
    /// [transformers.xb.XbIds#uicCountry] has to miss it for the classifier to reach its id-space
    /// arm. [tests.TestXbIds] pins that half; what is pinned here is that the arm is reached and
    /// calls these stops Italian. The ids are real — the first is a stop on the STA Pustertal runs.
    public static void testItalianRegionalIdsResolveThroughTheIdSpace() {
        String pustertal = "IT:ITH10:ScheduledStopPoint:1170:52:31080";
        Check.equals("31_80", XbIds.canonUic(pustertal),
                "the canonicaliser still reads the local number as a UIC-shaped code");
        Check.isNull(XbIds.uicCountry(XbIds.canonUic(pustertal)),
                "and the lookup refuses it, which is what makes the id-space arm reachable");
        Check.equals("IT", XbIds.stopCountry(pustertal), "so the stop is now in Italy");

        String abruzzo = "IT:ITF1:ScheduledStopPoint:TNPNTS00000000022030";
        Check.isNull(XbIds.uicCountry(XbIds.canonUic(abruzzo)),
                "an Abruzzo stop's local number is not a country either");
        Check.equals("IT", XbIds.stopCountry(abruzzo), "and is now IT");
        Check.equals("IT", XbIds.stopCountry("IT:ITI3:ScheduledStopPoint:ATMA_20820"),
                "Marche too -- it reaches the store in the ordinary IT:<NUTS> space, its "
                + "undeclared epd: token stripped at load by normalize-it-ids");

        // The two arms that beat the id space, in their order.
        Check.equals("AT", XbIds.stopCountry("it:apb:ScheduledStopPoint:at-47-3880-0-1:"),
                "a HAFAS prefix beats the id space: this STA stop is in AUSTRIA");
        Check.equals("CH", XbIds.stopCountry("at:obb:ScheduledStopPoint:ch-23016-20302-0-2:"),
                "and OeBB's coding of Buchs SG stays Swiss rather than becoming AT");
        Check.equals("IT", XbIds.stopCountry("830002114"), "a real UIC still resolves on its own");
        Check.isNull(XbIds.stopCountry("gtfs:IT-ITI3-ATMA:whatever"),
                "an id space nobody has taught it stays null rather than guessing");
    }

    /// The census's own fallback must agree with the pipeline's, or its "would change" columns
    /// would measure a rule nothing implements.
    public static void testCensusAgreesWithThePipeline() {
        for (String ref : new String[] {
                "IT:ITH10:ScheduledStopPoint:1170:52:31080",
                "IT:ITF1:ScheduledStopPoint:TNPNTS00000000022030",
                "it:apb:ScheduledStopPoint:at-47-3880-0-1:",
                "at:obb:ScheduledStopPoint:ch-23016-20302-0-2:",
                "830002114"}) {
            Check.equals(XbIds.stopCountry(ref), SspCountryCensus.fixedCountry(ref),
                    "census and pipeline agree on " + ref);
        }
    }

    /// The census reports transitions between the three classes `scanOneSource` sorts journeys
    /// into, so its rule has to be that rule: a candidate spans two countries INCLUDING its feed's,
    /// a stub lies wholly in one country that is not its feed's.
    public static void testClassificationMirrorsTheScanRule() {
        Check.equals("candidate", SspCountryCensus.classOf(set("IT", "AT"), "IT"), "spans the border");
        Check.equals("neither", SspCountryCensus.classOf(set("IT", "AT"), "CH"),
                "two countries, neither of them the feed's: scanOneSource skips it");
        Check.equals("stub", SspCountryCensus.classOf(set("CH"), "AT"),
                "wholly abroad is a foreign-leg stub");
        Check.equals("neither", SspCountryCensus.classOf(set("AT"), "AT"), "wholly domestic");
        Check.equals("neither", SspCountryCensus.classOf(set(), "AT"), "no resolvable stop at all");
        Check.equals("no-home", SspCountryCensus.classOf(set("IT"), null),
                "a feed the id table does not know is reported apart, not guessed at");
    }

    private static Set<String> set(String... s) {
        return new LinkedHashSet<>(java.util.Arrays.asList(s));
    }
}
