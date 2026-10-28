package tests;

// Trenitalia TrainNumber synthesis.

import noi.netex.model.ServiceJourney;
import noi.netex.model.TimetabledPassingTime;
import noi.netex.model.TrainNumber;
import noi.netex.model.TrainNumberRefStructure;
import toolkit.harness.InPlace;
import toolkit.store.Store;
import toolkit.store.Stores;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.Runner;
import toolkit.test.TestStore;
import toolkit.test.Xml;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import conv.TrenitaliaDbToDb;
import transformers.common.TrainNumbers;
import noi.netex.text.Mls;

public class TestTrenitaliaDbToDb {

    public static void main(String[] args) {
        Runner.run(TestTrenitaliaDbToDb.class);
    }

    public static void testAddTrainNumbersCreatesAndLinksTrainNumber(TestStore db) throws Exception {
        db.loadNetex("""
            <TimetableFrame id="tf" version="1">
              <vehicleJourneys>
                <ServiceJourney id="sj1" version="1"><Name>25</Name></ServiceJourney>
              </vehicleJourneys>
            </TimetableFrame>
            """);

        TestStore target = db.runDbToDb(TrenitaliaDbToDb::applyTrainNumbers);

        Xml.assertXmlEqual(
                target.exportNetex(),
                """
                <PublicationDelivery xmlns="http://www.netex.org.uk/netex" xmlns:gml="http://www.opengis.net/gml/3.2" version="ntx:1.1">
                  <PublicationTimestamp>2026-01-01T00:00:00</PublicationTimestamp>
                  <ParticipantRef>PyNeTExConv</ParticipantRef>
                  <dataObjects>
                    <GeneralFrame id="Database" version="1">
                      <members>
                        <TrainNumber id="IT:TrainNumber:25" version="1">
                          <ForAdvertisement>25</ForAdvertisement>
                        </TrainNumber>
                        <ServiceJourney id="sj1" version="1">
                          <Name>25</Name>
                          <trainNumbers>
                            <TrainNumberRef version="1" ref="IT:TrainNumber:25"/>
                          </trainNumbers>
                        </ServiceJourney>
                      </members>
                    </GeneralFrame>
                  </dataObjects>
                </PublicationDelivery>""");
    }

    public static void testDistinctNumberIsDeduplicatedAcrossJourneys(TestStore db) throws Exception {
        db.loadNetex("""
            <TimetableFrame id="tf" version="1">
              <vehicleJourneys>
                <ServiceJourney id="sj1" version="1"><Name>25</Name></ServiceJourney>
                <ServiceJourney id="sj2" version="1"><Name>25</Name></ServiceJourney>
              </vehicleJourneys>
            </TimetableFrame>
            """);

        TestStore target = db.runDbToDb(TrenitaliaDbToDb::applyTrainNumbers);

        List<TrainNumber> trainNumbers = new ArrayList<>();
        List<ServiceJourney> journeys = new ArrayList<>();
        try (Txn txn = target.store.roTxn()) {
            for (Object o : target.store.iterOnlyObjects(txn, TrainNumber.class)) trainNumbers.add((TrainNumber) o);
            for (Object o : target.store.iterOnlyObjects(txn, ServiceJourney.class)) journeys.add((ServiceJourney) o);
        }

        // Two journeys share number 25 -> exactly one first-class TrainNumber.
        Check.equals(1, trainNumbers.size(), "TrainNumber count");
        Check.equals("IT:TrainNumber:25", trainNumbers.get(0).getId(), "TrainNumber id");
        // ...and both journeys link to it.
        List<String> refs = new ArrayList<>();
        for (ServiceJourney sj : journeys) {
            if (sj.getTrainNumbers() == null) continue;
            for (TrainNumberRefStructure ref : sj.getTrainNumbers().getTrainNumberRef()) {
                refs.add(ref.getRef() + "|" + ref.getVersion());
            }
        }
        refs.sort(null);
        Check.equals(List.of("IT:TrainNumber:25|1", "IT:TrainNumber:25|1"), refs, "journey trainNumber refs");
    }

    public static void testAddTrainNumbersIgnoresWords(TestStore db) throws Exception {
        db.loadNetex("""
            <TimetableFrame id="tf" version="1">
              <vehicleJourneys>
                <ServiceJourney id="sj1" version="1"><Name>Frecciarossa</Name></ServiceJourney>
              </vehicleJourneys>
            </TimetableFrame>
            """);

        TestStore target = db.runDbToDb(TrenitaliaDbToDb::applyTrainNumbers);

        try (Txn txn = target.store.roTxn()) {
            int n = 0;
            for (Object ignored : target.store.iterOnlyObjects(txn, TrainNumber.class)) n++;
            Check.equals(0, n, "No TrainNumber created for a name containing a word");
        }
    }

    public static void testTrainNumberReadFromTexttypeName() {
        ServiceJourney numbered = new ServiceJourney();
        numbered.setId("x");
        numbered.setVersion("1");
        numbered.setName(Mls.of("rj 25"));
        Check.equals("25", TrainNumbers.trainNumberForJourney(numbered), "numbered name");

        ServiceJourney named = new ServiceJourney();
        named.setId("y");
        named.setVersion("1");
        named.setName(Mls.of("Frecciarossa"));
        Check.isNull(TrainNumbers.trainNumberForJourney(named), "word name");
    }
}
