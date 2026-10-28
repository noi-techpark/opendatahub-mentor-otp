package tools;

// Counts the border splits whose two halves carry different train numbers and sit on different
// Lines — the shape ÖBB publishes no JourneyMeeting for and transformers.feedfix.SplitJourneys,
// which requires one train number, cannot synthesise one for. A diagnostic, never a gate; reads
// only.
//
// The question is otherwise unmeasured. It is answered by running SplitJourneys' candidate rule
// with its first conjunct removed and narrowing what comes out, one printed step at a time:
//
//   same StopPlace at the ends, same publisher, aligned wait in [0, MAX_WAIT_S], calendars meet,
//   not a turnback              -- the five conjuncts that are not the train number
//     -> the two train numbers DIFFER          (what the pass cannot reach)
//     -> the two Lines DIFFER                  (what the exporter cannot write: it writes a
//                                               meeting only between journeys of one Line)
//     -> no published meeting joins them       (so nothing covers it today)
//     -> at least one half calls in a country other than its publisher's  (so the cross-border
//                                               coupling would care)
//
// Run it on a store with no synthesised meetings (data/at-prestitch.lmdb), or the "no published
// meeting" step scores this pass's own output as the feed's.
//
// Only journeys that carry a train number are read, which is SplitJourneys' own population: a split
// train's halves are rail and both carry a number, the partner half the partner's. A pair where one
// half has no number at all is out of scope and is not counted.

import jakarta.xml.bind.JAXBElement;
import noi.netex.model.DayTypeRefStructure;
import noi.netex.model.JourneyMeeting;
import noi.netex.model.JourneyRefStructure;
import noi.netex.model.LineRefStructure;
import noi.netex.model.PassengerStopAssignment;
import noi.netex.model.ServiceJourney;
import noi.netex.model.StopPlace;
import noi.netex.model.TimetabledPassingTime;
import noi.netex.model.TrainNumber;
import noi.netex.text.Mls;
import noi.netex.time.XmlTime;
import toolkit.store.ObjectRow;
import toolkit.store.Store;
import toolkit.store.Stores;
import toolkit.store.Txn;
import toolkit.util.Log;
import transformers.feedfix.SplitJourneys;
import transformers.xb.XbCalendar;
import transformers.xb.XbCalendar.DaySet;
import transformers.xb.XbGroups;
import transformers.xb.XbIds;
import transformers.xb.XbScan;
import transformers.xb.XbTypes.Dict;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SplitJourneyCrossNumberProbe {

    private static final int NONE = -1;

    private SplitJourneyCrossNumberProbe() {}

    /// One journey's two ends. Ids are retained here, unlike in the pass: every survivor of the
    /// funnel has to be nameable.
    private static final class Ends {
        final List<String> id = new ArrayList<>();
        final List<String> line = new ArrayList<>();
        final List<String> dayCode = new ArrayList<>();
        final List<int[]> ends = new ArrayList<>();   // {first, last, second, penultimate}
        final List<int[]> secs = new ArrayList<>();   // {depart, arrive}
        final List<Integer> num = new ArrayList<>();
        final List<Integer> publisher = new ArrayList<>();
        final List<Integer> days = new ArrayList<>();
        final List<Boolean> crossesBorder = new ArrayList<>();
        int n;
    }

    public static void main(String[] args) throws Exception {
        Path store = Path.of(args.length > 0 ? args[0] : "data/at-prestitch.lmdb");
        int wantExamples = 5;
        for (int i = 1; i < args.length - 1; i++) {
            if ("--examples".equals(args[i])) wantExamples = Integer.parseInt(args[i + 1]);
        }
        try (Store db = Stores.open(store, true); Txn txn = db.roTxn()) {
            run(db, txn, store.toString(), wantExamples);
        }
    }

    static void run(Store db, Txn txn, String name, int wantExamples) {
        Dict stations = new Dict();
        Dict numbers = new Dict();
        Dict publishers = new Dict();
        Dict dayKeys = new Dict();
        List<DaySet> daySets = new ArrayList<>();

        Map<String, String> sspToStopPlace = new HashMap<>();
        for (Object o : db.iterOnlyObjects(txn, PassengerStopAssignment.class)) {
            PassengerStopAssignment psa = (PassengerStopAssignment) o;
            String ssp = XbScan.sspRef(psa);
            String sp = XbScan.stopPlaceRef(psa);
            if (ssp != null && sp != null) sspToStopPlace.putIfAbsent(ssp, sp);
        }
        Map<String, String> tnValue = new HashMap<>();
        for (Object o : db.iterOnlyObjects(txn, TrainNumber.class)) {
            TrainNumber tn = (TrainNumber) o;
            tnValue.put(tn.getId(), XbIds.normalizeTrainNumber(tn.getForAdvertisement()));
        }
        Map<String, DaySet> dayTypeDates = XbCalendar.readDayTypeDates(db, txn);

        Set<String> publishedPairs = new HashSet<>();
        long publishedMeetings = 0;
        for (Object o : db.iterOnlyObjects(txn, JourneyMeeting.class)) {
            JourneyMeeting jm = (JourneyMeeting) o;
            publishedMeetings++;
            JourneyRefStructure f = jm.getFromJourneyRef();
            JourneyRefStructure t = jm.getToJourneyRef();
            if (f == null || t == null || f.getRef() == null || t.getRef() == null) continue;
            publishedPairs.add(f.getRef() + ' ' + t.getRef());
        }

        Ends e = new Ends();
        long journeys = 0;
        long noNumber = 0;
        XbScan.PatternCache patterns = new XbScan.PatternCache(db, txn);
        for (ObjectRow row : db.iterObjects(txn, ServiceJourney.class)) {
            ServiceJourney sj = (ServiceJourney) row.object();
            journeys++;
            String number = XbScan.journeyNumber(sj, tnValue);
            if (number == null) {
                noNumber++;
                continue;
            }
            if (sj.getPassingTimes() == null) continue;
            List<TimetabledPassingTime> tt = sj.getPassingTimes().getTimetabledPassingTime();
            if (tt.size() < 2) continue;
            List<String> refs = XbScan.stopRefs(sj, tt, patterns);
            if (refs == null) continue;
            int last = tt.size() - 1;
            int first = station(stations, sspToStopPlace, refs.get(0));
            int lastSt = station(stations, sspToStopPlace, refs.get(last));
            if (first == NONE || lastSt == NONE) continue;
            Integer dep = seconds(tt.get(0), false);
            Integer arr = seconds(tt.get(last), true);
            if (dep == null || arr == null) continue;

            String code = dayCode(sj);
            int dayIdx = dayKeys.lookup(code);
            if (dayIdx == NONE) {
                dayIdx = dayKeys.intern(code);
                daySets.add(XbCalendar.journeyDays(sj, dayTypeDates));
            }
            // Does it call outside its publisher's country? The pre-consolidation analogue of
            // XbGroups.crossesABorder, reading the stop's country off its own ref.
            String home = XbIds.feedCountry(sj.getId());
            boolean crosses = false;
            for (String r : refs) {
                String cc = r == null ? null : XbIds.stopCountry(r);
                if (cc != null && home != null && !cc.equals(home)) {
                    crosses = true;
                    break;
                }
            }
            e.id.add(sj.getId());
            e.line.add(lineRef(sj));
            e.dayCode.add(code);
            e.ends.add(new int[] {first, lastSt,
                    station(stations, sspToStopPlace, refs.get(1)),
                    station(stations, sspToStopPlace, refs.get(last - 1))});
            e.secs.add(new int[] {dep, arr});
            e.num.add(numbers.intern(number));
            e.publisher.add(publishers.intern(XbGroups.publisher(sj.getId())));
            e.days.add(dayIdx);
            e.crossesBorder.add(crosses);
            e.n++;
        }
        Log.info("[xnum] %s: %,d journeys, %,d carry no train number, %,d with two readable ends "
                + "over %,d stations; %,d meetings published", name, journeys, noNumber, e.n,
                stations.size(), publishedMeetings);

        // Bucket on (station, publisher): publisher equality is one of the five conjuncts.
        Map<Long, List<Integer>> arriving = new HashMap<>();
        Map<Long, List<Integer>> departing = new HashMap<>();
        for (int i = 0; i < e.n; i++) {
            arriving.computeIfAbsent(slot(e.ends.get(i)[1], e.publisher.get(i)),
                    k -> new ArrayList<>()).add(i);
            departing.computeIfAbsent(slot(e.ends.get(i)[0], e.publisher.get(i)),
                    k -> new ArrayList<>()).add(i);
        }

        long relaxed = 0;
        long diffNumber = 0;
        long diffLine = 0;
        long unpublished = 0;
        long crossing = 0;
        long partnerHalf = 0;
        Map<String, Long> byOperator = new java.util.TreeMap<>();
        Map<String, Long> byOperatorShort = new java.util.TreeMap<>();
        Map<String, Long> byDelta = new java.util.TreeMap<>();
        Map<String, Long> byDeltaShort = new java.util.TreeMap<>();
        List<int[]> survivors = new ArrayList<>();       // {a, b, meetingDays}
        for (Map.Entry<Long, List<Integer>> bucket : arriving.entrySet()) {
            List<Integer> deps = departing.get(bucket.getKey());
            if (deps == null) continue;
            for (int a : bucket.getValue()) {
                for (int b : deps) {
                    if (a == b) continue;
                    int wait = SplitJourneys.alignedWait(e.secs.get(a)[1], e.secs.get(b)[0]);
                    if (wait < 0 || wait > SplitJourneys.MAX_WAIT_S) continue;
                    if (e.ends.get(a)[3] != NONE && e.ends.get(a)[3] == e.ends.get(b)[2]) continue;
                    DaySet meet = XbCalendar.meetingDays(daySets.get(e.days.get(a)),
                            e.secs.get(a)[1], daySets.get(e.days.get(b)), e.secs.get(b)[0]);
                    if (meet.isEmpty()) continue;
                    relaxed++;

                    if (e.num.get(a).equals(e.num.get(b))) continue;
                    diffNumber++;

                    String la = e.line.get(a);
                    String lb = e.line.get(b);
                    if (la != null && la.equals(lb)) continue;
                    diffLine++;

                    if (publishedPairs.contains(e.id.get(a) + ' ' + e.id.get(b))) continue;
                    unpublished++;

                    if (!e.crossesBorder.get(a) && !e.crossesBorder.get(b)) continue;
                    crossing++;

                    // The signature of a split, as against a connection that happens to fall inside
                    // the window: the foreign half is published under the partner's operator code
                    // (10CH1-SBB-22 against 10A11-OEBB-67) and the dwell is administrative —
                    // 13478's is zero. Two different trains meeting at a border hub have neither.
                    boolean split = partnerCoded(e.id.get(a)) != partnerCoded(e.id.get(b));
                    if (!split) continue;
                    partnerHalf++;
                    String code = partnerCoded(e.id.get(a))
                            ? operatorToken(e.id.get(a)) : operatorToken(e.id.get(b));
                    byOperator.merge(code, 1L, Long::sum);
                    // The number relation. Freilassing's PKP pairs are 40416/416 and 40407/407 and
                    // the HZ Nightjet is 40237/237: the partner half's number is the domestic one
                    // plus a round offset. The histogram says whether it holds generally.
                    long delta = delta(numbers.value(e.num.get(a)), numbers.value(e.num.get(b)));
                    byDelta.merge(delta == Long.MIN_VALUE ? "not two numbers"
                            : delta % 10000 == 0 ? String.valueOf(delta) : "other", 1L, Long::sum);
                    if (wait > 300) continue;
                    byOperatorShort.merge(code, 1L, Long::sum);
                    byDeltaShort.merge(delta == Long.MIN_VALUE ? "not two numbers"
                            : delta % 10000 == 0 ? String.valueOf(delta) : "other", 1L, Long::sum);
                    survivors.add(new int[] {a, b, meet.size()});
                }
            }
        }

        Log.info("[xnum] FUNNEL");
        Log.info("[xnum]   %,10d  pairs passing the five conjuncts that are NOT the train number",
                relaxed);
        Log.info("[xnum]   %,10d  ... whose two train numbers DIFFER (unreachable by the pass)",
                diffNumber);
        Log.info("[xnum]   %,10d  ... and whose two Lines DIFFER (unwritable by the exporter)",
                diffLine);
        Log.info("[xnum]   %,10d  ... and which no published meeting joins", unpublished);
        Log.info("[xnum]   %,10d  ... and where a half calls outside its publisher's country",
                crossing);
        Log.info("[xnum]   %,10d  ... and where exactly ONE half is partner-coded (not OEBB)",
                partnerHalf);
        Log.info("[xnum]   %,10d  ... and the dwell is under 5 minutes", survivors.size());
        Log.info("[xnum] |numberA - numberB| over the partner-coded pairs, all / dwell under 5 min:");
        for (Map.Entry<String, Long> o : byDelta.entrySet()) {
            Log.info("[xnum]   %,8d / %,-8d %s", o.getValue(),
                    byDeltaShort.getOrDefault(o.getKey(), 0L), o.getKey());
        }
        Log.info("[xnum] the partner-coded half's operator, all / dwell under 5 min:");
        for (Map.Entry<String, Long> o : byOperator.entrySet()) {
            Log.info("[xnum]   %,8d / %,-8d %s", o.getValue(),
                    byOperatorShort.getOrDefault(o.getKey(), 0L), o.getKey());
        }

        if (survivors.isEmpty()) return;

        Map<Integer, String> names = names(db, txn, stations, survivors, e);
        // Grouped by partner operator, because the families are not alike: SBB/CD/DBAG are genuine
        // border splits while WEST and CAT are standalone operators republished in the ÖBB feed. A
        // verdict has to be reached per family, not per corpus.
        survivors.sort(Comparator
                .comparing((int[] s) -> partnerCoded(e.id.get(s[0]))
                        ? operatorToken(e.id.get(s[0])) : operatorToken(e.id.get(s[1])))
                .thenComparingInt(s -> -s[2]));
        Map<String, Integer> shown = new HashMap<>();
        Log.info("[xnum] the %,d survivors, up to %d per partner operator:", survivors.size(),
                wantExamples);
        for (int i = 0; i < survivors.size(); i++) {
            int a = survivors.get(i)[0];
            int b = survivors.get(i)[1];
            String op = partnerCoded(e.id.get(a))
                    ? operatorToken(e.id.get(a)) : operatorToken(e.id.get(b));
            if (shown.merge(op, 1, Integer::sum) > wantExamples) continue;
            Log.info("[xnum] ---- %s, %d meeting days", op, survivors.get(i)[2]);
            describe(e, a, "ARRIVES", names, stations, numbers);
            describe(e, b, "DEPARTS", names, stations, numbers);
            Log.info("[xnum]        wait %d s at %s", SplitJourneys.alignedWait(e.secs.get(a)[1],
                    e.secs.get(b)[0]), station(names, stations, e.ends.get(a)[1]));
        }
    }

    private static void describe(Ends e, int i, String role, Map<Integer, String> names,
            Dict stations, Dict numbers) {
        Log.info("[xnum]   %s  %s", role, e.id.get(i));
        Log.info("[xnum]        train %s   line %s   calendar %s   crosses a border %s",
                numbers.value(e.num.get(i)), e.line.get(i), e.dayCode.get(i).trim(),
                e.crossesBorder.get(i));
        Log.info("[xnum]        %s %s  ->  %s %s",
                hhmm(e.secs.get(i)[0]), station(names, stations, e.ends.get(i)[0]),
                hhmm(e.secs.get(i)[1]), station(names, stations, e.ends.get(i)[1]));
    }

    private static String station(Map<Integer, String> names, Dict stations, int s) {
        String n = names.get(s);
        return stations.value(s) + (n == null ? "" : " (" + n + ")");
    }

    private static Map<Integer, String> names(Store db, Txn txn, Dict stations,
            List<int[]> survivors, Ends e) {
        Set<Integer> wanted = new HashSet<>();
        for (int[] s : survivors) {
            for (int k : new int[] {s[0], s[1]}) {
                wanted.add(e.ends.get(k)[0]);
                wanted.add(e.ends.get(k)[1]);
            }
        }
        Map<Integer, String> out = new HashMap<>();
        for (Object o : db.iterOnlyObjects(txn, StopPlace.class)) {
            StopPlace sp = (StopPlace) o;
            int s = stationOf(stations, sp.getId());
            if (s == NONE || !wanted.contains(s) || out.containsKey(s)) continue;
            if (Mls.hasText(sp.getName())) out.put(s, Mls.text(sp.getName()));
        }
        return out;
    }

    private static int stationOf(Dict stations, String id) {
        return stations.lookup(id);
    }

    private static String hhmm(int sec) {
        int s = ((sec % 86400) + 86400) % 86400;
        return String.format("%02d:%02d%s", s / 3600, (s % 3600) / 60, sec >= 86400 ? "+1" : "");
    }

    private static long slot(int station, int publisher) {
        return ((long) station << 32) | (publisher & 0xffffffffL);
    }

    private static int station(Dict stations, Map<String, String> sspToStopPlace, String ssp) {
        String sp = ssp == null ? null : sspToStopPlace.get(ssp);
        return sp == null ? NONE : stations.intern(sp);
    }

    /// SplitJourneys' own hour-24-tolerant reader, which is package-private there.
    private static Integer seconds(TimetabledPassingTime t, boolean arriving) {
        if (t == null) return null;
        Integer primary = arriving
                ? raw(t.getArrivalTime(), t.getArrivalDayOffset())
                : raw(t.getDepartureTime(), t.getDepartureDayOffset());
        if (primary != null) return primary;
        return arriving
                ? raw(t.getDepartureTime(), t.getDepartureDayOffset())
                : raw(t.getArrivalTime(), t.getArrivalDayOffset());
    }

    private static Integer raw(XmlTime time, BigInteger dayOffset) {
        if (time == null || time.isUnparsed()) return null;
        int tod = time.getHour() * 3600 + time.getMinute() * 60 + time.getSecond();
        int off = dayOffset == null ? 0 : dayOffset.intValueExact();
        return tod + 86400 * off;
    }

    private static String dayCode(ServiceJourney sj) {
        if (sj.getDayTypes() == null) return "(no day types)";
        StringBuilder b = new StringBuilder("dt:");
        for (JAXBElement<? extends DayTypeRefStructure> r : sj.getDayTypes().getDayTypeRef()) {
            b.append(r.getValue().getRef()).append(' ');
        }
        return b.toString();
    }

    /// |a - b| when both normalised numbers are integers, else Long.MIN_VALUE.
    private static long delta(String a, String b) {
        try {
            return Math.abs(Long.parseLong(a) - Long.parseLong(b));
        } catch (NumberFormatException | NullPointerException ex) {
            return Long.MIN_VALUE;
        }
    }

    private static String operatorToken(String journeyId) {
        int at = journeyId.indexOf(":ServiceJourney:");
        String[] parts = journeyId.substring(at + ":ServiceJourney:".length()).split("-");
        return parts.length > 1 ? parts[1] : "?";
    }

    /// Is this journey published under a partner's operator code? The Mentz local id is
    /// `<line>-<operator>-<n>-<n>-<n>`, so the second token says who runs it: `10CH1-SBB-22` is
    /// SBB's half of an ÖBB train, `10A11-OEBB-67` is ÖBB's own.
    private static boolean partnerCoded(String journeyId) {
        int at = journeyId.indexOf(":ServiceJourney:");
        if (at < 0) return false;
        String[] parts = journeyId.substring(at + ":ServiceJourney:".length()).split("-");
        return parts.length > 1 && !"OEBB".equals(parts[1]);
    }

    private static String lineRef(ServiceJourney sj) {
        JAXBElement<? extends LineRefStructure> el = sj.getLineRef();
        return el == null ? null : el.getValue().getRef();
    }
}
