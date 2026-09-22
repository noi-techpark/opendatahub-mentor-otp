package fix;

// CLI + wiring only. The transform itself is transformers/feedfix/MentzLineVersions.java.
//
// The Makefile names this file by path, so moving or renaming it breaks the sta-db and at stages.
// The algorithm carries no such constraint, so it lives in the transformer where other callers can
// reach it.

import toolkit.harness.DbToDb;
import transformers.feedfix.MentzLineVersions;

public class ResolveMentzLineVersions {

    private ResolveMentzLineVersions() {}

    public static void main(String[] args) {
        DbToDb.driverMain("resolve-mentz-line-versions", args, MentzLineVersions::apply);
    }
}
