package tests;

// The Italian consolidating merge's id qualification: colliding ids from later sources must be
// qualified to ids that are unique per source, so that with three or more untagged sources the
// fallback suffix still separates them.
//
// The tests drive the public phase function ItMerge.merge(...) directly; Runner runs every store
// test on both formats.

import transformers.common.ItMerge;
import noi.netex.model.EntityStructure;
import noi.netex.model.Line;
import noi.netex.model.LineRefStructure;
import noi.netex.model.ObjectFactory;
import noi.netex.model.Route;
import toolkit.store.Store;
import toolkit.store.Stores;
import toolkit.store.Txn;
import toolkit.store.UnresolvedRow;
import toolkit.test.Check;
import toolkit.test.Runner;
import toolkit.test.TestStore;
import toolkit.util.Log;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Set;
import noi.netex.text.Mls;

public class TestItMergeDbToDb {

    private static final ObjectFactory FACTORY = new ObjectFactory();

    public static void main(String[] args) {
        Runner.run(TestItMergeDbToDb.class);
    }

    /// alternating id, name pairs.
    private static String linesFrame(String... idName) {
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < idName.length; i += 2) {
            lines.append("<Line id=\"").append(idName[i]).append("\" version=\"1\"><Name>")
                    .append(idName[i + 1]).append("</Name></Line>");
        }
        return "<ServiceFrame id=\"sf\" version=\"1\"><lines>" + lines + "</lines></ServiceFrame>";
    }

    /// One unassigned, locatable ScheduledStopPoint -- the shape SSP synthesis acts on.
    private static String sspFrame(String id, String name, String lat, String lon) {
        return "<ServiceFrame id=\"sf\" version=\"1\"><scheduledStopPoints>"
                + "<ScheduledStopPoint id=\"" + id + "\" version=\"1\"><Name>" + name + "</Name>"
                + "<Location><Latitude>" + lat + "</Latitude><Longitude>" + lon + "</Longitude></Location>"
                + "</ScheduledStopPoint></scheduledStopPoints></ServiceFrame>";
    }

    /// Two operators publishing one ScheduledStopPoint id at different places. 10,110 SSP ids on
    /// the real corpus are published by more than one operator.
    ///
    /// Qualification renames the second source's SSP, and because every id synthesis derives is a
    /// string substitution on the SSP id, the StopPlace, Quay and PSA ids come out distinct for
    /// free.
    public static void testNationalSynthesisMintsDistinctQuaysForCollidingSspIds(TestStore ts)
            throws Exception {
        Path f1 = ts.buildDbFile(sspFrame("IT:reg:ScheduledStopPoint:1234", "Uno", "45.0", "9.0"), "s1.mdbx");
        Path f2 = ts.buildDbFile(sspFrame("IT:reg:ScheduledStopPoint:1234", "Due", "44.0", "8.0"), "s2.mdbx");
        Path target = ts.tempPath("merged-synth.mdbx");
        ItMerge.merge(List.of(f1, f2), target, List.of("a", "b"), true, true, true, ts.format, true);

        try (Store db = Stores.open(target, true); Txn txn = db.roTxn()) {
            Set<String> sspIds = new TreeSet<>();
            for (Object o : db.iterOnlyObjects(txn, noi.netex.model.ScheduledStopPoint.class)) {
                sspIds.add(((noi.netex.model.ScheduledStopPoint) o).getId());
            }
            Check.equals(Set.of("IT:reg:ScheduledStopPoint:1234", "IT:reg:ScheduledStopPoint:1234:b"),
                    sspIds, "the second source's SSP is qualified, so the two stay distinct");

            Set<String> spIds = new TreeSet<>();
            Set<String> quayIds = new TreeSet<>();
            for (Object o : db.iterOnlyObjects(txn, noi.netex.model.StopPlace.class)) {
                noi.netex.model.StopPlace sp = (noi.netex.model.StopPlace) o;
                spIds.add(sp.getId());
                if (sp.getQuays() == null) continue;
                for (jakarta.xml.bind.JAXBElement<?> el : sp.getQuays().getQuayRefOrQuay()) {
                    if (el.getValue() instanceof noi.netex.model.Quay q) quayIds.add(q.getId());
                }
            }
            Check.equals(Set.of("IT:reg:StopPlace:1234", "IT:reg:StopPlace:1234:b"), spIds,
                    "one synthesised StopPlace per SSP, ids inheriting the qualification");
            Check.equals(Set.of("IT:reg:Quay:1234", "IT:reg:Quay:1234:b"), quayIds,
                    "and one Quay each, with ids that did NOT collide");

            Map<String, String> quayRefBySp = new TreeMap<>();
            for (Object o : db.iterOnlyObjects(txn, noi.netex.model.PassengerStopAssignment.class)) {
                noi.netex.model.PassengerStopAssignment psa = (noi.netex.model.PassengerStopAssignment) o;
                Check.that(psa.getStopPlaceRef() != null, "every synthesised PSA names a StopPlace");
                Check.that(psa.getQuayRef() != null, "and a Quay -- OTP needs both, see the transform");
                quayRefBySp.put(psa.getStopPlaceRef().getValue().getRef(),
                        psa.getQuayRef().getValue().getRef());
            }
            Check.equals("IT:reg:Quay:1234", quayRefBySp.get("IT:reg:StopPlace:1234"),
                    "each PSA names ITS OWN quay");
            Check.equals("IT:reg:Quay:1234:b", quayRefBySp.get("IT:reg:StopPlace:1234:b"),
                    "including the qualified one -- not the other source's");
        }
    }

    /// The national duplicate-stop merge. Two operators publish the same physical stop under
    /// different ids, 6.9 m apart with the same name. One survivor takes both quays, the loser is
    /// deleted by omission, and the loser's PSA is re-pointed.
    public static void testNationalMergeCollapsesDuplicateStops(TestStore ts) throws Exception {
        String a = "<SiteFrame id=\"site\" version=\"1\"><stopPlaces>"
                + "<StopPlace id=\"IT:reg:StopPlace:a\" version=\"1\"><Name>Piazza Roma</Name>"
                + "<Centroid><Location><Latitude>40.85000</Latitude><Longitude>14.25000</Longitude></Location></Centroid>"
                + "<quays><Quay id=\"IT:reg:Quay:a\" version=\"1\"/></quays></StopPlace>"
                + "</stopPlaces></SiteFrame>"
                + "<ServiceFrame id=\"sfa\" version=\"1\"><stopAssignments>"
                + "<PassengerStopAssignment id=\"IT:reg:PassengerStopAssignment:a\" version=\"1\" order=\"1\">"
                + "<ScheduledStopPointRef ref=\"IT:reg:ScheduledStopPoint:a\"/>"
                + "<StopPlaceRef ref=\"IT:reg:StopPlace:a\"/></PassengerStopAssignment>"
                + "</stopAssignments></ServiceFrame>";
        String b = "<SiteFrame id=\"site\" version=\"1\"><stopPlaces>"
                + "<StopPlace id=\"IT:reg:StopPlace:b\" version=\"1\"><Name>Piazza Roma</Name>"
                + "<Centroid><Location><Latitude>40.85005</Latitude><Longitude>14.25005</Longitude></Location></Centroid>"
                + "<quays><Quay id=\"IT:reg:Quay:b\" version=\"1\"/></quays></StopPlace>"
                + "</stopPlaces></SiteFrame>"
                + "<ServiceFrame id=\"sfb\" version=\"1\"><stopAssignments>"
                + "<PassengerStopAssignment id=\"IT:reg:PassengerStopAssignment:b\" version=\"1\" order=\"1\">"
                + "<ScheduledStopPointRef ref=\"IT:reg:ScheduledStopPoint:b\"/>"
                + "<StopPlaceRef ref=\"IT:reg:StopPlace:b\"/></PassengerStopAssignment>"
                + "</stopAssignments></ServiceFrame>";
        Path f1 = ts.buildDbFile(a, "m1.mdbx");
        Path f2 = ts.buildDbFile(b, "m2.mdbx");
        Path target = ts.tempPath("merged-dups.mdbx");
        ItMerge.merge(List.of(f1, f2), target, List.of("x", "y"), true, true, true, ts.format);

        try (Store db = Stores.open(target, true); Txn txn = db.roTxn()) {
            Set<String> spIds = new TreeSet<>();
            Set<String> quaysOnSurvivor = new TreeSet<>();
            for (Object o : db.iterOnlyObjects(txn, noi.netex.model.StopPlace.class)) {
                noi.netex.model.StopPlace sp = (noi.netex.model.StopPlace) o;
                spIds.add(sp.getId());
                if (sp.getQuays() == null) continue;
                for (jakarta.xml.bind.JAXBElement<?> el : sp.getQuays().getQuayRefOrQuay()) {
                    if (el.getValue() instanceof noi.netex.model.Quay q) quaysOnSurvivor.add(q.getId());
                }
            }
            Check.equals(1, spIds.size(), "the two copies collapse to one StopPlace");
            String survivor = spIds.iterator().next();
            Check.equals(Set.of("IT:reg:Quay:a", "IT:reg:Quay:b"), quaysOnSurvivor,
                    "the survivor holds BOTH quays -- one station, two poles");

            for (Object o : db.iterOnlyObjects(txn, noi.netex.model.PassengerStopAssignment.class)) {
                noi.netex.model.PassengerStopAssignment psa = (noi.netex.model.PassengerStopAssignment) o;
                Check.equals(survivor, psa.getStopPlaceRef().getValue().getRef(),
                        "every PSA points at the survivor, including the loser's");
            }
        }
    }

    /// id -> Name for every Line row, asserting one blob per id.
    private static Map<String, String> lineNames(Path dbPath) throws Exception {
        Map<String, String> names = new TreeMap<>();
        try (Store db = Stores.open(dbPath, true); Txn txn = db.roTxn()) {
            for (Object o : db.iterOnlyObjects(txn, Line.class)) {
                Line line = (Line) o;
                Check.that(!names.containsKey(line.getId()), "duplicate blob for " + line.getId());
                names.put(line.getId(), Mls.text(line.getName()));
            }
        }
        return names;
    }

    /// Has this database's one-shot blob-repair sweep already run?
    private static boolean markerPresent(Path dbPath) throws Exception {
        try (Store db = Stores.open(dbPath, true); Txn txn = db.roTxn()) {
            return db.blobRepairMarkerPresent(txn);
        }
    }

    /// resolve() logs this line once per sweep, and only from inside the marker-gated branch that
    /// runs it (AbstractLmdbStore.resolve()), so its count in a captured log is the sweep call
    /// count.
    private static final String SWEEP_LINE = "mis-typed blob repaired";

    private static int sweepCalls(Path logPath) throws Exception {
        int n = 0;
        for (String line : Files.readAllLines(logPath, StandardCharsets.UTF_8)) {
            if (line.contains(SWEEP_LINE)) n++;
        }
        return n;
    }

    /// A feed declaring one Codespace, plus a Line of its own so the sources are distinguishable.
    private static String codespaceFrame(String xmlns, String url, String lineId) {
        return "<ResourceFrame id=\"rf\" version=\"1\"><codespaces>"
                + "<Codespace id=\"ita\"><Xmlns>" + xmlns + "</Xmlns>"
                + "<XmlnsUrl>" + url + "</XmlnsUrl></Codespace>"
                + "</codespaces></ResourceFrame>"
                + linesFrame(lineId, lineId);
    }

    private static Set<String> codespaceIds(Path dbPath) throws Exception {
        Set<String> ids = new TreeSet<>();
        try (Store db = Stores.open(dbPath, true); Txn txn = db.roTxn()) {
            Class<?> cs = db.classForName("Codespace");
            if (cs == null) return ids;
            for (Object o : db.iterOnlyObjects(txn, cs)) {
                ids.add(((EntityStructure) o).getId());
            }
        }
        return ids;
    }

    /// Every Italian NAP feed ships the same `<Codespace id="ita">` boilerplate, so qualifying the
    /// repeats put one copy per feed in the export -- 160 of them in the national store, differing
    /// only in the suffix. Identical bytes under one id are one declaration.
    public static void testIdenticalDeclarationsCollapseToOne(TestStore ts) throws Exception {
        Path target = ts.tempPath("merged-cs.mdbx");
        ItMerge.merge(identicalDeclarationFeeds(ts), target, List.of("one", "two", "three"),
                true, true, true, ts.format);

        Check.equals(Set.of("ita"), codespaceIds(target),
                "three feeds declaring the same codespace leave one declaration");
        Check.equals(Set.of("l1", "l2", "l3"), new TreeSet<>(lineNames(target).keySet()),
                "and nothing else is dropped with them");
    }

    /// The oracle must drop the same ones.
    public static void testIdenticalDeclarationsCollapseObjectLevelToo(TestStore ts) throws Exception {
        Path target = ts.tempPath("merged-cs-ol.mdbx");
        ItMerge.merge(identicalDeclarationFeeds(ts), target, List.of("one", "two", "three"),
                true, true, false, ts.format);

        Check.equals(Set.of("ita"), codespaceIds(target), "object-level matches the fast path");
    }

    /// Byte equality is the whole test: two publishers claiming one codespace id for DIFFERENT
    /// declarations are still two declarations, and neither may be silently lost.
    public static void testDifferingDeclarationsStillQualify(TestStore ts) throws Exception {
        Path target = ts.tempPath("merged-cs-diff.mdbx");
        ItMerge.merge(List.of(
                        ts.buildDbFile(codespaceFrame("ita", "http://www.ita.it", "l1"), "d1.mdbx"),
                        ts.buildDbFile(codespaceFrame("other", "http://other.example", "l2"), "d2.mdbx")),
                target, List.of("one", "two"), true, true, true, ts.format);

        Check.equals(Set.of("ita", "ita:two"), codespaceIds(target),
                "a declaration that differs is qualified, not dropped");
    }

    private static List<Path> identicalDeclarationFeeds(TestStore ts) throws Exception {
        return List.of(
                ts.buildDbFile(codespaceFrame("ita", "http://www.ita.it", "l1"), "c1.mdbx"),
                ts.buildDbFile(codespaceFrame("ita", "http://www.ita.it", "l2"), "c2.mdbx"),
                ts.buildDbFile(codespaceFrame("ita", "http://www.ita.it", "l3"), "c3.mdbx"));
    }

    /// Three single-Line feeds that all use the id `dup`, so sources 2 and 3 both collide.
    private static List<Path> threeCollidingFeeds(TestStore ts) throws Exception {
        return List.of(
                ts.buildDbFile(linesFrame("dup", "first", "a1", "A1"), "f1.mdbx"),
                ts.buildDbFile(linesFrame("dup", "second", "b1", "B1"), "f2.mdbx"),
                ts.buildDbFile(linesFrame("dup", "third", "c1", "C1"), "f3.mdbx"));
    }

    /// Sources 2 and 3 both collide with source 1's 'dup': the untagged fallback must qualify them
    /// to two different ids, so all three objects survive the merge.
    public static void testThreeUntaggedSourcesGetDistinctQualifiedIds(TestStore ts) throws Exception {
        Path target = ts.tempPath("merged.mdbx");
        ItMerge.merge(threeCollidingFeeds(ts), target, null, true, true, true, ts.format);

        Map<String, String> names = lineNames(target); // asserts one blob per id
        Check.equals("first", names.get("dup"), "the first source keeps the unqualified id");
        Map<String, String> qualified = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : names.entrySet()) {
            if (e.getKey().startsWith("dup:")) qualified.put(e.getKey(), e.getValue());
        }
        List<String> qualifiedNames = new ArrayList<>(qualified.values());
        qualifiedNames.sort(null);
        Check.equals(List.of("second", "third"), qualifiedNames,
                "both later sources must survive under distinct qualified ids, got " + names);
        Check.equals(2, qualified.size(), "the two qualified ids must be distinct, got " + names);
        TreeSet<String> rest = new TreeSet<>(names.keySet());
        rest.remove("dup");
        rest.removeAll(qualified.keySet());
        Check.equals(new TreeSet<>(List.of("a1", "b1", "c1")), rest, "the non-colliding lines survive");
    }

    /// The --object-level reference path must qualify identically to the fast path.
    public static void testThreeUntaggedSourcesObjectLevelMatches(TestStore ts) throws Exception {
        Path target = ts.tempPath("merged-ol.mdbx");
        ItMerge.merge(threeCollidingFeeds(ts), target, null, true, true, false, ts.format);

        Map<String, String> names = lineNames(target);
        Check.equals("first", names.get("dup"), "the first source keeps the unqualified id");
        List<String> qualifiedNames = new ArrayList<>();
        for (Map.Entry<String, String> e : names.entrySet()) {
            if (e.getKey().startsWith("dup:")) qualifiedNames.add(e.getValue());
        }
        qualifiedNames.sort(null);
        Check.equals(List.of("second", "third"), qualifiedNames, "object-level matches the fast path");
    }

    /// explicit tags are untouched by the fallback fix.
    public static void testTaggedSourcesUseTheirTags(TestStore ts) throws Exception {
        Path target = ts.tempPath("merged-tagged.mdbx");
        ItMerge.merge(threeCollidingFeeds(ts), target,
                List.of("one", "two", "three"), true, true, true, ts.format);

        Map<String, String> names = lineNames(target);
        Check.equals("first", names.get("dup"), "the first source keeps the unqualified id");
        Check.equals("second", names.get("dup:two"), "source 2 qualified by its tag");
        Check.equals("third", names.get("dup:three"), "source 3 qualified by its tag");
    }

    /// Marker propagation, asserted through `SWEEP_LINE`: with both sources swept, the target
    /// inherits the marker and resolve() skips the full sweep, so bind-time repair alone must leave
    /// a qualified referrer's blob consistent. feed2's Line 'dup' is qualified to 'dup:two'; its
    /// Route's LineRef must be rewritten to 'dup:two' and resolve to the 'second' Line.
    public static void testMarkerPropagationKeepsQualifiedReferrerResolvable(TestStore ts) throws Exception {
        Path f1 = ts.buildDbFile(linesFrame("dup", "first"), "f1.mdbx");
        Path f2 = ts.buildDbFile(
                "<ServiceFrame id=\"sf2\" version=\"1\">"
                        + "<lines><Line id=\"dup\" version=\"1\"><Name>second</Name></Line></lines>"
                        + "<routes><Route id=\"r2\" version=\"1\"><LineRef ref=\"dup\" version=\"1\"/></Route></routes>"
                        + "</ServiceFrame>",
                "f2.mdbx");
        Check.that(markerPresent(f1) && markerPresent(f2), "precondition: sources are swept");
        Path target = ts.tempPath("merged.mdbx");
        Path log = ts.tempPath("merged.log");
        Log.toFile(log);
        try {
            ItMerge.merge(List.of(f1, f2), target,
                    List.of("one", "two"), true, false, true, ts.format);
        } finally {
            Log.toFile(null);
        }
        Check.equals(0, sweepCalls(log),
                "marker propagated -> resolve() must SKIP the full blob-repair sweep");
        Check.that(markerPresent(target), "marker propagated -> the full-DB sweep is skipped");
        try (Store db = Stores.open(target, true); Txn txn = db.roTxn()) {
            List<Route> routes = new ArrayList<>();
            for (Object o : db.iterOnlyObjects(txn, Route.class)) routes.add((Route) o);
            Check.equals(1, routes.size(), "exactly one Route");
            Check.equals("dup:two", routes.get(0).getLineRef().getValue().getRef(),
                    "LineRef rewritten to the qualified id");
            Line line = (Line) db.loadObjectByReference(txn, routes.get(0).getLineRef().getValue());
            Check.that(line != null, "qualified LineRef resolves");
            Check.equals("second", Mls.text(line.getName()), "resolves to feed2's Line");
        }
    }

    /// The DB_UNRESOLVED half of CloneSupport.referrerFullKeys. feed2's Route references its own
    /// Line 'dup' with a mismatched version ('9' vs the stored '1') and feed2 is built without
    /// resolve(), so the reference exists only as an unresolved row — no outward edge for the
    /// resolved-half scan to find. The merge must still patch the Route: its LineRef re-qualified
    /// to 'dup:two' and bound to feed2's own Line ('second').
    public static void testUnresolvedReferrerRequalifiedToOwnSource(TestStore ts) throws Exception {
        Path f1 = ts.buildDbFile(linesFrame("dup", "first", "a1", "A1"), "f1.mdbx");
        Path f2 = ts.tempPath("f2.mdbx");
        try (Store raw = Stores.create(f2, ts.format)) { // direct inserts, NO resolve()
            Route route = new Route();
            route.setId("r2");
            route.setVersion("1");
            route.setLineRef(FACTORY.createLineRef(
                    new LineRefStructure().withRef("dup").withVersion("9"))); // version mismatch
            try (Txn txn = raw.rwTxn()) {
                raw.insertAnyObjects(txn, List.of(
                        new Line().withId("dup").withVersion("1")
                                .withName(Mls.of("second")),
                        route));
                txn.commit();
            }
            try (Txn txn = raw.roTxn()) {
                boolean unresolvedDup = false;
                for (UnresolvedRow row : raw.iterUnresolved(txn)) {
                    byte[] enc = row.encodedTarget();
                    int n = 0;
                    while (n < enc.length && enc[n] != 0) n++;
                    if ("dup".equals(new String(enc, 0, n, StandardCharsets.UTF_8))) unresolvedDup = true;
                }
                Check.that(unresolvedDup, "precondition: Route's LineRef sits in the unresolved map");
                int edges = 0;
                for (long[] ignored : raw.iterEdges(txn)) edges++;
                Check.equals(0, edges, "precondition: no resolved edge exists in feed2");
            }
        }

        Path target = ts.tempPath("merged-unres.mdbx");
        Path log = ts.tempPath("merged-unres.log");
        Log.toFile(log);
        try {
            ItMerge.merge(List.of(f1, f2), target,
                    List.of("one", "two"), true, false, true, ts.format);
        } finally {
            Log.toFile(null);
        }
        // Negative control for testMarkerPropagationKeepsQualifiedReferrerResolvable's zero-sweep
        // assertion: f2 carries no marker, so resolve() runs its one-shot sweep here.
        Check.that(!markerPresent(f2), "precondition: the hand-built source is un-swept");
        Check.equals(1, sweepCalls(log),
                "an un-swept source -> resolve() runs the full blob-repair sweep exactly once");

        Map<String, String> names = lineNames(target);
        Check.equals("first", names.get("dup"), "feed1's Line keeps the unqualified id");
        Check.equals("second", names.get("dup:two"), "feed2's colliding Line qualified");
        try (Store db = Stores.open(target, true); Txn txn = db.roTxn()) {
            List<Route> routes = new ArrayList<>();
            for (Object o : db.iterOnlyObjects(txn, Route.class)) routes.add((Route) o);
            Check.equals(1, routes.size(), "exactly one Route");
            Check.equals("dup:two", routes.get(0).getLineRef().getValue().getRef(),
                    "the UNRESOLVED referrer's ref was re-qualified");
            Line line = (Line) db.loadObjectByReference(txn, routes.get(0).getLineRef().getValue());
            Check.that(line != null, "the re-qualified ref resolves");
            Check.equals("second", Mls.text(line.getName()),
                    "…and to the RIGHT source's object (not feed1's 'first')");
        }
    }
}
