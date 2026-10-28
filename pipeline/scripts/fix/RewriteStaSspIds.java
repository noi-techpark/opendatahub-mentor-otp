package fix;

// Rewrite the STA feed's ScheduledStopPoint ids to the SIRI feed format:
//   from:  IT:ITH1:ScheduledStopPoint:it-22021-7010-51-32073:
//   to:    IT:ITH10:ScheduledStopPoint:7010:51:32073
//
// Every class is copied verbatim except ScheduledStopPoint, which is re-emitted under the rewritten
// id -- so the stale originals do not survive the copy -- and the ref-bearing classes, which are
// overlaid with their ScheduledStopPointRef/RoutePointRefs rewritten.

import noi.netex.model.PassengerStopAssignment;
import noi.netex.model.Route;
import noi.netex.model.RoutePoint;
import noi.netex.model.RoutePointRefStructure;
import noi.netex.model.ScheduledStopPoint;
import noi.netex.model.ScheduledStopPointRefStructure;
import noi.netex.model.ServiceJourneyPattern;
import noi.netex.model.ServiceLink;
import noi.netex.model.TimingLink;
import noi.netex.model.VersionOfObjectRefStructure;
import toolkit.harness.DbToDb;
import toolkit.model.RecursiveAttributes;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.util.Log;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RewriteStaSspIds {

    private static final Pattern PATTERN = Pattern.compile("^.*:ScheduledStopPoint:it-22021-(.+):$");

    /// Object types that may transitively contain ScheduledStopPointRef or RoutePointRef.
    private static final Class<?>[] REF_BEARING_TYPES = {
            ServiceJourneyPattern.class,
            ServiceLink.class,
            TimingLink.class,
            PassengerStopAssignment.class,
            Route.class,
            RoutePoint.class,
    };

    public static void main(String[] args) {
        DbToDb.driverMain("rewrite-sta-ssp-ids", args, RewriteStaSspIds::apply);
    }

    /// The SIRI-format id, or null when the id does not match the STA pattern.
    static String newId(String oldId) {
        Matcher m = PATTERN.matcher(oldId);
        return m.matches() ? "IT:ITH10:ScheduledStopPoint:" + m.group(1).replace('-', ':') : null;
    }

    /// Named so tests can run it via TestStore.runDbToDb, without the CLI sandwich.
    public static void apply(Store src, Txn stx, Store dst, Txn dtx) {
        for (Class<?> clazz : src.dbNames(stx)) {
            if (clazz == ScheduledStopPoint.class) continue;
            src.copyMap(stx, dst, dtx, clazz);
        }

        dst.insertAnyObjects(dtx, renamedSsps(src, stx));
        dst.insertAnyObjects(dtx, updatedObjects(src, stx));
    }

    /// Every ScheduledStopPoint, matching ones under the rewritten id. A plain setId suffices —
    /// iterOnlyObjects yields fresh, detached beans, so mutating before yielding is equivalent.
    static Iterator<Object> renamedSsps(Store db, Txn txn) {
        Iterator<Object> ssps = db.iterOnlyObjects(txn, ScheduledStopPoint.class).iterator();
        return new Iterator<>() {
            long renamed = 0;
            boolean logged;

            @Override
            public boolean hasNext() {
                if (ssps.hasNext()) return true;
                if (!logged) {
                    logged = true;
                    Log.info("[sta_ssp] renamed %d ScheduledStopPoints to the SIRI id format", renamed);
                }
                return false;
            }

            @Override
            public Object next() {
                if (!hasNext()) throw new NoSuchElementException();
                ScheduledStopPoint ssp = (ScheduledStopPoint) ssps.next();
                String newId = newId(ssp.getId());
                if (newId != null) {
                    renamed++;
                    ssp.setId(newId);
                }
                return ssp;
            }
        };
    }

    /// Walk each ref-bearing object with the recursive-attributes walk, rewrite matching
    /// ScheduledStopPointRef/RoutePointRef refs in place, and yield only the changed objects.
    static Iterator<Object> updatedObjects(Store db, Txn txn) {
        ArrayDeque<Object> ready = new ArrayDeque<>();
        return new Iterator<>() {
            int nextClass = 0;
            Iterator<Object> objects = Collections.emptyIterator();

            @Override
            public boolean hasNext() {
                while (ready.isEmpty()) {
                    if (!objects.hasNext()) {
                        if (nextClass == REF_BEARING_TYPES.length) return false;
                        objects = db.iterOnlyObjects(txn, REF_BEARING_TYPES[nextClass++]).iterator();
                        continue;
                    }
                    Object obj = objects.next();
                    boolean[] changed = {false};
                    RecursiveAttributes.walk(obj, new RecursiveAttributes.Visitor() {
                        @Override
                        public void reference(VersionOfObjectRefStructure ref) {
                            // Deliberately broad: matching the ...RefStructure base types, which
                            // the concrete `...Ref` elements extend, also catches ServiceLink's
                            // FromPointRef/ToPointRef, which a concrete-class check misses, leaving
                            // those refs permanently dangling once the ids are rewritten.
                            if (ref instanceof ScheduledStopPointRefStructure
                                    || ref instanceof RoutePointRefStructure) {
                                String newRef = ref.getRef() == null ? null : newId(ref.getRef());
                                if (newRef != null) {
                                    ref.setRef(newRef);
                                    changed[0] = true;
                                }
                            }
                        }

                        @Override
                        public void node(Object o) {}
                    });
                    if (changed[0]) ready.add(obj);
                }
                return true;
            }

            @Override
            public Object next() {
                if (!hasNext()) throw new NoSuchElementException();
                return ready.poll();
            }
        };
    }
}
