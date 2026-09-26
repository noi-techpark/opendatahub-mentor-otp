package fix;

// Rename each Italo StopPlace to the Trenitalia station it stands on, so the rail merge can fold
// the two publishers' copies together.
//
// Italo keys its stops by its own three-letter codes -- RMT, MC-, BC- -- and carries no UIC or RFI
// code anywhere, so nothing in the two feeds matches by id. The consolidation matches on an
// accent/case/punctuation-folded name inside a radius, and the names do not agree either: Italo
// appends an English gloss (`Roma Termini (Rome)`), abbreviates differently (`Bologna Centrale`
// against `BOLOGNA C.LE`), reorders (`Milano Rho Fiera` against `Rho Fiera Milano`) and translates
// (`Padua` against `PADOVA`). 26 of its 70 stations merge nationally; the other 44 reach the graph
// as a second station on the same platform.
//
// The coordinates do agree, and they agree decisively. Sorted by distance to the nearest Trenitalia
// station, Italo's stops are two populations with an empty band between them: 59 counterparts from
// 0 m to 251 m, nothing at all from 251 m to 539 m, then 11 genuinely separate places from 539 m
// (Villa San Giovanni Marittima, the Sicily ferry terminal) out to 21.7 km (the Cortina coach). So
// the match is nearest-neighbour inside RADIUS_M, and a name is written rather than compared.
//
// Renaming beats teaching the merge a looser name rule, which was the alternative. Dropping the
// name gate there merges 8 pairs of differently-named Trenitalia stations into each other at this
// radius; a containment rule reaches 50 of the 70 against this pass's 59, and folds VENEZIA MESTRE
// into VENEZIA MESTRE BINARIO 1 GIARDINO. Both also ask the toolkit to carry a rule that only two
// Italian publishers need.
//
// The rename is invisible downstream. Trenitalia wins every survivor election in the merge that
// follows -- one quay per station against Italo's none -- so the object that survives is the
// Trenitalia StopPlace under the name it already had, and this pass exists only to make the two
// meet.
//
// GUARD_M is the band the measurement says is empty. A stop landing in it is a coordinate that
// moved, and the pass refuses instead of binding a station to a neighbour it cannot check.

import noi.netex.model.StopPlace;
import noi.netex.text.Mls;
import toolkit.harness.Args;
import toolkit.store.Store;
import toolkit.store.Stores;
import toolkit.store.Txn;
import toolkit.util.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class AlignItaloStopNames {

    private AlignItaloStopNames() {}

    /// Nearest counterpart inside this is the same station. 300 m, from a band measured empty
    /// between 251 m and 539 m -- 49 m of clearance over the furthest counterpart, 239 m under the
    /// nearest separate place, and the room is on the side of not merging.
    private static final double RADIUS_M = 300.0;

    /// The top of that empty band, and it has to stay under the 539 m where the separate places
    /// start: Villa San Giovanni Marittima is the Sicily ferry terminal, 539 m from the station of
    /// that name, and a ceiling above it refuses the terminal instead of leaving it alone. A
    /// nearest station between RADIUS_M and here is the ambiguity the measurement says does not
    /// exist, so it stops the run rather than resolving one way.
    private static final double GUARD_M = 500.0;

    private static final double M_PER_DEG = 111_320.0;

    /// A reference station: its folded-out name and position.
    private record Station(String name, double lat, double lon) {}

    public static void main(String[] args) throws Exception {
        Args a = Args.parse(args,
                "AlignItaloStopNames <src> <reference-db> <target> [--format v2] [--log-file F]",
                "--format=", "--log-file=").expectAtLeast(3);
        String logFile = a.get("--log-file", null);
        if (logFile != null) Log.toFile(Path.of(logFile));
        List<String> positional = a.positional();
        Path src = Path.of(positional.get(0));
        Path ref = Path.of(positional.get(1));
        Path target = Path.of(positional.get(2));
        for (Path p : List.of(src, ref)) {
            if (!Files.exists(p) || !Stores.exists(p)) {
                Log.error("[align-italo] %s contains no database", p);
                System.exit(1);
            }
        }
        if (Stores.exists(target)) {
            Log.error("[align-italo] target %s already contains a database -- refusing", target);
            System.exit(1);
        }
        String fmt = a.get("--format", null);
        if (fmt == null) fmt = Stores.sniffFormat(src);

        List<Station> stations = reference(ref);
        try (Store sdb = Stores.open(src, true); Txn stx = sdb.roTxn();
             Store tdb = Stores.create(target, fmt)) {
            try (Txn dtx = tdb.rwTxn()) {
                for (Class<?> clazz : sdb.dbNames(stx)) {
                    if (clazz == StopPlace.class) continue;
                    sdb.copyMap(stx, tdb, dtx, clazz);
                }
                tdb.insertAnyObjects(dtx, renamed(sdb, stx, stations).iterator());
                dtx.commit();
            }
        }
    }

    /// Every located StopPlace in the reference store.
    private static List<Station> reference(Path ref) {
        List<Station> out = new ArrayList<>();
        try (Store db = Stores.open(ref, true); Txn txn = db.roTxn()) {
            for (Object o : db.iterOnlyObjects(txn, StopPlace.class)) {
                StopPlace sp = (StopPlace) o;
                double[] c = toolkit.transform.common.ScheduledStopPoints.stopplaceCoord(sp);
                if (c == null) continue;
                String name = text(sp.getName());
                if (!name.isEmpty()) out.add(new Station(name, c[0], c[1]));
            }
        }
        Log.info("[align-italo] reference holds %d located stations", out.size());
        return out;
    }

    /// Every StopPlace, the matched ones under their counterpart's name. Refuses on a stop whose
    /// nearest counterpart sits in the guard band.
    private static List<Object> renamed(Store db, Txn txn, List<Station> stations) {
        List<Object> out = new ArrayList<>();
        long matched = 0, unmatched = 0, unlocated = 0;
        List<String> ambiguous = new ArrayList<>();
        for (Object o : db.iterOnlyObjects(txn, StopPlace.class)) {
            StopPlace sp = (StopPlace) o;
            out.add(sp);
            double[] c = toolkit.transform.common.ScheduledStopPoints.stopplaceCoord(sp);
            if (c == null) {
                unlocated++;
                continue;
            }
            Station best = null;
            double bestD = Double.MAX_VALUE;
            for (Station s : stations) {
                double d = metres(c[0], c[1], s.lat(), s.lon());
                if (d < bestD) {
                    bestD = d;
                    best = s;
                }
            }
            if (best == null || bestD > GUARD_M) {
                unmatched++;
                continue;
            }
            if (bestD > RADIUS_M) {
                ambiguous.add(String.format("%s (%s) -> %s at %.0f m",
                        text(sp.getName()), sp.getId(), best.name(), bestD));
                continue;
            }
            sp.setName(Mls.of(best.name()));
            matched++;
        }
        if (!ambiguous.isEmpty()) {
            for (String s : ambiguous) Log.error("[align-italo] in the guard band: %s", s);
            throw new IllegalStateException(String.format(
                    "%d stop(s) whose nearest counterpart is between %.0f m and %.0f m: the band "
                    + "measured empty, so a coordinate moved and the match needs re-measuring",
                    ambiguous.size(), RADIUS_M, GUARD_M));
        }
        Log.info("[align-italo] renamed %d StopPlaces to their counterpart within %.0f m; "
                + "%d beyond %.0f m left alone, %d unlocated",
                matched, RADIUS_M, unmatched, GUARD_M, unlocated);
        return out;
    }

    /// Flat-earth metres. The distances that decide anything here are under a kilometre.
    private static double metres(double latA, double lonA, double latB, double lonB) {
        double dLat = (latA - latB) * M_PER_DEG;
        double dLon = (lonA - lonB) * M_PER_DEG * Math.cos(Math.toRadians((latA + latB) / 2));
        return Math.hypot(dLat, dLon);
    }

    private static String text(noi.netex.model.MultilingualString n) {
        return Mls.textOrEmpty(n).trim();
    }
}
