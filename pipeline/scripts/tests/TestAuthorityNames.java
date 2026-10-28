package tests;

// The Mentz Authority name swap, driven through resolve-mentz-line-versions: the long text in
// PublicCode becomes the Name, an Authority already written the right way round is left alone, and
// a second run changes nothing.

import fix.ResolveMentzLineVersions;
import noi.netex.model.Authority;
import noi.netex.text.Codes;
import noi.netex.text.Mls;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.Runner;
import toolkit.test.TestStore;

public class TestAuthorityNames {

    /// The ServiceFrame's ValidBetween is the frame end the Mentz pass refuses to run without.
    private static final String FRAMES = """
        <ServiceFrame id="sf" version="1">
          <ValidBetween><FromDate>2025-12-14T00:00:00</FromDate><ToDate>2026-12-14T23:59:59</ToDate></ValidBetween>
        </ServiceFrame>
        <ResourceFrame id="rf" version="1">
          <organisations>
            <Authority id="at:vvt:Authority:vvt:" version="any">
              <PublicCode>Verkehrsverbund Tirol</PublicCode>
              <Name>vvt</Name>
              <ShortName>vvt</ShortName>
            </Authority>
            <Authority id="ch:1:Authority:11" version="any">
              <PublicCode>SBB</PublicCode>
              <Name>Schweizerische Bundesbahnen SBB</Name>
              <ShortName>SBB</ShortName>
            </Authority>
          </organisations>
        </ResourceFrame>
        """;

    public static void main(String[] args) {
        Runner.run(TestAuthorityNames.class);
    }

    private static Authority find(TestStore db, String id) {
        try (Txn txn = db.store.roTxn()) {
            for (Object o : db.store.iterOnlyObjects(txn, Authority.class)) {
                if (id.equals(((Authority) o).getId())) return (Authority) o;
            }
        }
        throw new AssertionError("no Authority " + id);
    }

    public static void testCodeAndNameAreSwapped(TestStore db) throws Exception {
        db.loadNetex(FRAMES);

        Authority a = find(db.runDbToDb(ResolveMentzLineVersions::apply), "at:vvt:Authority:vvt:");

        Check.equals("Verkehrsverbund Tirol", Mls.text(a.getName()), "the long text is the Name");
        Check.equals("vvt", Codes.text(a.getPublicCode()), "the feed code is the PublicCode");
        Check.equals("vvt", Mls.text(a.getShortName()), "the ShortName is kept");
    }

    public static void testAnAuthorityWrittenTheRightWayRoundIsLeftAlone(TestStore db)
            throws Exception {
        db.loadNetex(FRAMES);

        Authority a = find(db.runDbToDb(ResolveMentzLineVersions::apply), "ch:1:Authority:11");

        Check.equals("Schweizerische Bundesbahnen SBB", Mls.text(a.getName()), "the Name is kept");
        Check.equals("SBB", Codes.text(a.getPublicCode()), "the PublicCode is kept");
    }

    public static void testASecondRunChangesNothing(TestStore db) throws Exception {
        db.loadNetex(FRAMES);

        Authority a = find(db.runDbToDb(ResolveMentzLineVersions::apply)
                .runDbToDb(ResolveMentzLineVersions::apply), "at:vvt:Authority:vvt:");

        Check.equals("Verkehrsverbund Tirol", Mls.text(a.getName()), "not swapped back");
        Check.equals("vvt", Codes.text(a.getPublicCode()), "the code stays the PublicCode");
    }
}
