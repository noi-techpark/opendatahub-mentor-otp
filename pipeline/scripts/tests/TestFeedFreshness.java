package tests;

// tools.FeedFreshness reports the last day a feed's timetable runs on. It must read the service
// calendar (OperatingPeriod, OperatingDay, DayTypeAssignment) and ignore validity dates that say
// nothing about service, such as a frame's ValidBetween.

import tools.FeedFreshness;
import toolkit.test.Check;
import toolkit.test.Runner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class TestFeedFreshness {

    public static void main(String[] args) {
        Runner.run(TestFeedFreshness.class);
    }

    private static final String NS = "xmlns=\"http://www.netex.org.uk/netex\"";

    private static String doc(String body) {
        return "<?xml version=\"1.0\"?><PublicationDelivery " + NS + "><dataObjects>"
                + "<CompositeFrame><ValidBetween><FromDate>2026-01-01T00:00:00</FromDate>"
                + "<ToDate>2099-12-31T00:00:00</ToDate></ValidBetween>"
                + body + "</CompositeFrame></dataObjects></PublicationDelivery>";
    }

    private static LocalDate last(String xml) throws Exception {
        return FeedFreshness.lastServiceDay(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    /// The FlixBus shape: UicOperatingPeriods, with a frame ValidBetween far beyond them.
    public static void testOperatingPeriodsNotValidBetween() throws Exception {
        String xml = doc("<ServiceCalendarFrame><operatingPeriods>"
                + "<UicOperatingPeriod><FromDate>2026-05-10T00:00:00</FromDate><ToDate>2026-11-10T00:00:00</ToDate></UicOperatingPeriod>"
                + "<OperatingPeriod><FromDate>2026-05-10</FromDate><ToDate>2026-07-31</ToDate></OperatingPeriod>"
                + "</operatingPeriods></ServiceCalendarFrame>");
        Check.equals(LocalDate.of(2026, 11, 10), last(xml), "latest operating period end, not 2099");
    }

    public static void testOperatingDaysAndAssignments() throws Exception {
        String xml = doc("<ServiceCalendarFrame>"
                + "<operatingDays><OperatingDay><CalendarDate>2026-12-12</CalendarDate></OperatingDay></operatingDays>"
                + "<dayTypeAssignments><DayTypeAssignment><Date>2026-12-13</Date></DayTypeAssignment></dayTypeAssignments>"
                + "</ServiceCalendarFrame>");
        Check.equals(LocalDate.of(2026, 12, 13), last(xml), "latest of operating days and assigned dates");
    }

    /// The Swiss shape: AvailabilityConditions with a FromDate and one bit per day. The last 1 bit
    /// is the last service day; a ToDate beside the bits, or a holiday DayTypeAssignment earlier in
    /// the year, must not decide it.
    public static void testValidDayBits() throws Exception {
        String xml = doc("<ServiceCalendarFrame>"
                + "<ServiceCalendar><FromDate>2025-12-14</FromDate><ToDate>2026-12-12</ToDate>"
                + "<dayTypeAssignments><DayTypeAssignment><Date>2026-12-08</Date></DayTypeAssignment></dayTypeAssignments>"
                + "</ServiceCalendar>"
                + "<validityConditions>"
                + "<AvailabilityCondition><FromDate>2025-12-14T00:00:00</FromDate><ToDate>2026-12-12T23:59:59</ToDate>"
                + "<ValidDayBits>0011100</ValidDayBits></AvailabilityCondition>"
                + "<AvailabilityCondition><FromDate>2026-12-01T00:00:00</FromDate>"
                + "<ValidDayBits>000000000001000</ValidDayBits></AvailabilityCondition>"
                + "<AvailabilityCondition><FromDate>2026-12-20</FromDate><ValidDayBits>0000</ValidDayBits></AvailabilityCondition>"
                + "</validityConditions></ServiceCalendarFrame>");
        Check.equals(LocalDate.of(2026, 12, 12), last(xml), "1 Dec + 11 days; an all-zero period adds nothing");
    }

    /// A ToDate outside an operating period (here a frame's ValidBetween) is not a service day.
    public static void testNoServiceCalendar() throws Exception {
        Check.isNull(last(doc("")), "a feed with no service calendar has no last day");
    }

    public static void testZipGzipAndPlain() throws Exception {
        String a = doc("<UicOperatingPeriod><ToDate>2026-11-10</ToDate></UicOperatingPeriod>");
        String b = doc("<OperatingDay><CalendarDate>2026-12-01</CalendarDate></OperatingDay>");
        Path dir = Files.createTempDirectory("feedfreshness");
        try {
            Path zip = dir.resolve("feed.zip");
            try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(out)) {
                z.putNextEntry(new ZipEntry("a.xml"));
                z.write(a.getBytes(StandardCharsets.UTF_8));
                z.putNextEntry(new ZipEntry("readme.txt"));
                z.write("<ToDate>2099-01-01</ToDate>".getBytes(StandardCharsets.UTF_8));
                z.putNextEntry(new ZipEntry("b.xml"));
                z.write(b.getBytes(StandardCharsets.UTF_8));
            }
            Check.equals(LocalDate.of(2026, 12, 1), FeedFreshness.scan(zip).lastServiceDay(),
                    "every .xml entry of a zip is read, other entries are not");

            Path gz = dir.resolve("feed.xml.gz");
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (GZIPOutputStream g = new GZIPOutputStream(buf)) {
                g.write(a.getBytes(StandardCharsets.UTF_8));
            }
            Files.write(gz, buf.toByteArray());
            Check.equals(LocalDate.of(2026, 11, 10), FeedFreshness.scan(gz).lastServiceDay(), "gzip");

            Path xml = dir.resolve("feed.xml");
            Files.writeString(xml, b);
            Check.equals(LocalDate.of(2026, 12, 1), FeedFreshness.scan(xml).lastServiceDay(), "plain xml");
        } finally {
            try (var s = Files.walk(dir)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
}
