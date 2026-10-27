package tests;

// fix.AlignItaloStopNames: an Italo station takes the name of the Trenitalia station it stands on,
// and an Italo bus stop on the same spot keeps its own.

import fix.AlignItaloStopNames;
import noi.netex.model.StopPlace;
import noi.netex.text.Mls;
import toolkit.store.Store;
import toolkit.store.Stores;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.Runner;
import toolkit.test.TestStore;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class TestAlignItaloStopNames {

    public static void main(String[] args) {
        Runner.run(TestAlignItaloStopNames.class);
    }

    static final String ITALO = """
        <SiteFrame id="sf" version="1"><stopPlaces>
          <StopPlace id="IT::StopPlace:SMN" version="1"><Name>Firenze S.M.Novella (Florence)</Name>
            <Centroid><Location><Longitude>11.2480</Longitude><Latitude>43.7764</Latitude></Location></Centroid>
            <TransportMode>rail</TransportMode><StopPlaceType>railStation</StopPlaceType></StopPlace>
          <StopPlace id="IT::StopPlace:SMN-itabus" version="1"><Name>Firenze S.M.Novella (Florence) Itabus</Name>
            <Centroid><Location><Longitude>11.2480</Longitude><Latitude>43.7764</Latitude></Location></Centroid>
            <TransportMode>bus</TransportMode><StopPlaceType>onstreetBus</StopPlaceType></StopPlace>
        </stopPlaces></SiteFrame>
        """;

    static final String TRENITALIA = """
        <SiteFrame id="sf" version="1"><stopPlaces>
          <StopPlace id="IT::StopPlace:S06421" version="1"><Name>FIRENZE S. M. NOVELLA</Name>
            <Centroid><Location><Longitude>11.2482</Longitude><Latitude>43.7766</Latitude></Location></Centroid>
          </StopPlace>
        </stopPlaces></SiteFrame>
        """;

    public static void testABusStopBesideAStationKeepsItsName(TestStore db) throws Exception {
        Path src = db.buildDbFile(ITALO, "italo.lmdb");
        Path ref = db.buildDbFile(TRENITALIA, "trenitalia.lmdb");
        Path out = db.tempPath("aligned.lmdb");
        AlignItaloStopNames.main(new String[] {src.toString(), ref.toString(), out.toString()});

        Map<String, String> names = new HashMap<>();
        try (Store s = Stores.open(out, true); Txn txn = s.roTxn()) {
            for (Object o : s.iterOnlyObjects(txn, StopPlace.class)) {
                StopPlace sp = (StopPlace) o;
                names.put(sp.getId(), Mls.text(sp.getName()));
            }
        }
        Check.equals("FIRENZE S. M. NOVELLA", names.get("IT::StopPlace:SMN"),
                "the station takes its counterpart's name");
        Check.equals("Firenze S.M.Novella (Florence) Itabus", names.get("IT::StopPlace:SMN-itabus"),
                "the bus stop keeps its own");
    }
}
