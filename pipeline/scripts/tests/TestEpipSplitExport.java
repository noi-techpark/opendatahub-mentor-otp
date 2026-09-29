package tests;

// The EPIP split export. The stay-seated link and the ServiceLink are fixture data.
//
// The zip is laid out on OTP's three file levels:
//
//   shared-*.xml     indexed for the whole build -- no journeys, no patterns, no ServiceLinks
//   gSS-shared.xml   the SHARD's ServiceLinks + interchanges, indexed for the whole group
//   gSS-NNNN.xml     patterns + their journeys, index dropped per file -- and nothing else
//
// --journeys-per-file is a bound, so two patterns sharing a link land in different entries at 1. A
// pattern and all of its journeys stay in one entry: TripPatternMapper indexes a pattern's journeys
// from one file's localValues() only. Every entry is an independent, well-formed
// PublicationDelivery; no journey is lost or doubled; no z-links entry at all. The second method
// pins colocation across a multi-shard fan-out (shards exceeding the pattern count, empty shards,
// ref-less orphans).
//
// Assertions stay structural: a byte-copy export does not control attribute order or per-fragment
// ns declarations. The PublicationTimestamp is the exception -- it is the exporter's own, not a
// fragment's, and --publication-timestamp pins it, so the last method asserts it exactly.

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import toolkit.harness.DbToDb;
import toolkit.test.Check;
import toolkit.test.Runner;
import toolkit.test.TestStore;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class TestEpipSplitExport {

    static final String NS = "http://www.netex.org.uk/netex";

    /// A numbered group entry, and a shared group entry.
    static final String NUMBERED = "g\\d\\d-\\d{4}\\.xml";
    static final String SHARED_GROUP = "g\\d\\d-shared\\.xml";

    public static void main(String[] args) {
        Runner.run(TestEpipSplitExport.class);
    }

    /// One exported zip: the entry names in PHYSICAL order (the order the exporter generated them
    /// in), and every entry parsed, keyed by name and sorted by it.
    private record Zip(List<String> order, Map<String, Document> byName) {}

    /// source db (verbatim fixture) -> split zip, every entry parsed (parsing IS the
    /// well-formedness assertion).
    private static Zip export(TestStore ts, String... exportArgs) throws Exception {
        Path linked = ts.buildDbFile(NETEX, "source.lmdb");
        Path out = ts.tempPath("split.zip");
        List<String> argv = new ArrayList<>(List.of(linked.toString(), out.toString()));
        argv.addAll(List.of(exportArgs));
        conv.EpipDbToXml.main(argv.toArray(new String[0]));

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        List<String> order = new ArrayList<>();
        Map<String, Document> entries = new TreeMap<>();
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(out))) {
            for (ZipEntry e; (e = zin.getNextEntry()) != null; ) {
                byte[] bytes = zin.readAllBytes();
                order.add(e.getName());
                entries.put(e.getName(),
                        dbf.newDocumentBuilder().parse(new ByteArrayInputStream(bytes)));
            }
        }
        return new Zip(order, entries);
    }

    /// All descendants of `localName` in the NeTEx namespace.
    private static List<Element> iter(Document doc, String localName) {
        NodeList nl = doc.getElementsByTagNameNS(NS, localName);
        List<Element> out = new ArrayList<>();
        for (int i = 0; i < nl.getLength(); i++) {
            out.add((Element) nl.item(i));
        }
        return out;
    }

    private static List<String> ids(Document doc, String localName) {
        return iter(doc, localName).stream().map(e -> e.getAttribute("id")).toList();
    }

    /// First DIRECT child element `localName` of `e`.
    private static Element directChild(Element e, String localName) {
        for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element el && NS.equals(el.getNamespaceURI())
                    && localName.equals(el.getLocalName())) {
                return el;
            }
        }
        return null;
    }

    public static void testSplitZipLayoutAndContent(TestStore ts) throws Exception {
        Zip zip = export(ts, "--shards", "1", "--journeys-per-file", "1");
        Map<String, Document> entries = zip.byName();
        List<String> names = List.copyOf(entries.keySet());
        // The exporter writes entries in their final sorted order and buffers nothing.
        // gSS-shared.xml is emitted after its shard's numbered files and has to sort after them too
        // ('0' is 0x30, 's' is 0x73).
        Check.equals(zip.order().stream().sorted().toList(), zip.order(),
                "generation order == name-sorted order: nothing is buffered");
        List<String> groups = names.stream().filter(nm -> nm.matches(NUMBERED)).toList();
        Check.equals(3, groups.size(), "3 journeys at 1 journey/file -> 3 numbered group entries");
        Check.that(names.contains("g00-shared.xml"),
                "the shard's links and interchanges get their own shared group entry");
        Check.that(!names.contains("z-links.xml"), "no z-links entry: its two halves moved");
        Check.that(names.contains("shared-04-links.xml"),
                "trainNumbers are shared: every group's journeys reference them");
        Check.that(names.stream().anyMatch(nm -> nm.startsWith("shared-02-sites")), "shared-02-sites present");
        Check.that(names.stream().anyMatch(nm -> nm.startsWith("shared-03-network")), "shared-03-network present");
        Check.that(names.stream().anyMatch(nm -> nm.startsWith("shared-05-calendar")), "shared-05-calendar present");
        Check.equals(List.of("ch:Tn:25"), ids(entries.get("shared-04-links.xml"), "TrainNumber"),
                "shared-04 carries the train numbers");

        // The ServiceLink is emitted once, in its shard's shared group entry, and nowhere else: not
        // in a numbered entry, not in shared-03 (which has no serviceLinks container) and not in
        // the shared-06 closure (ServiceLink is an OTHER_REFERENCED class, so skipExisting drops it
        // there). OTP indexes the shared group entry for the whole group, and ServiceLinkMapper
        // resolves through the parent index, so every numbered entry of the shard still sees it.
        String linkEntry = null;
        for (Map.Entry<String, Document> en : entries.entrySet()) {
            if (!ids(en.getValue(), "ServiceLink").isEmpty()) {
                Check.that(linkEntry == null, "IT:Sl:1 emitted exactly once");
                linkEntry = en.getKey();
            }
        }
        Check.equals("g00-shared.xml", linkEntry,
                "the shard's ServiceLinks live in its shared group entry, not with a pattern");
        Check.that(!patternGroup(entries, "IT:Pat:1").equals(patternGroup(entries, "IT:Pat:2")),
                "a shared ServiceLink no longer forces two patterns into one entry");

        List<String> journeys = new ArrayList<>();
        List<String> interchanges = new ArrayList<>();
        String interchangeEntry = null;
        for (Map.Entry<String, Document> en : entries.entrySet()) {
            String name = en.getKey();
            Document doc = en.getValue();
            Check.equals("PublicationDelivery", doc.getDocumentElement().getLocalName(),
                    name + ": entry is an independent PublicationDelivery");
            List<Element> sjEls = iter(doc, "ServiceJourney");
            List<String> sjs = sjEls.stream().map(e -> e.getAttribute("id")).toList();
            List<String> sjis = ids(doc, "ServiceJourneyInterchange");
            Set<String> patternIds = new HashSet<>(ids(doc, "ServiceJourneyPattern"));
            if (name.matches(NUMBERED)) {
                // Only a single pattern with more journeys than the bound can overshoot it, since
                // one pattern cannot be split; the fixture has no such pattern.
                Check.that(!sjs.isEmpty(), name + ": a group entry is never empty of journeys");
                Check.that(sjs.size() <= 1, name + ": journeys-per-file is a bound, not a target");
                Check.equals(List.of(), ids(doc, "ServiceLink"),
                        name + ": links moved up to the shared group entry");
                for (Element e : sjEls) {
                    Element ref = directChild(e, "ServiceJourneyPatternRef");
                    if (ref != null) {
                        Check.that(patternIds.contains(ref.getAttribute("ref")),
                                name + ": journey's pattern colocated in the same entry");
                    }
                }
            } else if (name.matches(SHARED_GROUP)) {
                // Journeys and patterns here would sit in OTP's index for the entire group.
                Check.that(!ids(doc, "ServiceLink").isEmpty() || !sjis.isEmpty(),
                        name + ": a shared group entry is never empty");
                Check.equals(List.of(), sjs, name + ": no journeys in a shared group entry");
                Check.equals(Set.of(), patternIds, name + ": patterns live with their journeys");
            } else {
                Check.equals(List.of(), sjs, name + ": journeys ONLY in group entries");
                Check.equals(Set.of(), patternIds, name + ": patterns live with their journeys");
            }
            if (!sjis.isEmpty()) {
                Check.that(name.matches(SHARED_GROUP),
                        name + ": interchanges live in a gSS-shared.xml and nowhere else");
                interchangeEntry = name;
            }
            journeys.addAll(sjs);
            interchanges.addAll(sjis);
        }
        Check.equals(List.of("IT:Sj:1", "IT:Sj:2", "ch:Sj:1"), journeys.stream().sorted().toList(),
                "every journey exported, exactly once");
        Check.equals(List.of("IT-CH:ServiceJourneyInterchange:25:IT_Sj_1:ch_Sj_1"), interchanges,
                "the one stay-seated link");
        // OTP maps interchanges at the end of the whole group (NetexMapper.performGroupMapping), by
        // which point every journey of the shard is a mapped trip, so within a shard the placement
        // is free. The shard is not: a link whose two journeys landed in different shards is a
        // silently dropped transfer.
        Check.equals("g00-shared.xml", interchangeEntry,
                "the interchange sits in the shared group entry of its shard");
    }

    /// The single group entry holding `patternId`.
    private static String patternGroup(Map<String, Document> entries, String patternId) {
        for (Map.Entry<String, Document> en : entries.entrySet()) {
            if (ids(en.getValue(), "ServiceJourneyPattern").contains(patternId)) return en.getKey();
        }
        return null;
    }

    /// The colocation invariant across a multi-shard fan-out: every pattern is emitted in exactly
    /// one group entry, together with all the journeys that reference it. Shards exceeding the
    /// pattern count (empty shards) and ref-less orphan journeys must neither lose, double, nor
    /// separate anything.
    public static void testPatternJourneyColocationAcrossShards(TestStore ts) throws Exception {
        Map<String, Document> entries =
                export(ts, "--journeys-per-file", "1000", "--shards", "4").byName();

        Map<String, String> patternEntry = new LinkedHashMap<>();   // pattern id -> the ONE entry
        Map<String, String[]> journeyEntry = new LinkedHashMap<>(); // journey id -> {entry, ref|null}
        for (Map.Entry<String, Document> en : entries.entrySet()) {
            String name = en.getKey();
            if (!name.matches(NUMBERED)) {
                continue;
            }
            for (Element e : iter(en.getValue(), "ServiceJourneyPattern")) {
                Check.that(!patternEntry.containsKey(e.getAttribute("id")),
                        e.getAttribute("id") + " never doubled across entries");
                patternEntry.put(e.getAttribute("id"), name);
            }
            for (Element e : iter(en.getValue(), "ServiceJourney")) {
                Element ref = directChild(e, "ServiceJourneyPatternRef");
                journeyEntry.put(e.getAttribute("id"),
                        new String[] {name, ref == null ? null : ref.getAttribute("ref")});
            }
        }

        for (Map.Entry<String, String[]> je : journeyEntry.entrySet()) {
            String ref = je.getValue()[1];
            if (ref != null) {
                Check.equals(je.getValue()[0], patternEntry.get(ref),
                        je.getKey() + " split from its pattern");
            }
        }
        // ch:Sj:1 is the ref-less orphan.
        Check.equals(List.of("IT:Sj:1", "IT:Sj:2", "ch:Sj:1"),
                journeyEntry.keySet().stream().sorted().toList(), "nothing lost in the fan-out");
        // A ServiceLink is in exactly one shared group entry and in no numbered one: the union-find
        // keeps a link's patterns inside one shard, so the link is written once even though every
        // shard has its own shared entry.
        Map<String, String> linkEntries = new LinkedHashMap<>();
        for (Map.Entry<String, Document> en : entries.entrySet()) {
            List<String> linkIds = ids(en.getValue(), "ServiceLink");
            if (en.getKey().matches(NUMBERED)) {
                Check.equals(List.of(), linkIds, en.getKey() + ": no links in a numbered entry");
                continue;
            }
            if (!en.getKey().matches(SHARED_GROUP)) {
                continue;
            }
            for (String id : linkIds) {
                String prev = linkEntries.put(id, en.getKey());
                Check.that(prev == null, id + " emitted in both " + prev + " and " + en.getKey());
            }
        }
        // The bin-packing puts the single component in shard 0, so shards 1-3 are empty and emit no
        // shared group entry: an empty one would be a PublicationDelivery with no frame in it.
        Check.equals(List.of("g00-shared.xml"),
                entries.keySet().stream().filter(nm -> nm.matches(SHARED_GROUP)).toList(),
                "an empty shard emits no shared group entry");
        Set<String> referenced = journeyEntry.values().stream()
                .map(v -> v[1]).filter(Objects::nonNull).collect(Collectors.toSet());
        Check.equals(referenced, patternEntry.keySet(),
                "exactly the referenced patterns emitted");
    }

    /// The envelope is repeated per entry, so the values the pipeline passes have to reach ALL of
    /// them, and the timestamp has to be the same in all of them: `open()` runs per entry, and a
    /// clock read there would give each one its own.
    ///
    /// This is the multi-entry half of the check. The toolkit's own TestDbToZip pins the flag
    /// plumbing; only a split export can show that eleven entries agree.
    public static void testTheEnvelopeIsPinnedAcrossEveryEntry(TestStore ts) throws Exception {
        Zip zip = export(ts, "--shards", "1", "--journeys-per-file", "1",
                "--participant-ref", "NOI", "--description", "a pinned delivery",
                "--publication-timestamp", "2026-01-01T00:00:00", "--frame-version", "20260101");

        Set<String> stamps = new TreeSet<>();
        Set<String> participants = new TreeSet<>();
        for (Map.Entry<String, Document> e : zip.byName().entrySet()) {
            Document doc = e.getValue();
            Check.equals(1, iter(doc, "PublicationTimestamp").size(),
                    e.getKey() + ": one PublicationTimestamp");
            stamps.add(iter(doc, "PublicationTimestamp").get(0).getTextContent());
            participants.add(iter(doc, "ParticipantRef").get(0).getTextContent());
            Check.equals("20260101",
                    iter(doc, "CompositeFrame").get(0).getAttribute("version"),
                    e.getKey() + ": the composite frame carries the pinned version");
        }
        Check.that(zip.byName().size() > 1, "more than one entry, so agreement means something");
        Check.equals(Set.of("2026-01-01T00:00"), stamps,
                "every entry carries the SAME pinned timestamp, verbatim");
        Check.equals(Set.of("NOI"), participants, "and the same ParticipantRef");
    }

    static final String NETEX = """
<ServiceFrame id="sf" version="1">
  <serviceLinks>
    <ServiceLink id="IT:Sl:1" version="1"><Distance>100</Distance><FromPointRef ref="830000020" version="1"/><ToPointRef ref="830000010" version="1"/></ServiceLink>
  </serviceLinks>
  <journeyPatterns>
    <ServiceJourneyPattern id="IT:Pat:1" version="1"><pointsInSequence>
      <StopPointInJourneyPattern id="ip0" version="1" order="1"><ScheduledStopPointRef ref="830000020" version="1"/><OnwardServiceLinkRef ref="IT:Sl:1" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="ip1" version="1" order="2"><ScheduledStopPointRef ref="830000010" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="ip2" version="1" order="2"><ScheduledStopPointRef ref="850000300" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="ip3" version="1" order="3"><ScheduledStopPointRef ref="850000400" version="1"/></StopPointInJourneyPattern>
    </pointsInSequence></ServiceJourneyPattern>
    <ServiceJourneyPattern id="IT:Pat:2" version="1"><pointsInSequence>
      <StopPointInJourneyPattern id="jp0" version="1" order="1"><ScheduledStopPointRef ref="830000020" version="1"/><OnwardServiceLinkRef ref="IT:Sl:1" version="1"/></StopPointInJourneyPattern>
      <StopPointInJourneyPattern id="jp1" version="1" order="2"><ScheduledStopPointRef ref="830000010" version="1"/></StopPointInJourneyPattern>
    </pointsInSequence></ServiceJourneyPattern>
  </journeyPatterns>
  <validityConditions>
    <AvailabilityCondition id="ac1" version="1"><FromDate>2026-01-05T00:00:00</FromDate><ToDate>2026-01-06T00:00:00</ToDate><ValidDayBits>1</ValidDayBits></AvailabilityCondition>
  </validityConditions>
</ServiceFrame>
<ResourceFrame id="rf" version="1">
  <trainNumbers><TrainNumber id="ch:Tn:25" version="1"><ForAdvertisement>25</ForAdvertisement></TrainNumber></trainNumbers>
</ResourceFrame>
<ServiceCalendarFrame id="scf" version="1">
  <dayTypes><DayType id="dt1" version="1"/></dayTypes>
  <operatingPeriods><UicOperatingPeriod id="op1" version="1"><FromDate>2026-01-05T00:00:00</FromDate><ToDate>2026-01-06T00:00:00</ToDate><ValidDayBits>1</ValidDayBits></UicOperatingPeriod></operatingPeriods>
  <dayTypeAssignments><DayTypeAssignment id="dta1" version="1" order="1"><OperatingPeriodRef ref="op1" version="1"/><DayTypeRef ref="dt1" version="1"/></DayTypeAssignment></dayTypeAssignments>
</ServiceCalendarFrame>
<TimetableFrame id="tf" version="1">
  <vehicleJourneys>
    <ServiceJourney id="IT:Sj:1" version="1">
      <Name>25</Name>
      <dayTypes><DayTypeRef ref="dt1" version="1"/></dayTypes>
      <ServiceJourneyPatternRef ref="IT:Pat:1" version="1"/>
      <passingTimes>
        <TimetabledPassingTime id="pt0" version="1"><StopPointInJourneyPatternRef ref="ip0" version="1" order="1"/><DepartureTime>09:40:00</DepartureTime></TimetabledPassingTime>
        <TimetabledPassingTime id="pt1" version="1"><StopPointInJourneyPatternRef ref="ip1" version="1" order="2"/><DepartureTime>10:00:00</DepartureTime></TimetabledPassingTime>
        <TimetabledPassingTime id="pt2" version="1"><StopPointInJourneyPatternRef ref="ip2" version="1" order="2"/><DepartureTime>11:00:00</DepartureTime></TimetabledPassingTime>
        <TimetabledPassingTime id="pt3" version="1"><StopPointInJourneyPatternRef ref="ip3" version="1" order="3"/><ArrivalTime>12:00:00</ArrivalTime></TimetabledPassingTime>
      </passingTimes>
    </ServiceJourney>
    <ServiceJourney id="IT:Sj:2" version="1">
      <dayTypes><DayTypeRef ref="dt1" version="1"/></dayTypes>
      <ServiceJourneyPatternRef ref="IT:Pat:2" version="1"/>
      <passingTimes>
        <TimetabledPassingTime id="qt0" version="1"><StopPointInJourneyPatternRef ref="jp0" version="1" order="1"/><DepartureTime>14:40:00</DepartureTime></TimetabledPassingTime>
        <TimetabledPassingTime id="qt1" version="1"><StopPointInJourneyPatternRef ref="jp1" version="1" order="2"/><ArrivalTime>15:00:00</ArrivalTime></TimetabledPassingTime>
      </passingTimes>
    </ServiceJourney>
    <ServiceJourney id="ch:Sj:1" version="1">
      <validityConditions><AvailabilityConditionRef ref="ac1" version="1"/></validityConditions>
      <trainNumbers><TrainNumberRef ref="ch:Tn:25"/></trainNumbers>
      <calls>
        <Call id="cl1" version="1" order="1"><ScheduledStopPointRef ref="850000300" version="1"/><Departure><Time>11:05:00</Time></Departure></Call>
        <Call id="cl2" version="1" order="2"><ScheduledStopPointRef ref="850000400" version="1"/><Arrival><Time>11:30:00</Time></Arrival></Call>
      </calls>
    </ServiceJourney>
  </vehicleJourneys>
  <journeyInterchanges>
    <ServiceJourneyInterchange id="IT-CH:ServiceJourneyInterchange:25:IT_Sj_1:ch_Sj_1" version="1">
      <StaySeated>true</StaySeated>
      <Planned>true</Planned>
      <Guaranteed>true</Guaranteed>
      <Advertised>true</Advertised>
      <CrossBorder>true</CrossBorder>
      <FromPointRef ref="830000200" version="1"/>
      <ToPointRef ref="850000300" version="1"/>
      <FromJourneyRef nameOfRefClass="ServiceJourney" ref="IT:Sj:1"/>
      <ToJourneyRef nameOfRefClass="ServiceJourney" ref="ch:Sj:1"/>
    </ServiceJourneyInterchange>
  </journeyInterchanges>
</TimetableFrame>
""";
}
