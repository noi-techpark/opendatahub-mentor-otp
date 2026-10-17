package tests;

// transformers.feedfix.ItaloConnections, through the stage that runs it (fix.ItaloLineCodes), on an
// Italo-shaped fixture: lines named after their numbers, one pattern per journey, stops assigned to
// StopPlaces with no Quays, calendars as UicOperatingPeriods.
//
// Each pair is built after one met in the feed:
//   9967_8111  sold as one connection on italotreno.com, change at Napoli Centrale; both legs run
//              by solo journeys, the solo 8111 giving Salerno an arrival only;
//   8959_9987  the 8959 leg runs on more days than its solo journey;
//   1021_8907  Lucca Comics coach to Firenze, then train; 1021_8908 shares the coach leg;
//   1071_9904  the Parma coach end absent, the pair starting at Reggio Emilia AV;
//   9998_9923  origin and destination only, the change station omitted;
//   7001_7002  no solo journey of either number.

import fix.ItaloLineCodes;
import noi.netex.model.AllVehicleModesOfTransportEnumeration;
import noi.netex.model.EntityStructure;
import noi.netex.model.Line;
import noi.netex.model.Operator;
import noi.netex.model.PassengerStopAssignment;
import noi.netex.model.ScheduledStopPoint;
import noi.netex.model.ServiceJourney;
import noi.netex.model.ServiceJourneyInterchange;
import noi.netex.model.ServiceJourneyPattern;
import noi.netex.model.StopPlace;
import noi.netex.model.StopPointInJourneyPattern;
import noi.netex.model.StopTypeEnumeration;
import noi.netex.model.TimetabledPassingTime;
import noi.netex.text.Codes;
import noi.netex.text.Mls;
import toolkit.store.ObjectRow;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.test.Check;
import toolkit.test.Runner;
import toolkit.test.TestStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class TestItaloConnections {

    public static void main(String[] args) {
        Runner.run(TestItaloConnections.class);
    }

    // ---------------------------------------------------------------------------------- fixture

    /// Code, name, latitude, longitude.
    static final String[][] STOPS = {
        {"MIL", "Milano Centrale (Milan)", "45.4862", "9.2045"},
        {"RMT", "Roma Termini (Rome)", "41.9010", "12.5016"},
        {"NAC", "Napoli Centrale (Naples)", "40.8530", "14.2720"},
        {"SAL", "Salerno (Amalfi Coast)", "40.6750", "14.7726"},
        {"RCE", "Reggio Calabria", "38.1030", "15.6390"},
        {"SMN", "Firenze S.M.Novella (Florence)", "43.7764", "11.2480"},
        {"BOL", "Bologna Centrale", "44.5057", "11.3426"},
        {"AAV", "Reggio Emilia AV", "44.7330", "10.6640"},
        {"GEN", "Genova Brignole", "44.4060", "8.9460"},
        {"BRE", "Brescia", "45.5330", "10.2130"},
        {"LUX", "Lucca Comics and Games BUS", "43.8430", "10.5050"},
    };

    record J(String id, String line, String dayType, String[] calls) {}

    /// `stop arr/dep`, a time left empty where the feed gives none.
    static final List<J> JOURNEYS = List.of(
        new J("9967-1-1-1", "9967", "all", new String[] {
            "MIL /06:15", "RMT 09:24/09:41", "NAC 10:53/"}),
        new J("8111--2-1-1-1", "8111", "all", new String[] {
            "MIL /06:40", "RMT 10:19/10:30", "NAC 11:43/12:00", "SAL 12:44/", "RCE 16:56/"}),
        new J("9967-8111-1-1-1", "9967_8111", "all", new String[] {
            "MIL /06:15", "RMT 09:24/09:41", "NAC 10:53/12:00", "SAL 12:44/12:46", "RCE 16:56/"}),

        new J("8959-1-1-1", "8959", "mon", new String[] {"BRE /13:29", "RMT 17:40/"}),
        new J("9987-1-1-1", "9987", "all", new String[] {
            "MIL /15:15", "RMT 18:25/18:40", "NAC 19:55/"}),
        new J("8959-9987-1-1-1", "8959_9987", "all", new String[] {
            "BRE /13:29", "RMT 17:40/18:40", "NAC 19:55/"}),

        new J("8907-1-1-1", "8907", "all", new String[] {
            "BOL /10:42", "SMN 11:20/11:28", "RMT 13:05/"}),
        new J("8908-1-1-1", "8908", "all", new String[] {"SMN /11:39", "BOL 12:20/"}),
        new J("1021-8907-1-1-1", "1021_8907", "all", new String[] {
            "LUX /09:25", "SMN 10:45/11:28", "RMT 13:05/"}),
        new J("1021-8908-1-1-1", "1021_8908", "all", new String[] {
            "LUX /09:25", "SMN 10:45/11:39", "BOL 12:20/"}),

        new J("9904-1-1-1", "9904", "all", new String[] {
            "RMT /05:40", "AAV 08:28/08:30", "MIL 09:20/"}),
        new J("1071-9904-1-1-1", "1071_9904", "all", new String[] {"AAV /08:30", "MIL 09:20/"}),

        new J("9998-1-1-1", "9998", "all", new String[] {"GEN /06:05", "MIL 08:05/"}),
        new J("9923-1-1-1", "9923", "all", new String[] {"MIL /09:40", "BOL 10:54/"}),
        new J("9998-9923-1-1-1", "9998_9923", "all", new String[] {"GEN /06:05", "BOL 10:54/"}),

        new J("7001-7002-1-1-1", "7001_7002", "all", new String[] {"BRE /07:00", "BOL 09:00/"})
    );

    static String netex() {
        StringBuilder cal = new StringBuilder();
        cal.append("<ServiceCalendarFrame id=\"IT::ServiceCalendarFrame:ita\" version=\"1\"><dayTypes>");
        for (J j : JOURNEYS) {
            cal.append("<DayType id=\"IT::DayType:").append(j.id()).append("\" version=\"1\"/>");
        }
        cal.append("</dayTypes><operatingPeriods>");
        for (J j : JOURNEYS) {
            String bits = j.dayType().equals("all") ? "1111111" : "1";
            cal.append("<UicOperatingPeriod id=\"IT::UicOperatingPeriod:").append(j.id())
                    .append("\" version=\"1\"><FromDate>2026-11-09T00:00:00</FromDate>")
                    .append("<ToDate>2026-11-15T00:00:00</ToDate><ValidDayBits>").append(bits)
                    .append("</ValidDayBits></UicOperatingPeriod>");
        }
        cal.append("</operatingPeriods><dayTypeAssignments>");
        for (J j : JOURNEYS) {
            cal.append("<DayTypeAssignment id=\"IT::DayTypeAssignment:").append(j.id())
                    .append("\" version=\"1\" order=\"1\"><OperatingPeriodRef ref=\"IT::UicOperatingPeriod:")
                    .append(j.id()).append("\"/><DayTypeRef ref=\"IT::DayType:").append(j.id())
                    .append("\"/></DayTypeAssignment>");
        }
        cal.append("</dayTypeAssignments></ServiceCalendarFrame>");

        StringBuilder sites = new StringBuilder("<SiteFrame id=\"IT::SiteFrame:ita\" version=\"1\"><stopPlaces>");
        StringBuilder ssps = new StringBuilder("<scheduledStopPoints>");
        StringBuilder psas = new StringBuilder("<stopAssignments>");
        for (String[] s : STOPS) {
            String loc = "<Longitude>" + s[3] + "</Longitude><Latitude>" + s[2] + "</Latitude>";
            sites.append("<StopPlace id=\"IT::StopPlace:").append(s[0]).append("\" version=\"1\"><Name>")
                    .append(s[1]).append("</Name><Centroid><Location>").append(loc)
                    .append("</Location></Centroid><StopPlaceType>railStation</StopPlaceType></StopPlace>");
            ssps.append("<ScheduledStopPoint id=\"IT::ScheduledStopPoint:").append(s[0])
                    .append("\" version=\"1\"><Name>").append(s[1]).append("</Name><Location>").append(loc)
                    .append("</Location><PublicCode>").append(s[0]).append("</PublicCode></ScheduledStopPoint>");
            psas.append("<PassengerStopAssignment id=\"IT::PassengerStopAssignment:").append(s[0])
                    .append("\" version=\"1\" order=\"1\"><ScheduledStopPointRef ref=\"IT::ScheduledStopPoint:")
                    .append(s[0]).append("\"/><StopPlaceRef ref=\"IT::StopPlace:").append(s[0])
                    .append("\"/></PassengerStopAssignment>");
        }
        sites.append("</stopPlaces></SiteFrame>");
        ssps.append("</scheduledStopPoints>");
        psas.append("</stopAssignments>");

        StringBuilder lines = new StringBuilder("<lines>");
        Set<String> seen = new TreeSet<>();
        for (J j : JOURNEYS) {
            if (!seen.add(j.line())) continue;
            lines.append("<Line id=\"IT::Line:").append(lineId(j)).append("\" version=\"1\"><Name>")
                    .append(j.line()).append("</Name><TransportMode>rail</TransportMode><PublicCode>")
                    .append(j.line()).append("</PublicCode><OperatorRef ref=\"IT::Operator:1\" version=\"1\"/></Line>");
        }
        lines.append("</lines>");

        StringBuilder patterns = new StringBuilder("<journeyPatterns>");
        StringBuilder journeys = new StringBuilder("<TimetableFrame id=\"IT::TimetableFrame:ita\" version=\"1\"><vehicleJourneys>");
        for (J j : JOURNEYS) {
            String pat = "IT::ServiceJourneyPattern:" + j.id();
            patterns.append("<ServiceJourneyPattern id=\"").append(pat).append("\" version=\"1\"><Name>")
                    .append(j.id()).append("</Name><RouteView><LineRef ref=\"IT::Line:").append(lineId(j))
                    .append("\"/></RouteView><pointsInSequence>");
            StringBuilder tts = new StringBuilder("<passingTimes>");
            String first = null;
            for (int i = 0; i < j.calls().length; i++) {
                String[] c = j.calls()[i].split(" ");
                String[] t = (c[1] + " ").split("/");
                String point = "IT::StopPointInJourneyPattern:" + j.id() + ":" + (i + 1);
                patterns.append("<StopPointInJourneyPattern id=\"").append(point).append("\" order=\"")
                        .append(i + 1).append("\" version=\"1\"><ScheduledStopPointRef ref=\"IT::ScheduledStopPoint:")
                        .append(c[0]).append("\"/></StopPointInJourneyPattern>");
                tts.append("<TimetabledPassingTime id=\"IT::TimetabledPassingTime:").append(j.id()).append(":")
                        .append(i + 1).append("\" version=\"1\"><StopPointInJourneyPatternRef ref=\"")
                        .append(point).append("\" version=\"1\"/>");
                if (!t[0].isBlank()) tts.append("<ArrivalTime>").append(t[0]).append(":00</ArrivalTime>");
                if (!t[1].isBlank()) {
                    tts.append("<DepartureTime>").append(t[1].trim()).append(":00</DepartureTime>");
                    if (first == null) first = t[1].trim();
                }
                tts.append("</TimetabledPassingTime>");
            }
            patterns.append("</pointsInSequence></ServiceJourneyPattern>");
            tts.append("</passingTimes>");
            String last = j.calls()[j.calls().length - 1].split(" ")[0];
            journeys.append("<ServiceJourney id=\"IT::ServiceJourney:").append(j.id())
                    .append("\" version=\"1\"><Name>").append(last).append("</Name><TransportMode>rail</TransportMode>")
                    .append("<DepartureTime>").append(first).append(":00</DepartureTime><dayTypes><DayTypeRef ref=\"IT::DayType:")
                    .append(j.id()).append("\"/></dayTypes><ServiceJourneyPatternRef ref=\"").append(pat)
                    .append("\" version=\"1\"/><OperatorRef ref=\"IT::Operator:1\" version=\"1\"/><FlexibleLineView>")
                    .append("<LineRef ref=\"IT::Line:").append(lineId(j)).append("\"/></FlexibleLineView>")
                    .append(tts).append("</ServiceJourney>");
        }
        patterns.append("</journeyPatterns>");
        journeys.append("</vehicleJourneys></TimetableFrame>");

        return cal
                + "<ResourceFrame id=\"IT::ResourceFrame:ita\" version=\"1\"><organisations>"
                + "<Operator id=\"IT::Operator:1\" version=\"1\"><Name>Italo</Name>"
                + "<OrganisationType>operator</OrganisationType></Operator></organisations></ResourceFrame>"
                + "<ServiceFrame id=\"IT::ServiceFrame:ita\" version=\"1\">" + lines + ssps + psas
                + patterns + "</ServiceFrame>" + journeys + sites;
    }

    static String lineId(J j) {
        return j.line().replace('_', '-') + "-1-1";
    }

    // -------------------------------------------------------------------------------- the run

    record Run(TestStore src, TestStore dst) {

        <T> Map<String, T> all(Class<T> clazz) {
            Map<String, T> out = new LinkedHashMap<>();
            try (Txn txn = dst.store.roTxn()) {
                for (Object o : dst.store.iterOnlyObjects(txn, clazz)) {
                    out.put(((EntityStructure) o).getId(), clazz.cast(o));
                }
            }
            return out;
        }

        ServiceJourney journey(String id) {
            return all(ServiceJourney.class).get("IT::ServiceJourney:" + id);
        }

        List<String> interchanges() {
            List<String> out = new ArrayList<>();
            for (ServiceJourneyInterchange x : all(ServiceJourneyInterchange.class).values()) {
                out.add(local(x.getFromJourneyRef().getRef()) + "@" + local(x.getFromPointRef().getRef())
                        + " -> " + local(x.getToJourneyRef().getRef()) + "@" + local(x.getToPointRef().getRef()));
            }
            out.sort(null);
            return out;
        }

        void assertVerbatim(Class<?> clazz, String id) {
            byte[] before = raw(src.store, clazz, id);
            byte[] after = raw(dst.store, clazz, id);
            Check.that(before != null && after != null, clazz.getSimpleName() + " " + id + " is in both stores");
            Check.that(Arrays.equals(before, after), clazz.getSimpleName() + " " + id + " was re-marshalled");
        }

        private static byte[] raw(Store store, Class<?> clazz, String id) {
            try (Txn txn = store.roTxn()) {
                for (ObjectRow r : store.iterObjects(txn, clazz)) {
                    if (r.object() instanceof EntityStructure e && id.equals(e.getId())) {
                        return store.loadRawObject(txn, clazz, r.localKey());
                    }
                }
            }
            return null;
        }
    }

    static Run run(TestStore db) throws Exception {
        db.loadNetex(netex());
        return new Run(db, db.runDbToDb(ItaloLineCodes::apply));
    }

    static String local(String id) {
        return id.substring(id.lastIndexOf(':') + 1);
    }

    static List<String> calls(ServiceJourney sj, Map<String, ServiceJourneyPattern> patterns) {
        ServiceJourneyPattern pat = patterns.get(sj.getJourneyPatternRef().getValue().getRef());
        Map<String, String> ssp = new LinkedHashMap<>();
        for (var pt : pat.getPointsInSequence()
                .getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern()) {
            ssp.put(pt.getId(), local(((StopPointInJourneyPattern) pt).getScheduledStopPointRef().getValue().getRef()));
        }
        List<String> out = new ArrayList<>();
        for (TimetabledPassingTime t : sj.getPassingTimes().getTimetabledPassingTime()) {
            out.add(ssp.get(t.getPointInJourneyPatternRef().getValue().getRef()) + " "
                    + (t.getArrivalTime() == null ? "" : t.getArrivalTime().toString().substring(0, 5)) + "/"
                    + (t.getDepartureTime() == null ? "" : t.getDepartureTime().toString().substring(0, 5)));
        }
        return out;
    }

    // ---------------------------------------------------------------------------------- tests

    /// The connection Italo sells as 9967 then 8111 at Napoli Centrale becomes a change between
    /// the two solo journeys. Salerno, a one-time call in the solo 8111, still matches.
    public static void testAConnectionBetweenSoloJourneysBecomesAnInterchange(TestStore db) throws Exception {
        Run r = run(db);
        Check.isNull(r.journey("9967-8111-1-1-1"), "the pair is removed");
        Check.that(r.interchanges().contains("9967-1-1-1@NAC -> 8111--2-1-1-1@NAC"),
                "one interchange at Napoli Centrale between the solo journeys");
        ServiceJourneyInterchange x = r.all(ServiceJourneyInterchange.class).values().stream()
                .filter(i -> i.getFromJourneyRef().getRef().endsWith("9967-1-1-1")).findFirst().orElseThrow();
        Check.equals(Boolean.FALSE, x.isGuaranteed(), "Italo guarantees no connection");
        Check.equals(Boolean.FALSE, x.isStaySeated(), "the passenger changes train");
        Check.equals(Boolean.TRUE, x.isAdvertised(), "advertised");
        Check.isNull(r.all(Line.class).get("IT::Line:9967-8111-1-1"), "the pair's Line goes with it");
    }

    /// A leg the solo journey runs on fewer days than the pair is emitted as its own journey.
    public static void testALegOnExtraDaysIsEmitted(TestStore db) throws Exception {
        Run r = run(db);
        ServiceJourney leg = r.journey("8959-8959-9987-1-1-1");
        Check.that(leg != null, "the 8959 leg is a journey of its own");
        Check.equals("8959", Codes.text(leg.getPublicCode()), "numbered 8959");
        Check.equals(List.of("BRE /13:29", "RMT 17:40/"),
                calls(leg, r.all(ServiceJourneyPattern.class)), "cut at Roma, arrival only at the end");
        Check.equals("IT::Line:8959-1-1", leg.getFlexibleLineView().getLineRef().getValue().getRef(),
                "on the solo 8959 Line");
        Check.that(r.interchanges().contains("8959-8959-9987-1-1-1@RMT -> 9987-1-1-1@RMT"),
                "and changes onto the solo 9987 at Roma");
    }

    /// The Lucca shape: an Itabus coach to a bus stop of its own beside Firenze, then the train.
    public static void testACoachLegRunsAsItabusFromItsOwnStop(TestStore db) throws Exception {
        Run r = run(db);
        ServiceJourney coach = r.journey("1021-1021-8907-1-1-1");
        Check.that(coach != null, "the coach leg is a journey");
        Check.equals(AllVehicleModesOfTransportEnumeration.BUS, coach.getTransportMode(), "a bus");
        Check.equals("1021", Codes.text(coach.getPublicCode()), "numbered 1021");
        Check.equals("IT::Operator:itabus", coach.getOperatorRef().getRef(), "run by Itabus");
        Check.equals(List.of("LUX /09:25", "SMN-itabus 10:45/"),
                calls(coach, r.all(ServiceJourneyPattern.class)), "to the hub bus stop");

        Line line = r.all(Line.class).get("IT::Line:1021");
        Check.equals("Itabus", Codes.text(line.getPublicCode()), "an Itabus Line");
        Check.equals(AllVehicleModesOfTransportEnumeration.BUS, line.getTransportMode(), "of bus mode");
        Operator itabus = r.all(Operator.class).get("IT::Operator:itabus");
        Check.equals("Itabus", Mls.text(itabus.getName()), "the operator is minted");

        StopPlace hub = r.all(StopPlace.class).get("IT::StopPlace:SMN-itabus");
        Check.equals("Firenze S.M.Novella (Florence) Itabus", Mls.text(hub.getName()), "named for the hub");
        Check.equals(StopTypeEnumeration.ONSTREET_BUS, hub.getStopPlaceType(), "a bus stop");
        Check.equals(1, hub.getQuays().getQuayRefOrQuay().size(), "with a Quay");
        PassengerStopAssignment psa = r.all(PassengerStopAssignment.class).get("IT::PassengerStopAssignment:SMN-itabus");
        Check.equals("IT::Quay:SMN-itabus", psa.getQuayRef().getValue().getRef(), "assigned to its Quay");
        Check.that(r.all(ScheduledStopPoint.class).containsKey("IT::ScheduledStopPoint:SMN-itabus"),
                "and its own stop point");

        StopPlace lucca = r.all(StopPlace.class).get("IT::StopPlace:LUX");
        Check.equals(StopTypeEnumeration.ONSTREET_BUS, lucca.getStopPlaceType(), "Lucca is retyped");
        Check.equals(AllVehicleModesOfTransportEnumeration.BUS, lucca.getTransportMode(), "as a bus stop");
        Check.equals("IT::Quay:LUX",
                r.all(PassengerStopAssignment.class).get("IT::PassengerStopAssignment:LUX").getQuayRef().getValue().getRef(),
                "with a Quay its assignment names");
        Check.equals(StopTypeEnumeration.RAIL_STATION,
                r.all(StopPlace.class).get("IT::StopPlace:SMN").getStopPlaceType(), "Firenze stays a station");
    }

    /// One coach run sold with two trains is one journey with two interchanges.
    public static void testASharedCoachLegIsEmittedOnce(TestStore db) throws Exception {
        Run r = run(db);
        long coaches = r.all(ServiceJourney.class).values().stream()
                .filter(sj -> "1021".equals(Codes.text(sj.getPublicCode()))).count();
        Check.equals(1L, coaches, "one 1021 journey");
        Check.that(r.interchanges().containsAll(List.of(
                        "1021-1021-8907-1-1-1@SMN-itabus -> 8907-1-1-1@SMN",
                        "1021-1021-8907-1-1-1@SMN-itabus -> 8908-1-1-1@SMN")),
                "onto both trains, from the bus stop to the station");
    }

    /// A pair whose coach end the feed omits is the train's own run.
    public static void testACoachPairWithoutCoachStopsIsTheTrain(TestStore db) throws Exception {
        Run r = run(db);
        Check.isNull(r.journey("1071-9904-1-1-1"), "the pair is removed");
        Check.isNull(r.all(Line.class).get("IT::Line:1071"), "no coach Line");
        Check.that(r.interchanges().stream().noneMatch(s -> s.contains("9904")), "no interchange");
    }

    /// Origin and destination only: both runs are solo journeys, the change is not published.
    public static void testAPairOmittingItsChangeIsDropped(TestStore db) throws Exception {
        Run r = run(db);
        Check.isNull(r.journey("9998-9923-1-1-1"), "the pair is removed");
        Check.that(r.interchanges().stream().noneMatch(s -> s.contains("9998") || s.contains("9923")),
                "and no interchange is invented");
    }

    /// A pair no rule places stays as the feed published it.
    public static void testAnUnplacedPairIsLeftAlone(TestStore db) throws Exception {
        Run r = run(db);
        r.assertVerbatim(ServiceJourney.class, "IT::ServiceJourney:7001-7002-1-1-1");
        Check.that(r.all(Line.class).containsKey("IT::Line:7001-7002-1-1"), "its Line stays");
    }

    /// Every remaining journey carries its number.
    public static void testSoloJourneysCarryTheirNumber(TestStore db) throws Exception {
        Run r = run(db);
        Check.equals("8111", Codes.text(r.journey("8111--2-1-1-1").getPublicCode()), "8111");
        Check.equals("9967", Codes.text(r.journey("9967-1-1-1").getPublicCode()), "9967");
        Check.equals("Italo", Codes.text(r.all(Line.class).get("IT::Line:8111-1-1").getPublicCode()),
                "and a train Line keeps the operator as its code");
    }

    /// Removed pairs take their calendars with them; a calendar an emitted leg uses stays.
    public static void testCalendarsFollowTheirJourneys(TestStore db) throws Exception {
        Run r = run(db);
        Set<String> dayTypes = r.all(noi.netex.model.DayType.class).keySet();
        Check.that(!dayTypes.contains("IT::DayType:9967-8111-1-1-1"), "a dropped pair's DayType goes");
        Check.that(dayTypes.contains("IT::DayType:8959-9987-1-1-1"), "the emitted 8959 leg keeps its");
        Check.that(!r.all(noi.netex.model.UicOperatingPeriod.class)
                .containsKey("IT::UicOperatingPeriod:9967-8111-1-1-1"), "with its period");
    }
}
