package tests;

// The atlas draws what the tiling decided. These check that it draws the RIGHT thing, on the same
// Brenner fixture TestXbTiling asserts the coupling itself against.

import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.Runner;
import toolkit.test.TestStore;
import tools.XbCoupleAtlas;
import tools.XbCoupleAtlas.Atlas;
import tools.XbCoupleAtlas.Band;
import tools.XbCoupleAtlas.Row;

import javax.xml.parsers.DocumentBuilderFactory;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class TestXbCoupleAtlas {

    public static void main(String[] args) {
        Runner.run(TestXbCoupleAtlas.class);
    }

    /// Two publishers, one handover: two leg rows and one junction, and the junction is drawn at the
    /// border station rather than at either end of either journey.
    public static void testTheCoupledLegsAndTheirJunctionAreDrawn(TestStore db) throws Exception {
        brenner(db, false);
        Atlas atlas = read(db);
        Band band = one(atlas);

        Check.isNull(band.refusal(), "the group tiled");
        Check.equals(2, kind(band, XbCoupleAtlas.LEG).size(), "two legs, one per country");
        Check.equals(1, band.junctions().length, "and one junction between them");
        Check.equals("SP:Brennero", atlas.stations().ids().value(band.junctions()[0]),
                "anchored at the border station both legs serve — not at a journey endpoint");
        Check.equals(1, band.junctionCap()[0],
                "one variant pair could be linked there, which is the ≤n the bar is labelled with");
    }

    /// The cut is what `from`/`to` bound, and it has to be the run the leg OWNS — the Italian leg
    /// keeps two of its four stops.
    public static void testTheCutIsDrawnWhereTheLegWasTruncated(TestStore db) throws Exception {
        brenner(db, false);
        Band band = one(read(db));
        Row italian = null;
        for (Row r : kind(band, XbCoupleAtlas.LEG)) {
            if (r.node().id.equals("IT:Sj:1826")) italian = r;
        }
        Check.that(italian != null, "the Trenitalia leg is drawn");
        Check.equals(0, italian.from(), "it keeps its run from the first stop");
        Check.equals(1, italian.to(), "to the border, so stops 2 and 3 are drawn as cut away");
        Check.equals(4, italian.node().canon.length,
                "while the journey itself still has four stops to draw");
    }

    /// The third publication of the same train is dropped as redundant, and a dropped publication
    /// is a different fact from an uncoupled one — it is carried by a kept leg, not left in the
    /// graph. The band has to say which.
    public static void testADroppedPublicationIsDrawnAsDropped(TestStore db) throws Exception {
        brenner(db, true);
        Band band = one(read(db));
        List<Row> dropped = kind(band, XbCoupleAtlas.DROPPED);
        Check.equals(1, dropped.size(), "one of the two Italian publications is redundant");
        Check.equals("it:apb:Sj:1826", dropped.get(0).node().id, "and it is the STA copy");
        Check.equals(-1, dropped.get(0).from(), "a dropped publication keeps nothing");
        Check.equals(0, kind(band, XbCoupleAtlas.UNCOUPLED).size(),
                "and it is NOT counted as uncoupled — the two mean opposite things");
    }

    /// The output has to be a document a browser will open, and unescaped journey ids are the
    /// obvious way to lose that.
    public static void testTheSvgParses(TestStore db) throws Exception {
        brenner(db, true);
        Atlas atlas = read(db);
        Path dir = Files.createTempDirectory("xb-atlas-test");
        try {
            int pages = XbCoupleAtlas.write(dir, atlas, 100);
            Check.equals(1, pages, "one sheet for one group");
            for (String f : new String[] {"xb-map.svg", "xb-groups-001.svg"}) {
                byte[] svg = Files.readAllBytes(dir.resolve(f));
                Check.that(svg.length > 0, f + " is not empty");
                DocumentBuilderFactory.newInstance().newDocumentBuilder()
                        .parse(new ByteArrayInputStream(svg));   // throws if malformed
            }
            String sheet = Files.readString(dir.resolve("xb-groups-001.svg"));
            Check.that(sheet.contains("Train 1826"), "the sheet names the train");
            Check.that(sheet.contains("Brennero"), "and labels the border station by name");
            Check.that(Files.readString(dir.resolve("xb-index.md")).contains("1826"),
                    "and the index lists it");
        } finally {
            for (Path p : Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    /// A journey id carries `<` in no feed, but it carries `&` in some, and the escaping is one
    /// shared routine.
    public static void testTextIsXmlEscaped() {
        Check.equals("a&amp;b&lt;c&gt;d&quot;e", XbCoupleAtlas.esc("a&b<c>d\"e"),
                "every character XML reserves is escaped");
        Check.equals("", XbCoupleAtlas.esc(null), "and a null name is empty, not the string 'null'");
    }

    // ------------------------------------------------------------------------------------- //

    /// The Brenner fixture, reused verbatim from [TestXbTiling] so the two cannot drift apart.
    /// `third` adds the STA publication of the Italian section, which is the redundant one.
    static void brenner(TestStore db, boolean third) throws Exception {
        db.loadNetex(TestXbTiling.STOPS);
        db.loadNetex("<ServiceFrame id=\"psa\" version=\"1\">" + TestXbTiling.ASSIGNMENTS
                + "</ServiceFrame>");
        db.loadNetex(TestXbTiling.CALENDAR);
        db.loadNetex(TestXbTiling.journey("IT:Pat:1", "IT:Sj:1826",
                new String[] {TestXbTiling.IT_F, TestXbTiling.IT_B, TestXbTiling.IT_G,
                        TestXbTiling.IT_I},
                new String[] {"20:00:00", "20:30:00", "20:45:00", "21:20:00"}));
        if (third) {
            db.loadNetex(TestXbTiling.journey("it:apb:Pat:1", "it:apb:Sj:1826",
                    new String[] {"it:apb:ScheduledStopPoint:830003400s",
                            "it:apb:ScheduledStopPoint:830002001s",
                            "it:apb:ScheduledStopPoint:810000300s",
                            "it:apb:ScheduledStopPoint:810001187s"},
                    new String[] {"20:01:00", "20:31:00", "20:46:00", "21:21:00"}));
        }
        db.loadNetex(TestXbTiling.journey("at:obb:Pat:1", "at:obb:Sj:1826",
                new String[] {TestXbTiling.AT_B, TestXbTiling.AT_G, TestXbTiling.AT_I},
                new String[] {"20:32:00", "20:47:00", "21:22:00"}));
    }

    static Atlas read(TestStore db) throws Exception {
        try (Txn txn = db.store.roTxn()) {
            return XbCoupleAtlas.read(db.store, txn, null);
        }
    }

    static Band one(Atlas atlas) {
        Check.equals(1, atlas.bands().size(), "the fixture is one group");
        return atlas.bands().get(0);
    }

    static List<Row> kind(Band band, int kind) {
        List<Row> out = new ArrayList<>();
        for (Row r : band.rows()) {
            if (r.kind() == kind) out.add(r);
        }
        return out;
    }
}
