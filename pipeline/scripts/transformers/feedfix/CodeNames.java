package transformers.feedfix;

// The NAP's GTFS->NeTEx converter publishes a stop's code as its Name and the stop's name as its
// ShortName, on the StopPlace and on every Quay it embeds. Milano Centrale, as the Puglia
// Trenitalia asset publishes it:
//
//   <StopPlace id="IT:ITF4:StopPlace:830001700_ReteTrenitalia_GTFS">
//     <Name>830001700</Name><ShortName>MILANO CENTRALE</ShortName>
//     <PrivateCode>830001700</PrivateCode><PublicCode>830001700</PublicCode>
//
// A `_<dataset>` id suffix is that converter's signature. Feeds from other converters publish the
// same stations named, so a station reaches the merge as both shapes at once.
//
// The repair moves the name onto Name and leaves every code where it is, ShortName included.
//
// ScheduledStopPoints.spName folds a digit like any other character, so a code-named stop is a
// legitimate merge key before this runs and its copies cluster only with each other. Repairing at
// load moves them into the named cluster, which changes which StopPlace survives the national merge
// and so which id the export carries for those stations.

import jakarta.xml.bind.JAXBElement;
import noi.netex.model.GroupOfEntities_VersionStructure;
import noi.netex.model.Quay;
import noi.netex.model.StopPlace;
import noi.netex.text.Mls;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class CodeNames {

    private CodeNames() {}

    /// Whether `s` names something rather than coding it: present, and not all digits.
    public static boolean isLabel(String s) {
        return s != null && !s.strip().isEmpty() && !s.strip().chars().allMatch(Character::isDigit);
    }

    /// Whether `s` codes something rather than naming it: present, and all digits.
    public static boolean isCode(String s) {
        return s != null && !s.strip().isEmpty() && s.strip().chars().allMatch(Character::isDigit);
    }

    /// Take the name out of ShortName on every StopPlace and embedded Quay whose Name holds nothing
    /// but the stop's own code, over `db` at `txn`, yielding each StopPlace the repair touched.
    ///
    /// `pending` holds StopPlaces an earlier overlay in the same pass has already changed. Decoding
    /// a row twice gives two objects, so an id `pending` covers is repaired on the object `pending`
    /// holds and every other on a freshly decoded one, leaving the caller one object per id to
    /// overlay.
    public static List<StopPlace> repairCodeNames(Store db, Txn txn, Map<String, StopPlace> pending) {
        List<StopPlace> out = new ArrayList<>();
        long quays = 0;
        for (Object o : db.iterOnlyObjects(txn, StopPlace.class)) {
            StopPlace sp = (StopPlace) o;
            StopPlace target = pending.getOrDefault(sp.getId(), sp);
            boolean hit = takeShortName(target);
            if (target.getQuays() != null) {
                for (JAXBElement<?> el : target.getQuays().getQuayRefOrQuay()) {
                    if (el.getValue() instanceof Quay q && takeShortName(q)) {
                        quays++;
                        hit = true;
                    }
                }
            }
            if (hit) out.add(target);
        }
        Log.info("[repair_code_names] took the name of %d StopPlaces and %d Quays from ShortName",
                out.size(), quays);
        return out;
    }

    /// Move ShortName's text onto Name where Name is a code and ShortName is a label; whether it
    /// fired. Name keeps its own element, and with it any lang the feed put there.
    private static boolean takeShortName(GroupOfEntities_VersionStructure e) {
        if (!isCode(Mls.text(e.getName()))) return false;
        String label = Mls.text(e.getShortName());
        if (!isLabel(label)) return false;
        Mls.setText(e.getName(), label);
        return true;
    }
}
