package conv;

// Copies the source feed verbatim into a fresh target and, while doing so, gives every numbered
// Trenitalia journey a first-class TrainNumber. Everything stable is copyMap'd; only the
// ServiceJourney map is re-emitted, so the synthesised trainNumbers links can be overlaid,
// alongside the created TrainNumbers. No id changes, so references are only created, never
// updated.

import noi.netex.model.ServiceJourney;
import toolkit.harness.DbToDb;
import toolkit.store.Store;
import toolkit.store.Txn;
import transformers.common.TrainNumbers;

public class TrenitaliaDbToDb {

    public static void main(String[] args) {
        // The caller (DbToDb) owns the transactions — source ro, target rw, commit + mandatory
        // resolve()/resolveEmbeddings() afterwards.
        DbToDb.driverMain("trenitalia-db-to-db", args, TrenitaliaDbToDb::applyTrainNumbers);
    }

    /// Named so tests can run the transform without the CLI sandwich.
    public static void applyTrainNumbers(Store src, Txn stx, Store dst, Txn dtx) {
        // Copy every class verbatim except ServiceJourney; the journeys are re-emitted below
        // so the synthesised links overlay them (their id is unchanged, key preserved).
        for (Class<?> clazz : src.dbNames(stx)) {
            if (clazz == ServiceJourney.class) continue;
            src.copyMap(stx, dst, dtx, clazz);
        }
        dst.insertAnyObjects(dtx, TrainNumbers.updates(src, stx));
    }
}
