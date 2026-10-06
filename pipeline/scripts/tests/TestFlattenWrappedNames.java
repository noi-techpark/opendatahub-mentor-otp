package tests;

// The FlixBus <Text>-wrapped name flattening: a name held in an undeclared child element becomes the
// element's own content, an embedded Quay is reached by the same walk as its StopPlace, and a name
// already written plainly is left alone.

import fix.FlattenWrappedNames;
import jakarta.xml.bind.JAXBElement;
import noi.netex.model.MultilingualString;
import noi.netex.model.Operator;
import noi.netex.model.Quay;
import noi.netex.model.StopPlace;
import noi.netex.text.Mls;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.Runner;
import toolkit.test.TestStore;
import transformers.feedfix.WrappedNames;

public class TestFlattenWrappedNames {

    /// Two wrapped names -- a StopPlace and the Quay it embeds -- and one written plainly, so the
    /// same fixture pins both the repair and its restraint.
    private static final String FRAMES = """
        <ResourceFrame id="rf" version="1">
          <organisations>
            <Operator id="FTG:Operator:FLIXBUS-eu" version="1">
              <Name><Text>FlixBus-eu</Text></Name>
            </Operator>
            <Operator id="FTG:Operator:PLAIN" version="1">
              <Name>Already Plain</Name>
            </Operator>
          </organisations>
        </ResourceFrame>
        <SiteFrame id="sf" version="1">
          <stopPlaces>
            <StopPlace id="FTG:StopPlace:S1" version="1">
              <Name>
                <Text>Mirto Crosia</Text>
              </Name>
              <quays>
                <Quay id="FTG:Quay:S1" version="1">
                  <Name><Text>Mirto Crosia</Text></Name>
                </Quay>
              </quays>
            </StopPlace>
          </stopPlaces>
        </SiteFrame>
        """;

    public static void main(String[] args) {
        Runner.run(TestFlattenWrappedNames.class);
    }

    public static void testWrappedNameBecomesTheElementsOwnContent(TestStore db) throws Exception {
        db.loadNetex(FRAMES);

        TestStore target = db.runDbToDb(FlattenWrappedNames::apply);

        MultilingualString name = null;
        try (Txn txn = target.store.roTxn()) {
            for (Object o : target.store.iterOnlyObjects(txn, Operator.class)) {
                Operator op = (Operator) o;
                if ("FTG:Operator:FLIXBUS-eu".equals(op.getId())) name = op.getName();
            }
        }
        Check.equals("FlixBus-eu", Mls.text(name), "the wrapped Operator name is readable");
        Check.that(!WrappedNames.isWrapped(name), "no child element survives the flattening");
        for (Object c : name.getContent()) {
            Check.that(!(c instanceof JAXBElement), "content holds the string, not an element");
        }
    }

    /// The pass walks the object graph rather than a list of name-bearing classes, which is what
    /// reaches a Quay: it is not a row of its own, only a child of the StopPlace being repaired.
    public static void testAnEmbeddedQuayIsReachedByTheSameWalk(TestStore db) throws Exception {
        db.loadNetex(FRAMES);

        TestStore target = db.runDbToDb(FlattenWrappedNames::apply);

        MultilingualString spName = null;
        MultilingualString quayName = null;
        try (Txn txn = target.store.roTxn()) {
            for (Object o : target.store.iterOnlyObjects(txn, StopPlace.class)) {
                StopPlace sp = (StopPlace) o;
                spName = sp.getName();
                for (JAXBElement<?> el : sp.getQuays().getQuayRefOrQuay()) {
                    if (el.getValue() instanceof Quay q) quayName = q.getName();
                }
            }
        }
        Check.equals("Mirto Crosia", Mls.text(spName), "the StopPlace name is flattened");
        Check.equals("Mirto Crosia", Mls.text(quayName), "the embedded Quay name is flattened");
        Check.that(!WrappedNames.isWrapped(quayName), "the Quay keeps no child element either");
    }

    /// A feed mixing both shapes -- FlixBus writes Codespace/Description plainly and everything else
    /// wrapped -- must come through with the plain one untouched.
    public static void testAPlainNameIsLeftAlone(TestStore db) throws Exception {
        db.loadNetex(FRAMES);

        TestStore target = db.runDbToDb(FlattenWrappedNames::apply);

        MultilingualString plain = null;
        try (Txn txn = target.store.roTxn()) {
            for (Object o : target.store.iterOnlyObjects(txn, Operator.class)) {
                Operator op = (Operator) o;
                if ("FTG:Operator:PLAIN".equals(op.getId())) plain = op.getName();
            }
        }
        Check.equals("Already Plain", Mls.text(plain), "a plain name reads as it did");
    }

    /// The wrapper is pretty-printed, so the flattened content list carries the indentation around
    /// the child. A name that keeps it is not the name the feed meant.
    public static void testIndentationAroundTheWrapperIsNotKept(TestStore db) throws Exception {
        db.loadNetex(FRAMES);

        TestStore target = db.runDbToDb(FlattenWrappedNames::apply);

        MultilingualString spName = null;
        try (Txn txn = target.store.roTxn()) {
            for (Object o : target.store.iterOnlyObjects(txn, StopPlace.class)) {
                spName = ((StopPlace) o).getName();
            }
        }
        Check.equals("Mirto Crosia", Mls.text(spName), "no leading or trailing whitespace survives");
    }
}
