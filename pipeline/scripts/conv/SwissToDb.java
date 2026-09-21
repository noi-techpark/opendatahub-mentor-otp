package conv;

// Swiss feed loader: a FeedToDb-harness configuration with two deltas, both in options():
//   1. MEMBER FILTER: only token-matching members load. Every current member matches; the filter
//      is kept defensively against future drops.
//   2. DEDUP MERGE: StopPlace quay-union merge, registered defensively — the swiss zip has no
//      cross-member duplicate stops, and the CH chain gate passes either way.
// DropForeignJourneys remains a separate pipeline stage.

import noi.netex.model.StopPlace;
import toolkit.harness.FeedToDb;
import toolkit.load.ZipLoad;
import toolkit.transform.common.MpLoadRules;

import java.util.Map;

public class SwissToDb {

    static final String[] SWISS_MEMBER_TOKENS = {
            "_RESOURCE_", "_SITE_", "_SERVICE_", "_SERVICECALENDAR_", "_TIMETABLE_", "_COMMON_"};

    /// The swiss feed semantics as ZipLoad options — the ONE definition (LoadFeeds reuses it).
    public static ZipLoad.Options options() {
        return new ZipLoad.Options(
                SwissToDb::swissMember,
                null, // archive order: no name ordering
                Map.of(StopPlace.class, MpLoadRules::mergeStopPlaceQuays),
                null);
    }

    public static void main(String[] args) throws Exception {
        FeedToDb.driverMain("swiss", args, options());
    }

    static boolean swissMember(String name) {
        for (String t : SWISS_MEMBER_TOKENS) {
            if (name.contains(t)) return true;
        }
        return false;
    }
}
