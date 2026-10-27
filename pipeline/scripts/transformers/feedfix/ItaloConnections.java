package transformers.feedfix;

// Italo's sold connections, split into the runs they are made of.
//
// Italo publishes every connection it sells as one ServiceJourney under a Line named after both
// numbers: `8959_9987` is train 8959 to Roma Termini and train 9987 onward to Napoli, the change
// published as a dwell. A pair starts and ends where the passenger does, often mid-route, and the
// solo journeys of both numbers are published beside it. Numbers 1xxx are Itabus coaches.
//
// The pass removes every pair it can place and emits, per pair:
//   - each leg as a journey of its own number with that number as PublicCode, or nothing where a
//     solo journey already runs the leg on every day the pair does;
//   - a ServiceJourneyInterchange from the first leg to the second at the change stop;
//   - for a coach leg, a bus Line and journey under an Itabus Operator, calling at a bus stop of
//     its own at the hub station.
// It also retypes the stops only coaches call at as bus stops, and gives every solo journey its
// number as PublicCode. A pair no rule places is left as published and logged.
//
// Checking the feed against Italo's own timetable. italotreno.com serves its station-to-station
// search from a public JSON endpoint, which a manual check can query:
//
//     https://www.italotreno.com/api/OrariDinamiciService?Departure=MC_&Arrival=RCE
//         &GoingOutDate=Mon+Nov+09+2026&IsNoStop=false&ComingBackDate=null&IsRoundTrip=false
//
// Station codes are the feed's ScheduledStopPoint ids with `-` written `_`
// (`IT::ScheduledStopPoint:MC-` is `MC_`). Each `GoingOutResults` entry is one sold itinerary:
// `Segments` holds one entry per train or coach with its `TrainNumber`, and each segment's `Legs`
// give stop-to-stop `STD`/`STA`. A connection is a result with two segments, which is what a pair
// here encodes. What the answers contain:
//   - results span the week around `GoingOutDate`; the date of each is in its own `STD`;
//   - a train's segment inside a connection covers only its part of the run, so compare a train
//     against a result holding it as the sole segment;
//   - in weeks with engineering works the site shows revised times and the export keeps the base
//     timetable (Roma and Napoli moved by 5-10 minutes in October 2026); compare in a week without
//     works;
//   - the feed's `SGM` (Villa San Giovanni Marittima) is the site's `VSG`, and the feed's `TRX`
//     ("Treviso BUS") is the site's Treviso Centrale `TVC`.

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import jakarta.xml.bind.JAXBElement;
import noi.netex.model.AllVehicleModesOfTransportEnumeration;
import noi.netex.model.DayType;
import noi.netex.model.DayTypeAssignment;
import noi.netex.model.DayTypeRefStructure;
import noi.netex.model.FlexibleLineView;
import noi.netex.model.JourneyPatternRefStructure;
import noi.netex.model.JourneyRefStructure;
import noi.netex.model.Line;
import noi.netex.model.LineRefStructure;
import noi.netex.model.ObjectFactory;
import noi.netex.model.OperatingPeriod_VersionStructure;
import noi.netex.model.Operator;
import noi.netex.model.OperatorRefStructure;
import noi.netex.model.OrganisationTypeEnumeration;
import noi.netex.model.PassengerStopAssignment;
import noi.netex.model.PointInJourneyPatternRefStructure;
import noi.netex.model.PointInLinkSequence_VersionedChildStructure;
import noi.netex.model.Quay;
import noi.netex.model.QuayRefStructure;
import noi.netex.model.Quays_RelStructure;
import noi.netex.model.RouteView;
import noi.netex.model.ScheduledStopPoint;
import noi.netex.model.ScheduledStopPointRefStructure;
import noi.netex.model.ServiceJourney;
import noi.netex.model.ServiceJourneyInterchange;
import noi.netex.model.ServiceJourneyPattern;
import noi.netex.model.StopPlace;
import noi.netex.model.StopPlaceRefStructure;
import noi.netex.model.StopPointInJourneyPattern_VersionedChildStructure;
import noi.netex.model.StopTypeEnumeration;
import noi.netex.model.TimetabledPassingTime;
import noi.netex.model.UicOperatingPeriod;
import noi.netex.text.Codes;
import noi.netex.text.Mls;
import noi.netex.time.XmlDateTime;
import noi.netex.time.XmlTime;
import toolkit.store.ObjectRow;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.util.Log;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class ItaloConnections {

    private ItaloConnections() {}

    private static final ObjectFactory FACTORY = new ObjectFactory();

    /// The trailing variant counter Italo appends to a Line's name.
    private static final Pattern VARIANT = Pattern.compile("_#\\d+$");

    static final String ITABUS_OPERATOR = "IT::Operator:itabus";
    static final String HUB_SUFFIX = "-itabus";
    private static final String VERSION = "1";

    /// What [#split] produced: objects to insert over a clone, the source full keys the clone must
    /// skip, and the Line ids it removed, which the labelling pass must not re-emit.
    public record Split(List<Object> objects, LongOpenHashSet skip, Set<String> removedLines) {}

    /// One call: the stop point, and arrival and departure in seconds from the operating day's
    /// midnight, null where the feed gives none.
    record Call(String ssp, Integer arr, Integer dep) {}

    /// One journey as read from the store.
    record Journey(String id, String version, byte[] localKey, String line, List<String> numbers,
                   List<Call> calls, List<String> dayTypes) {}

    /// Where a pair changes: the call index both legs share, or how it is resolved without one.
    enum Kind { SPLIT, OMITTED, JOINED }

    record Placement(Kind kind, int change, String rule) {}

    public static Split split(Store db, Txn txn) {
        return new Pass(db, txn).run();
    }

    private static final class Pass {

        private final Store db;
        private final Txn txn;

        private final Map<String, Line> lines = new LinkedHashMap<>();
        private final Map<String, Long> lineKeys = new HashMap<>();
        private final Map<String, String> pointSsp = new HashMap<>();
        private final Map<String, Long> patternKeys = new HashMap<>();
        private final Map<String, Integer> patternUse = new HashMap<>();
        private final Map<String, ScheduledStopPoint> ssps = new HashMap<>();
        private final Map<String, StopPlace> stopPlaces = new HashMap<>();
        private final Map<String, PassengerStopAssignment> assignments = new HashMap<>();
        private final Map<String, Set<LocalDate>> dayTypeDays = new HashMap<>();
        private final Set<String> undatedDayTypes = new HashSet<>();
        private final List<Journey> journeys = new ArrayList<>();
        private final Map<String, List<Journey>> solos = new HashMap<>();

        private final List<Object> out = new ArrayList<>();
        private final LongOpenHashSet skip = new LongOpenHashSet();
        private final Set<String> removedLines = new HashSet<>();

        Pass(Store db, Txn txn) {
            this.db = db;
            this.txn = txn;
        }

        Split run() {
            read();
            List<Journey> pairs = new ArrayList<>();
            for (Journey j : journeys) {
                if (j.numbers().size() == 2) pairs.add(j);
                else if (j.numbers().size() == 1) {
                    solos.computeIfAbsent(j.numbers().get(0), k -> new ArrayList<>()).add(j);
                }
            }
            Set<String> offRail = offRailStops(pairs);

            Map<String, Integer> rules = new LinkedHashMap<>();
            Map<List<Object>, String> emitted = new HashMap<>();
            Map<String, String> lineForNumber = new HashMap<>();
            Map<String, String> hubStops = new LinkedHashMap<>();
            Set<String> interchanges = new HashSet<>();
            Set<String> removed = new HashSet<>();
            Set<String> keptDayTypes = new HashSet<>();
            int reused = 0;
            int dropped = 0;
            for (Journey p : pairs) {
                Placement at = place(p, offRail);
                rules.merge(at.rule(), 1, Integer::sum);
                if (at.kind() == Kind.JOINED) {
                    Log.info("[italo-connections] %s left joined: %s", p.id(), at.rule());
                    continue;
                }
                removed.add(p.id());
                if (at.kind() == Kind.OMITTED) continue;
                int k = at.change();
                String[] legIds = new String[2];
                String[] legVersions = new String[2];
                String[] legSsps = new String[2];
                for (int side = 0; side < 2; side++) {
                    String number = p.numbers().get(side);
                    int from = side == 0 ? 0 : k;
                    int to = side == 0 ? k : p.calls().size() - 1;
                    if (to - from < 1) {
                        dropped++;
                        continue;
                    }
                    List<Call> leg = p.calls().subList(from, to + 1);
                    boolean coach = isCoach(number);
                    String hubSsp = coach ? p.calls().get(k).ssp() : null;
                    Journey solo = coach ? null : soloRunning(number, leg, p);
                    if (solo != null) {
                        legIds[side] = solo.id();
                        legVersions[side] = solo.version();
                        legSsps[side] = p.calls().get(k).ssp();
                        reused++;
                        continue;
                    }
                    Set<LocalDate> pairDays = days(p);
                    List<Object> key = List.of(number, ends(leg),
                            pairDays == null ? p.dayTypes() : pairDays);
                    String id = emitted.get(key);
                    if (id == null) {
                        String lineId = lineForNumber.computeIfAbsent(number,
                                n -> lineFor(n, p.line(), coach));
                        if (coach) hubStops.putIfAbsent(hubSsp, hubId(hubSsp));
                        id = emitLeg(p, number, from, to, lineId, coach,
                                coach ? hubStops.get(hubSsp) : null, side);
                        emitted.put(key, id);
                        keptDayTypes.addAll(p.dayTypes());
                    }
                    legIds[side] = id;
                    legVersions[side] = VERSION;
                    legSsps[side] = coach ? sspId(hubStops.get(hubSsp)) : p.calls().get(k).ssp();
                }
                if (legIds[0] != null && legIds[1] != null
                        && interchanges.add(legIds[0] + " " + legIds[1])) {
                    out.add(interchange(legIds[0], legVersions[0], legSsps[0],
                            legIds[1], legVersions[1], legSsps[1]));
                }
            }

            for (List<Journey> list : solos.values()) {
                for (Journey j : list) out.add(numbered(j));
            }
            if (!hubStops.isEmpty()) out.add(itabus());
            for (Map.Entry<String, String> hub : hubStops.entrySet()) {
                out.addAll(hubStop(hub.getKey(), hub.getValue()));
            }
            for (String ssp : offRail) out.addAll(retypeAsBusStop(ssp));
            removeAll(removed, keptDayTypes);

            Log.info("[italo-connections] %,d pairs: %s; %,d new journeys, %,d legs on a solo "
                    + "journey, %,d legs with no calls of their own, %,d interchanges, %,d hub bus "
                    + "stops", pairs.size(), rules, emitted.size(), reused, dropped,
                    interchanges.size(), hubStops.size());
            return new Split(out, skip, removedLines);
        }

        // --------------------------------------------------------------------------- reading

        private void read() {
            for (ObjectRow r : db.iterObjects(txn, Line.class)) {
                Line l = (Line) r.object();
                lines.put(l.getId(), l);
                lineKeys.put(l.getId(), db.fullKeyOf(Line.class, r.localKey()));
            }
            for (ObjectRow r : db.iterObjects(txn, ServiceJourneyPattern.class)) {
                ServiceJourneyPattern pat = (ServiceJourneyPattern) r.object();
                patternKeys.put(pat.getId(),
                        db.fullKeyOf(ServiceJourneyPattern.class, r.localKey()));
                if (pat.getPointsInSequence() == null) continue;
                for (PointInLinkSequence_VersionedChildStructure pt : points(pat)) {
                    if (pt instanceof StopPointInJourneyPattern_VersionedChildStructure sp
                            && sp.getScheduledStopPointRef() != null) {
                        pointSsp.put(sp.getId(), sp.getScheduledStopPointRef().getValue().getRef());
                    }
                }
            }
            for (Object o : db.iterOnlyObjects(txn, ScheduledStopPoint.class)) {
                ScheduledStopPoint s = (ScheduledStopPoint) o;
                ssps.put(s.getId(), s);
            }
            for (Object o : db.iterOnlyObjects(txn, StopPlace.class)) {
                StopPlace s = (StopPlace) o;
                stopPlaces.put(s.getId(), s);
            }
            for (Object o : db.iterOnlyObjects(txn, PassengerStopAssignment.class)) {
                PassengerStopAssignment a = (PassengerStopAssignment) o;
                if (a.getScheduledStopPointRef() != null) {
                    assignments.put(a.getScheduledStopPointRef().getValue().getRef(), a);
                }
            }
            readDays();
            for (ObjectRow r : db.iterObjects(txn, ServiceJourney.class)) {
                ServiceJourney sj = (ServiceJourney) r.object();
                String line = lineRef(sj);
                Line l = line == null ? null : lines.get(line);
                List<String> numbers = l == null ? List.of() : numbers(l);
                List<String> dayTypes = new ArrayList<>();
                if (sj.getDayTypes() != null) {
                    for (JAXBElement<? extends DayTypeRefStructure> d
                            : sj.getDayTypes().getDayTypeRef()) {
                        dayTypes.add(d.getValue().getRef());
                    }
                }
                journeys.add(new Journey(sj.getId(), sj.getVersion(), r.localKey(), line, numbers,
                        calls(sj), dayTypes));
                String pat = patternRef(sj);
                if (pat != null) patternUse.merge(pat, 1, Integer::sum);
            }
        }

        /// The dates each DayType runs on. A DayType whose assignments name a period this pass
        /// cannot expand has no known dates, and no leg on it is matched to a solo journey.
        private void readDays() {
            Map<String, Set<LocalDate>> periods = new HashMap<>();
            for (Object o : db.iterOnlyObjects(txn, UicOperatingPeriod.class)) {
                UicOperatingPeriod u = (UicOperatingPeriod) o;
                Set<LocalDate> days = uicDays(u);
                if (days != null) periods.put(u.getId(), days);
            }
            for (Object o : db.iterOnlyObjects(txn, DayTypeAssignment.class)) {
                DayTypeAssignment a = (DayTypeAssignment) o;
                if (a.getDayTypeRef() == null) continue;
                String dt = a.getDayTypeRef().getValue().getRef();
                Set<LocalDate> days = null;
                if (Boolean.FALSE.equals(a.isIsAvailable())) {
                    undatedDayTypes.add(dt);
                    continue;
                }
                if (a.getOperatingPeriodRef() != null) {
                    days = periods.get(a.getOperatingPeriodRef().getValue().getRef());
                } else if (a.getDate() != null && !a.getDate().isUnparsed()) {
                    days = Set.of(a.getDate().toLocalDateTime().toLocalDate());
                }
                if (days == null) undatedDayTypes.add(dt);
                else dayTypeDays.computeIfAbsent(dt, x -> new HashSet<>()).addAll(days);
            }
        }

        private static Set<LocalDate> uicDays(OperatingPeriod_VersionStructure p) {
            if (!(p instanceof UicOperatingPeriod u) || u.getValidDayBits() == null) return null;
            XmlDateTime from = u.getFromDate();
            if (from == null || from.isUnparsed()) return null;
            LocalDate start = from.toLocalDateTime().toLocalDate();
            String bits = u.getValidDayBits();
            Set<LocalDate> days = new HashSet<>();
            for (int i = 0; i < bits.length(); i++) {
                if (bits.charAt(i) == '1') days.add(start.plusDays(i));
            }
            return days;
        }

        /// A journey's dates, or null when one of its DayTypes has none this pass can read.
        private Set<LocalDate> days(Journey j) {
            Set<LocalDate> days = new HashSet<>();
            for (String dt : j.dayTypes()) {
                if (undatedDayTypes.contains(dt) || !dayTypeDays.containsKey(dt)) return null;
                days.addAll(dayTypeDays.get(dt));
            }
            return days;
        }

        /// The calls in passing-time list order, which `sort-italo-sequences` has put into the
        /// pattern's `order` sequence.
        private List<Call> calls(ServiceJourney sj) {
            List<Call> calls = new ArrayList<>();
            if (sj.getPassingTimes() == null) return calls;
            for (TimetabledPassingTime t : sj.getPassingTimes().getTimetabledPassingTime()) {
                PointInJourneyPatternRefStructure ref = t.getPointInJourneyPatternRef() == null
                        ? null : t.getPointInJourneyPatternRef().getValue();
                String ssp = ref == null ? null : pointSsp.get(ref.getRef());
                calls.add(new Call(ssp, seconds(t.getArrivalTime(), t.getArrivalDayOffset()),
                        seconds(t.getDepartureTime(), t.getDepartureDayOffset())));
            }
            return calls;
        }

        // ------------------------------------------------------------------------- placement

        /// Stops no solo journey calls at. Every solo journey is a train, so these are the stops
        /// only coaches serve.
        private Set<String> offRailStops(List<Journey> pairs) {
            Set<String> rail = new HashSet<>();
            for (List<Journey> list : solos.values()) {
                for (Journey j : list) for (Call c : j.calls()) rail.add(c.ssp());
            }
            Set<String> off = new LinkedHashSet<>();
            for (Journey p : pairs) {
                for (Call c : p.calls()) {
                    if (c.ssp() != null && !rail.contains(c.ssp())) off.add(c.ssp());
                }
            }
            return off;
        }

        private Placement place(Journey p, Set<String> offRail) {
            List<Call> c = p.calls();
            if (c.size() < 2 || c.stream().anyMatch(x -> x.ssp() == null)) {
                return new Placement(Kind.JOINED, -1, "unreadable calls");
            }
            String a = p.numbers().get(0);
            String b = p.numbers().get(1);
            if (isCoach(a) || isCoach(b)) return placeCoach(c, isCoach(a), offRail);

            Integer head = headLength(c, solos.getOrDefault(a, List.of()));
            Integer tail = tailStart(c, solos.getOrDefault(b, List.of()));
            if (head != null && tail != null) {
                return head.equals(tail) ? new Placement(Kind.SPLIT, head, "head and tail meet")
                        : new Placement(Kind.OMITTED, -1, "change station omitted");
            }
            if (head != null) return new Placement(Kind.SPLIT, head, "head only");
            if (tail != null) return new Placement(Kind.SPLIT, tail, "tail only");
            if (c.size() == 2 && calledBy(c.get(0).ssp(), a) && calledBy(c.get(1).ssp(), b)) {
                return new Placement(Kind.OMITTED, -1, "change station omitted");
            }
            return new Placement(Kind.JOINED, -1, "no solo journey matches");
        }

        /// A coach leg is the run of off-rail stops at its end of the pair plus the station next to
        /// it. Where the pair has no off-rail stop the feed omits the coach end altogether (Parma,
        /// Aosta, Courmayeur), and the whole pair is the train's run.
        private static Placement placeCoach(List<Call> c, boolean coachFirst, Set<String> offRail) {
            int n = c.size();
            List<Integer> off = new ArrayList<>();
            for (int i = 0; i < n; i++) if (offRail.contains(c.get(i).ssp())) off.add(i);
            if (off.isEmpty()) {
                return new Placement(Kind.SPLIT, coachFirst ? 0 : n - 1, "coach end absent");
            }
            if (coachFirst) {
                int run = 0;
                while (run < n && offRail.contains(c.get(run).ssp())) run++;
                return off.get(off.size() - 1) < run && run < n
                        ? new Placement(Kind.SPLIT, run, "coach run")
                        : new Placement(Kind.JOINED, -1, "off-rail stop outside the coach run");
            }
            int run = n - 1;
            while (run >= 0 && offRail.contains(c.get(run).ssp())) run--;
            return off.get(0) > run && run >= 0
                    ? new Placement(Kind.SPLIT, run, "coach run")
                    : new Placement(Kind.JOINED, -1, "off-rail stop outside the coach run");
        }

        /// The last call index of the longest head of `c` that is a stretch of one of `runs`.
        private static Integer headLength(List<Call> c, List<Journey> runs) {
            Integer best = null;
            for (Journey s : runs) {
                for (int k = c.size() - 1; k > 0; k--) {
                    if (stretchOf(c.subList(0, k + 1), s.calls())) {
                        if (best == null || k > best) best = k;
                        break;
                    }
                }
            }
            return best;
        }

        /// The first call index of the longest tail of `c` that is a stretch of one of `runs`.
        private static Integer tailStart(List<Call> c, List<Journey> runs) {
            Integer best = null;
            for (Journey s : runs) {
                for (int k = 0; k < c.size() - 1; k++) {
                    if (stretchOf(c.subList(k, c.size()), s.calls())) {
                        if (best == null || k < best) best = k;
                        break;
                    }
                }
            }
            return best;
        }

        /// Whether `leg` is a contiguous stretch of `run`, call for call. The leg's first call is
        /// compared on its departure and its last on its arrival, those being the times a leg
        /// keeps there. A time either side omits matches any: the feed gives some intermediate
        /// calls one time only, and a pair and its solo journey do not always omit the same one.
        static boolean stretchOf(List<Call> leg, List<Call> run) {
            for (int j = 0; j + leg.size() <= run.size(); j++) {
                if (!Objects.equals(run.get(j).ssp(), leg.get(0).ssp())) continue;
                boolean match = true;
                for (int n = 0; n < leg.size() && match; n++) {
                    Call x = leg.get(n);
                    Call y = run.get(j + n);
                    match = Objects.equals(x.ssp(), y.ssp())
                            && (n == 0 || agree(x.arr(), y.arr()))
                            && (n == leg.size() - 1 || agree(x.dep(), y.dep()));
                }
                if (match) return true;
            }
            return false;
        }

        /// The leg as it runs: arrival only at its last call, departure only at its first. The
        /// other time at the change stop belongs to the other leg.
        private static List<Call> ends(List<Call> leg) {
            List<Call> out = new ArrayList<>(leg);
            Call first = out.get(0);
            Call last = out.get(out.size() - 1);
            out.set(0, new Call(first.ssp(), null, first.dep()));
            out.set(out.size() - 1, new Call(last.ssp(), last.arr(), null));
            return out;
        }

        private static boolean agree(Integer a, Integer b) {
            return a == null || b == null || a.equals(b);
        }

        private boolean calledBy(String ssp, String number) {
            for (Journey s : solos.getOrDefault(number, List.of())) {
                for (Call c : s.calls()) if (ssp.equals(c.ssp())) return true;
            }
            return false;
        }

        /// The solo journey of `number` that runs `leg` on every date the pair does, or null.
        private Journey soloRunning(String number, List<Call> leg, Journey pair) {
            Set<LocalDate> pairDays = days(pair);
            if (pairDays == null) return null;
            for (Journey s : solos.getOrDefault(number, List.of())) {
                Set<LocalDate> soloDays = days(s);
                if (soloDays != null && soloDays.containsAll(pairDays)
                        && stretchOf(leg, s.calls())) {
                    return s;
                }
            }
            return null;
        }

        // -------------------------------------------------------------------------- emission

        /// The pair cut to calls `[from..to]`, under its own id and pattern, as a journey of
        /// `number`. Leg ids carry the pair's own suffix, which keeps two pairs' legs apart.
        private String emitLeg(Journey p, String number, int from, int to, String lineId,
                               boolean coach, String hub, int side) {
            ServiceJourney sj = (ServiceJourney) db.loadObject(txn, ServiceJourney.class,
                    p.localKey());
            JourneyPatternRefStructure patRef = sj.getJourneyPatternRef().getValue();
            ServiceJourneyPattern pat =
                    (ServiceJourneyPattern) db.loadObjectByReference(txn, patRef, false);
            String suffix = number + "-" + local(p.id());
            String id = "IT::ServiceJourney:" + suffix;
            String patId = "IT::ServiceJourneyPattern:" + suffix;

            List<TimetabledPassingTime> tt = sj.getPassingTimes().getTimetabledPassingTime();
            List<TimetabledPassingTime> keep = new ArrayList<>(tt.subList(from, to + 1));
            List<PointInLinkSequence_VersionedChildStructure> pts = points(pat);
            Map<String, PointInLinkSequence_VersionedChildStructure> byId = new HashMap<>();
            for (PointInLinkSequence_VersionedChildStructure pt : pts) byId.put(pt.getId(), pt);
            List<PointInLinkSequence_VersionedChildStructure> keepPts = new ArrayList<>();
            for (int i = 0; i < keep.size(); i++) {
                TimetabledPassingTime t = keep.get(i);
                PointInLinkSequence_VersionedChildStructure pt =
                        byId.get(t.getPointInJourneyPatternRef().getValue().getRef());
                String pointId = patId + ":" + (i + 1);
                pt.setId(pointId);
                pt.setOrder(BigInteger.valueOf(i + 1L));
                if (pt instanceof StopPointInJourneyPattern_VersionedChildStructure sp) {
                    sp.setOnwardServiceLinkRef(null);
                    sp.setOnwardTimingLinkRef(null);
                    boolean atHub = coach && i == (side == 0 ? keep.size() - 1 : 0);
                    if (atHub) sp.setScheduledStopPointRef(sspRef(sspId(hub)));
                }
                keepPts.add(pt);
                t.setId("IT::TimetabledPassingTime:" + suffix + ":" + (i + 1));
                t.getPointInJourneyPatternRef().getValue().setRef(pointId);
            }
            TimetabledPassingTime first = keep.get(0);
            first.setArrivalTime(null);
            first.setArrivalDayOffset(null);
            TimetabledPassingTime last = keep.get(keep.size() - 1);
            last.setDepartureTime(null);
            last.setDepartureDayOffset(null);
            tt.clear();
            tt.addAll(keep);
            pts.clear();
            pts.addAll(keepPts);
            pat.setLinksInSequence(null);
            pat.setId(patId);
            pat.setName(Mls.of(suffix));
            if (pat.getRouteView() != null) pat.getRouteView().setLineRef(lineRef(lineId));
            else pat.setRouteView(new RouteView().withLineRef(lineRef(lineId)));
            patRef.setRef(patId);

            sj.setId(id);
            sj.setVersion(VERSION);
            sj.setPublicCode(Codes.of(number));
            sj.setDepartureTime(first.getDepartureTime());
            sj.setDepartureDayOffset(first.getDepartureDayOffset());
            String lastSsp = coach && side == 0 ? sspId(hub) : p.calls().get(to).ssp();
            sj.setName(Mls.of(local(lastSsp)));
            setLine(sj, lineId);
            if (coach) {
                sj.setTransportMode(AllVehicleModesOfTransportEnumeration.BUS);
                sj.setOperatorRef(operatorRef(ITABUS_OPERATOR));
            }
            out.add(pat);
            out.add(sj);
            return id;
        }

        /// The Line a leg of `number` joins: a solo Line of that number where the feed has one,
        /// else a new Line built from the pair's.
        private String lineFor(String number, String pairLine, boolean coach) {
            if (!coach) {
                for (Line l : lines.values()) {
                    List<String> n = numbers(l);
                    if (n.size() == 1 && n.get(0).equals(number)) return l.getId();
                }
            }
            Line l = (Line) db.loadObjectByReference(txn, lineRefStructure(pairLine), false);
            l.setId("IT::Line:" + number);
            l.setVersion(VERSION);
            l.setName(Mls.of(number));
            if (coach) {
                l.setPublicCode(Codes.of("Itabus"));
                l.setTransportMode(AllVehicleModesOfTransportEnumeration.BUS);
                l.setOperatorRef(operatorRef(ITABUS_OPERATOR));
            } else {
                l.setPublicCode(Codes.of("Italo"));
            }
            out.add(l);
            return l.getId();
        }

        /// A solo journey with its number as PublicCode.
        private ServiceJourney numbered(Journey j) {
            ServiceJourney sj = (ServiceJourney) db.loadObject(txn, ServiceJourney.class,
                    j.localKey());
            sj.setPublicCode(Codes.of(j.numbers().get(0)));
            return sj;
        }

        /// The change from one leg to the next. Italo sells the legs as one purchase, each under
        /// its own carrier's conditions, so the interchange is planned and advertised with
        /// `Guaranteed` false.
        private static ServiceJourneyInterchange interchange(String from, String fromVersion,
                String fromSsp, String to, String toVersion, String toSsp) {
            ServiceJourneyInterchange x = new ServiceJourneyInterchange();
            x.setId("IT::ServiceJourneyInterchange:" + local(from) + ":" + local(to));
            x.setVersion(VERSION);
            x.setStaySeated(Boolean.FALSE);
            x.setPlanned(Boolean.TRUE);
            x.setGuaranteed(Boolean.FALSE);
            x.setAdvertised(Boolean.TRUE);
            x.setFromPointRef(plainSspRef(fromSsp));
            x.setToPointRef(plainSspRef(toSsp));
            x.setFromJourneyRef(journeyRef(from, fromVersion));
            x.setToJourneyRef(journeyRef(to, toVersion));
            return x;
        }

        private static Operator itabus() {
            Operator o = new Operator();
            o.setId(ITABUS_OPERATOR);
            o.setVersion(VERSION);
            o.setName(Mls.of("Itabus"));
            o.setLegalName(Mls.of("Itabus"));
            o.getOrganisationType().add(OrganisationTypeEnumeration.OPERATOR);
            return o;
        }

        // ----------------------------------------------------------------------------- stops

        /// The bus stop a coach calls at beside the hub station `stationSsp`: its own StopPlace,
        /// Quay, ScheduledStopPoint and assignment, at the station's centroid, the feed giving no
        /// bay position. Its name keeps it apart from the station through the rail merges, which
        /// fold only stops that are close and share a name.
        private List<Object> hubStop(String stationSsp, String hubLocal) {
            ScheduledStopPoint station = ssps.get(stationSsp);
            StopPlace stationPlace = stopPlace(stationSsp);
            String name = Mls.text(station.getName()) + " Itabus";
            String placeId = "IT::StopPlace:" + hubLocal;
            String quayId = "IT::Quay:" + hubLocal;

            StopPlace sp = new StopPlace();
            sp.setId(placeId);
            sp.setVersion(VERSION);
            sp.setName(Mls.of(name));
            sp.setCentroid(stationPlace == null ? null : stationPlace.getCentroid());
            sp.setStopPlaceType(StopTypeEnumeration.ONSTREET_BUS);
            sp.setTransportMode(AllVehicleModesOfTransportEnumeration.BUS);
            sp.setQuays(quays(quayId, name, sp));

            ScheduledStopPoint ssp = new ScheduledStopPoint();
            ssp.setId(sspId(hubLocal));
            ssp.setVersion(VERSION);
            ssp.setName(Mls.of(name));
            ssp.setLocation(station.getLocation());

            return List.of(sp, ssp, assignment(hubLocal, ssp.getId(), placeId, quayId));
        }

        /// A stop only coaches call at, retyped as a bus stop with a Quay its assignment names.
        private List<Object> retypeAsBusStop(String ssp) {
            PassengerStopAssignment a = assignments.get(ssp);
            StopPlace sp = stopPlace(ssp);
            if (a == null || sp == null) return List.of();
            String quayId = "IT::Quay:" + local(sp.getId());
            sp.setStopPlaceType(StopTypeEnumeration.ONSTREET_BUS);
            sp.setTransportMode(AllVehicleModesOfTransportEnumeration.BUS);
            sp.setQuays(quays(quayId, Mls.text(sp.getName()), sp));
            a.setQuayRef(FACTORY.createQuayRef(quayRef(quayId)));
            return List.of(sp, a);
        }

        private static Quays_RelStructure quays(String quayId, String name, StopPlace sp) {
            Quay q = new Quay();
            q.setId(quayId);
            q.setVersion(VERSION);
            q.setName(Mls.of(name));
            q.setCentroid(sp.getCentroid());
            Quays_RelStructure quays = new Quays_RelStructure();
            quays.getQuayRefOrQuay().add(FACTORY.createQuay(q));
            return quays;
        }

        private static PassengerStopAssignment assignment(String local, String ssp, String place,
                                                          String quay) {
            PassengerStopAssignment a = new PassengerStopAssignment();
            a.setId("IT::PassengerStopAssignment:" + local);
            a.setVersion(VERSION);
            a.setOrder(BigInteger.ONE);
            a.setScheduledStopPointRef(sspRef(ssp));
            StopPlaceRefStructure spr = new StopPlaceRefStructure();
            spr.setRef(place);
            spr.setVersion(VERSION);
            a.setStopPlaceRef(FACTORY.createStopPlaceRef(spr));
            a.setQuayRef(FACTORY.createQuayRef(quayRef(quay)));
            return a;
        }

        private StopPlace stopPlace(String ssp) {
            PassengerStopAssignment a = assignments.get(ssp);
            if (a == null || a.getStopPlaceRef() == null) return null;
            return stopPlaces.get(a.getStopPlaceRef().getValue().getRef());
        }

        // ---------------------------------------------------------------------------- removal

        /// Skip the placed pairs, the patterns and Lines left unused, and the calendar objects of
        /// DayTypes no remaining journey names.
        private void removeAll(Set<String> removed, Set<String> keptDayTypes) {
            Set<String> linesInUse = new HashSet<>();
            Set<String> dayTypesInUse = new HashSet<>(keptDayTypes);
            for (Journey j : journeys) {
                if (removed.contains(j.id())) {
                    skip.add(db.fullKeyOf(ServiceJourney.class, j.localKey()));
                    ServiceJourney sj = (ServiceJourney) db.loadObject(txn, ServiceJourney.class,
                            j.localKey());
                    String pat = patternRef(sj);
                    if (pat != null && patternUse.merge(pat, -1, Integer::sum) == 0
                            && patternKeys.containsKey(pat)) {
                        skip.add(patternKeys.get(pat).longValue());
                    }
                } else {
                    if (j.line() != null) linesInUse.add(j.line());
                    dayTypesInUse.addAll(j.dayTypes());
                }
            }
            for (Journey j : journeys) {
                if (!removed.contains(j.id()) || j.line() == null
                        || linesInUse.contains(j.line())) {
                    continue;
                }
                if (removedLines.add(j.line())) skip.add(lineKeys.get(j.line()).longValue());
            }
            Set<String> periodsInUse = new HashSet<>();
            Set<String> orphanPeriods = new HashSet<>();
            for (ObjectRow r : db.iterObjects(txn, DayTypeAssignment.class)) {
                DayTypeAssignment a = (DayTypeAssignment) r.object();
                String dt = a.getDayTypeRef() == null ? null
                        : a.getDayTypeRef().getValue().getRef();
                String period = a.getOperatingPeriodRef() == null ? null
                        : a.getOperatingPeriodRef().getValue().getRef();
                if (dt != null && !dayTypesInUse.contains(dt)) {
                    skip.add(db.fullKeyOf(DayTypeAssignment.class, r.localKey()));
                    if (period != null) orphanPeriods.add(period);
                } else if (period != null) {
                    periodsInUse.add(period);
                }
            }
            for (ObjectRow r : db.iterObjects(txn, DayType.class)) {
                DayType d = (DayType) r.object();
                if (!dayTypesInUse.contains(d.getId())) {
                    skip.add(db.fullKeyOf(DayType.class, r.localKey()));
                }
            }
            orphanPeriods.removeAll(periodsInUse);
            for (ObjectRow r : db.iterObjects(txn, UicOperatingPeriod.class)) {
                UicOperatingPeriod u = (UicOperatingPeriod) r.object();
                if (orphanPeriods.contains(u.getId())) {
                    skip.add(db.fullKeyOf(UicOperatingPeriod.class, r.localKey()));
                }
            }
        }
    }

    // ----------------------------------------------------------------------------- helpers

    private static List<PointInLinkSequence_VersionedChildStructure> points(
            ServiceJourneyPattern pat) {
        return pat.getPointsInSequence()
                .getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern();
    }

    /// The train or coach numbers a Line's name carries, its variant counter dropped.
    static List<String> numbers(Line l) {
        String name = Mls.text(l.getName());
        if (name == null || name.isBlank()) return List.of();
        return List.of(VARIANT.matcher(name).replaceFirst("").split("_"));
    }

    static boolean isCoach(String number) {
        return number.length() == 4 && number.charAt(0) == '1';
    }

    private static Integer seconds(XmlTime time, BigInteger dayOffset) {
        if (time == null || time.isUnparsed()) return null;
        int tod = time.getHour() * 3600 + time.getMinute() * 60 + time.getSecond();
        return tod + 86400 * (dayOffset == null ? 0 : dayOffset.intValueExact());
    }

    private static String lineRef(ServiceJourney sj) {
        if (sj.getLineRef() != null) return sj.getLineRef().getValue().getRef();
        FlexibleLineView v = sj.getFlexibleLineView();
        return v == null || v.getLineRef() == null ? null : v.getLineRef().getValue().getRef();
    }

    private static void setLine(ServiceJourney sj, String lineId) {
        if (sj.getFlexibleLineView() != null) sj.getFlexibleLineView().setLineRef(lineRef(lineId));
        else sj.setLineRef(lineRef(lineId));
    }

    private static String patternRef(ServiceJourney sj) {
        return sj.getJourneyPatternRef() == null ? null
                : sj.getJourneyPatternRef().getValue().getRef();
    }

    /// The part of an id after its last `:`.
    static String local(String id) {
        return id.substring(id.lastIndexOf(':') + 1);
    }

    static String hubId(String stationSsp) {
        return local(stationSsp) + HUB_SUFFIX;
    }

    static String sspId(String local) {
        return "IT::ScheduledStopPoint:" + local;
    }

    private static JAXBElement<LineRefStructure> lineRef(String id) {
        return FACTORY.createLineRef(lineRefStructure(id));
    }

    private static LineRefStructure lineRefStructure(String id) {
        LineRefStructure r = new LineRefStructure();
        r.setRef(id);
        r.setVersion(VERSION);
        return r;
    }

    private static JAXBElement<ScheduledStopPointRefStructure> sspRef(String id) {
        return FACTORY.createScheduledStopPointRef(plainSspRef(id));
    }

    private static ScheduledStopPointRefStructure plainSspRef(String id) {
        ScheduledStopPointRefStructure r = new ScheduledStopPointRefStructure();
        r.setRef(id);
        r.setVersion(VERSION);
        return r;
    }

    private static QuayRefStructure quayRef(String id) {
        QuayRefStructure r = new QuayRefStructure();
        r.setRef(id);
        r.setVersion(VERSION);
        return r;
    }

    private static OperatorRefStructure operatorRef(String id) {
        OperatorRefStructure r = new OperatorRefStructure();
        r.setRef(id);
        r.setVersion(VERSION);
        return r;
    }

    /// `nameOfRefClass` is set because the declared type is the base JourneyRefStructure, and a
    /// ref without it is filed under "Journey".
    private static JourneyRefStructure journeyRef(String ref, String version) {
        JourneyRefStructure r = new JourneyRefStructure();
        r.setRef(ref);
        r.setVersion(version);
        r.setNameOfRefClass("ServiceJourney");
        return r;
    }
}
