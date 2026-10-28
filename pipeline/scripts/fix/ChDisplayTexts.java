package fix;

// The Swiss destination displays state their text in Name and leave FrontText empty, on every one
// of them. The Makefile names this file by path and runs it in the $(CH_DB) chain.
//
// FrontText is the field a consumer reads. Without it a journey has no headsign but for the OTP
// fork's own fallback to Name, and the terminus naming pass finds no place to name a line after,
// so every Swiss line keeps its number.
//
// It runs before the EPIP stage, whose display synthesis declines a pattern whose first point
// already names a display that exists: a Swiss pattern names one, so nothing downstream repairs
// this.

import toolkit.harness.DbToDb;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.transform.feedfix.DisplayTexts;

public class ChDisplayTexts {

    private ChDisplayTexts() {}

    public static void main(String[] args) {
        DbToDb.driverMain("ch-display-texts", args, ChDisplayTexts::apply);
    }

    public static void apply(Store src, Txn stx, Store dst, Txn dtx) {
        dst.cloneFrom(src, stx, dtx, null, 0);
        dst.insertAnyObjects(dtx, DisplayTexts.backfillFrontText(src, stx).iterator());
    }
}
