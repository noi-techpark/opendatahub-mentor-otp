package transformers.feedfix;

// The Mentz exporter writes an Authority's name into PublicCode and its feed code into Name and
// ShortName, as the STA and Austrian feeds here show:
//
//   <PublicCode>Verkehrsverbund Tirol</PublicCode><Name>vvt</Name><ShortName>vvt</ShortName>
//
// OTP names an agency after the Authority's Name, so every route on those feeds is published under
// the code. The Operators of the same exports are written the right way round and are not touched.
//
// The repair swaps the two: the long text becomes the Name, the code becomes the PublicCode. It keys
// on Name repeating ShortName while PublicCode holds something else, so an Authority already written
// the right way round -- or one this pass has already swapped -- does not fire.

import noi.netex.model.Authority;
import noi.netex.text.Codes;
import noi.netex.text.Mls;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.util.Log;

import java.util.ArrayList;
import java.util.List;

public final class AuthorityNames {

    private AuthorityNames() {}

    /// Every Authority in `db` at `txn` whose Name and PublicCode were swapped.
    public static List<Object> swapCodeAndName(Store db, Txn txn) {
        List<Object> out = new ArrayList<>();
        for (Object o : db.iterOnlyObjects(txn, Authority.class)) {
            if (swap((Authority) o)) out.add(o);
        }
        Log.info("[authority-names] swapped Name and PublicCode on %d authorities", out.size());
        return out;
    }

    /// Whether it fired.
    static boolean swap(Authority authority) {
        String name = Mls.text(authority.getName());
        String code = Codes.text(authority.getPublicCode());
        if (name == null || name.isBlank() || code == null || code.isBlank()) return false;
        if (code.equals(name) || !name.equals(Mls.text(authority.getShortName()))) return false;
        authority.setName(Mls.of(code));
        authority.setPublicCode(Codes.of(name));
        return true;
    }
}
