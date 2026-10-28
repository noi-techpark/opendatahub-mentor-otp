package transformers.feedfix;

// What a publisher put in Line/PublicCode when the field wants the number a rider reads.
//
// OTP takes a route's short name off Line/PublicCode and its long name off Line/Name, so a feed that
// fills PublicCode with an internal composite gives every route on it a badge no rider recognises.
// Three shapes reach the merge, and the repairs key on the RELATIONSHIP between a Line's fields
// rather than on parsing the composite -- the separator vocabulary is per publisher and open-ended
// (`A_ATV`, `11_UL_terra_ACTVs.p.a`, `211_OP1`, `620083_pass_0083`) while the relationship is not.
//
// Naming a line is NOT here. These arms move a value the publisher already wrote into the field that
// wants it; they invent nothing. A line that states no name anywhere is named after the two places
// it runs between, which needs the whole corpus and happens in the EPIP transform.
//
// Veneto's ACTV publishes it as <ShortName>_<class>_<medium>_<operator>, the medium distinguishing
// land from water, so parsing would have to know `terra` from `mare` from `UL`. IT-1 needs to know
// none of them.
//
// A composite can also END on the code a rider reads, with another of the Line's fields holding that
// code alone: Marche's `ADRIABUS_45` beside PrivateCode `45`, TPER Bologna's `11_11A` beside
// ShortName `11A`. IT-3 takes the field the composite ends in.
//
// Trenitalia prefixes its company code onto both PublicCode and PrivateCode, `0083_REG` beside
// `0083_10083`. The shared prefix is what says the leading token is the company and not the line.
//
// Italo is a policy rather than a derivation: it publishes one Line per train-number pair and names
// it after the pair, so the pair becomes the line's name and the operator becomes its code. Its
// Lines carry no ShortName, PrivateCode or Description at all, so the access-point rules cannot fire
// on them whichever stage meets them.

import noi.netex.model.Line;
import noi.netex.model.PrivateCodeStructure;
import noi.netex.text.Codes;
import noi.netex.text.Mls;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class LineCodes {

    private LineCodes() {}

    /// Which publisher's shapes to repair. Each names the arms that fire, so a feed only ever meets
    /// the rules written for it.
    public enum Policy {
        /// IT-1 and IT-2: the Italian regional and national access-point feeds.
        IT_RAP,
        /// The `route_id` private code behind a `FlixBus `-prefixed public code.
        FLIX,
        /// One Line per train-number pair, named after the pair.
        ITALO,
        /// The company code Trenitalia prefixes onto both its public and its private code.
        TRENITALIA
    }

    /// Italo's trailing variant counter. The counter is also in the Line's id, which is what says it
    /// marks a variant rather than a third train.
    private static final Pattern ITALO_VARIANT = Pattern.compile("_#\\d+$");

    /// The Lines `policy` repaired, over `db` at `txn`, composed onto whatever `pending` already
    /// holds for an id. Decoding a row twice gives two objects, so an id `pending` covers is
    /// repaired on the object `pending` holds and every other on a freshly decoded one, leaving the
    /// caller one object per id to overlay.
    ///
    /// Per id and not per (id, version) row, deliberately: the only caller that passes a non-empty
    /// map is the RAP driver, whose `planOverlay` collapses every stored version of a changed id
    /// onto the overlay object's version. One object per id is exactly what that consumes. A caller
    /// overlaying rows one for one must pass an empty map and take the per-row list back.
    ///
    /// FlexibleLine is not read: no feed these policies cover publishes one.
    public static List<Line> repairLineCodes(Store db, Txn txn, Policy policy,
                                            Map<String, Line> pending) {
        List<Line> out = new ArrayList<>();
        long codes = 0;
        long names = 0;
        for (Object o : db.iterOnlyObjects(txn, Line.class)) {
            Line line = (Line) o;
            Line target = pending.getOrDefault(line.getId(), line);
            boolean code = false;
            boolean name = false;
            switch (policy) {
                case IT_RAP -> {
                    code = takeShortNameAsPublicCode(target);
                    name = takeDescriptionAsName(target);
                    code |= takeSuffixAsPublicCode(target);
                }
                case FLIX -> code = takeRouteIdAsPublicCode(target);
                case ITALO -> {
                    code = nameTheOperator(target);
                    name = spellTheTrainNumberPair(target);
                }
                case TRENITALIA -> code = dropSharedPrefix(target);
            }
            if (code) codes++;
            if (name) names++;
            if (code || name) out.add(target);
        }
        Log.info("[line-codes] %s: %,d public codes and %,d names repaired over %,d lines",
                policy, codes, names, out.size());
        return out;
    }

    /// IT-1. A PublicCode that is blank or repeats the PrivateCode is an internal value in a public
    /// field; the ShortName beside it is the code a rider reads. Whether it fired.
    static boolean takeShortNameAsPublicCode(Line line) {
        String shortName = Mls.text(line.getShortName());
        if (shortName == null || shortName.isBlank()) return false;
        String code = Codes.text(line.getPublicCode());
        String privateCode = line.getPrivateCode() == null ? null : line.getPrivateCode().getValue();
        boolean blank = code == null || code.isBlank();
        if (!blank && !shortName.equals(code) && !code.equals(privateCode)) return false;
        if (shortName.equals(code)) return false;
        line.setPublicCode(Codes.of(shortName));
        return true;
    }

    /// IT-2. A Name repeating one of the Line's own codes names nothing; where the Description holds
    /// something else it is the label the feed wrote. Whether it fired.
    static boolean takeDescriptionAsName(Line line) {
        String name = Mls.text(line.getName());
        if (name == null || name.isBlank()) return false;
        String description = Mls.text(line.getDescription());
        if (description == null || description.isBlank() || description.equals(name)) return false;
        if (!name.equals(Codes.text(line.getPublicCode()))
                && !name.equals(Mls.text(line.getShortName()))
                && !(line.getPrivateCode() != null
                        && name.equals(line.getPrivateCode().getValue()))) {
            return false;
        }
        Mls.setText(line.getName(), description);
        return true;
    }

    /// IT-3. A PublicCode ending on `_` and then the whole of the ShortName or the PrivateCode is a
    /// composite around that field, and the field is the code. The ShortName goes with it where it
    /// repeated the composite. Whether it fired.
    static boolean takeSuffixAsPublicCode(Line line) {
        String code = Codes.text(line.getPublicCode());
        if (code == null) return false;
        String shortName = Mls.text(line.getShortName());
        String privateCode = line.getPrivateCode() == null ? null : line.getPrivateCode().getValue();
        for (String field : new String[] {shortName, privateCode}) {
            if (field == null || field.isBlank() || field.equals(code)) continue;
            if (!code.endsWith("_" + field)) continue;
            takeCode(line, code, field);
            return true;
        }
        return false;
    }

    /// The Trenitalia arm. A PublicCode and a different PrivateCode opening on the same `<token>_`
    /// carry the company in that token; the rest of the PublicCode is the code. A PrivateCode equal
    /// to the PublicCode shares every prefix and says nothing. Whether it fired.
    static boolean dropSharedPrefix(Line line) {
        String code = Codes.text(line.getPublicCode());
        if (code == null || line.getPrivateCode() == null) return false;
        String privateCode = line.getPrivateCode().getValue();
        int sep = code.indexOf('_');
        if (sep <= 0 || privateCode == null || privateCode.equals(code)) return false;
        String prefix = code.substring(0, sep + 1);
        String rest = code.substring(sep + 1);
        if (rest.isBlank() || !privateCode.startsWith(prefix)) return false;
        takeCode(line, code, rest);
        return true;
    }

    /// Replace the composite `old` with `code` in PublicCode, and in a ShortName that repeated it.
    private static void takeCode(Line line, String old, String code) {
        line.setPublicCode(Codes.of(code));
        if (old.equals(Mls.text(line.getShortName()))) Mls.setText(line.getShortName(), code);
    }

    /// The FlixBus arm. The export carries the line's own code as a `route_id` private code and
    /// prefixes the public one with the publisher; the suffix test is what keeps this off anything
    /// else. Whether it fired.
    static boolean takeRouteIdAsPublicCode(Line line) {
        if (line.getPrivateCodes() == null) return false;
        String code = Codes.text(line.getPublicCode());
        if (code == null) return false;
        for (PrivateCodeStructure pc : line.getPrivateCodes().getPrivateCode()) {
            if (!"route_id".equals(pc.getType())) continue;
            String routeId = pc.getValue();
            if (routeId == null || routeId.isBlank()) continue;
            if (!code.endsWith(" " + routeId)) continue;
            line.setPublicCode(Codes.of(routeId));
            return true;
        }
        return false;
    }

    /// The Italo arm, half one: every Line carries the operator as its code, there being one Line
    /// per train-number pair and no line number to carry. Whether it fired.
    static boolean nameTheOperator(Line line) {
        if ("Italo".equals(Codes.text(line.getPublicCode()))) return false;
        line.setPublicCode(Codes.of("Italo"));
        return true;
    }

    /// The Italo arm, half two: the train-number pair read as a pair. A trailing `_#N` is the
    /// variant counter the Line's id also carries, so it goes before the join. Whether it fired.
    static boolean spellTheTrainNumberPair(Line line) {
        String name = Mls.text(line.getName());
        if (name == null || name.isBlank()) return false;
        String spelled = ITALO_VARIANT.matcher(name).replaceFirst("").replace("_", " / ");
        if (spelled.equals(name)) return false;
        Mls.setText(line.getName(), spelled);
        return true;
    }

}
