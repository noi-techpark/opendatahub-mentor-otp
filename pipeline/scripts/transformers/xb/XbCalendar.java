package transformers.xb;

// Calendars and the after-midnight arithmetic.
//
// ONE CALENDAR MECHANISM. Two exist upstream, split by publisher, and post-EPIP there is one:
// EpipJourneys converts every Swiss AvailabilityCondition into a DayType + UicOperatingPeriod and
// nulls the journey's validityConditions, and EpipCalendar converts the bare-date, OperatingPeriod
// and OperatingDay arms of the DayTypeAssignment union into a synthesised UicOperatingPeriod. So
// this file reads exactly one chain:
//
//     ServiceJourney.dayTypes -> DayTypeRef -> DayTypeAssignment -> OperatingPeriodRef
//                             -> UicOperatingPeriod (FromDate + ValidDayBits)
//
// AFTER MIDNIGHT. A journey running past midnight is written by one publisher as 24:30 on the
// departure date and by another as 00:30 on the following one. The rewrite that normalises the
// illegal form must run BEFORE the coupling: DayOffsetRewrite is EpipToDb phase D and this stage
// runs after EpipToDb. XbScan gates on a corpus that still carries hour-24 values, so the placement
// is checked rather than assumed.

import noi.netex.model.DayTypeAssignment;
import noi.netex.model.DayTypeRefStructure;
import noi.netex.model.OperatingPeriodRefStructure;
import noi.netex.model.ServiceJourney;
import noi.netex.model.TimetabledPassingTime;
import noi.netex.model.UicOperatingPeriod;
import noi.netex.time.XmlDateTime;
import noi.netex.time.XmlTime;
import jakarta.xml.bind.JAXBElement;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.util.Log;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class XbCalendar {

    private XbCalendar() {}

    // ------------------------------------------------------------------------------------- //

    /// A passing time as seconds since the journey's operating-day midnight, day offset folded in:
    /// `timeOfDay + 86400 * dayOffset`. The same contract `toolkit.transform.epip.CallsProfile`
    /// states for its running clock.
    ///
    /// Departure preferred over arrival, each with its OWN sibling offset element — they can differ
    /// at a stop where the train sits across midnight.
    ///
    /// Null when the passing time carries neither, which is not the same as 0: an absent time means
    /// "this leg does not say", and a leg that does not say cannot be matched on time.
    public static Integer seconds(TimetabledPassingTime t) {
        if (t == null) return null;
        Integer dep = seconds(t.getDepartureTime(), t.getDepartureDayOffset());
        return dep != null ? dep : seconds(t.getArrivalTime(), t.getArrivalDayOffset());
    }

    /// The arrival side specifically — what the LAST stop of a leg is read on.
    public static Integer arrivalSeconds(TimetabledPassingTime t) {
        if (t == null) return null;
        Integer arr = seconds(t.getArrivalTime(), t.getArrivalDayOffset());
        return arr != null ? arr : seconds(t.getDepartureTime(), t.getDepartureDayOffset());
    }

    /// One (time, sibling offset) pair. Null carrier in, null out.
    ///
    /// An UNPARSED or hour-24 carrier scores null rather than 0. Scoring 0 makes every
    /// after-midnight time compare as midnight and silently breaks the handover window on exactly
    /// the night trains this stage exists for. Post-rewrite no such value should exist at all and
    /// XbScan gates on it, so the null is what keeps a value slipping past the gate from being read
    /// as a real midnight.
    public static Integer seconds(XmlTime time, BigInteger dayOffset) {
        if (time == null || time.isUnparsed() || time.getHour() >= 24) return null;
        int tod = time.getHour() * 3600 + time.getMinute() * 60 + time.getSecond();
        int off = dayOffset == null ? 0 : dayOffset.intValueExact();
        return tod + 86400 * off;
    }

    // ------------------------------------------------------------------------------------- //

    /// The dates in J's own date space on which J at `secJ` and K at `secK` are at the same station
    /// within [XbProfile#MEETING_WINDOW_S] of each other. Empty when they never are.
    ///
    /// The arithmetic. J's stop happens at absolute second `d * 86400 + secJ` for each operating
    /// date `d`, K's at `e * 86400 + secK`. They meet when
    ///
    /// ```
    /// |(d - e) * 86400 + (secJ - secK)| <= W
    /// ```
    ///
    /// The window is 3,600 s wide and a day is 86,400, so at most one integer `k = d - e` can
    /// satisfy it — round `-(secJ - secK) / 86400` and check. If that one fails, no pairing of
    /// dates can succeed and the answer is empty without touching either date set. If it holds, the
    /// meeting dates are `D(J) & (D(K) + k)`: a bitset shift and an AND, and the shift is free
    /// because [DaySet#shifted] only moves the base.
    ///
    /// This is what makes the 24:30/00:30 split a non-event. J writes 24:30 on day d (`secJ` =
    /// 88,200 after the rewrite folds the offset in) and K writes 00:30 on day d+1 (`secK` = 1,800):
    /// the delta is exactly 86,400, `k` is -1, and `D(K) + (-1)` puts K's d+1 back on d.
    public static DaySet meetingDays(DaySet daysJ, int secJ, DaySet daysK, int secK) {
        if (daysJ.isEmpty() || daysK.isEmpty()) return DaySet.EMPTY;
        long delta = (long) secJ - secK;
        int k = (int) Math.round(-delta / 86400.0);
        if (Math.abs(k * 86400L + delta) > XbProfile.MEETING_WINDOW_S) return DaySet.EMPTY;
        return daysJ.intersect(daysK.shifted(k));
    }

    // ------------------------------------------------------------------------------------- //
    // Reading the day-type calendar.
    // ------------------------------------------------------------------------------------- //

    /// DayType id → the dates it is active on, read through the UicOperatingPeriod arm only.
    ///
    /// The other arms are recognised by TARGET ID, not by element name or nameOfRefClass — the same
    /// discrimination EpipCalendar makes, and for the same reason: a feed's `<OperatingPeriodRef>`
    /// may carry a NameOfClass that is not "UicOperatingPeriod". An assignment whose target is a
    /// plain OperatingPeriod or an OperatingDay describes days EpipDbToXml's calendar frame does not
    /// export.
    ///
    /// A DayType with several assigned periods gets their UNION. A DayType nothing assigns gets no
    /// entry at all — distinct from an entry holding an empty set — so a caller can tell "this
    /// journey's calendar is missing" from "this journey runs on no days", which are otherwise
    /// indistinguishable and both silent.
    public static Map<String, DaySet> readDayTypeDates(Store db, Txn txn) {
        Map<String, DaySet> byPeriod = new HashMap<>();
        for (Object o : db.iterOnlyObjects(txn, UicOperatingPeriod.class)) {
            UicOperatingPeriod p = (UicOperatingPeriod) o;
            byPeriod.put(p.getId(), periodDays(p));
        }
        Map<String, DaySet> byDayType = new LinkedHashMap<>();
        for (Object o : db.iterOnlyObjects(txn, DayTypeAssignment.class)) {
            DayTypeAssignment a = (DayTypeAssignment) o;
            if (a.getDayTypeRef() == null) continue;
            JAXBElement<OperatingPeriodRefStructure> el = a.getOperatingPeriodRef();
            if (el == null) continue;                       // the operating-day / bare-date arms
            DaySet days = byPeriod.get(el.getValue().getRef());
            if (days == null) continue;                     // not a UicOperatingPeriod: not exported
            String dayType = a.getDayTypeRef().getValue().getRef();
            byDayType.merge(dayType, days, DaySet::union);
        }
        Log.info("[xb] calendar: %d UicOperatingPeriods over %d DayTypes", byPeriod.size(),
                byDayType.size());
        return byDayType;
    }

    /// The dates one UicOperatingPeriod is active on.
    ///
    /// A period keyed by operating-day ref rather than by date carries no `FromDate`; it contributes
    /// nothing rather than throwing.
    static DaySet periodDays(UicOperatingPeriod p) {
        if (p.getFromOperatingDayRef() != null) return DaySet.EMPTY;
        return expandBits(p.getFromDate(), p.getValidDayBits(), p.getId());
    }

    /// `FromDate` plus a 0/1 `ValidDayBits` string, expanded to the active dates: bit *i* means
    /// `FromDate + i days`. Anything that is not `0` or `1` is stripped before indexing.
    ///
    /// A malformed `FromDate` throws — the one place in this stage where a bad value stops the run.
    /// An empty day list is exactly what a legitimately empty calendar produces, so returning one
    /// would be indistinguishable from it and every journey on that period would silently run on
    /// zero days, with no log and no counter.
    public static DaySet expandBits(XmlDateTime fromDate, String bits, String ownerId) {
        if (bits == null || bits.isEmpty()) return DaySet.EMPTY;
        if (fromDate == null) return DaySet.EMPTY;
        if (fromDate.isUnparsed()) {
            throw new IllegalStateException("[xb] unparseable operating-period FromDate \""
                    + fromDate.toXmlFormat() + "\" on " + ownerId + ": every journey on this period "
                    + "would silently run on zero days");
        }
        // The NORMALISATION BOUNDARY. The carrier's toString is the SOURCE LEXICAL, which differs
        // from the normalised value for offset-bearing and hour-24 times; toLocalDateTime is the
        // normalised one. Passing the carrier around instead compiles and is wrong.
        LocalDate from = fromDate.toLocalDateTime().toLocalDate();
        String clean = bits.replaceAll("[^01]", "");
        return DaySet.fromBits((int) from.toEpochDay(), clean);
    }

    /// The journey's operating dates: the union over its DayTypeRefs. Post-EPIP there is exactly
    /// one; the container is a list.
    ///
    /// An empty result means the journey can never match anything. That happens when EpipJourneys
    /// emitted a ref to a DayType it did not construct (its own known gap), and the caller counts
    /// it rather than refusing — this stage did not create it and cannot repair it.
    public static DaySet journeyDays(ServiceJourney sj, Map<String, DaySet> dayTypeDates) {
        if (sj.getDayTypes() == null) return DaySet.EMPTY;
        DaySet out = DaySet.EMPTY;
        for (JAXBElement<? extends DayTypeRefStructure> r : sj.getDayTypes().getDayTypeRef()) {
            DaySet d = dayTypeDates.get(r.getValue().getRef());
            if (d != null) out = out.union(d);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------- //
    // DaySet
    // ------------------------------------------------------------------------------------- //

    /// An immutable set of calendar dates, held as a bitset of epoch days with an explicit base.
    ///
    /// Shifting is free — [#shifted] rebinds the base and shares the words — which is what makes
    /// the day-offset arithmetic in [XbCalendar#meetingDays] a couple of instructions rather than a
    /// rebuild. A journey's span is about a timetable year, so the words are ~6 longs and the whole
    /// set is under 100 bytes.
    public static final class DaySet {

        /// The empty set. Its base is arbitrary and never read.
        public static final DaySet EMPTY = new DaySet(0, new long[0]);

        private final int base;      // epoch day of bit 0
        private final long[] words;

        private DaySet(int base, long[] words) {
            this.base = base;
            this.words = words;
        }

        /// From a `FromDate` epoch day and a cleaned 0/1 string.
        static DaySet fromBits(int fromEpochDay, String bits) {
            int last = bits.lastIndexOf('1');
            if (last < 0) return EMPTY;
            int first = bits.indexOf('1');
            long[] w = new long[((last - first) >> 6) + 1];
            for (int i = first; i <= last; i++) {
                if (bits.charAt(i) == '1') {
                    int b = i - first;
                    w[b >> 6] |= 1L << (b & 63);
                }
            }
            return new DaySet(fromEpochDay + first, w);
        }

        /// From explicit epoch days — the test-fixture route.
        public static DaySet of(int... epochDays) {
            if (epochDays.length == 0) return EMPTY;
            int min = epochDays[0];
            int max = epochDays[0];
            for (int d : epochDays) {
                min = Math.min(min, d);
                max = Math.max(max, d);
            }
            long[] w = new long[((max - min) >> 6) + 1];
            for (int d : epochDays) {
                int b = d - min;
                w[b >> 6] |= 1L << (b & 63);
            }
            return new DaySet(min, w);
        }

        /// From ISO date strings — the readable test-fixture route.
        public static DaySet ofDates(String... isoDates) {
            int[] days = new int[isoDates.length];
            for (int i = 0; i < isoDates.length; i++) {
                days[i] = (int) LocalDate.parse(isoDates[i]).toEpochDay();
            }
            return of(days);
        }

        /// The same set moved `k` days later. O(1): the words are shared, only the base moves.
        public DaySet shifted(int k) {
            return words.length == 0 ? EMPTY : new DaySet(base + k, words);
        }

        public boolean isEmpty() {
            for (long w : words) {
                if (w != 0) return false;
            }
            return true;
        }

        public boolean contains(int epochDay) {
            int b = epochDay - base;
            if (b < 0 || (b >> 6) >= words.length) return false;
            return (words[b >> 6] & (1L << (b & 63))) != 0;
        }

        public int size() {
            int n = 0;
            for (long w : words) n += Long.bitCount(w);
            return n;
        }

        /// The earliest date, as an epoch day; -1 when empty. Used only for deterministic reporting.
        public int firstDay() {
            for (int i = 0; i < words.length; i++) {
                if (words[i] != 0) return base + (i << 6) + Long.numberOfTrailingZeros(words[i]);
            }
            return -1;
        }

        public DaySet intersect(DaySet other) {
            if (isEmpty() || other.isEmpty()) return EMPTY;
            int lo = Math.max(base, other.base);
            int hi = Math.min(base + (words.length << 6), other.base + (other.words.length << 6));
            if (hi <= lo) return EMPTY;
            long[] w = new long[((hi - lo - 1) >> 6) + 1];
            for (int d = lo; d < hi; d++) {
                if (contains(d) && other.contains(d)) {
                    int b = d - lo;
                    w[b >> 6] |= 1L << (b & 63);
                }
            }
            DaySet out = new DaySet(lo, w);
            return out.isEmpty() ? EMPTY : out;
        }

        public boolean intersects(DaySet other) {
            return !intersect(other).isEmpty();
        }

        public DaySet union(DaySet other) {
            if (isEmpty()) return other;
            if (other.isEmpty()) return this;
            int lo = Math.min(base, other.base);
            int hi = Math.max(base + (words.length << 6), other.base + (other.words.length << 6));
            long[] w = new long[((hi - lo - 1) >> 6) + 1];
            for (int d = lo; d < hi; d++) {
                if (contains(d) || other.contains(d)) {
                    int b = d - lo;
                    w[b >> 6] |= 1L << (b & 63);
                }
            }
            return new DaySet(lo, w);
        }

        /// Is every date of `other` in this set? The containment the redundancy test refuses by
        /// default on.
        public boolean containsAll(DaySet other) {
            if (other.isEmpty()) return false;   // "covers nothing" is never "covers everything"
            int lo = other.base;
            int hi = other.base + (other.words.length << 6);
            for (int d = lo; d < hi; d++) {
                if (other.contains(d) && !contains(d)) return false;
            }
            return true;
        }

        /// The dates as ISO strings, sorted — reporting and test assertions only.
        public List<String> toIsoDates() {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < (words.length << 6); i++) {
                if (contains(base + i)) out.add(LocalDate.ofEpochDay(base + i).toString());
            }
            return out;
        }

        @Override
        public String toString() {
            return isEmpty() ? "{}" : size() + " days from " + LocalDate.ofEpochDay(firstDay());
        }
    }
}
