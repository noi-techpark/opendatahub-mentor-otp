package transformers.feedfix;

// The JourneyMeetings a Mentz feed does NOT publish: the second half of a train that was split at
// the border and published as two ServiceJourneys. EpipToDb converts every JourneyMeeting into a
// ServiceJourneyInterchange, resolving EACH SIDE's own ScheduledStopPoint at the meeting station
// (toolkit.transform.epip.EpipInterchange), so a meeting is all this pass has to emit.
//
// ÖBB publishes an international train as two journeys, the foreign half under the partner's
// operator code, and joins them with a JourneyMeeting — but only when both halves sit on the same
// Line, and a split train's halves are on two. Train 13478 is the case:
//
//   at:obb:ServiceJourney:10A11-OEBB-67-1-24960:65710:   Linz 06:56 -> St.Margrethen ARR 11:45
//   at:obb:ServiceJourney:10CH1-SBB-22-1-42300:65710:    St.Margrethen DEP 11:45 -> Zürich HB 13:28
//
// One TrainNumber (at:obb:TrainNumber:13478:), one DayType (at:obb:DayType:65710_6:), one StopPlace
// (ch:23016:20309), zero dwell — and two different Lines (10A11, 10CH1), so no meeting is written.
// Both of those line files contain ZERO JourneyMeetings between them; the file that does carry them,
// 10-CH1's neighbour 12-CH1, has 40 for 51 journeys, and there both halves are on one Line.
// Nationally that is 988 partner-coded journeys joined to their domestic half and 2,602 not.
//
// Without the meeting the cross-border coupling sees two unrelated journeys, the split station is a
// TERMINUS of both, and every anchor rule that needs an interior station refuses.
//
// It runs per feed, BEFORE consolidation: both halves are published by one feed under one stop
// registry, so the two PassengerStopAssignments already resolve to one StopPlace in at.lmdb and the
// join needs no station matching at all.

import jakarta.xml.bind.JAXBElement;
import noi.netex.model.DayTypeRefStructure;
import noi.netex.model.JourneyMeeting;
import noi.netex.model.JourneyRefStructure;
import noi.netex.model.LineRefStructure;
import noi.netex.model.PassengerStopAssignment;
import noi.netex.model.ScheduledStopPointRefStructure;
import noi.netex.model.ServiceJourney;
import noi.netex.model.TimetabledPassingTime;
import noi.netex.model.TrainNumber;
import noi.netex.time.XmlTime;
import toolkit.store.ObjectRow;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.util.Log;
import transformers.xb.XbCalendar;
import transformers.xb.XbCalendar.DaySet;
import transformers.xb.XbGroups;
import transformers.xb.XbIds;
import transformers.xb.XbScan;
import transformers.xb.XbTypes.Dict;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SplitJourneys {

    private SplitJourneys() {}

    /// How long the vehicle may stand at the split station. The border split is administrative, so
    /// the real dwell is minutes — 13478's is ZERO — but a portion detached and re-attached takes
    /// longer, and this is the same window every other stage of the corridor work uses
    /// ([transformers.xb.XbProfile#MEETING_WINDOW_S]).
    public static final int MAX_WAIT_S = 30 * 60;

    /// "no such value" for the interned int columns.
    private static final int NONE = -1;

    // ------------------------------------------------------------------------------------- //

    /// What the pass produced, and what it measured while producing it.
    ///
    /// @param meetings the synthesised meetings, ready to insert.
    /// @param census   the counters. Read them before trusting the meetings: the rule is derived
    ///                 from four line files, and the census is what says whether it holds
    ///                 corpus-wide.
    public record Result(List<JourneyMeeting> meetings, Census census) {}

    /// The feed's own 29,087 published meetings are the ANSWER KEY: a rule that cannot recover the
    /// pairs ÖBB itself states is wrong, whatever it does to the ones ÖBB omits. A cross-Line
    /// candidate has nothing to be scored against, so recall and precision are measurable only over
    /// the same-Line population.
    public static final class Census {
        public long journeys;
        public long journeysNoNumber;         // no train number at all: bus and tram, not this pass
        public long journeysUnusable;         // no pattern, no times, fewer than two stops
        public long journeysNoStopPlace;      // an endpoint whose stop point no assignment places
        public long publishedMeetings;
        public long publishedResolvable;      // both ends are journeys this pass could read
        public long publishedSameLine;        // ... and both ends are on ONE Line
        public long publishedSameNum;         // ... and both ends carry one train number
        public long publishedProposed;        // ... and the six conjuncts propose the pair
        public long publishedRecovered;       // ... and it also survived the reduction to one
        public long candidates;               // pairs surviving every conjunct and the reduction
        public long candidatesCrossLine;      // ... whose halves are on two Lines: the missing ones
        public long candidatesEmitted;
        public long rejectedTurnback;         // the vehicle goes back the way it came
        public long rejectedNoCommonDate;
        public long rejectedNotBest;          // another pairing of this journey ranked higher
        public long alreadyPublished;
        /// Days of service the emitted pairings explain, summed over pairs, and what the same
        /// greedy covers WITHOUT the day-aware rank.
        public long daysCovered;
        public long daysCoveredKeyOrder;
        public long pairsKeyOrder;

        public void report(String tag) {
            Log.info("%s read %,d journeys: %,d carry no train number (bus and tram — not a split "
                    + "train), %,d unusable, %,d with an unplaced endpoint",
                    tag, journeys, journeysNoNumber, journeysUnusable, journeysNoStopPlace);
            Log.info("%s the feed's OWN meetings: %,d published, %,d resolvable here, %,d join two "
                    + "journeys of ONE Line (%.1f %%) -- the omission this pass exists for -- and "
                    + "%,d join two under ONE train number",
                    tag, publishedMeetings, publishedResolvable, publishedSameLine,
                    pct(publishedSameLine, publishedResolvable), publishedSameNum);
            Log.info("%s RECALL over the population this pass claims: the conjuncts propose %,d of "
                    + "the %,d published same-number pairs (%.1f %%), and %,d survive the reduction "
                    + "to one successor (%.1f %%) -- the difference is the feed's own one-to-many, "
                    + "where the onward run is published under two operators' codes",
                    tag, publishedProposed, publishedSameNum, pct(publishedProposed, publishedSameNum),
                    publishedRecovered, pct(publishedRecovered, publishedSameNum));
            Log.info("%s refused: %,d turnbacks, %,d with no common date, %,d where the journey had "
                    + "a better pairing", tag, rejectedTurnback, rejectedNoCommonDate,
                    rejectedNotBest);
            Log.info("%s %,d candidates, %,d of them across two Lines; emitted %,d (%,d were "
                    + "already published)",
                    tag, candidates, candidatesCrossLine, candidatesEmitted, alreadyPublished);
            Log.info("%s the pairings explain %,d days of service over %,d pairs; the same greedy "
                    + "ranked on the store key instead covers %,d days over %,d pairs",
                    tag, daysCovered, candidates, daysCoveredKeyOrder, pairsKeyOrder);
        }

        private static double pct(long n, long of) {
            return of == 0 ? 0.0 : 100.0 * n / of;
        }
    }

    // ------------------------------------------------------------------------------------- //
    // The journey columns
    // ------------------------------------------------------------------------------------- //

    /// Every journey reduced to its two ENDS, in interned ints.
    ///
    /// One column set rather than one object per journey: the Austrian store holds 1,080,746
    /// ServiceJourneys and this pass has to see all of them, because whether a journey is the half
    /// of a split train is not decidable until the other half is found.
    ///
    /// The journey's ID is deliberately NOT retained. A candidate pair is re-loaded by full key at
    /// emit time, and there are thousands of those against a million journeys.
    private static final class Ends {
        long[] key = new long[1 << 16];
        int[] firstStation = new int[1 << 16];
        int[] lastStation = new int[1 << 16];
        /// Seconds since the journey's own operating-day midnight, day offset folded in.
        int[] departSec = new int[1 << 16];
        int[] arriveSec = new int[1 << 16];
        /// The SECOND station and the SECOND-TO-LAST one, which is all the turnback test needs.
        int[] secondStation = new int[1 << 16];
        int[] penultimateStation = new int[1 << 16];
        int[] num = new int[1 << 16];
        int[] publisher = new int[1 << 16];
        /// An index into [#daySets] — interned on the journey's DayTypeRef list, so the 99,253
        /// DayTypes of the Austrian store yield at most that many distinct sets rather than one per
        /// journey.
        int[] days = new int[1 << 16];
        int n;

        void add(long k, int first, int last, int dep, int arr, int second, int penultimate,
                int number, int pub, int dayKey) {
            if (n == key.length) grow();
            key[n] = k;
            firstStation[n] = first;
            lastStation[n] = last;
            departSec[n] = dep;
            arriveSec[n] = arr;
            secondStation[n] = second;
            penultimateStation[n] = penultimate;
            num[n] = number;
            publisher[n] = pub;
            days[n] = dayKey;
            n++;
        }

        private void grow() {
            int m = n * 2;
            key = Arrays.copyOf(key, m);
            firstStation = Arrays.copyOf(firstStation, m);
            lastStation = Arrays.copyOf(lastStation, m);
            departSec = Arrays.copyOf(departSec, m);
            arriveSec = Arrays.copyOf(arriveSec, m);
            secondStation = Arrays.copyOf(secondStation, m);
            penultimateStation = Arrays.copyOf(penultimateStation, m);
            num = Arrays.copyOf(num, m);
            publisher = Arrays.copyOf(publisher, m);
            days = Arrays.copyOf(days, m);
        }
    }

    // ------------------------------------------------------------------------------------- //

    /// Read the store, find the halves nothing joins, and build a [JourneyMeeting] for each.
    public static Result stitch(Store db, Txn txn, String tag) {
        Census census = new Census();
        Dict stations = new Dict();
        Dict numbers = new Dict();
        Dict publishers = new Dict();
        Dict dayKeys = new Dict();
        List<DaySet> daySets = new ArrayList<>();

        Map<String, String> sspToStopPlace = stopPlaceBySsp(db, txn);
        Map<String, String> tnValue = trainNumbers(db, txn);
        Map<String, DaySet> dayTypeDates = XbCalendar.readDayTypeDates(db, txn);

        // The feed's own meetings, and the ids they name: 29,087 meetings over ~58 k journey ids.
        Published published = readPublishedMeetings(db, txn, census);

        Ends ends = new Ends();
        Map<String, Integer> indexOfWanted = new HashMap<>();   // only the answer key's journeys
        Map<String, String> lineOfWanted = new HashMap<>();
        XbScan.PatternCache patterns = new XbScan.PatternCache(db, txn);
        for (ObjectRow row : db.iterObjects(txn, ServiceJourney.class)) {
            ServiceJourney sj = (ServiceJourney) row.object();
            census.journeys++;
            // THE TRAIN NUMBER FIRST. The overwhelming majority of the store's journeys are buses
            // and trams with no number at all; they cannot be half of a split train, and resolving
            // their patterns to find that out is most of this pass's wall clock.
            String number = XbScan.journeyNumber(sj, tnValue);
            if (number == null) {
                census.journeysNoNumber++;
                continue;
            }
            if (sj.getPassingTimes() == null) {
                census.journeysUnusable++;
                continue;
            }
            List<TimetabledPassingTime> tt = sj.getPassingTimes().getTimetabledPassingTime();
            if (tt.size() < 2) {
                census.journeysUnusable++;
                continue;
            }
            List<String> refs = XbScan.stopRefs(sj, tt, patterns);
            if (refs == null) {
                census.journeysUnusable++;
                continue;
            }
            int last = tt.size() - 1;
            int first = station(stations, sspToStopPlace, refs.get(0));
            int lastStation = station(stations, sspToStopPlace, refs.get(last));
            if (first == NONE || lastStation == NONE) {
                census.journeysNoStopPlace++;
                continue;
            }
            Integer dep = seconds(tt.get(0), /*arriving=*/false);
            Integer arr = seconds(tt.get(last), /*arriving=*/true);
            if (dep == null || arr == null) {
                census.journeysUnusable++;
                continue;
            }
            String key = dayKey(sj);
            int dayIdx = dayKeys.lookup(key);
            if (dayIdx == NONE) {
                dayIdx = dayKeys.intern(key);
                daySets.add(XbCalendar.journeyDays(sj, dayTypeDates));
                // The dictionary's index and the list's position are the SAME number or the columns
                // read another journey's calendar from here on.
                if (dayIdx != daySets.size() - 1) {
                    throw new IllegalStateException("[stitch] the DaySet column has drifted from its "
                            + "dictionary at " + sj.getId() + ": interned " + dayIdx + ", stored at "
                            + (daySets.size() - 1));
                }
            }
            ends.add(db.fullKeyOf(ServiceJourney.class, row.localKey()), first, lastStation,
                    dep, arr,
                    station(stations, sspToStopPlace, refs.get(1)),
                    station(stations, sspToStopPlace, refs.get(last - 1)),
                    numbers.intern(number),
                    publishers.intern(XbGroups.publisher(sj.getId())),
                    dayIdx);
            if (published.journeyIds.contains(sj.getId())) {
                indexOfWanted.put(sj.getId(), ends.n - 1);
                String line = lineRef(sj);
                if (line != null) lineOfWanted.put(sj.getId(), line);
            }
        }
        Log.info("%s columns built: %,d journeys with two readable ends over %,d stations",
                tag, ends.n, stations.size());

        // Scored TWICE, on either side of the reduction: "the rule cannot see this pair" and "the
        // rule saw it and preferred another" are different failures with different fixes, and one
        // recall number cannot tell them apart.
        List<Candidate> proposed = withDates(match(ends, census), ends, daySets, census);
        score(proposed, ends, published, indexOfWanted, lineOfWanted, census, true);
        List<Candidate> keyOrder = reduce(proposed, ends, new Census(), false);
        for (Candidate c : keyOrder) census.daysCoveredKeyOrder += c.days().size();
        census.pairsKeyOrder = keyOrder.size();
        List<Candidate> candidates = reduce(proposed, ends, census, true);
        for (Candidate c : candidates) census.daysCovered += c.days().size();
        score(candidates, ends, published, indexOfWanted, lineOfWanted, census, false);
        List<JourneyMeeting> meetings = emit(db, txn, candidates, ends, published, lineOfWanted,
                census);
        census.report(tag);
        return new Result(meetings, census);
    }

    // ------------------------------------------------------------------------------------- //
    // The rule
    // ------------------------------------------------------------------------------------- //

    /// One proposed continuation: journey `from` ends where journey `to` begins.
    record Candidate(int from, int to, int station, int waitSec, DaySet days) {}

    /// Every pair where one journey ENDS exactly where another BEGINS, under ONE TRAIN NUMBER, close
    /// enough in time to be one vehicle, on a date they can both run.
    ///
    /// Six conjuncts, and each of them refuses by default:
    ///
    ///  1. both carry the SAME normalised train number. Without it the other five conjuncts pair
    ///     every bus that ends at a hub with every bus that starts there within half an hour, all
    ///     of them one publisher;
    ///  2. the arriving journey's LAST stop and the departing journey's FIRST stop are one
    ///     StopPlace. Never the ScheduledStopPoint: 13478's two halves name St.Margrethen with two
    ///     different stop points, which is exactly the shape this pass exists for;
    ///  3. the same publisher codespace — a Verbund's bus does not continue as ÖBB's train, and
    ///     coupling two publishers is the cross-border stage's job, not this one's;
    ///  4. the wait is between zero and [#MAX_WAIT_S], measured on the ALIGNED clock, so a half
    ///     that runs past midnight and is dated the next day still meets its predecessor
    ///     ([XbCalendar#meetingDays] does the day arithmetic);
    ///  5. it is not a TURNBACK. A vehicle that arrives and leaves the way it came is a real
    ///     continuation and NOT one train: stitched, it would run out and back, which no route test
    ///     downstream can read. The test is that the departing journey's second stop is not the
    ///     arriving journey's second-to-last;
    ///  6. and then at most ONE successor per journey ([#reduce]).
    ///
    /// ÖBB's own meetings are overwhelmingly bus and tram through-workings: only 1,037 of the 27,130
    /// pairs recovered here share a train number. The border split, which is the population this
    /// pass exists for, does carry and share the number. Pairs whose two halves carry DIFFERENT
    /// numbers are left to the cross-border coupling, the stage built to decide that two
    /// publications are one train.
    private static List<Candidate> match(Ends ends, Census census) {
        // Bucket by (station a journey ARRIVES at, train number). Keying the bucket on the number as
        // well as the station is not an optimisation of conjunct 1, it IS conjunct 1.
        Map<Long, List<Integer>> arrivingAt = new HashMap<>();
        Map<Long, List<Integer>> departingFrom = new HashMap<>();
        for (int i = 0; i < ends.n; i++) {
            if (ends.num[i] == NONE) continue;                  // no train number: not this pass's
            arrivingAt.computeIfAbsent(slot(ends.lastStation[i], ends.num[i]),
                    k -> new ArrayList<>()).add(i);
            departingFrom.computeIfAbsent(slot(ends.firstStation[i], ends.num[i]),
                    k -> new ArrayList<>()).add(i);
        }
        List<Candidate> out = new ArrayList<>();
        for (Map.Entry<Long, List<Integer>> e : arrivingAt.entrySet()) {
            List<Integer> departures = departingFrom.get(e.getKey());
            if (departures == null) continue;
            int station = (int) (e.getKey() >>> 32);
            for (int a : e.getValue()) {
                for (int b : departures) {
                    if (a == b) continue;
                    if (ends.publisher[a] != ends.publisher[b]) continue;
                    int wait = alignedWait(ends.arriveSec[a], ends.departSec[b]);
                    if (wait < 0 || wait > MAX_WAIT_S) continue;
                    if (ends.penultimateStation[a] != NONE
                            && ends.penultimateStation[a] == ends.secondStation[b]) {
                        census.rejectedTurnback++;
                        continue;
                    }
                    out.add(new Candidate(a, b, station, wait, null));
                }
            }
        }
        return out;
    }

    private static long slot(int station, int num) {
        return ((long) station << 32) | (num & 0xffffffffL);
    }

    /// At most one successor per journey, and one predecessor.
    ///
    /// A train number is carried by every CALENDAR VARIANT of one train — 13478 by four Austrian
    /// halves and four Swiss ones — so conjunct 1 leaves a variant of the domestic half facing every
    /// variant of the foreign one. The feed itself says which pairs with which: the two halves of
    /// one variant carry the SAME DayType (`at:obb:DayType:65710_6:` for both halves of 13478),
    /// because they are one train on one set of days. That is the first rank.
    ///
    /// The second rank is how many days the pairing explains, from the meeting-day set [#withDates]
    /// computes. Train 13477 is the case: ÖBB publishes THREE Swiss halves against SEVEN Austrian
    /// ones, both partitioning the same 118 days, and only one pair shares a DayType. A greedy that
    /// claims the widest pairings first keeps a 65-day Swiss half from being paired with a 4-day
    /// Austrian one while a 59-day one is on offer.
    ///
    /// The wait comes third and the store key is the tie-break of LAST resort — it is there to make
    /// the order total, not to make it good.
    ///
    /// One successor rather than the feed's one-to-many. Where ÖBB writes two meetings it is writing
    /// them to two COPIES of the onward run, one under each operator's code, and the coupler reduces
    /// that to a matching anyway ([transformers.xb.XbStitch]). What that CANNOT represent is a
    /// genuine calendar fan-out — several halves whose day sets are disjoint, which is 13477: one
    /// journey in several composites is one journey in several groups, which XbCouple's
    /// written-journeys guard refuses.
    /// @param dayAware rank by how many days the pairing explains. False is the MEASUREMENT ARM: the
    ///        greedy is not a maximum matching, so a better ordering can produce FEWER pairs, and
    ///        "fewer pairs" and "less service covered" are not the same claim. Both totals are
    ///        reported and the day-aware one is what the pass uses.
    private static List<Candidate> reduce(List<Candidate> candidates, Ends ends, Census census,
            boolean dayAware) {
        List<Candidate> ordered = new ArrayList<>(candidates);
        java.util.Comparator<Candidate> order = java.util.Comparator
                .comparingInt((Candidate c) -> ends.days[c.from()] == ends.days[c.to()] ? 0 : 1);
        if (dayAware) order = order.thenComparingInt(c -> -c.days().size());
        ordered.sort(order
                .thenComparingInt(Candidate::waitSec)
                .thenComparingLong(c -> ends.key[c.from()])
                .thenComparingLong(c -> ends.key[c.to()]));
        Set<Integer> hasSuccessor = new HashSet<>();
        Set<Integer> hasPredecessor = new HashSet<>();
        List<Candidate> out = new ArrayList<>();
        for (Candidate c : ordered) {
            if (!hasSuccessor.add(c.from())) {
                census.rejectedNotBest++;
                continue;
            }
            if (!hasPredecessor.add(c.to())) {
                hasSuccessor.remove(c.from());
                census.rejectedNotBest++;
                continue;
            }
            out.add(c);
        }
        return out;
    }

    /// The wait at the split station on ONE timeline, or -1 when the two are never within a day of
    /// each other.
    ///
    /// The two journeys count seconds from their OWN operating-day midnight, and a half that leaves
    /// at 00:12 the next day reads 720 against its predecessor's 86,340 — a wait of MINUS a day if
    /// the raw numbers are subtracted. The whole-day component is removed the same way
    /// [XbCalendar#meetingDays] removes it, by rounding the difference to whole days.
    public static int alignedWait(int arriveSec, int departSec) {
        long delta = (long) departSec - arriveSec;
        int k = (int) Math.round(delta / 86400.0);
        return (int) (delta - 86400L * k);
    }

    /// Fill in each candidate's meeting dates, dropping the ones that share none.
    private static List<Candidate> withDates(List<Candidate> candidates, Ends ends,
            List<DaySet> daySets, Census census) {
        List<Candidate> out = new ArrayList<>(candidates.size());
        for (Candidate c : candidates) {
            DaySet days = XbCalendar.meetingDays(daySets.get(ends.days[c.from()]),
                    ends.arriveSec[c.from()], daySets.get(ends.days[c.to()]),
                    ends.departSec[c.to()]);
            if (days.isEmpty()) {
                census.rejectedNoCommonDate++;
                continue;
            }
            out.add(new Candidate(c.from(), c.to(), c.station(), c.waitSec(), days));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------- //
    // Scoring against the feed's own meetings
    // ------------------------------------------------------------------------------------- //

    /// The meetings the feed already publishes: the ordered journey pairs, and every journey id
    /// they name.
    private record Published(Set<String> pairs, Set<String> journeyIds) {}

    private static Published readPublishedMeetings(Store db, Txn txn, Census census) {
        Set<String> pairs = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (Object o : db.iterOnlyObjects(txn, JourneyMeeting.class)) {
            JourneyMeeting jm = (JourneyMeeting) o;
            census.publishedMeetings++;
            JourneyRefStructure from = jm.getFromJourneyRef();
            JourneyRefStructure to = jm.getToJourneyRef();
            if (from == null || to == null || from.getRef() == null || to.getRef() == null) continue;
            pairs.add(pairKey(from.getRef(), to.getRef()));
            ids.add(from.getRef());
            ids.add(to.getRef());
        }
        return new Published(pairs, ids);
    }

    private static String pairKey(String from, String to) {
        return from + ' ' + to;
    }

    /// Score the candidate set against the answer key. Measurement only — nothing here changes what
    /// is emitted.
    private static void score(List<Candidate> candidates, Ends ends, Published published,
            Map<String, Integer> indexOf, Map<String, String> lineOf, Census census,
            boolean beforeReduce) {
        // Which published pairs the rule proposes. The candidates are indices; the answer key is
        // ids, so the join goes through the small index the journey pass kept for exactly this.
        Map<Long, Candidate> byPair = new HashMap<>();
        for (Candidate c : candidates) {
            byPair.put(((long) c.from() << 32) | (c.to() & 0xffffffffL), c);
        }
        for (String pair : published.pairs()) {
            int nul = pair.indexOf(' ');
            String from = pair.substring(0, nul);
            String to = pair.substring(nul + 1);
            Integer a = indexOf.get(from);
            Integer b = indexOf.get(to);
            if (a == null || b == null) continue;      // an end this pass could not read
            boolean sameNumber = ends.num[a] != NONE && ends.num[a] == ends.num[b];
            if (beforeReduce) {
                census.publishedResolvable++;
                String la = lineOf.get(from);
                String lb = lineOf.get(to);
                if (la != null && la.equals(lb)) census.publishedSameLine++;
                if (sameNumber) census.publishedSameNum++;
            }
            Candidate c = byPair.get(((long) (int) a << 32) | ((int) b & 0xffffffffL));
            if (beforeReduce) {
                if (c != null && sameNumber) census.publishedProposed++;
                continue;
            }
            // Recall is scored over the SAME-NUMBER population only, because that is the population
            // this pass claims.
            if (c != null && sameNumber) census.publishedRecovered++;
        }
    }

    // ------------------------------------------------------------------------------------- //
    // Emitting
    // ------------------------------------------------------------------------------------- //

    /// Build the meeting for every candidate the feed does not already publish.
    ///
    /// The two journeys are re-loaded by full key here and nowhere else: their ids, versions and
    /// stop-point refs are needed only for the few thousand pairs that survived.
    private static List<JourneyMeeting> emit(Store db, Txn txn, List<Candidate> candidates,
            Ends ends, Published published, Map<String, String> lineOfWanted, Census census) {
        List<JourneyMeeting> out = new ArrayList<>();
        for (Candidate c : candidates) {
            census.candidates++;
            if (!(db.loadObjectByFullKey(txn, ends.key[c.from()]) instanceof ServiceJourney a)
                    || !(db.loadObjectByFullKey(txn, ends.key[c.to()]) instanceof ServiceJourney b)) {
                continue;
            }
            String la = lineRef(a);
            String lb = lineRef(b);
            // Across two Lines is the shape the exporter structurally cannot write, so this count
            // says whether the pass is doing the job it was built for.
            if (la != null && !la.equals(lb)) census.candidatesCrossLine++;
            if (published.pairs().contains(pairKey(a.getId(), b.getId()))) {
                census.alreadyPublished++;
                continue;
            }
            String atPoint = lastStopRef(a, db, txn);
            if (atPoint == null) continue;
            out.add(meeting(a, b, atPoint, c));
            census.candidatesEmitted++;
        }
        return out;
    }

    /// The meeting itself, in the shape the feed's own carry: the station, both journeys, and the
    /// window between the arrival and the departure.
    ///
    /// The id is the two WHOLE journey ids. Nothing shorter is injective — every ÖBB id ends with a
    /// colon and the segment before it is a calendar code shared across journeys, so an id built
    /// from the tail collides and the store keeps only the last writer, silently.
    ///
    /// No validity condition is synthesised. ÖBB's own meetings carry an AvailabilityConditionRef
    /// keyed by the calendar code, but the journeys carry DayTypeRefs and there is no
    /// AvailabilityCondition for most calendar codes to point at. A meeting here asserts a
    /// CONTINUATION and nothing about dates; the two journeys' own calendars bound it, and every
    /// consumer of the interchange re-derives the dates from them.
    static JourneyMeeting meeting(ServiceJourney from, ServiceJourney to, String atPoint,
            Candidate c) {
        JourneyMeeting jm = new JourneyMeeting();
        jm.setId("at:obb:JourneyMeeting:stitched:" + XbIds.slot(from.getId()) + ':'
                + XbIds.slot(to.getId()) + ':');
        jm.setVersion("any");
        ScheduledStopPointRefStructure at = new ScheduledStopPointRefStructure();
        at.setRef(atPoint);
        jm.setAtStopPointRef(at);
        jm.setFromJourneyRef(journeyRef(from));
        jm.setToJourneyRef(journeyRef(to));
        return jm;
    }

    /// A journey ref that needs no repair afterwards: the version is the journey's own, and the
    /// class is named explicitly, or the store files the unresolved row under "Journey" — which is
    /// neither the class the id names nor what the resolver writes back.
    private static JourneyRefStructure journeyRef(ServiceJourney sj) {
        JourneyRefStructure r = new JourneyRefStructure();
        r.setRef(sj.getId());
        r.setVersion(sj.getVersion());
        r.setNameOfRefClass("ServiceJourney");
        return r;
    }

    // ------------------------------------------------------------------------------------- //
    // Reading one journey
    // ------------------------------------------------------------------------------------- //

    /// A passing time in seconds since the journey's own operating-day midnight.
    ///
    /// NOT [XbCalendar#seconds], which refuses an hour-24 value. Here the after-midnight rewrite has
    /// not run yet — it is EpipToDb phase D — so `24:20:00` is the form the feed actually uses for a
    /// night train, and a reader that refuses it drops exactly the population that gets split at a
    /// border.
    static Integer seconds(TimetabledPassingTime t, boolean arriving) {
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

    /// The StopPlace a stop point is assigned to, interned. [#NONE] when nothing places it.
    private static int station(Dict stations, Map<String, String> sspToStopPlace, String sspRef) {
        String sp = sspRef == null ? null : sspToStopPlace.get(sspRef);
        return sp == null ? NONE : stations.intern(sp);
    }

    /// The journey's DayTypeRefs, joined — the key the DaySets are interned on.
    ///
    /// NEVER EMPTY. [Dict#intern] answers [#NONE] for an empty string WITHOUT assigning an index,
    /// while the caller appends to the parallel DaySet list either way — so one journey with no
    /// `<dayTypes>` would shift every later journey onto another journey's calendar, and the first
    /// candidate built from it would index the list at -1. A journey that carries no day types gets
    /// the sentinel and, through it, the empty DaySet, which meets nothing.
    private static String dayKey(ServiceJourney sj) {
        if (sj.getDayTypes() == null) return "(no day types)";
        StringBuilder b = new StringBuilder("dt:");
        for (JAXBElement<? extends DayTypeRefStructure> r : sj.getDayTypes().getDayTypeRef()) {
            b.append(r.getValue().getRef()).append(' ');
        }
        return b.toString();
    }

    private static String lineRef(ServiceJourney sj) {
        JAXBElement<? extends LineRefStructure> el = sj.getLineRef();
        return el == null ? null : el.getValue().getRef();
    }

    /// The stop-point ref of this journey's LAST call, which is what the meeting is anchored on.
    /// EpipToDb resolves the other side's own point at the same StopPlace, so only one is needed.
    private static String lastStopRef(ServiceJourney sj, Store db, Txn txn) {
        if (sj.getPassingTimes() == null) return null;
        List<TimetabledPassingTime> tt = sj.getPassingTimes().getTimetabledPassingTime();
        if (tt.isEmpty()) return null;
        List<String> refs = XbScan.stopRefs(sj, tt, new XbScan.PatternCache(db, txn));
        return refs == null ? null : refs.get(refs.size() - 1);
    }

    // ------------------------------------------------------------------------------------- //
    // The two small indexes
    // ------------------------------------------------------------------------------------- //

    /// ScheduledStopPoint ref → StopPlace ref, from the assignments. The same join
    /// [XbScan#readStations] makes, minus the country vote this pass has no use for.
    static Map<String, String> stopPlaceBySsp(Store db, Txn txn) {
        Map<String, String> out = new HashMap<>();
        for (Object o : db.iterOnlyObjects(txn, PassengerStopAssignment.class)) {
            PassengerStopAssignment psa = (PassengerStopAssignment) o;
            String ssp = XbScan.sspRef(psa);
            String sp = XbScan.stopPlaceRef(psa);
            if (ssp != null && sp != null) out.putIfAbsent(ssp, sp);
        }
        return out;
    }

    /// TrainNumber id → its normalised advertised number.
    static Map<String, String> trainNumbers(Store db, Txn txn) {
        Map<String, String> out = new HashMap<>();
        for (Object o : db.iterOnlyObjects(txn, TrainNumber.class)) {
            TrainNumber tn = (TrainNumber) o;
            out.put(tn.getId(), XbIds.normalizeTrainNumber(tn.getForAdvertisement()));
        }
        return out;
    }
}
