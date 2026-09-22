package fix;

// Driver for transformers.feedfix.SplitJourneys — the JourneyMeetings a Mentz feed leaves out when
// the two halves of a split train are published on different Lines. The Makefile names this file by
// path and runs it last in the Austrian chain ($(AT_DB)); moving or renaming it breaks that chain.
//
// It must run before the EPIP stage and before the consolidation: EpipToDb is what turns a
// JourneyMeeting into the ServiceJourneyInterchange the rest of the pipeline reads, so a meeting
// written after it is a meeting nothing converts, and ahead of the consolidation both halves are
// one feed's journeys over one stop registry, so the join is a StopPlace equality rather than a
// geometry problem.

import noi.netex.model.JourneyMeeting;
import toolkit.harness.DbToDb;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.util.Log;
import transformers.feedfix.SplitJourneys;

import java.util.List;

public class StitchSplitJourneys {

    private StitchSplitJourneys() {}

    public static void main(String[] args) {
        DbToDb.driverMain("stitch-split-journeys", args, StitchSplitJourneys::apply);
    }

    /// Raw-clone source into target, then insert the synthesised meetings. The pass reads the
    /// source, which the clone reproduces byte for byte.
    public static void apply(Store src, Txn stx, Store dst, Txn dtx) {
        SplitJourneys.Result result = SplitJourneys.stitch(src, stx, "[stitch]");
        dst.cloneFrom(src, stx, dtx, null, 0);
        List<JourneyMeeting> meetings = result.meetings();
        if (meetings.isEmpty()) {
            // Not a refusal. A feed with no split trains — every Italian and Swiss input, and the
            // Austrian one if ÖBB ever publishes the meetings itself — gets none, and the clone is
            // still a complete store.
            Log.info("[stitch] no meeting was synthesised; the target is the clone, unchanged");
            return;
        }
        dst.insertAnyObjects(dtx, meetings);
    }
}
