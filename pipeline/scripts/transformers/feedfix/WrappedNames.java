package transformers.feedfix;

// The FlixBus export declares ntx:1.1 and epip:EU_PI_NETWORK_OFFER 1.0, and wraps every name in the
// <Text> child NeTEx 2.0 added to MultilingualString. At 1.1 the type carries its text as its own
// content:
//
//   <Name lang="de">Gatterer</Name>                 STA, and every other feed here
//   <Name><Text>Mirto Crosia</Text></Name>          FlixBus
//
// 9,253 of that feed's 9,254 name elements carry the wrapper -- every StopPlace, Quay,
// ScheduledStopPoint and Line name, both Operator names, and Line's ShortName. Only
// Codespace/Description is written plainly. 9,252 of the 9,253 reach this pass; the one that does
// not is the PublicationDelivery envelope's own Description, which is not a stored object.
//
// OTP's NeTEx reader is 1.x, where MultilingualString holds one value string and the wrapper is
// out of reach. OTP refuses the build outright on the first name it validates -- `Missing mandatory
// name on Agency`, from an Authority the EPIP conversion synthesises off an Operator and hands the
// wrapped name straight to -- and every stop and line name behind it would reach the graph empty.
//
// The text is recoverable. The model here is 2.0, so JAXB parses the child into the mixed content
// list as a JAXBElement<TextType> and Mls.text flattens the list over it. This rewrites that
// flattened text back as the element's own content, which is what every other feed here already
// ships.
//
// Whitespace is stripped because the wrapper is pretty-printed: the content list around the child is
// the indentation, and a name flattened without stripping is `\n  Mirto Crosia\n`.

import jakarta.xml.bind.JAXBElement;
import noi.netex.model.MultilingualString;
import noi.netex.model.VersionOfObjectRefStructure;
import noi.netex.text.Mls;
import toolkit.model.RecursiveAttributes;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.util.Log;

import java.util.ArrayList;
import java.util.List;

public final class WrappedNames {

    private WrappedNames() {}

    /// Whether `m` holds its text in a child element. Text is the only child the type declares and
    /// the @XmlElementRef binding admits nothing else, so any JAXBElement present is the wrapper.
    public static boolean isWrapped(MultilingualString m) {
        if (m == null) return false;
        for (Object c : m.getContent()) {
            if (c instanceof JAXBElement) return true;
        }
        return false;
    }

    /// Flatten every wrapped MultilingualString reachable from `obj`, in place; how many fired.
    ///
    /// A wrapper whose text flattens to nothing is left as it is. There is no name to recover there,
    /// and rewriting it to an empty content list would turn a shape a later reader can still diagnose
    /// into one it cannot.
    public static int flatten(Object obj) {
        int[] hits = {0};
        RecursiveAttributes.walk(obj, new RecursiveAttributes.Visitor() {
            @Override
            public void reference(VersionOfObjectRefStructure ref) {}

            @Override
            public void node(Object node) {
                if (!(node instanceof MultilingualString m) || !isWrapped(m)) return;
                String text = Mls.text(m);
                if (text == null || text.strip().isEmpty()) return;
                Mls.setText(m, text.strip());
                hits[0]++;
            }
        });
        return hits[0];
    }

    /// Every object in `db` at `txn` carrying a wrapped name, with its names flattened.
    ///
    /// Every class is scanned rather than a list of the name-bearing ones: the wrapper is a property
    /// of the exporter, not of any one class, and a Quay reached through its StopPlace is repaired by
    /// the same walk that repairs the StopPlace, with no separate embedding rule to keep in step.
    public static List<Object> flattenWrappedNames(Store db, Txn txn) {
        List<Object> out = new ArrayList<>();
        long names = 0;
        for (Class<?> clazz : db.dbNames(txn)) {
            for (Object o : db.iterOnlyObjects(txn, clazz)) {
                int hits = flatten(o);
                if (hits > 0) {
                    names += hits;
                    out.add(o);
                }
            }
        }
        Log.info("[flatten_wrapped_names] flattened %d <Text>-wrapped names on %d objects",
                names, out.size());
        return out;
    }
}
