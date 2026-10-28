package transformers.xb;

// Every table and threshold the cross-border stage keys on, for the one corridor that exists
// (IT/CH/AT/DE).

import java.util.List;
import java.util.Map;

public final class XbProfile {

    private XbProfile() {}

    /// UIC country-code prefix → ISO country, over the `<cc>_<n>` canonical form.
    ///
    /// A lookup miss is a miss, never a pass-through — see [XbIds#uicCountry].
    static final Map<String, String> UIC_COUNTRY = Map.of(
            "80", "DE",
            "81", "AT",
            "83", "IT",
            "85", "CH",
            "87", "FR",
            "79", "SI");

    /// Stations whose UIC coding disagrees with the country they physically sit in. Tirano is coded
    /// 85 (CH) and is in Italy.
    static final Map<String, String> HONORARY_COUNTRY = Map.of("85_9369", "IT");

    /// The `xx-` HAFAS country infix a feed uses when it codes a stop in another country:
    /// `at:obb:ScheduledStopPoint:ch-23016-20302-0-2:` is ÖBB's coding of a Swiss stop. Consulted
    /// before the id-space tables.
    public static final Map<String, String> HAFAS_COUNTRY = Map.ofEntries(
            Map.entry("at", "AT"),
            Map.entry("it", "IT"),
            Map.entry("ch", "CH"),
            Map.entry("de", "DE"),
            Map.entry("si", "SI"),
            Map.entry("hu", "HU"),
            Map.entry("cz", "CZ"),
            Map.entry("sk", "SK"),
            Map.entry("fl", "FL"));

    /// Journey id-space prefix → the publishing feed's authoritative country, checked in order.
    ///
    /// Case-sensitive: `IT:` is Trenitalia and the RAP regions, `it:` is STA/South Tyrol. No id
    /// starts with both, so the order between them does not matter.
    ///
    /// `de:`, `si:` and `fl:` are absent: they are stop-only spaces here, and [#STOP_ID_SPACES]
    /// carries them for [XbIds#stopCountry].
    static final String[][] JOURNEY_ID_SPACES = {
            {"IT:", "IT"},
            {"it:", "IT"},
            {"ch:", "CH"},
            {"at:", "AT"},
            {"DE:", "DE"},
    };

    /// Id-space prefix → country for spaces that occur on stops, checked after [#JOURNEY_ID_SPACES]
    /// and only by [XbIds#stopCountry]. This arm answers for 275,010 of the corpus's 425,116 stop
    /// ids.
    static final String[][] STOP_ID_SPACES = {
            {"de:", "DE"},
            {"si:", "SI"},
            {"fl:", "FL"},
    };

    /// The countries that can own a consolidated station, in tie-break order. Membership is also
    /// the filter: a UIC vote for anything outside this list does not count.
    static final List<String> BORDER_PRECEDENCE = List.of("CH", "AT", "IT", "DE");

    /// StopPlace id prefix → the feed that published it, for the same-namespace guard in the
    /// bus-quay merge. `?` when nothing matches.
    public static String feedNamespace(String stopPlaceId) {
        if (stopPlaceId == null) return "?";
        for (String p : new String[] {"ch:", "IT", "it:", "DE", "de:", "at:"}) {
            if (stopPlaceId.startsWith(p)) {
                return p.endsWith(":") ? p.substring(0, p.length() - 1) : p;
            }
        }
        return "?";
    }

    /// The full id space: everything up to and including the second colon — `ch:23016:`,
    /// `IT:ITC4:`, `at:48:`. Finer than [#feedNamespace], which stops at the first token and so
    /// cannot tell SBB's `ch:2:` from another operator's `ch:23016:` copy of a Swiss stop.
    ///
    /// Not a drop-in replacement for the coarse one: in the `IT:` prefix the fine space separates
    /// mere NUTS regions of one publisher family.
    public static String idSpace(String id) {
        if (id == null) return "?";
        int first = id.indexOf(':');
        if (first < 0) return id;
        int second = id.indexOf(':', first + 1);
        return second < 0 ? id.substring(0, first + 1) : id.substring(0, second + 1);
    }

    // ------------------------------------------------------------------ thresholds

    /// Two rail stops within this many degrees of latitude (~440 m) are copies of one physical
    /// station.
    public static final double CONSOLIDATE_RADIUS_DEG = 0.004;

    /// The widest a consolidated station may end up, ~880 m; a wider cluster is dropped whole.
    ///
    /// The clustering is single-linkage, so [#CONSOLIDATE_RADIUS_DEG] bounds each link and this
    /// bounds the chain of links.
    public static final double CONSOLIDATE_MAX_DIAMETER_DEG = 2 * CONSOLIDATE_RADIUS_DEG;

    /// ~1 km: the radius at which two stop ids from different publishers are read as the same
    /// physical stop when neither the consolidation nor a shared code connects them.
    public static final double GEO_PAIR_RADIUS_DEG = 0.009;

    /// How far apart two operators' rail stations may be and still be one station for matching.
    ///
    /// A road pair is one stop published twice — measured distances 0, 1, 1, 2 m, frequently the
    /// same embedded HAFAS id under two prefixes — while a rail pair is two operators' own station
    /// records for one place: Abfaltersbach is 1,378 m between the ÖBB and Trenitalia publications.
    ///
    /// ~1,500 m, measured against a road radius held at 1,002 m
    /// (scripts/tools/XbAliasRadiusProbe.java, 2026-08-31).
    public static final double RAIL_PAIR_RADIUS_DEG = 0.013475;   // 1500 m

    /// The same, for everything that is not rail-served.
    public static final double BUS_PAIR_RADIUS_DEG = GEO_PAIR_RADIUS_DEG;

    /// A border handover of one physical train differs between publishers only by dwell and customs
    /// time. 30 minutes is the one window: the shared-stop time agreement, the single-shared-stop
    /// arm and the span containment all use it.
    public static final int MEETING_WINDOW_S = 30 * 60;

    /// Spatial-index bucket (~1.1 km); probe the 3x3 neighbourhood.
    public static final double BUCKET_DEG = 0.01;

    /// How many calls of its own a parallel publication may make between a leg's two endpoints that
    /// the leg never makes, and still be that leg's train under another calendar.
    ///
    /// The only bound in the corridor test. The other axis — calls the leg makes that the
    /// publication skips — is not bounded at all.
    ///
    /// An added call is the only signal in the data that this publication reaches the leg's far
    /// endpoint by a different route, and nothing downstream can see it: `XbLinks.junction` asks
    /// only about the handover station, and `XbGroups.keepsNoOtherLegsTerritory` refuses only a
    /// detour through a station some other leg holds — a detour through country no leg covers
    /// passes it. Left unbounded, a publication sharing nothing but the two endpoints would be cut
    /// to them and given a stay-seated link across a border it never crosses.
    ///
    /// Not zero: the premise is that two publishers describe one train differently, and a stop
    /// one advertises and the other does not is the ordinary case.
    ///
    /// No publication that reaches this rung adds a station at all in the current corpus, so only
    /// TestXbEdges exercises the bound.
    public static final int PARALLEL_MAX_ADDED = 1;
}
