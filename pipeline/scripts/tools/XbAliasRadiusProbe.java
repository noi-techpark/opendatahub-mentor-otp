package tools;

// Sweeps the station alias radius, reporting what each width pairs and what it changes downstream.
// Reads only.
//
// The alias is the identity under which two publishers' copies of one physical station are
// recognised as one for MATCHING. It reaches 1,002 m today and pairs 102 stations; Abfaltersbach is
// published by ÖBB and by Trenitalia 1,378 m apart, so it falls outside.
//
// It is not the merge radius. XbProfile.CONSOLIDATE_RADIUS_DEG (~440 m) deletes StopPlaces; the
// alias is an identity used while matching and then discarded. The one thing a wider alias can do
// wrong is call two DIFFERENT stations one, which shows up here as links that should not exist.
//
// The numbers to read, per radius:
//
//   pairs        how many stations were identified. Growth far beyond a handful is the alarm.
//   uncoupled    the population this is meant to reduce, and the refusal breakdown under it.
//   dropped      redundancy. A jump means the wider alias found duplicates.
//   legs/groups  the SHAPE of the corpus's groups. The alias is supposed to let existing groups
//                tile, not invent new ones.
//
// USE:
//   java -Xmx8g $JVM -cp netex-toolkit-shaded.jar scripts/tools/XbAliasRadiusProbe.java \
//        data/epip.lmdb [--radii 1002:1002,1500:1002]

import toolkit.store.Store;
import toolkit.store.Stores;
import toolkit.store.Txn;
import toolkit.util.Log;
import tools.XbCoupleAtlas.Atlas;
import transformers.xb.XbGroups;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class XbAliasRadiusProbe {

    public static void main(String[] args) throws Exception {
        Path source = null;
        // railMetres:roadMetres. The road radius is held at today's 1,002 m throughout: the road
        // pairs are duplicate publications of one stop at 0-42 m.
        List<String> configs = new ArrayList<>(List.of("1002:1002", "1378:1002", "1500:1002",
                "2000:1002"));
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--radii")) {
                configs.clear();
                for (String r : args[++i].split(",")) configs.add(r.trim());
            } else if (args[i].startsWith("--")) {
                Log.error("unknown flag %s", args[i]);
                System.exit(1);
            } else {
                source = Path.of(args[i]);
            }
        }
        if (source == null || !Stores.exists(source)) {
            Log.error("usage: XbAliasRadiusProbe <epip_db> [--radii railM:roadM,...]");
            System.exit(1);
            return;
        }

        Map<String, Atlas> runs = new LinkedHashMap<>();
        try (Store db = Stores.open(source, true); Txn txn = db.roTxn()) {
            for (String cfg : configs) {
                String[] parts = cfg.split(":");
                int railM = Integer.parseInt(parts[0]);
                int roadM = parts.length > 1 ? Integer.parseInt(parts[1]) : railM;
                Log.info("[radius] ==== rail %d m, road %d m ====", railM, roadM);
                runs.put(cfg, XbCoupleAtlas.read(db, txn, null, railM / 111_320.0,
                        roadM / 111_320.0));
            }
        }

        Log.info("[radius] %11s %8s %8s %8s %9s %9s %10s", "rail:road", "groups", "legs", "variants",
                "dropped", "uncoupled", "junctions");
        for (Map.Entry<String, Atlas> e : runs.entrySet()) {
            var t = e.getValue().totals();
            Log.info("[radius] %11s %8d %8d %8d %9d %9d %10d", e.getKey(), t.groups, t.legs,
                    t.variants, t.dropped, t.uncoupled, t.junctions);
        }

        // A wider alias should empty the redundancy bucket specifically, not shave a little off
        // every row.
        Log.info("[radius] refusals by reason:");
        for (int i = 0; i < XbGroups.PARALLEL_REFUSAL.length; i++) {
            StringBuilder row = new StringBuilder();
            for (Map.Entry<String, Atlas> e : runs.entrySet()) {
                row.append(String.format("%11d", e.getValue().totals().refusals[i]));
            }
            Log.info("[radius]  %s   %s", row, XbGroups.PARALLEL_REFUSAL[i]);
        }
        Log.info("[radius]  %s   (columns)",
                runs.keySet().stream().map(m -> String.format("%11s", m))
                        .reduce("", String::concat));

        for (Map.Entry<String, Atlas> e : runs.entrySet()) {
            Atlas a = e.getValue();
            int[] rep = a.alias().rep();
            List<String> lines = new ArrayList<>();
            int railRail = 0;
            int mixed = 0;
            int busBus = 0;
            for (int i = 0; i < rep.length; i++) {
                if (rep[i] == i) continue;
                int j = rep[i];
                boolean ri = a.railPool().get(i);
                boolean rj = a.railPool().get(j);
                if (ri && rj) railRail++;
                else if (ri || rj) mixed++;
                else busBus++;
                double dlat = (a.stations().lat()[i] - a.stations().lat()[j]) * 110_540.0;
                double dlon = (a.stations().lon()[i] - a.stations().lon()[j]) * 111_320.0
                        * Math.cos(Math.toRadians(a.stations().lat()[i]));
                lines.add(String.format("%5.0f m  %-4s %-46s  %-4s %s",
                        Math.sqrt(dlat * dlat + dlon * dlon), ri ? "rail" : "bus",
                        a.stations().ids().value(i), rj ? "rail" : "bus",
                        a.stations().ids().value(j)));
            }
            Log.info("[radius] %s: %d pairs — %d rail↔rail, %d rail↔bus, %d bus↔bus",
                    e.getKey(), lines.size(), railRail, mixed, busBus);
            lines.sort(null);
            for (int i = 0; i < lines.size() && i < 40; i++) Log.info("[radius]    %s", lines.get(i));
            if (lines.size() > 40) {
                Log.info("[radius]    ... and %d more, not printed", lines.size() - 40);
            }
        }
    }
}
