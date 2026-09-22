package tests;

// The time and calendar readers.
//
// The day of an after-midnight passing time lives in a SIBLING element (<DepartureDayOffset>), so a
// reader that takes only the time carrier cannot see it.

import noi.netex.model.ObjectFactory;
import noi.netex.model.TimetabledPassingTime;
import noi.netex.time.XmlDateTime;
import noi.netex.time.XmlTime;
import toolkit.test.Check;
import toolkit.test.Runner;
import transformers.xb.XbCalendar;
import transformers.xb.XbCalendar.DaySet;

import java.math.BigInteger;
import java.util.List;

public class TestXbCalendar {

    public static void main(String[] args) {
        Runner.run(TestXbCalendar.class);
    }

    /// The sibling offset is worth 86,400 seconds and the reader has to add it.
    public static void testTheDayOffsetIsAddedToEveryTime() {
        Check.equals(1200, XbCalendar.seconds(XmlTime.parse("00:20:00"), null),
                "no offset: twenty past midnight, this operating day");
        Check.equals(87600, XbCalendar.seconds(XmlTime.parse("00:20:00"), BigInteger.ONE),
                "offset 1: the SAME clock time, the next day -- and not the same value");
        Check.equals(1200 + 2 * 86400, XbCalendar.seconds(XmlTime.parse("00:20:00"),
                BigInteger.TWO), "a multi-day offset is not capped at one");
        Check.equals(86399, XbCalendar.seconds(XmlTime.parse("23:59:59"), null),
                "the last second of the operating day");
    }

    /// An illegal value scores NULL, not 0. Null propagates as "this leg does not say", and a leg
    /// that does not say cannot be matched.
    public static void testAnIllegalHourScoresNullNotMidnight() {
        Check.isNull(XbCalendar.seconds(XmlTime.parse("24:20:00"), null),
                "an hour past the boundary is not a time this stage can use");
        Check.isNull(XbCalendar.seconds(XmlTime.parse("24:00:00"), null),
                "nor is the legal-but-day-losing midnight-end-of-day form");
        Check.isNull(XbCalendar.seconds(XmlTime.parse("30:05:00"), null), "nor a multi-day one");
        Check.isNull(XbCalendar.seconds(null, null), "an absent element is absent");
    }

    /// Departure wins over arrival, and each carries its own offset — a train can arrive before
    /// midnight and leave after it.
    public static void testDepartureWinsAndCarriesItsOwnOffset() {
        ObjectFactory factory = new ObjectFactory();
        TimetabledPassingTime t = new TimetabledPassingTime();
        t.setArrivalTime(XmlTime.parse("23:55:00"));
        t.setDepartureTime(XmlTime.parse("00:05:00"));
        t.setDepartureDayOffset(BigInteger.ONE);
        Check.equals(86400 + 300, XbCalendar.seconds(t),
                "the departure and ITS offset, not the arrival");
        Check.equals(86100, XbCalendar.arrivalSeconds(t),
                "and the arrival side reads its own, which here has no offset");

        TimetabledPassingTime arrivalOnly = new TimetabledPassingTime();
        arrivalOnly.setArrivalTime(XmlTime.parse("21:20:00"));
        Check.equals(76800, XbCalendar.seconds(arrivalOnly),
                "a terminus with no departure falls back to the arrival");
        Check.that(factory != null, "the model factory loads");
    }

    /// A malformed FromDate must THROW, not return an empty list — which is what a legitimately
    /// empty calendar produces.
    public static void testAMalformedFromDateThrows() {
        boolean threw = false;
        try {
            XbCalendar.expandBits(XmlDateTime.parse("05/01/2026"), "111", "op:1");
        } catch (IllegalStateException e) {
            threw = true;
            Check.that(e.getMessage().contains("05/01/2026"), "and names the lexeme: " + e.getMessage());
            Check.that(e.getMessage().contains("op:1"), "and the period it was on");
        }
        Check.that(threw, "a lexeme LocalDate.parse refuses is a defect, not an empty calendar");
    }

    /// An ABSENT FromDate is not malformed: a period keyed by operating-day ref carries none, and it
    /// contributes no dates rather than throwing.
    public static void testAnAbsentFromDateIsEmptyNotFatal() {
        Check.that(XbCalendar.expandBits(null, "111", "op:1").isEmpty(),
                "no from-date, no dates");
        Check.that(XbCalendar.expandBits(XmlDateTime.parse("2026-01-05T00:00:00"), null, "op:1")
                .isEmpty(), "no bits, no dates");
    }

    /// Bit *i* means FromDate + i days, and anything that is not 0 or 1 is stripped first.
    public static void testBitsExpandToDates() {
        DaySet d = XbCalendar.expandBits(XmlDateTime.parse("2026-01-05T00:00:00"), "1011", "op:1");
        Check.equals(List.of("2026-01-05", "2026-01-07", "2026-01-08"), d.toIsoDates(),
                "bit i is FromDate + i days");
        DaySet spaced = XbCalendar.expandBits(XmlDateTime.parse("2026-01-05T00:00:00"),
                "10 11\n", "op:1");
        Check.equals(d.toIsoDates(), spaced.toIsoDates(), "whitespace in the bit string is stripped");
    }

    /// The set operations the matching and the redundancy test rest on.
    public static void testDaySetOperations() {
        DaySet week = DaySet.ofDates("2026-01-05", "2026-01-06", "2026-01-07");
        DaySet two = DaySet.ofDates("2026-01-06", "2026-01-07");
        Check.that(week.containsAll(two), "a superset covers a subset");
        Check.that(!two.containsAll(week), "and not the other way round");
        Check.that(!week.containsAll(DaySet.EMPTY),
                "covering NOTHING is never covering everything -- the redundancy test refuses by "
                + "default, and an empty date set is the case it must not read as covered");
        Check.equals(two.toIsoDates(), week.intersect(two).toIsoDates(), "intersection");
        Check.equals(week.toIsoDates(), week.union(two).toIsoDates(), "union");
        Check.equals(List.of("2026-01-06", "2026-01-07", "2026-01-08"),
                week.shifted(1).toIsoDates(), "shifting moves every date");
        Check.equals(week.toIsoDates(), week.shifted(3).shifted(-3).toIsoDates(),
                "and shifting back is the identity");
    }
}
