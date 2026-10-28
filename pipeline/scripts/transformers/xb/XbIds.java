package transformers.xb;

// Identifier parsing: UIC codes, the country a stop or a journey belongs to, train numbers, and the
// slots a constructed interchange id is built from.
//
// Only the WHOLE journey id is injective. Anything that shortens one for use as a key loses the
// identity the id exists to carry.

import java.math.BigInteger;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class XbIds {

    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern HAFAS_CC = Pattern.compile("(?:^|:)([a-z]{2})-\\d");

    private XbIds() {}

    // ------------------------------------------------------------------ UIC codes

    /// The canonical `<cc>_<n>` form of whatever UIC code an id or a PrivateCode carries: the
    /// longest digit run (first maximal), split after two characters with the remainder read as an
    /// integer. Null for null/empty input, no digits, or a run shorter than three digits.
    ///
    /// It answers for almost any string and most of its answers are not UIC codes at all, so
    /// [#uicCountry] is what decides whether an id carries a code.
    public static String canonUic(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        Matcher m = DIGITS.matcher(raw);
        String longest = null;
        while (m.find()) {
            String g = m.group();
            if (longest == null || g.length() > longest.length()) longest = g;
        }
        if (longest == null || longest.length() < 3) return null;
        return longest.substring(0, 2) + "_" + new BigInteger(longest.substring(2));
    }

    /// The ISO country of a canonical UIC code, or null when the two-digit prefix is not one the
    /// table names.
    ///
    /// There is no pass-through arm and there must never be one: an unrecognised prefix means the
    /// digits were not a UIC code, and the only honest answer to "which country is `31`" is that
    /// the question was wrong.
    public static String uicCountry(String canon) {
        if (canon == null || canon.isEmpty()) return null;
        String honorary = XbProfile.HONORARY_COUNTRY.get(canon);
        if (honorary != null) return honorary;
        int sep = canon.indexOf('_');
        return XbProfile.UIC_COUNTRY.get(sep < 0 ? canon : canon.substring(0, sep));
    }

    /// The UIC canon of a StopPlace-ish (PrivateCode preferred, id as the fallback) but ONLY when it
    /// is a real UIC country. Null otherwise — an ÖBB Verbund-local `369` canonicalises to `36_9`,
    /// which is not a code, and neither is the `38_77` its id yields.
    public static String realUic(String privateCode, String id) {
        String c = canonUic(privateCode);
        if (uicCountry(c) == null) c = canonUic(id);
        return uicCountry(c) != null ? c : null;
    }

    // ------------------------------------------------------------------ countries

    /// The publishing feed's authoritative country for a JOURNEY id, from its id-space prefix alone.
    /// Null when no prefix matches — which the scan reads as "not classifiable" and skips, so a
    /// missing row here removes a whole region from matching and the output then looks like a data
    /// gap.
    public static String feedCountry(String objectId) {
        if (objectId == null || objectId.isEmpty()) return null;
        for (String[] p : XbProfile.JOURNEY_ID_SPACES) {
            if (objectId.startsWith(p[0])) return p[1];
        }
        return null;
    }

    /// The country a stop sits in, decided in three steps: a HAFAS `xx-` infix, else a REAL UIC
    /// country, else the country of the id space the ref belongs to. Null when none of the three
    /// answers — 18 of 425,116 refs on the 2026-08-26 corpus, all `hr:`/`hu:`/`cz:` strays.
    ///
    /// The order is not interchangeable. HAFAS must win over the id space or every foreign stop a
    /// feed codes itself is filed under the publisher's country; the UIC step must be the STRICT
    /// lookup or it answers for everything and the id-space arm is never reached.
    public static String stopCountry(String stopRef) {
        if (stopRef == null || stopRef.isEmpty()) return null;
        Matcher m = HAFAS_CC.matcher(stopRef);
        if (m.find()) {
            String cc = XbProfile.HAFAS_COUNTRY.get(m.group(1));
            if (cc != null) return cc;
        }
        String uic = uicCountry(canonUic(stopRef));
        if (uic != null) return uic;
        return stopSpaceCountry(stopRef);
    }

    /// The country a STOP id's own space belongs to: the journey table first, then the stop-only
    /// spaces. Separate from [#feedCountry] because widening THAT table to reach a stop-only space
    /// would move journey classification along with it.
    public static String stopSpaceCountry(String stopId) {
        String c = feedCountry(stopId);
        if (c != null) return c;
        if (stopId == null) return null;
        for (String[] p : XbProfile.STOP_ID_SPACES) {
            if (stopId.startsWith(p[0])) return p[1];
        }
        return null;
    }

    // ------------------------------------------------------------------ train numbers

    /// Normalise a train number for cross-publisher matching: the FIRST digit run as an integer
    /// string with leading zeros dropped, else the upper-cased stripped token so an alphanumeric
    /// number is still matchable. BigInteger because a digit run has no length bound.
    public static String normalizeTrainNumber(String num) {
        if (num == null) return null;
        String n = num.strip();
        if (n.isEmpty()) return null;
        Matcher m = DIGITS.matcher(n);
        return m.find() ? new BigInteger(m.group()).toString() : n.toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ id construction

    /// The journey-identity slot of a constructed interchange id: the WHOLE journey id with `':'`
    /// replaced by `'_'` so it cannot be mistaken for a slot separator.
    ///
    /// Every ÖBB and STA journey id ends with a colon, so the last colon-separated segment is empty
    /// for two whole feeds, and the segment before it is an ÖBB calendar code (`s9q10`) shared
    /// across journeys. Neither identifies a journey, and the store's key is `(id, version)` with
    /// the second write winning silently.
    public static String slot(String id) {
        return id == null ? "" : id.replace(':', '_');
    }
}
