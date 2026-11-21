package tools;

// How far into the future does each source feed's timetable reach? A diagnostic: reads only,
// changes nothing, needs no store and no toolkit classes.
//
// A feed whose timetable has ended still loads without complaint, so its services quietly drop out
// of the graph -- the FlixBus export from 11 May 2026 ran out on 10 November and nothing said so.
// This reads each file in input/ and reports the last day any service runs on.
//
// The service calendar is what counts, not a frame's ValidBetween, which publishers set far into
// the future. The last service day is the latest of:
//   - OperatingPeriod / UicOperatingPeriod  ToDate
//   - OperatingDay                          CalendarDate
//   - DayTypeAssignment                     Date
//   - any element with FromDate + ValidDayBits (the Swiss export's AvailabilityConditions):
//     FromDate plus the position of the last 1 bit
//
// Usage: FeedFreshness [--today YYYY-MM-DD] [--min-days N] <file>...
//   Files may be .xml, .xml.gz / .gz or .zip (every .xml entry is read). Anything else is skipped.
//   With N > 0, exit 1 when a feed reaches fewer than N days ahead (an ended one included).
//   N = 0, the default, only reports and exits 0. Exit 2 on a usage or read error.

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class FeedFreshness {

    /// The last service day found in one file, or null when it holds no service calendar.
    public record Result(Path file, LocalDate lastServiceDay) {}

    public static void main(String[] args) {
        LocalDate today = LocalDate.now();
        long minDays = 0;
        List<Path> files = new ArrayList<>();
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--today" -> today = LocalDate.parse(args[++i]);
                    case "--min-days" -> minDays = Long.parseLong(args[++i]);
                    default -> files.add(Path.of(args[i]));
                }
            }
        } catch (RuntimeException e) {
            System.err.println("usage: FeedFreshness [--today YYYY-MM-DD] [--min-days N] <file>...");
            System.exit(2);
        }

        int short_ = 0;
        System.out.printf("%-48s %-12s %s%n", "feed", "last day", "days left");
        for (Path f : files) {
            if (!isFeed(f)) continue;
            Result r;
            try {
                r = scan(f);
            } catch (IOException | XMLStreamException e) {
                System.err.println(f.getFileName() + ": cannot read: " + e.getMessage());
                System.exit(2);
                return;
            }
            String last = r.lastServiceDay() == null ? "-" : r.lastServiceDay().toString();
            String left;
            if (r.lastServiceDay() == null) {
                left = "no service calendar";
            } else {
                long d = ChronoUnit.DAYS.between(today, r.lastServiceDay());
                left = d < 0 ? "ENDED " + (-d) + " days ago" : Long.toString(d);
                if (minDays > 0 && d < minDays) {
                    left += "   <-- under " + minDays;
                    short_++;
                }
            }
            System.out.printf("%-48s %-12s %s%n", f.getFileName(), last, left);
        }
        if (short_ > 0) {
            System.out.println(short_ + " feed(s) end within " + minDays + " days");
            System.exit(1);
        }
    }

    static boolean isFeed(Path f) {
        String n = f.getFileName().toString().toLowerCase();
        return Files.isRegularFile(f) && (n.endsWith(".xml") || n.endsWith(".gz") || n.endsWith(".zip"));
    }

    /// Reads one file and returns the last service day across every NeTEx document in it.
    public static Result scan(Path f) throws IOException, XMLStreamException {
        String n = f.getFileName().toString().toLowerCase();
        LocalDate last = null;
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(f))) {
            if (n.endsWith(".zip")) {
                ZipInputStream zip = new ZipInputStream(raw);
                ZipEntry e;
                while ((e = zip.getNextEntry()) != null) {
                    if (e.isDirectory() || !e.getName().toLowerCase().endsWith(".xml")) continue;
                    last = later(last, lastServiceDay(zip));
                }
            } else if (n.endsWith(".gz")) {
                last = lastServiceDay(new GZIPInputStream(raw));
            } else {
                last = lastServiceDay(raw);
            }
        }
        return new Result(f, last);
    }

    /// One open element: its name, and the FromDate / ValidDayBits children seen so far.
    private static final class Open {
        final String name;
        LocalDate from;
        String bits;
        Open(String name) { this.name = name; }
    }

    /// Streams one NeTEx document. Leaves `in` open, so a zip can move on to its next entry.
    public static LocalDate lastServiceDay(InputStream in) throws XMLStreamException {
        XMLInputFactory xf = XMLInputFactory.newFactory();
        xf.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        xf.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        XMLStreamReader r = xf.createXMLStreamReader(new NonClosing(in));
        Deque<Open> path = new ArrayDeque<>();
        LocalDate last = null;
        try {
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String name = r.getLocalName();
                    Open parent = path.peek();
                    String parentName = parent == null ? null : parent.name;
                    // Leaf elements whose text we want: read it here, which consumes the end tag.
                    if (isServiceDate(parentName, name)) {
                        last = later(last, parseDate(r.getElementText()));
                    } else if (parent != null && name.equals("FromDate")) {
                        parent.from = parseDate(r.getElementText());
                    } else if (parent != null && name.equals("ValidDayBits")) {
                        parent.bits = r.getElementText().trim();
                    } else {
                        path.push(new Open(name));
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    Open closed = path.pop();
                    last = later(last, lastBitDay(closed.from, closed.bits));
                }
            }
        } finally {
            r.close();
        }
        return last;
    }

    /// A period given as a start date plus one bit per day (the Swiss export's AvailabilityConditions,
    /// or a UicOperatingPeriod): its last service day is the start plus the position of the last 1.
    static LocalDate lastBitDay(LocalDate from, String bits) {
        if (from == null || bits == null) return null;
        int i = bits.lastIndexOf('1');
        return i < 0 ? null : from.plusDays(i);
    }

    static boolean isServiceDate(String parent, String name) {
        if (parent == null) return false;
        return switch (name) {
            case "ToDate" -> parent.equals("OperatingPeriod") || parent.equals("UicOperatingPeriod");
            case "CalendarDate" -> parent.equals("OperatingDay");
            case "Date" -> parent.equals("DayTypeAssignment");
            default -> false;
        };
    }

    /// Accepts a date or a date-time ("2026-11-10" or "2026-11-10T00:00:00"); anything else is ignored.
    static LocalDate parseDate(String s) {
        String t = s.trim();
        if (t.length() < 10) return null;
        try {
            return LocalDate.parse(t.substring(0, 10));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static LocalDate later(LocalDate a, LocalDate b) {
        if (a == null) return b;
        if (b == null) return a;
        return b.isAfter(a) ? b : a;
    }

    /// StAX closes the stream it reads; a zip entry must stay open for the next one.
    private static final class NonClosing extends java.io.FilterInputStream {
        NonClosing(InputStream in) { super(in); }
        @Override public void close() {}
    }
}
