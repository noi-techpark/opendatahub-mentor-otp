package tests;

// Cutting a journey whose pattern is a SUPERSET of it: the journey calls at some of its pattern's
// points, not all of them.
//
// The ÖBB and STA feeds publish superset patterns in quantity, and OTP discards every journey whose
// passing times do not agree with its pattern's points -- JourneyPatternSJMismatch -- so a
// truncation that leaves them disagreeing throws the journey away along with every interchange
// referencing it.

import noi.netex.model.LinkInLinkSequence_VersionedChildStructure;
import noi.netex.model.PointInLinkSequence_VersionedChildStructure;
import noi.netex.model.ServiceJourney;
import noi.netex.model.ServiceJourneyPattern;
import noi.netex.model.StopPointInJourneyPattern_VersionedChildStructure;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.Runner;
import toolkit.test.TestStore;
import transformers.xb.XbTruncate;

import java.util.ArrayList;
import java.util.List;

public class TestXbTruncate {

    public static void main(String[] args) {
        Runner.run(TestXbTruncate.class);
    }

    /// Five pattern points, a journey calling at four of them, cut to its first two calls. The kept
    /// points must be the ones those calls REFERENCE (jp0, jp2), not the ones at the same indices
    /// (jp0, jp1).
    public static void testASupersetPatternIsSelectedByRefNotByIndex(TestStore db) throws Exception {
        db.loadNetex(pattern("at:obb:Pat:2", 5));
        db.loadNetex(journey("at:obb:Sj:4", "at:obb:Pat:2", new int[] {0, 2, 3, 4}));

        XbTruncate.Cut cut = cut(db, "at:obb:Sj:4", 0, 1);
        Check.that(cut != null, "the cut succeeded");
        Check.that(!cut.misaligned(), "and reconciled");
        List<PointInLinkSequence_VersionedChildStructure> pts = points(cut);
        Check.equals(2, pts.size(), "two points kept");
        Check.equals("SSP:jp0", stopRef(pts.get(0)), "the first is jp0");
        Check.equals("SSP:jp2", stopRef(pts.get(1)),
                "the second is jp2 — NOT jp1, which is where plain index arithmetic would land");
        Check.equals(2, cut.sj().getPassingTimes().getTimetabledPassingTime().size(),
                "the journey is truncated to match");
    }

    /// The kept points are jp0 and jp2, which are not adjacent in the original — so no original
    /// ServiceLink joins them and the container goes rather than carrying a link that spans a gap.
    public static void testNonContiguousKeptPointsDropTheLinksWhole(TestStore db) throws Exception {
        db.loadNetex(pattern("at:obb:Pat:2", 5));
        db.loadNetex(journey("at:obb:Sj:4", "at:obb:Pat:2", new int[] {0, 2, 3, 4}));

        XbTruncate.Cut cut = cut(db, "at:obb:Sj:4", 0, 1);
        Check.that(cut != null, "the cut succeeded");
        Check.equals(0, links(cut).size(),
                "linksInSequence is dropped, not left at a length the consumer would reject");
    }

    /// The contiguous case still keeps points−1 links.
    public static void testContiguousKeptPointsKeepPointsMinusOneLinks(TestStore db) throws Exception {
        db.loadNetex(pattern("at:obb:Pat:3", 5));
        db.loadNetex(journey("at:obb:Sj:5", "at:obb:Pat:3", new int[] {0, 1, 2, 3, 4}));

        XbTruncate.Cut cut = cut(db, "at:obb:Sj:5", 1, 3);
        Check.that(cut != null, "the cut succeeded");
        Check.equals(3, points(cut).size(), "three points kept");
        Check.equals(2, links(cut).size(), "and two links between them");
    }

    // ------------------------------------------------------------------------------------- //

    static XbTruncate.Cut cut(TestStore db, String sjId, int from, int to) throws Exception {
        try (Txn txn = db.store.roTxn()) {
            ServiceJourney sj = null;
            for (Object o : db.store.iterOnlyObjects(txn, ServiceJourney.class)) {
                if (((ServiceJourney) o).getId().equals(sjId)) sj = (ServiceJourney) o;
            }
            Check.that(sj != null, "fixture journey " + sjId + " is in the store");
            return XbTruncate.cut(db.store, txn, sj, from, to, /*shared=*/false);
        }
    }

    static List<PointInLinkSequence_VersionedChildStructure> points(XbTruncate.Cut cut) {
        ServiceJourneyPattern pat = (ServiceJourneyPattern) cut.pattern();
        if (pat == null || pat.getPointsInSequence() == null) return List.of();
        List<PointInLinkSequence_VersionedChildStructure> out = new ArrayList<>();
        for (PointInLinkSequence_VersionedChildStructure p : pat.getPointsInSequence()
                .getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern()) {
            out.add(p);
        }
        return out;
    }

    static List<LinkInLinkSequence_VersionedChildStructure> links(XbTruncate.Cut cut) {
        ServiceJourneyPattern pat = (ServiceJourneyPattern) cut.pattern();
        if (pat == null || pat.getLinksInSequence() == null) return List.of();
        return pat.getLinksInSequence().getServiceLinkInJourneyPatternOrTimingLinkInJourneyPattern();
    }

    static String stopRef(PointInLinkSequence_VersionedChildStructure p) {
        return ((StopPointInJourneyPattern_VersionedChildStructure) p)
                .getScheduledStopPointRef().getValue().getRef();
    }

    /// `n` points, `n-1` links, ids `jp0..`, each on its own ScheduledStopPoint.
    static String pattern(String id, int n) {
        StringBuilder pts = new StringBuilder();
        StringBuilder lks = new StringBuilder();
        for (int i = 0; i < n; i++) {
            pts.append(String.format(
                    "<StopPointInJourneyPattern id=\"%s:jp%d\" version=\"1\" order=\"%d\">"
                    + "<ScheduledStopPointRef ref=\"SSP:jp%d\"/></StopPointInJourneyPattern>",
                    id, i, i + 1, i));
            if (i + 1 < n) {
                lks.append(String.format(
                        "<ServiceLinkInJourneyPattern id=\"%s:l%d\" version=\"1\" order=\"%d\">"
                        + "<ServiceLinkRef ref=\"SL:%d\"/></ServiceLinkInJourneyPattern>",
                        id, i, i + 1, i));
            }
        }
        return "<ServiceFrame id=\"sf:" + id + "\" version=\"1\"><journeyPatterns>"
                + "<ServiceJourneyPattern id=\"" + id + "\" version=\"1\">"
                + "<pointsInSequence>" + pts + "</pointsInSequence>"
                + "<linksInSequence>" + lks + "</linksInSequence>"
                + "</ServiceJourneyPattern></journeyPatterns></ServiceFrame>";
    }

    /// A journey calling at `calls` of the pattern's points, each passing time naming the point it
    /// belongs to — which is the correspondence the truncation reads.
    static String journey(String id, String patId, int[] calls) {
        StringBuilder tt = new StringBuilder();
        for (int i = 0; i < calls.length; i++) {
            tt.append(String.format(
                    "<TimetabledPassingTime id=\"%s:t%d\" version=\"1\">"
                    + "<StopPointInJourneyPatternRef ref=\"%s:jp%d\"/>"
                    + "<DepartureTime>0%d:00:00</DepartureTime></TimetabledPassingTime>",
                    id, i, patId, calls[i], i + 6));
        }
        return "<TimetableFrame id=\"tf:" + id + "\" version=\"1\"><vehicleJourneys>"
                + "<ServiceJourney id=\"" + id + "\" version=\"1\">"
                + "<JourneyPatternRef ref=\"" + patId + "\"/>"
                + "<passingTimes>" + tt + "</passingTimes>"
                + "</ServiceJourney></vehicleJourneys></TimetableFrame>";
    }
}
