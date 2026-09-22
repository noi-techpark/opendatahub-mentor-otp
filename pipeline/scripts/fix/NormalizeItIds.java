package fix;

// Move every Italian RAP feed onto the id space it is meant to publish, `IT:<NUTS-2>:`. This is the
// store-side half: probe, then rewrite.
//
// It runs on every RAP operator feed, with no allowlist. Nearly all of them deviate, if only by the
// one `epd:`-prefixed ValidBetween a feed inherits from its CompositeFrame.
//
// Every feed gets the full object pass; there is no clone fast path for the ones that already
// conform. The probe reads the id index, which holds top-level ids only — embedded objects are
// indexed separately, in `_embedding_idx`, which the SPI does not expose for iteration — so a feed
// whose only deviating ids are embedded probes clean. That is the RouteView case in `rename` below.

import noi.netex.model.EntityStructure;
import noi.netex.model.VersionOfObjectRefStructure;
import toolkit.harness.DbToDb;
import toolkit.keycodec.NulKeyCodec;
import toolkit.model.NetexUtils;
import toolkit.model.RecursiveAttributes;
import toolkit.store.BytesKey;
import toolkit.store.IdRow;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.util.Log;
import transformers.feedfix.ItIdSpaces;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;

public class NormalizeItIds {

    public static void main(String[] args) {
        DbToDb.driverMain("normalize-it-ids", args, NormalizeItIds::apply);
    }

    /// What the probe found: the feed's home space, whether anything needs correcting, and the
    /// per-rule tally over top-level ids.
    record Plan(String home, boolean any, long[] byRule) {}

    /// Named so tests can run it via TestStore.runDbToDb, without the CLI sandwich.
    public static void apply(Store src, Txn stx, Store dst, Txn dtx) {
        Plan plan = probe(src, stx);
        Log.info("[it-ids] home space %s%s", plan.home() == null ? "(none)" : plan.home(),
                plan.any() ? "" : " (no top-level id needs correcting)");
        for (int i = 0; i < ItIdSpaces.RULES.length; i++) {
            if (plan.byRule()[i] > 0) {
                Log.info("[it-ids]   %-44s %,9d top-level ids", ItIdSpaces.RULES[i], plan.byRule()[i]);
            }
        }
        long[] tally = new long[2];   // {ids rewritten, refs rewritten}, embedded ids included
        for (Class<?> clazz : src.dbNames(stx)) {
            dst.insertAnyObjects(dtx, corrected(src, stx, clazz, plan.home(), tally));
        }
        Log.info("[it-ids] rewrote %,d ids (top-level and embedded) and %,d references",
                tally[0], tally[1]);
    }

    /// The cheap pass: id-index keys only, no decode. It produces the home space, the per-rule log
    /// line, and the collision check.
    ///
    /// It refuses a correction that would land on an id this store already holds for the same class
    /// and version — that is not a rename, it is two objects becoming one.
    static Plan probe(Store db, Txn txn) {
        String home = ItIdSpaces.homeSpace(db, txn);
        Set<BytesKey> keys = new HashSet<>();
        for (IdRow row : db.iterIdIndex(txn)) keys.add(new BytesKey(row.encodedKey()));
        long[] byRule = new long[ItIdSpaces.RULES.length];
        boolean any = false;
        for (IdRow row : db.iterIdIndex(txn)) {
            byte[] key = row.encodedKey();
            String id = NulKeyCodec.idPart(key);
            int rule = ItIdSpaces.ruleOf(id, home);
            if (rule < 0) continue;
            byRule[rule]++;
            any = true;
            byte[] spliced = spliceId(key, ItIdSpaces.rewrite(id, home));
            if (keys.contains(new BytesKey(spliced))) {
                throw new IllegalStateException("[it-ids] correcting " + id + " to "
                        + ItIdSpaces.rewrite(id, home) + " would collide with an id this store "
                        + "already holds for the same class and version — refusing to merge two "
                        + "objects into one");
            }
        }
        return new Plan(home, any, byRule);
    }

    /// `key` with everything before its first NUL replaced by `newId`; version and class trailer
    /// are carried over byte for byte.
    static byte[] spliceId(byte[] key, String newId) {
        int tailFrom = NulKeyCodec.idPrefixLen(key) - 1;   // index OF the first NUL
        byte[] idB = newId.getBytes(StandardCharsets.UTF_8);
        byte[] out = Arrays.copyOf(idB, idB.length + key.length - tailFrom);
        System.arraycopy(key, tailFrom, out, idB.length, key.length - tailFrom);
        return out;
    }

    /// Every object of one class, corrected: its own id, every embedded object's id, and every
    /// reference it holds.
    ///
    /// iterOnlyObjects yields fresh, detached beans, so mutating before yielding is equivalent to
    /// copying first.
    static Iterator<Object> corrected(Store db, Txn txn, Class<?> clazz, String home, long[] tally) {
        Iterator<Object> objects = db.iterOnlyObjects(txn, clazz).iterator();
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return objects.hasNext();
            }

            @Override
            public Object next() {
                if (!objects.hasNext()) throw new NoSuchElementException();
                Object obj = objects.next();
                rename(obj, home, tally);
                RecursiveAttributes.walk(obj, new RecursiveAttributes.Visitor() {
                    @Override
                    public void reference(VersionOfObjectRefStructure ref) {
                        String r = ref.getRef();
                        if (r == null) return;
                        String fixed = ItIdSpaces.rewrite(r, home);
                        if (!fixed.equals(r)) {
                            ref.setRef(fixed);
                            tally[1]++;
                        }
                    }

                    @Override
                    public void node(Object o) {
                        rename(o, home, tally);
                    }
                });
                return obj;
            }
        };
    }

    /// Correct one object's own id, whatever kind of model object it is.
    ///
    /// `EntityStructure` is not the only id-bearing root: 821 model classes carry a `getId`/`setId`
    /// pair without extending it, rooted at `DerivedViewStructure`, `*_RelStructure`,
    /// `LocationStructure` and four others. The loader mints an id for every id-less element by
    /// substitution on its nearest id-bearing ancestor (`CopyingCapture`, "arm 1"), so the
    /// `RouteView` the Marche feeds embed in each ServiceJourneyPattern — which the source
    /// publishes with no id at all — is stored as `epd:IT:ITI3:RouteView:ADRIABUS_14_6320838`,
    /// inheriting the pattern's space; an `instanceof EntityStructure` arm walks straight past it.
    ///
    /// References cannot arrive here — [RecursiveAttributes] yields those to `reference` instead —
    /// so a ref's `id` is never mistaken for an object's.
    private static void rename(Object o, String home, long[] tally) {
        if (o instanceof EntityStructure e) {
            String fixed = corrected(e.getId(), home, tally);
            if (fixed != null) e.setId(fixed);
            return;
        }
        Method[] idAccess = ID_ACCESSORS.get(o.getClass());
        if (idAccess == null) return;
        try {
            String fixed = corrected((String) idAccess[0].invoke(o), home, tally);
            if (fixed != null) idAccess[1].invoke(o, fixed);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read or write the id of " + o.getClass(), e);
        }
    }

    /// The corrected id, or null when there is nothing to change. Counts as it goes.
    private static String corrected(String id, String home, long[] tally) {
        if (id == null) return null;
        String fixed = ItIdSpaces.rewrite(id, home);
        if (fixed.equals(id)) return null;
        tally[0]++;
        return fixed;
    }

    /// `{getId, setId}` for a model class outside the [EntityStructure] hierarchy, or null when it
    /// has no such pair.
    private static final ClassValue<Method[]> ID_ACCESSORS = new ClassValue<>() {
        @Override
        protected Method[] computeValue(Class<?> type) {
            Method get = NetexUtils.method(type, "getId");
            if (get == null || get.getReturnType() != String.class) return null;
            try {
                return new Method[] {get, type.getMethod("setId", String.class)};
            } catch (NoSuchMethodException e) {
                return null;   // readable but not writable: nothing this pass can do
            }
        }
    };
}
