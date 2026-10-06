package tests;

// Why Italo needs a codespace of its own: the EPIP stage attributes a Line that arrives without an
// authorityRef from the sole Authority of its codespace, and a synthesised Authority is stored, so on
// the next run it is indistinguishable from a published one. Two publishers in one space and only one
// of them synthesised therefore means that one answers for the other.
//
// `IT::` was that pair. Italo publishes no Authority, so `it-rap-db-to-db` mints `IT::Authority:1` for
// it per feed; Trenitalia's chain runs no backfill, so its 14 Lines reach the `it` shard unattributed
// with an `IT::Operator:` of their own. SHARED below is that store, and it records the defect.
//
// Both fixtures carry a third publisher's Authority in an unrelated space. That is not decoration: the
// backfill has a "one published Authority and nothing matched it, so reuse it" arm, which in a store
// this small would answer for everyone and hide what is being tested. The real `it` shard holds 302,
// so the third one is what makes these fixtures behave like it.

import noi.netex.model.Authority;
import noi.netex.model.Line;
import noi.netex.text.Mls;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.Runner;
import toolkit.test.TestStore;
import toolkit.transform.common.LineAuthorities;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.Set;

public class TestItaloAuthorityCodespace {

    private static final String ITALO_AUTHORITY = """
          <Authority id="%1$s:Authority:1" version="1"><Name>Italo</Name>
            <OrganisationType>authority</OrganisationType>
          </Authority>
          <Operator id="%1$s:Operator:1" version="1"><Name>Italo</Name></Operator>
        """;

    /// Trenitalia as it arrives at the shard: an Operator, and a Line naming it and nothing else.
    private static final String TRENITALIA = """
          <Operator id="IT::Operator:05403151003:TRENITALIA:TRENITALIA" version="1">
            <Name>TRENITALIA</Name>
          </Operator>
        """;

    /// A RAP region, so the store has more than one published Authority.
    private static final String OTHER_PUBLISHER = """
          <Authority id="IT:ITC4:Authority:LOM-1" version="1"><Name>Lombardia</Name>
            <OrganisationType>authority</OrganisationType>
          </Authority>
        """;

    private static String store(String italoSpace) {
        return "<ResourceFrame id=\"rf\" version=\"1\"><organisations>"
                + ITALO_AUTHORITY.formatted(italoSpace)
                + TRENITALIA
                + OTHER_PUBLISHER
                + "</organisations></ResourceFrame>"
                + "<ServiceFrame id=\"sf\" version=\"1\"><lines>"
                // Italo's Line is already attributed, by the per-feed backfill.
                + "<Line id=\"" + italoSpace + ":Line:9916-1-1\" version=\"1\"><Name>9916</Name>"
                + "<TransportMode>rail</TransportMode>"
                + "<AuthorityRef ref=\"" + italoSpace + ":Authority:1\" version=\"1\"/>"
                + "<OperatorRef ref=\"" + italoSpace + ":Operator:1\" version=\"1\"/></Line>"
                // Trenitalia's is not.
                + "<Line id=\"IT::Line:railTRENITALIA:10083\" version=\"1\"><Name>Regionale</Name>"
                + "<TransportMode>rail</TransportMode>"
                + "<OperatorRef ref=\"IT::Operator:05403151003:TRENITALIA:TRENITALIA\" version=\"1\"/>"
                + "</Line>"
                + "</lines></ServiceFrame>";
    }

    public static void main(String[] args) {
        Runner.run(TestItaloAuthorityCodespace.class);
    }

    /// The defect, recorded. While Italo sits in `IT::`, its synthesised Authority is the only one in
    /// that space, so Trenitalia's Line takes it and every Trenitalia journey is published as Italo's.
    public static void testSharingTheSpaceGivesTrenitaliaItalosAuthority(TestStore db) throws Exception {
        db.loadNetex(store("IT:"));   // `IT:` + `:Authority:` spells the empty-token space

        Check.equals("IT::Authority:1", trenitaliaAuthorityRef(db),
                "sharing IT:: hands Trenitalia's Line the Authority minted for Italo");
    }

    /// The fix: with Italo in `IT:ITALO:`, `IT::` has one tenant, so nothing answers for
    /// Trenitalia's Operator by codespace and the backfill mints Trenitalia its own -- which is what
    /// the Sep-24 export contains, from before Italo joined the feed set.
    public static void testItaloInItsOwnSpaceLeavesTrenitaliaToItself(TestStore db) throws Exception {
        db.loadNetex(store("IT:ITALO"));

        Check.equals("IT::Authority:05403151003:TRENITALIA:TRENITALIA", trenitaliaAuthorityRef(db),
                "Trenitalia's Line is attributed from its own Operator, not from Italo's Authority");
    }

    /// And the Authority it mints is Trenitalia's, by name as well as by id.
    public static void testTheMintedAuthorityCarriesTheOperatorsName(TestStore db) throws Exception {
        db.loadNetex(store("IT:ITALO"));

        Set<String> minted = new TreeSet<>();
        for (Object o : backfill(db)) {
            if (o instanceof Authority a) minted.add(a.getId() + " = " + Mls.text(a.getName()));
        }
        Check.equals(Set.of("IT::Authority:05403151003:TRENITALIA:TRENITALIA = TRENITALIA"), minted,
                "one Authority synthesised, for the one unattributed publisher");
    }

    // ------------------------------------------------------------------------------- //

    /// The repair the backfill proposes, read off the Line it returns rather than off the store: the
    /// pass reads only, and its caller is what writes.
    private static List<Object> backfill(TestStore db) {
        try (Txn txn = db.store.roTxn()) {
            return new ArrayList<>(LineAuthorities.backfillLineAuthorities(db.store, txn));
        }
    }

    private static String trenitaliaAuthorityRef(TestStore db) {
        for (Object o : backfill(db)) {
            if (o instanceof Line l && "IT::Line:railTRENITALIA:10083".equals(l.getId())) {
                return l.getAuthorityRef() == null ? null : l.getAuthorityRef().getRef();
            }
        }
        Check.that(false, "the backfill did not attribute Trenitalia's Line at all");
        return null;
    }
}
