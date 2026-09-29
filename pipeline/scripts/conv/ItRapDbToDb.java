package conv;

// Per-operator RAP preparation in a single clone+overlay pass:
//   1. backfill StopPlace centroids from child Quays;
//   2. take a stop's name back out of ShortName where its Name holds only the stop's code;
//   3. backfill Line authorities, synthesising Authorities from Operators as needed.
//
// 1 and 2 both overlay StopPlace, and a row decoded twice gives two objects, so 2 runs over 1's
// objects rather than over a second decode of the same rows.
//
// The authority backfill has to stay per operator: its soleAuthority shortcut assumes 0 or 1
// Authorities in scope, and nationally there are 385.

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import noi.netex.model.Authority;
import noi.netex.model.EntityInVersionStructure;
import noi.netex.model.EntityStructure;
import noi.netex.model.Line;
import noi.netex.model.Network;
import noi.netex.model.PassengerStopAssignment;
import noi.netex.model.StopPlace;
import toolkit.harness.DbToDb;
import toolkit.keycodec.NulKeyCodec;
import toolkit.store.ObjectRow;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.transform.common.LineAuthorities;
import toolkit.transform.common.ScheduledStopPoints;
import transformers.feedfix.CodeNames;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ItRapDbToDb {

    public static void main(String[] args) {
        DbToDb.driverMain("it-rap-db-to-db", args, ItRapDbToDb::applyItRap);
    }

    /// raw-clone source into target applying the RAP-readiness overlays.
    public static void applyItRap(Store src, Txn stx, Store dst, Txn dtx) {
        List<ObjectRow> spRows = rows(src, stx, StopPlace.class);
        List<ObjectRow> psaRows = rows(src, stx, PassengerStopAssignment.class);
        List<ObjectRow> lineRows = rows(src, stx, Line.class);
        List<ObjectRow> netRows = rows(src, stx, Network.class);

        Map<String, StopPlace> stopFixed = new LinkedHashMap<>();
        for (StopPlace sp : ScheduledStopPoints.backfillStopplaceCentroidsFromQuays(src, stx)) {
            stopFixed.put(sp.getId(), sp);
        }
        for (StopPlace sp : CodeNames.repairCodeNames(src, stx, stopFixed)) {
            stopFixed.put(sp.getId(), sp);
        }

        List<Object> authorityOverlay = LineAuthorities.backfillLineAuthorities(src, stx);
        Map<String, Line> lineFixed = new LinkedHashMap<>();
        Map<String, Network> netFixed = new LinkedHashMap<>();
        List<Authority> newAuthorities = new ArrayList<>();
        for (Object o : authorityOverlay) {
            if (o instanceof Line l) lineFixed.put(l.getId(), l);
            else if (o instanceof Network n) netFixed.put(n.getId(), n);
            else newAuthorities.add((Authority) o);
        }

        Map<String, Object> stopOverlay = new LinkedHashMap<>(stopFixed);

        LongOpenHashSet skip = new LongOpenHashSet();       // rows the clone must not copy
        LongOpenHashSet reinserted = new LongOpenHashSet(); // rows overwritten in place below
        List<Object> overlayInserts = new ArrayList<>();

        planOverlay(src, spRows, StopPlace.class, stopOverlay, Set.of(), skip, reinserted, overlayInserts);
        planOverlay(src, psaRows, PassengerStopAssignment.class, Map.of(), Set.of(), skip, reinserted, overlayInserts);
        planOverlay(src, lineRows, Line.class, lineFixed, Set.of(), skip, reinserted, overlayInserts);
        planOverlay(src, netRows, Network.class, netFixed, Set.of(), skip, reinserted, overlayInserts);

        List<Object> newInserts = new ArrayList<>(newAuthorities);

        // The clone drops an index row whose referenced side was skipped, so a resolved referrer
        // outside the re-inserted set has to be re-inserted for its reference to re-extract.
        LongOpenHashSet patch = toolkit.harness.CloneSupport.referrerFullKeys(src, stx, skip, reinserted);

        dst.cloneFrom(src, stx, dtx, skip.isEmpty() ? null : skip::contains, 0);
        dst.insertAnyObjects(dtx, overlayInserts);
        dst.insertAnyObjects(dtx, newInserts);
        if (!patch.isEmpty()) {
            dst.insertAnyObjects(dtx, toolkit.harness.CloneSupport.loadPatchObjects(src, stx, patch));
        }
    }

    private static List<ObjectRow> rows(Store db, Txn txn, Class<?> clazz) {
        List<ObjectRow> out = new ArrayList<>();
        for (ObjectRow r : db.iterObjects(txn, clazz)) out.add(r);
        return out;
    }

    /// Overlay by id over a raw clone, with delete-by-omission for droppedIds: unchanged rows stay
    /// cloned bytes, a changed row is overwritten in place, and a row of the same id but a
    /// different version is skipped — every stored version of a changed id collapses onto the
    /// overlay object's.
    private static void planOverlay(Store src, List<ObjectRow> rows, Class<?> clazz,
            Map<String, ? extends Object> overlay, Set<String> droppedIds,
            LongOpenHashSet skip, LongOpenHashSet reinserted, List<Object> overlayInserts) {
        int cidx = src.classIdxInt(clazz);
        Map<String, Object> chosen = new LinkedHashMap<>();
        for (ObjectRow row : rows) {
            EntityStructure obj = (EntityStructure) row.object();
            long fk = src.fullKeyOf(clazz, row.localKey());
            if (obj.getId() != null && droppedIds.contains(obj.getId())) {
                skip.add(fk);
                continue;
            }
            Object overlayObj = overlay.get(obj.getId());
            if (overlayObj == null) continue;
            chosen.putIfAbsent(obj.getId(), overlayObj);
            if (java.util.Objects.equals(version(overlayObj), version(obj))) {
                reinserted.add(fk);
            } else {
                skip.add(fk);
            }
        }
        overlayInserts.addAll(chosen.values());
    }

    private static String version(Object o) {
        return o instanceof EntityInVersionStructure e ? e.getVersion() : null;
    }
}
