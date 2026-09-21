package conv;

// Mentz/ÖBB feed loader: a FeedToDb-harness configuration with three semantic deltas, all visible
// in options():
//   1. LOAD ORDER: members named *STOP_OFFER* load FIRST (the shared stop registry), then the
//      per-line files sorted by name. With last-wins duplicate-id precedence, order IS semantics.
//   2. DEDUP MERGE: a duplicate StopPlace quay-union-merges instead of last-wins replacing.
//      Registered for the load only.
//   3. POST PASS: hyphenated parent-site refs (at-43-18595) are rewritten to the colon form the
//      StopPlace ids use (at:43:18595) before resolve, so parent edges wire up.
// The Mentz line-version fix and DropForeignJourneys remain separate pipeline stages.

import noi.netex.model.StopPlace;
import toolkit.harness.Ensemble;
import toolkit.harness.FeedToDb;
import toolkit.load.ZipLoad;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.util.Log;
import toolkit.transform.common.MpLoadRules;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class ObbToDb {

    /// \d must match any Unicode decimal digit, hence UNICODE_CHARACTER_CLASS.
    static final Pattern HYPHEN_SITE_REF =
            Pattern.compile("at-\\d+-\\d+", Pattern.UNICODE_CHARACTER_CLASS);

    /// Zip members in load order: STOP_OFFER first, then by name — that IS the dedup precedence.
    static final Comparator<String> STOP_OFFER_FIRST = Comparator
            .comparingInt((String name) -> name.contains("STOP_OFFER") ? 0 : 1)
            .thenComparing(Comparator.naturalOrder());

    /// The ÖBB feed semantics as ZipLoad options — the ONE definition (LoadFeeds reuses it).
    public static ZipLoad.Options options() {
        return new ZipLoad.Options(
                null,
                STOP_OFFER_FIRST,
                Map.of(StopPlace.class, MpLoadRules::mergeStopPlaceQuays),
                ObbToDb::normalizeParentSiteRefs);
    }

    public static void main(String[] args) throws Exception {
        FeedToDb.driverMain("obb", args, options());
    }

    /// Normalise the parent-site refs, on the Ensemble streaming pass.
    static void normalizeParentSiteRefs(Store store) {
        int[] n = {0};
        Ensemble.Operation op = obj -> {
            StopPlace sp = (StopPlace) obj;
            String ref = sp.getParentSiteRef() == null ? null : sp.getParentSiteRef().getRef();
            if (ref != null && HYPHEN_SITE_REF.matcher(ref).matches()) {
                sp.getParentSiteRef().setRef(ref.replace("-", ":"));
                n[0]++;
                return true;
            }
            return false;
        };
        try (Txn txn = store.rwTxn()) {
            store.insertAnyObjects(txn,
                    Ensemble.applyOperations(store, txn, Map.of(StopPlace.class, List.of(op))));
            txn.commit();
        }
        if (n[0] > 0) Log.info("[obb] normalised %d hyphenated parent-site refs", n[0]);
    }
}
