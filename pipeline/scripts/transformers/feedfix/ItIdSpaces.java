package transformers.feedfix;

// The Italian RAP id spaces, and the six ways the source feeds get them wrong.
//
// A RAP feed is meant to publish `IT:<NUTS-2>:<Class>:<local>` -- `IT:ITC4:`, `IT:ITH5:`,
// `IT:ITI1:` and so on. What turns up instead, over the stored top-level objects this pass can
// reach:
//
//   epd:IT:                 The Marche feeds prefix every object and every ref this way --
//                           DayTypes, ServiceJourneys, ScheduledStopPoints, Lines, a complete
//                           feed. `epd` matches none of the codespaces those feeds declare
//                           (`rmgtfsxml`, `gtfs`), so it is an undeclared prefix rather than a
//                           codespace the data defines. Elsewhere it is one ValidBetween per feed,
//                           inherited from a CompositeFrame id: frames themselves are structural
//                           to NetexLoader and are never stored, but that child is.
//   epd:it:                 ValidBetween, the same shape in lower case -- and with no NUTS token at
//                           all, so this one cannot simply drop the `epd:` and be done.
//   IT:IT14:Operator:       One per Lazio feed, carrying that feed's OperatorRefs. The same feeds'
//                           other ids say ITI4.
//   IT::Operator:           Two feeds, and the two are in different regions (bolzano ITH1,
//                           veneto/DOLOMITIBUS ITH3).
//   it:apb:Operator:        bolzano -- alongside that feed's `IT::Operator:` ids, under different
//                           local parts. Bolzano-only, so the rule lies dormant whenever that feed
//                           is out of the allowlist.
//   IT-ITF3:VM: / :VT:      campania/ALILAURO -- hyphen where the rest of the feed uses a colon,
//                           and an abbreviation where NeTEx wants the class name.
//
// Deliberately absent: `it:ServiceCalendar:C01`, which many source feeds publish. A ServiceCalendar
// is a frame-level container the loader does not store as an object, so no rule here could fire on
// it. Also absent, and not reachable from here: Trenitalia's `IT::VehicleJourney:` (national rail,
// with no NUTS region to move under) and STA's `it:apb:` / `IT:ITH1:`->`IT:ITH10:` (its own step,
// fix/RewriteStaSspIds.java). Both arrive on chains this pass is not wired into.
//
// The Authorities `it-rap-db-to-db` synthesises from Operators inherit the Operator's space, so a
// bad Operator id makes a bad Authority id; the correction runs before that stage.
//
// The whole correction is a pure function of the id string: there is no rename map to build or
// hold, and the same call corrects a top-level id, an embedded object's id and a reference alike.

import toolkit.keycodec.NulKeyCodec;
import toolkit.store.IdRow;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.util.Log;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ItIdSpaces {

    /// Rule names, indexed by the value [#ruleOf] returns. `<H>` is the feed's home space.
    public static final String[] RULES = {
        "epd:IT: -> IT:",
        "epd:<other>: -> <H>:",
        "IT:IT14: -> <H>:",
        "IT:: -> <H>:",
        "it:apb: -> <H>:",
        "<H-hyphenated>:VM: -> <H>:VehicleModel:",
        "<H-hyphenated>:VT: -> <H>:VehicleType:",
    };

    private static final String EPD_IT = "epd:IT:";
    private static final String EPD = "epd:";

    private ItIdSpaces() {}

    /// The rule that fires on `id`, or -1 when it is already conforming (or when `home` is null
    /// and the rule needs one).
    ///
    /// The rules are mutually exclusive and applied as such: `epd:IT:` drops its token and leaves
    /// `IT:ITI3:…`, which no later rule matches, so no id is corrected twice. Rule 1 is the same
    /// prefix with anything else behind it — `epd:it:`, which carries no NUTS token and so cannot
    /// be fixed by dropping `epd:` alone — and it replaces both tokens rather than one.
    public static int ruleOf(String id, String home) {
        if (id == null) return -1;
        if (id.startsWith(EPD_IT)) return 0;
        if (home == null) return -1;
        if (id.startsWith(EPD)) return spaceEnd(id) < 0 ? -1 : 1;
        if (id.startsWith("IT:IT14:")) return 2;
        if (id.startsWith("IT::")) return 3;
        if (id.startsWith("it:apb:")) return 4;
        // The hyphen form of the feed's own space, not a literal `IT-ITF3`: derived, it cannot
        // misfire on a region whose ids happen to contain another region's code.
        String hyphen = home.replace(':', '-');
        if (id.startsWith(hyphen + ":VM:")) return 5;
        if (id.startsWith(hyphen + ":VT:")) return 6;
        return -1;
    }

    /// The corrected id, or `id` itself when no rule fires.
    public static String rewrite(String id, String home) {
        int rule = ruleOf(id, home);
        if (rule < 0) return id;
        return switch (rule) {
            case 0 -> id.substring(EPD.length());
            case 1 -> home + id.substring(spaceEnd(id));
            case 2 -> home + ":" + id.substring("IT:IT14:".length());
            case 3 -> home + ":" + id.substring("IT::".length());
            case 4 -> home + ":" + id.substring("it:apb:".length());
            case 5 -> home + ":VehicleModel:" + id.substring(home.length() + ":VM:".length());
            case 6 -> home + ":VehicleType:" + id.substring(home.length() + ":VT:".length());
            default -> throw new IllegalStateException("unreachable rule " + rule);
        };
    }

    /// Index of the colon ending an `epd:<token>` space, i.e. where the home space is spliced in;
    /// -1 when the id has no second colon and so no space to replace.
    private static int spaceEnd(String id) {
        return id.indexOf(':', EPD.length());
    }

    /// The feed's home space — the modal `IT:<token>` over the id index, counted on the
    /// post-`epd:`-strip form so Marche votes `IT:ITI3` rather than `epd:IT`. It is derived per
    /// feed rather than written down: the two feeds carrying `IT::Operator:` are in different
    /// regions.
    ///
    /// `IT:` with an empty token and `IT:IT14` are excluded from the vote: they are two of the
    /// things being corrected, and a broken space must not get to name the space that replaces it.
    ///
    /// Null unless one space holds a strict majority of the ids that vote. A RAP operator feed is
    /// one operator in one region, so its own space is 99%+ of its ids; anything less is a feed
    /// this table has not been read against, and the caller skips the rules that need a home
    /// rather than guessing one.
    public static String homeSpace(Store db, Txn txn) {
        Map<String, Long> votes = new LinkedHashMap<>();
        long total = 0;
        for (IdRow row : db.iterIdIndex(txn)) {
            String id = NulKeyCodec.idPart(row.encodedKey());
            if (id.startsWith(EPD)) id = id.substring(EPD.length());
            // Marche votes IT:ITI3 because of the line above; `epd:it:` and every other lower-case
            // space falls out here, having no NUTS token to offer.
            if (!id.startsWith("IT:")) continue;
            int end = id.indexOf(':', "IT:".length());
            if (end < 0) continue;
            String space = id.substring(0, end);
            if (space.length() <= "IT:".length()) continue;   // `IT::…`, an empty NUTS token
            if (space.equals("IT:IT14")) continue;
            votes.merge(space, 1L, Long::sum);
            total++;
        }
        String best = null;
        long bestCount = 0;
        for (Map.Entry<String, Long> e : votes.entrySet()) {
            if (e.getValue() > bestCount) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        if (best == null || bestCount * 2 <= total) {
            Log.info("[it-id-spaces] no majority IT:<NUTS> space over %,d ids (%d candidates) "
                    + "-- the rules that need one are skipped", total, votes.size());
            return null;
        }
        return best;
    }
}
