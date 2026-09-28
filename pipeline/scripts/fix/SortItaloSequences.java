package fix;

// Driver for transformers.feedfix.ItaloSequences -- the Italo OAP feed's lexically ordered
// pointsInSequence and passingTimes. The Makefile names this file by path and runs it at the head of
// the OAP chain, on the raw load, ahead of normalize-it-ids.

import toolkit.harness.DbToDb;
import toolkit.store.Store;
import toolkit.store.Txn;
import transformers.feedfix.ItaloSequences;

public class SortItaloSequences {

    private SortItaloSequences() {}

    public static void main(String[] args) {
        DbToDb.driverMain("sort-italo-sequences", args, SortItaloSequences::apply);
    }

    /// Raw-clone source into target, then overwrite the patterns and journeys the pass re-sequenced.
    /// The pass reads the source, which the clone reproduces byte for byte, so an object already in
    /// sequence is never re-marshalled.
    ///
    /// No skip predicate and no reference patching: the sort moves children inside one object and
    /// changes no id, version or ref, so every re-emitted object lands on the row the clone already
    /// wrote for it. The caller's resolveEmbeddings() then rewires each re-emitted journey's
    /// StopPointInJourneyPatternRefs, which name embedded children and so carry no id-index row.
    public static void apply(Store src, Txn stx, Store dst, Txn dtx) {
        dst.cloneFrom(src, stx, dtx, null, 0);
        dst.insertAnyObjects(dtx, ItaloSequences.inOrderSequence(src, stx));
    }
}
