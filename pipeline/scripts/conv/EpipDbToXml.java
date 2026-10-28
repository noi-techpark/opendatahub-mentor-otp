package conv;

// CLI over toolkit.harness.DbToZip for the EPIP export profile, toolkit.harness.EpipDbToZip:
// every object of a store streamed into an EPIP-shaped NeTEx zip, laid out on OTP's three file
// levels. What each entry holds and why is in EpipDbToZip's header; nothing about it is here.
//
// This file used to be a 1164-line VERBATIM COPY of netex-toolkit's scripts/EpipDbToXml.java, kept
// here so this repository has one source root and the Makefile needs no sibling checkout. The
// profile is in the jar now, so there is nothing left to drift: the toolkit's own driver is these
// same lines without the `package conv;`.

import toolkit.harness.DbToZip;
import toolkit.harness.EpipDbToZip;

public class EpipDbToXml {

    public static void main(String[] args) throws Exception {
        // --level and --log-file come from DbToZip; EpipDbToZip.flags() declares --shards and
        // --journeys-per-file, the two the harness cannot know about.
        DbToZip.driverMain(EpipDbToZip.TAG, args,
                EpipDbToZip.USAGE, EpipDbToZip.flags(), EpipDbToZip::setup);
    }
}
