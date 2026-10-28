package tests;

// fix.AtLineProducts: the Austrian product labelling, driven end to end over a clone.
//
// The shape under test is the one the driver used to lose. A Mentz export ships many version rows of
// one Line id -- up to 37 in Carinthia -- and the store keys each as its own row. The labelling pass
// returns one object per ROW, and the driver overlays them straight, so every row has to come out
// labelled. While a map keyed by line id sat between the two, only one row ever did.
//
// twoversions runs one product and is stored twice. coded already states a code and takes only the
// written-out name. bus runs a category that offers no written-out form.

import noi.netex.model.Line;
import noi.netex.text.Codes;
import noi.netex.text.Mls;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.Runner;
import toolkit.test.TestStore;

import java.util.LinkedHashMap;
import java.util.Map;

import fix.AtLineProducts;

public class TestAtLineProducts {

    private static final String NETEX = """
        <ResourceFrame id="rf" version="1">
          <typesOfValue>
            <ValueSet id="vs1" version="1" nameOfClass="TypeOfProductCategory">
              <values>
                <TypeOfProductCategory id="at:x:TypeOfProductCategory:REX" version="1"><Name>Regional-Express</Name><ShortName>REX</ShortName></TypeOfProductCategory>
                <TypeOfProductCategory id="at:x:TypeOfProductCategory:BUS" version="1"><ShortName>BUS</ShortName></TypeOfProductCategory>
              </values>
            </ValueSet>
          </typesOfValue>
        </ResourceFrame>
        <ServiceFrame id="sf" version="1">
          <lines>
            <Line id="at:x:Line:twoversions" version="1"><Name>A11</Name><TransportMode>rail</TransportMode></Line>
            <Line id="at:x:Line:twoversions" version="7"><Name>A11</Name><TransportMode>rail</TransportMode></Line>
            <Line id="at:x:Line:coded" version="1"><Name>REX</Name><ShortName>REX</ShortName><TransportMode>rail</TransportMode><PublicCode>REX</PublicCode></Line>
            <Line id="at:x:Line:bus" version="1"><Name>B7</Name><TransportMode>bus</TransportMode></Line>
          </lines>
        </ServiceFrame>
        <TimetableFrame id="tf" version="1">
          <vehicleJourneys>
            <ServiceJourney id="at:x:ServiceJourney:t1" version="1"><LineRef ref="at:x:Line:twoversions" version="1"/><TypeOfProductCategoryRef ref="at:x:TypeOfProductCategory:REX" version="1"/></ServiceJourney>
            <ServiceJourney id="at:x:ServiceJourney:c1" version="1"><LineRef ref="at:x:Line:coded" version="1"/><TypeOfProductCategoryRef ref="at:x:TypeOfProductCategory:REX" version="1"/></ServiceJourney>
            <ServiceJourney id="at:x:ServiceJourney:b1" version="1"><LineRef ref="at:x:Line:bus" version="1"/><TypeOfProductCategoryRef ref="at:x:TypeOfProductCategory:BUS" version="1"/></ServiceJourney>
          </vehicleJourneys>
        </TimetableFrame>
        """;

    public static void main(String[] args) {
        Runner.run(TestAtLineProducts.class);
    }

    /// Line (id, version) to the row itself, read out of the TARGET store, so the assertions are
    /// about rows that survived the clone rather than about objects in a list.
    private static Map<String, Line> rows(TestStore db) throws Exception {
        db.loadNetex(NETEX);
        TestStore target = db.runDbToDb(AtLineProducts::apply);
        Map<String, Line> out = new LinkedHashMap<>();
        try (Txn txn = target.store.roTxn()) {
            for (Object o : target.store.iterOnlyObjects(txn, Line.class)) {
                Line line = (Line) o;
                out.put(line.getId() + "@" + line.getVersion(), line);
            }
        }
        return out;
    }

    public static void testEveryVersionRowOfALineIsLabelled(TestStore db) throws Exception {
        Map<String, Line> rows = rows(db);
        Line first = rows.get("at:x:Line:twoversions@1");
        Line second = rows.get("at:x:Line:twoversions@7");

        Check.that(first != null && second != null,
                "both stored versions survive the clone: " + rows.keySet());
        Check.equals("REX", Codes.text(first.getPublicCode()),
                "the first stored version takes the code of the product its journeys run under");
        Check.equals("REX", Codes.text(second.getPublicCode()),
                "and so does the second: the store keys a Line by (id, version) and the pass "
                        + "returns one object per row, so both rows reach the overlay");
        Check.equals("Regional-Express", Mls.text(second.getName()),
                "the later row is named too, which is the half a map keyed by line id dropped");
    }

    public static void testACodedLineTakesOnlyTheWrittenOutName(TestStore db) throws Exception {
        Line coded = rows(db).get("at:x:Line:coded@1");
        Check.equals("REX", Codes.text(coded.getPublicCode()),
                "a Line already coding the abbreviation keeps the code a rider reads");
        Check.equals("Regional-Express", Mls.text(coded.getName()),
                "and gains the written-out form as its name");
    }

    public static void testACategoryWithNoWrittenOutFormEmptiesTheName(TestStore db)
            throws Exception {
        Line bus = rows(db).get("at:x:Line:bus@1");
        Check.equals("BUS", Codes.text(bus.getPublicCode()),
                "the code lands even where the category offers no written-out form");
        Check.isNull(Mls.text(bus.getName()),
                "and the name is emptied rather than filled with the abbreviation twice; the EPIP "
                        + "transform's backstop is what closes it");
    }
}
