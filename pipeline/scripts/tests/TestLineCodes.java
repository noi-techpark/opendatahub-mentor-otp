package tests;

// The Line/PublicCode repairs, one fixture Line per shape the merge meets.
//
// Each rule keys on the relationship between a Line's fields rather than on parsing the composite,
// which is what makes one rule cover `_ATV`, `_UL_terra_`, `_mare_`, `_OP1` and `_pass_` alike. The
// `_pass_` line is a Trenitalia regional republication, excluded from the allowlist today and kept
// here so the rule is pinned against its return.

import noi.netex.model.Line;
import noi.netex.text.Codes;
import noi.netex.text.Mls;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.Runner;
import toolkit.test.TestStore;
import transformers.feedfix.LineCodes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TestLineCodes {

    private static final String IT_FRAMES = """
        <ServiceFrame id="sf" version="1">
          <lines>
            <Line id="IT:ITH3:Line:A_ATV" version="1"><Name>LEGNAGO PORTO - TERRANEGRA</Name><ShortName>A</ShortName><Description>LEGNAGO PORTO - TERRANEGRA</Description><TransportMode>bus</TransportMode><PublicCode>A_ATV</PublicCode><PrivateCode>A_ATV</PrivateCode></Line>
            <Line id="IT:ITH3:Line:11_UL_terra_ACTV" version="1"><Name>Santa Maria Elisabetta - Pellestrina</Name><ShortName>11</ShortName><Description>UL</Description><TransportMode>bus</TransportMode><PublicCode>11_UL_terra_ACTV</PublicCode><PrivateCode>11_UL_terra_ACTV</PrivateCode></Line>
            <Line id="IT:ITH3:Line:5_UM_mare_ACTV" version="1"><Name>Burano - Torcello</Name><ShortName>5.1</ShortName><Description>UM</Description><TransportMode>water</TransportMode><PublicCode>5_UM_mare_ACTV</PublicCode><PrivateCode>5_UM_mare_ACTV</PrivateCode></Line>
            <Line id="IT:ITI4:Line:211_OP1" version="1"><Name>211</Name><ShortName>211</ShortName><Description>211</Description><TransportMode>bus</TransportMode><PublicCode>211_OP1</PublicCode><PrivateCode>211_OP1</PrivateCode></Line>
            <Line id="IT:ITF3:Line:01-CE" version="1"><Name>01-CE</Name><ShortName>01-CE</ShortName><Description>Caserta-S.Maria C.V.-Capua</Description><TransportMode>bus</TransportMode><PublicCode/><PrivateCode>01-CE</PrivateCode></Line>
            <Line id="IT:ITI4:Line:620083_pass_0083" version="1"><Name>Regionale Veloce</Name><ShortName>RV</ShortName><TransportMode>rail</TransportMode><PublicCode>620083_pass_0083</PublicCode><PrivateCode>620083_pass_0083</PrivateCode></Line>
            <Line id="IT::Line:railTRENITALIA:0083_60083" version="1"><Name>Eurocity</Name><ShortName>0083_EC</ShortName><TransportMode>rail</TransportMode><PublicCode>0083_EC</PublicCode><PrivateCode>0083_60083</PrivateCode></Line>
            <Line id="IT:ITI3:Line:ADRIABUS_45" version="1"><Name>Carpegna-Sassocorvaro-Montecchio-Pesaro</Name><ShortName>ADRIABUS_45</ShortName><TransportMode>bus</TransportMode><PublicCode>ADRIABUS_45</PublicCode><PrivateCode>45</PrivateCode></Line>
            <Line id="IT:ITH5:Line:TPER-BO:11_11A" version="1"><Name>ITC Luxemburg - Ponticella</Name><ShortName>11A</ShortName><TransportMode>bus</TransportMode><PublicCode>11_11A</PublicCode></Line>
            <Line id="IT:ITG2:Line:busCTM:U_EX" version="1"><Name>Supplemento Linea University Express</Name><ShortName>U_EX</ShortName><TransportMode>bus</TransportMode><PublicCode>U_EX</PublicCode><PrivateCode>U_EX</PrivateCode></Line>
            <Line id="IT:ITC2:Line:110" version="1"><Name>AOSTA/PONT-SAINT-MARTIN</Name><ShortName>110</ShortName><TransportMode>bus</TransportMode><PublicCode>110</PublicCode><PrivateCode>10110</PrivateCode></Line>
            <Line id="IT:ITF1:Line:TNLINE495" version="1"><Name>S.U. LANCIANO LINEA 2/</Name><Description>S.U. LANCIANO LINEA 2/</Description><TransportMode>bus</TransportMode></Line>
            <Line id="IT:ITALO:Line:9963-9944-1-1" version="1"><Name>9963_9944</Name><TransportMode>rail</TransportMode><PublicCode>9963_9944</PublicCode></Line>
            <Line id="IT:ITALO:Line:1045-9961--1-1-1" version="1"><Name>1045_9961_#1</Name><TransportMode>rail</TransportMode><PublicCode>1045_9961_#1</PublicCode></Line>
            <Line id="IT:ITALO:Line:9997-1-1" version="1"><Name>9997</Name><TransportMode>rail</TransportMode><PublicCode>9997</PublicCode></Line>
          </lines>
        </ServiceFrame>
        """;

    private static final String FLIX_FRAMES = """
        <ServiceFrame id="sf" version="1">
          <lines>
            <Line id="FTG:Line:N177" version="1">
              <privateCodes><PrivateCode type="route_id">N177</PrivateCode></privateCodes>
              <Name>Düsseldorf - Genoa</Name><ShortName>FlixBus N177</ShortName>
              <TransportMode>bus</TransportMode><PublicCode>FlixBus N177</PublicCode>
            </Line>
            <Line id="FTG:Line:OTHER" version="1">
              <privateCodes><PrivateCode type="route_id">777</PrivateCode></privateCodes>
              <Name>Somewhere - Elsewhere</Name>
              <TransportMode>bus</TransportMode><PublicCode>Carrier 42</PublicCode>
            </Line>
          </lines>
        </ServiceFrame>
        """;

    public static void main(String[] args) {
        Runner.run(TestLineCodes.class);
    }

    private static Map<String, Line> run(TestStore db, String frames, LineCodes.Policy policy)
            throws Exception {
        db.loadNetex(frames);
        Map<String, Line> out = new LinkedHashMap<>();
        try (Txn txn = db.store.roTxn()) {
            for (Line l : LineCodes.repairLineCodes(db.store, txn, policy, new LinkedHashMap<>())) {
                out.put(l.getId(), l);
            }
        }
        return out;
    }

    /// Every composite shape, land and water, plus the republication that is excluded today.
    public static void testAnInternalCompositeGivesWayToTheShortName(TestStore db) throws Exception {
        Map<String, Line> fixed = run(db, IT_FRAMES, LineCodes.Policy.IT_RAP);

        Check.equals("A", Codes.text(fixed.get("IT:ITH3:Line:A_ATV").getPublicCode()),
                "an operator-suffixed code gives way to the ShortName");
        Check.equals("11", Codes.text(fixed.get("IT:ITH3:Line:11_UL_terra_ACTV").getPublicCode()),
                "and so does a class-and-medium composite, with no separator parsed");
        Check.equals("5.1", Codes.text(fixed.get("IT:ITH3:Line:5_UM_mare_ACTV").getPublicCode()),
                "the water medium takes the same rule as the land one");
        Check.equals("211", Codes.text(fixed.get("IT:ITI4:Line:211_OP1").getPublicCode()),
                "an operator suffix on a numbered line goes too");
        Check.equals("01-CE", Codes.text(fixed.get("IT:ITF3:Line:01-CE").getPublicCode()),
                "an EMPTY PublicCode element counts as absent, so the ShortName fills it");
        Check.equals("RV", Codes.text(fixed.get("IT:ITI4:Line:620083_pass_0083").getPublicCode()),
                "a _pass_ republication is covered without a rule of its own");
    }

    /// A composite ending on another field's whole value gives way to that field.
    public static void testACompositeEndingOnAFieldGivesWayToIt(TestStore db) throws Exception {
        Map<String, Line> fixed = run(db, IT_FRAMES, LineCodes.Policy.IT_RAP);

        Line marche = fixed.get("IT:ITI3:Line:ADRIABUS_45");
        Check.equals("45", Codes.text(marche.getPublicCode()),
                "an operator-prefixed code gives way to the PrivateCode it ends in");
        Check.equals("45", Mls.text(marche.getShortName()),
                "and the ShortName repeating the composite goes with it");
        Check.equals("11A", Codes.text(fixed.get("IT:ITH5:Line:TPER-BO:11_11A").getPublicCode()),
                "a code ending in the ShortName gives way to it, with no PrivateCode at all");
        Check.isNull(fixed.get("IT:ITG2:Line:busCTM:U_EX"),
                "a code that IS the ShortName and the PrivateCode ends in no other field");
    }

    /// Trenitalia prefixes its company onto both codes, and only the shared prefix goes.
    public static void testTrenitaliaDropsTheCompanyPrefix(TestStore db) throws Exception {
        Map<String, Line> fixed = run(db, IT_FRAMES, LineCodes.Policy.TRENITALIA);

        Line ec = fixed.get("IT::Line:railTRENITALIA:0083_60083");
        Check.equals("EC", Codes.text(ec.getPublicCode()), "the category behind the company prefix");
        Check.equals("EC", Mls.text(ec.getShortName()), "and the ShortName that repeated it");
        Check.equals("Eurocity", Mls.text(ec.getName()), "the name was already right");
        Check.isNull(fixed.get("IT:ITH5:Line:TPER-BO:11_11A"),
                "a prefix with no PrivateCode to share it is not a company");
        Check.isNull(fixed.get("IT:ITI3:Line:ADRIABUS_45"),
                "nor is one the PrivateCode does not open on");
        Check.isNull(fixed.get("IT:ITG2:Line:busCTM:U_EX"),
                "nor one the PrivateCode shares by being the same code");
    }

    /// The Name is only taken from the Description where the Name is one of the Line's own codes.
    public static void testANameRepeatingACodeGivesWayToTheDescription(TestStore db)
            throws Exception {
        Map<String, Line> fixed = run(db, IT_FRAMES, LineCodes.Policy.IT_RAP);

        Check.equals("Caserta-S.Maria C.V.-Capua",
                Mls.text(fixed.get("IT:ITF3:Line:01-CE").getName()),
                "a Name repeating the code gives way to the Description");
        Check.equals("LEGNAGO PORTO - TERRANEGRA",
                Mls.text(fixed.get("IT:ITH3:Line:A_ATV").getName()),
                "a Name that is already a label is kept, Description or not");
        Check.equals("Santa Maria Elisabetta - Pellestrina",
                Mls.text(fixed.get("IT:ITH3:Line:11_UL_terra_ACTV").getName()),
                "and a Description holding only the service class does not become a name");
    }

    /// The rule has to be a no-op on a feed that already publishes a public code.
    public static void testACorrectlyCodedLineIsLeftAlone(TestStore db) throws Exception {
        Map<String, Line> fixed = run(db, IT_FRAMES, LineCodes.Policy.IT_RAP);

        Check.isNull(fixed.get("IT::Line:railTRENITALIA:0083_60083"),
                "Trenitalia's EC line is untouched: its own stage drops the company prefix");
        Check.isNull(fixed.get("IT:ITC2:Line:110"),
                "and so is a line whose PublicCode already equals its ShortName");
        Check.isNull(fixed.get("IT:ITF1:Line:TNLINE495"),
                "a line with no code anywhere gets nothing invented for it");
    }

    /// Both Italian chains end on one driver, which applies the access-point rules. They have to be
    /// unable to fire on Italo, whose own stage does its labelling: its Lines carry no ShortName,
    /// which IT-1 needs, and no Description, which IT-2 needs.
    public static void testTheAccessPointRulesCannotFireOnItalo(TestStore db) throws Exception {
        Map<String, Line> fixed = run(db, IT_FRAMES, LineCodes.Policy.IT_RAP);

        for (String id : List.of("IT:ITALO:Line:9963-9944-1-1", "IT:ITALO:Line:1045-9961--1-1-1",
                "IT:ITALO:Line:9997-1-1")) {
            Check.isNull(fixed.get(id), id + " is untouched by the access-point rules");
        }
    }

    public static void testItaloTakesTheOperatorAndSpellsThePair(TestStore db) throws Exception {
        Map<String, Line> fixed = run(db, IT_FRAMES, LineCodes.Policy.ITALO);

        Line pair = fixed.get("IT:ITALO:Line:9963-9944-1-1");
        Check.equals("Italo", Codes.text(pair.getPublicCode()),
                "every Italo line carries the operator, there being no line number to carry");
        Check.equals("9963 / 9944", Mls.text(pair.getName()), "and the pair read as a pair");

        Line variant = fixed.get("IT:ITALO:Line:1045-9961--1-1-1");
        Check.equals("1045 / 9961", Mls.text(variant.getName()),
                "the trailing _#N is the variant counter the id also carries, so it goes first");

        Line single = fixed.get("IT:ITALO:Line:9997-1-1");
        Check.equals("Italo", Codes.text(single.getPublicCode()), "a single number still gets the code");
        Check.equals("9997", Mls.text(single.getName()), "and its name is left as the one number");
    }

    public static void testFlixBusDropsThePublisherPrefix(TestStore db) throws Exception {
        Map<String, Line> fixed = run(db, FLIX_FRAMES, LineCodes.Policy.FLIX);

        Line n177 = fixed.get("FTG:Line:N177");
        Check.equals("N177", Codes.text(n177.getPublicCode()),
                "the route_id behind the publisher prefix becomes the public code");
        Check.equals("Düsseldorf - Genoa", Mls.text(n177.getName()), "the name was already right");
        Check.isNull(fixed.get("FTG:Line:OTHER"),
                "a public code that does not END with the route_id is left alone: the suffix test is "
                        + "what keeps this rule off everything else");
    }

    /// An empty route_id names no code. The suffix test reduces to `endsWith(" ")` on one, which any
    /// code with a trailing space satisfies, and the line would be left with a blank public code --
    /// a route OTP ships with no short name.
    public static void testAnEmptyRouteIdLeavesThePublicCodeAlone(TestStore db) throws Exception {
        Map<String, Line> fixed = run(db, """
            <ServiceFrame id="sf" version="1">
              <lines>
                <Line id="FTG:Line:BLANK" version="1">
                  <privateCodes><PrivateCode type="route_id"></PrivateCode></privateCodes>
                  <Name>Somewhere - Elsewhere</Name>
                  <TransportMode>bus</TransportMode><PublicCode>FlixBus </PublicCode>
                </Line>
              </lines>
            </ServiceFrame>
            """, LineCodes.Policy.FLIX);

        Check.isNull(fixed.get("FTG:Line:BLANK"),
                "an empty route_id is not a code, so the rule does not fire and the line is "
                        + "not rewritten");
    }

    /// A Line two passes both touch has to reach the overlay as one object carrying both repairs.
    public static void testRepairsComposeOnAPendingObject(TestStore db) throws Exception {
        db.loadNetex(IT_FRAMES);
        try (Txn txn = db.store.roTxn()) {
            Map<String, Line> pending = new LinkedHashMap<>();
            List<Line> first = LineCodes.repairLineCodes(
                    db.store, txn, LineCodes.Policy.IT_RAP, pending);
            for (Line l : first) pending.put(l.getId(), l);

            // A second run over the same pending map must find nothing left to do, which is what
            // says the first run's writes are on the objects the caller holds.
            List<Line> second = LineCodes.repairLineCodes(
                    db.store, txn, LineCodes.Policy.IT_RAP, pending);
            Check.equals(0, second.size(),
                    "a second pass over the repaired objects changes nothing: " + second.size());
        }
    }
}
