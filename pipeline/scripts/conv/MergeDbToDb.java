package conv;

// The shard merge: the per-country EPIP shards back into one store, first source wins on a
// colliding (id, version, class). The shards share one population by construction -- every shard
// carries a copy of every consolidated survivor StopPlace -- so a collision here is two copies of
// one object. conv.ItMergeDbToDb is the other policy (qualify colliding ids so both survive) and
// is for the Italian region merge, where a collision means two different objects.

import toolkit.harness.MergeToDb;

public class MergeDbToDb {

    private MergeDbToDb() {}

    public static void main(String[] args) throws Exception {
        MergeToDb.driverMain("merge-db", args);
    }
}
