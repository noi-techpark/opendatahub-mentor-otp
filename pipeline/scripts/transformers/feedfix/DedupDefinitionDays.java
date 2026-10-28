package transformers.feedfix;

// Phase 2 of MentzLineVersions.
//
// PROV-CAP: dedup-definition-days
//
// Where two versions of one duplicate-id Line place the same journey definition on the same day,
// one keeps the day and the others lose it. A journey definition is
//
//     (line id, ScheduledStopPointRef sequence, passing-time vector)
//
// i.e. what a rider sees: two ServiceJourneys with the same line, the same stops in the same order
// and the same times (with day offsets) are the same journey.
//
// It is a capability because it moves stored and exported bytes: it thins the affected journeys'
// calendars and adds one calendar triple for each, so a store built before it is stale and the
// provenance check refuses it. The sidecar is stamped from a constant in the jar while this fix
// lives in a script outside it, so the stamp means "built by a release carrying the dedup" rather
// than "built by this file".
//
// Which version keeps a contested day: the one whose ValidBetween opens latest, ties broken on the
// version attribute (numerically when both sides are numeric). On the coterminous shape the later
// version is the re-issue; on the nested shape — holiday overrides sit inside ordinary versions —
// the inner window is the override, so a later FromDate does not mean "supersedes" in this data.
//
// Untouched: duplication within one line version, and every Line, ValidBetween and window. A
// journey that loses all its days runs on no date and is removed; OTP would otherwise map it to
// its empty-calendar sentinel and publish it as a phantom trip. Nothing else is deleted.

import jakarta.xml.bind.JAXBElement;
import noi.netex.model.DayType;
import noi.netex.model.DayTypeAssignment;
import noi.netex.model.DayTypeRefStructure;
import noi.netex.model.DayTypeRefs_RelStructure;
import noi.netex.model.Line;
import noi.netex.model.ObjectFactory;
import noi.netex.model.OperatingPeriodRefStructure;
import noi.netex.model.PointInLinkSequence_VersionedChildStructure;
import noi.netex.model.ScheduledStopPointRefStructure;
import noi.netex.model.ServiceJourney;
import noi.netex.model.ServiceJourneyPattern;
import noi.netex.model.StopPointInJourneyPattern_VersionedChildStructure;
import noi.netex.model.TimetabledPassingTime;
import noi.netex.model.UicOperatingPeriod;
import noi.netex.model.ValidBetween;
import noi.netex.time.XmlDateTime;
import noi.netex.time.XmlTime;
import toolkit.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

public final class DedupDefinitionDays {

    private DedupDefinitionDays() {}

    private static final ObjectFactory FACTORY = new ObjectFactory();

    /// Bit 0 of every day set. Fixed, so a BitSet index is comparable across lines.
    static final LocalDate EPOCH = LocalDate.of(2020, 1, 1);

    /// Test seam, `null` in every shipped run. When set, it may clear days from a journey's
    /// retained set after the dedup has finished but before the guard recomputes coverage — an
    /// over-deletion the guard must then report and abort on.
    /// `tests/TestDedupDefinitionDays.java` sets it in a try/finally and asserts that.
    private static BiConsumer<String, BitSet> sabotage = null;

    /// Installs (or, with `null`, removes) the sabotage described above. A shipped run never calls
    /// it; `tests/TestDedupDefinitionDays.java` clears it in a `finally`.
    public static void setSabotage(BiConsumer<String, BitSet> hook) { sabotage = hook; }

    /// One journey the resolve re-pointed, with the line version it was re-pointed for.
    public record Affected(ServiceJourney journey, String lineId, String version) {}

    /// What the pass did, and what the guard measured.
    ///
    /// @param lines                 duplicate-id lines that carried at least one comparable journey
    /// @param definitions           distinct journey definitions seen on them
    /// @param instancesBefore       Σ over definitions Σ over versions |days(version)| — the multiset
    /// @param instancesAfter        the same, after the dedup
    /// @param coverageBefore        Σ over definitions |⋃ over versions days(version)| — the union
    /// @param coverageAfter         the same, after; must equal coverageBefore
    /// @param journeysConsidered    journeys that carried a resolvable definition and a day
    /// @param journeysSkipped       journeys with no pattern or no passing times (not comparable)
    /// @param journeysThinned       journeys whose calendar lost at least one day
    /// @param journeysEmptied       journeys left with no day at all, and therefore removed
    public record Result(
            long lines, long definitions,
            long instancesBefore, long instancesAfter,
            long coverageBefore, long coverageAfter,
            long journeysConsidered, long journeysSkipped,
            long journeysThinned, long journeysEmptied) {

        /// Duplicated (definition, day) instances removed.
        public long duplicatesRemoved() { return instancesBefore - instancesAfter; }

        /// Duplicated instances the input carried: the multiset minus the set.
        public long duplicatesBefore() { return instancesBefore - coverageBefore; }

        /// Duplicated instances left. The rule removes all of them, so this is 0.
        ///
        /// Scoped to [#journeysConsidered]. A journey with no pattern, no passing times or no day
        /// is not comparable, so it never enters a definition group and its duplication, if it has
        /// any, is neither measured nor removed. A zero here covers only the journeys that could
        /// be compared.
        public long duplicatesAfter() { return instancesAfter - coverageAfter; }

        /// The guarded quantity: (definition, day) instances of service that ran before and run
        /// under no surviving journey afterwards. A dedup cannot delete service, so this is 0 and
        /// anything else aborts the run.
        public long deleted() { return coverageBefore - coverageAfter; }

        @Override public String toString() {
            return String.format(
                    "lines=%d definitions=%d journeys=%d (skipped %d) "
                    + "(definition,day) instances %d -> %d (removed %d, duplicates %d -> %d) "
                    + "coverage %d -> %d (DELETED %d) thinned=%d removed=%d",
                    lines, definitions, journeysConsidered, journeysSkipped,
                    instancesBefore, instancesAfter, duplicatesRemoved(),
                    duplicatesBefore(), duplicatesAfter(),
                    coverageBefore, coverageAfter, deleted(), journeysThinned, journeysEmptied);
        }
    }

    // ------------------------------------------------------------------ the pass

    /// Deduplicates, mutates the journeys in place, and appends the private calendar triples to
    /// `overlay`. The day bits are read from the calendar objects already in `overlay` — from what
    /// the resolve has just synthesised, never from the store.
    ///
    /// @param lines     the resolve's own line index (id -> versions), for the ValidBetween ranking
    /// @param affected  every journey `rewriteJourney` re-pointed, in emission order
    /// @param stopSeqs  ServiceJourneyPattern id -> a digest of its ScheduledStopPointRef sequence
    /// @param overlay   the resolve's overlay: read for calendar objects, appended to, and the
    ///                  journeys left with no day removed from
    /// @param dropped   collects those journeys, for the caller to remove from the store as well
    /// @throws IllegalStateException if the guard finds coverage moved, or cannot prove it looked
    public static Result dedupe(Map<String, List<Line>> lines, List<Affected> affected,
                                Map<String, String> stopSeqs, List<Object> overlay,
                                List<ServiceJourney> dropped) {
        Map<String, DaySet> dayTypeDays = indexSynthesisedCalendar(overlay);

        Map<String, List<Affected>> byLine = new LinkedHashMap<>();
        for (Affected a : affected) byLine.computeIfAbsent(a.lineId(), k -> new ArrayList<>()).add(a);

        long lineCount = 0, defCount = 0;
        long before = 0, after = 0, covBefore = 0, covAfter = 0;
        long considered = 0, skipped = 0, thinned = 0, emptied = 0, moved = 0;
        String firstMoved = null;
        List<Object> appended = new ArrayList<>();
        // Identity-keyed: the model classes generate no equals(), and two journeys of one
        // definition differ only in their id.
        Set<ServiceJourney> emptiedJourneys = Collections.newSetFromMap(new IdentityHashMap<>());

        for (Map.Entry<String, List<Affected>> e : byLine.entrySet()) {
            Map<String, Long> rank = versionRanks(lines.get(e.getKey()));

            // Group this line's journeys by definition. A journey with no pattern, no stop
            // sequence, no passing times or no day at all is not comparable and is left alone.
            Map<String, List<Rec>> groups = new LinkedHashMap<>();
            for (Affected a : e.getValue()) {
                String stops = stopSeqOf(a.journey(), stopSeqs);
                String times = timesDigest(a.journey());
                BitSet days = daysOf(a.journey(), dayTypeDays);
                if (stops == null || times == null || days.isEmpty()) { skipped++; continue; }
                considered++;
                groups.computeIfAbsent(stops + '|' + times, k -> new ArrayList<>())
                        .add(new Rec(a, days, rank.getOrDefault(nullToEmpty(a.version()), Long.MIN_VALUE)));
            }
            if (groups.isEmpty()) continue;
            lineCount++;
            defCount += groups.size();

            for (List<Rec> group : groups.values()) {
                // Per line version, the union of its journeys' days. The report's duplication
                // metric is Σ_v |days(v)| − |⋃_v days(v)|, so a version that runs one definition
                // twice on one day counts once here — within-version duplication is out of scope.
                Map<String, BitSet> perVersion = new LinkedHashMap<>();
                Map<String, Long> perVersionRank = new HashMap<>();
                for (Rec r : group) {
                    String v = nullToEmpty(r.a.version());
                    perVersion.computeIfAbsent(v, k -> new BitSet()).or(r.days);
                    perVersionRank.put(v, r.rank);
                }
                BitSet union = new BitSet();
                for (BitSet b : perVersion.values()) { before += b.cardinality(); union.or(b); }
                covBefore += union.cardinality();

                if (perVersion.size() > 1) {
                    // Latest opener first; each version keeps only the contested days no
                    // higher-ranked version already owns.
                    List<String> order = new ArrayList<>(perVersion.keySet());
                    order.sort(Comparator.<String, Long>comparing(perVersionRank::get)
                            .thenComparing(DedupDefinitionDays::versionKey).reversed());
                    BitSet taken = new BitSet();
                    for (String v : order) {
                        BitSet drop = (BitSet) perVersion.get(v).clone();
                        drop.and(taken);
                        taken.or(perVersion.get(v));
                        if (drop.isEmpty()) continue;
                        for (Rec r : group) {
                            if (!v.equals(nullToEmpty(r.a.version()))) continue;
                            r.days.andNot(drop);
                        }
                    }
                }

                if (sabotage != null) for (Rec r : group) sabotage.accept(r.a.journey().getId(), r.days);

                // Recomputed from the mutated beans, never from `taken` — that is what lets the
                // guard see an over-deletion at all.
                Map<String, BitSet> afterVersion = new LinkedHashMap<>();
                for (Rec r : group) {
                    afterVersion.computeIfAbsent(nullToEmpty(r.a.version()), k -> new BitSet()).or(r.days);
                }
                BitSet unionAfter = new BitSet();
                for (BitSet b : afterVersion.values()) { after += b.cardinality(); unionAfter.or(b); }
                covAfter += unionAfter.cardinality();
                // Per definition, not only in total: a totals-only guard would net a day lost on
                // one definition against a day gained on another and report zero.
                if (!unionAfter.equals(union)) {
                    moved++;
                    if (firstMoved == null) {
                        firstMoved = e.getKey() + " definition " + group.get(0).a.journey().getId()
                                + " (days " + union.cardinality() + " -> " + unionAfter.cardinality() + ")";
                    }
                }

                for (Rec r : group) {
                    if (r.days.equals(r.original)) continue;
                    thinned++;
                    // Nothing left to run. Every one of its days is served by the version that took
                    // them, so the journey is removed rather than re-pointed at an all-zero
                    // calendar.
                    if (r.days.isEmpty()) {
                        emptied++;
                        emptiedJourneys.add(r.a.journey());
                        continue;
                    }
                    repoint(r, appended);
                }
            }
        }

        Result result = new Result(lineCount, defCount, before, after, covBefore, covAfter,
                considered, skipped, thinned, emptied);

        // -------------------------------------------------------------- the guard
        // A run that examined journeys and found no coverage has measured nothing, so its
        // "deleted 0" would be a green over nothing.
        if (considered > 0 && covBefore == 0) {
            throw new IllegalStateException("[mentz-dedup] GUARD INOPERATIVE: " + considered
                    + " journeys considered but coverage measured 0 — the day sets did not resolve");
        }
        if (result.deleted() != 0) {
            throw new IllegalStateException("[mentz-dedup] GUARD: the dedup DELETED "
                    + result.deleted() + " (definition, day) instances of service. A "
                    + "(definition, day) dedup cannot delete service — every day must keep exactly "
                    + "one owning line version. Refusing to write. " + result);
        }
        if (moved != 0) {
            throw new IllegalStateException("[mentz-dedup] GUARD: the day set of " + moved
                    + " journey definition(s) MOVED, while the totals happened to net out. First: "
                    + firstMoved + ". " + result);
        }
        if (result.duplicatesAfter() != 0) {
            throw new IllegalStateException("[mentz-dedup] GUARD: " + result.duplicatesAfter()
                    + " duplicated (definition, day) instances survived the dedup. " + result);
        }
        overlay.addAll(appended);
        // After the guards, so an abort never leaves the overlay half-edited. The journeys are in
        // the overlay because rewriteJourney re-pointed them.
        if (!emptiedJourneys.isEmpty()) {
            overlay.removeIf(emptiedJourneys::contains);
            dropped.addAll(emptiedJourneys);
        }
        last = result;
        Log.info("[mentz-dedup] %s", result);
        if (result.journeysSkipped() > 0) {
            Log.warn("[mentz-dedup] %d journeys were not comparable (no pattern, no passing times "
                    + "or no day) and were left untouched; duplication within that set is NOT "
                    + "measured by the counts above", result.journeysSkipped());
        }
        return result;
    }

    private static Result last = null;

    /// What the last [#dedupe] in this JVM measured, or `null`.
    public static Result lastResult() { return last; }

    /// One journey in a definition group: its live day set, a frozen copy, and its version rank.
    private static final class Rec {
        final Affected a;
        final BitSet days;
        final BitSet original;
        final long rank;
        Rec(Affected a, BitSet days, long rank) {
            this.a = a;
            this.days = days;
            this.original = (BitSet) days.clone();
            this.rank = rank;
        }
    }

    // ------------------------------------------------------------------ calendar

    /// A resolved period: the day it starts on, and one bit per day.
    private record DaySet(long fromEpochDay, String bits) {
        void orInto(BitSet out) {
            int base = (int) (fromEpochDay - EPOCH.toEpochDay());
            if (base < 0) {
                // A day before EPOCH has no bit, and a journey thinned from a day set that
                // silently dropped one would lose real service. The corpus runs 2025-2026;
                // move EPOCH if a feed ever predates it.
                throw new IllegalStateException("[mentz-dedup] operating period starts "
                        + LocalDate.ofEpochDay(fromEpochDay) + ", before the day-set epoch " + EPOCH);
            }
            for (int i = 0; i < bits.length(); i++) {
                if (bits.charAt(i) == '1') out.set(base + i);
            }
        }
    }

    /// DayType id -> its days, read out of the overlay the resolve just built. The three objects
    /// of each synthesised triple are all in there: the assignment carries both refs, so nothing
    /// is derived from the id shape.
    private static Map<String, DaySet> indexSynthesisedCalendar(List<Object> overlay) {
        Map<String, UicOperatingPeriod> uic = new HashMap<>();
        List<DayTypeAssignment> assignments = new ArrayList<>();
        for (Object o : overlay) {
            if (o instanceof UicOperatingPeriod p) uic.put(p.getId(), p);
            else if (o instanceof DayTypeAssignment d) assignments.add(d);
        }
        Map<String, DaySet> out = new HashMap<>();
        for (DayTypeAssignment d : assignments) {
            if (d.getDayTypeRef() == null || d.getOperatingPeriodRef() == null) continue;
            String dayTypeId = d.getDayTypeRef().getValue().getRef();
            String periodId = d.getOperatingPeriodRef().getValue().getRef();
            if (out.containsKey(dayTypeId)) {
                // The resolve emits exactly one triple per synthesised DayType — its id is a
                // bijection with the (line, version segment, source DayType) cache key. If that
                // stops holding, a put() here would take one period and drop the other, leaving
                // every day set below short.
                throw new IllegalStateException("[mentz-dedup] DayType " + dayTypeId
                        + " has more than one synthesised assignment; the day set would be wrong");
            }
            UicOperatingPeriod u = uic.get(periodId);
            if (u != null && u.getFromDate() != null && u.getValidDayBits() != null) {
                out.put(dayTypeId, new DaySet(epochDay(u.getFromDate()), u.getValidDayBits()));
            }
            // No plain-OperatingPeriod arm: the resolve emits exactly one shape, a
            // UicOperatingPeriod with explicit bits, at least one of them set. A plain
            // OperatingPeriod cannot reach this overlay.
        }
        return out;
    }

    private static long epochDay(XmlDateTime dt) {
        return dt.toLocalDateTime().toLocalDate().toEpochDay();
    }

    /// The union of a journey's DayTypeRefs' days.
    private static BitSet daysOf(ServiceJourney j, Map<String, DaySet> dayTypeDays) {
        BitSet out = new BitSet();
        if (j.getDayTypes() == null) return out;
        for (JAXBElement<? extends DayTypeRefStructure> ref : j.getDayTypes().getDayTypeRef()) {
            DaySet d = dayTypeDays.get(ref.getValue().getRef());
            if (d != null) d.orInto(out);
        }
        return out;
    }

    /// Gives one thinned journey its own calendar. The synthesised DayTypes it referenced are
    /// shared between journeys (the resolve caches them per (line, version segment, source
    /// DayType)), so its days cannot be cleared in place without moving other journeys — hence a
    /// private triple, and a single ref replacing the shared ones. Safe only while the DayTypes
    /// the resolve creates carry an id and a version and nothing else, which makes a journey's
    /// whole calendar content the union of their day bits.
    ///
    /// Never called for an empty day set — that journey is removed instead — so it mints no
    /// all-zero period.
    private static void repoint(Rec r, List<Object> out) {
        String jid = r.a.journey().getId();
        LocalDate from = EPOCH.plusDays(r.days.nextSetBit(0));
        LocalDate to = EPOCH.plusDays(r.days.length() - 1L);
        StringBuilder sb = new StringBuilder();
        for (int i = r.days.nextSetBit(0); i < r.days.length(); i++) sb.append(r.days.get(i) ? '1' : '0');
        String bits = sb.toString();

        String dayTypeId = jid + ":DayType:dedup";
        String periodId = jid + ":UicOperatingPeriod:dedup";

        DayType dt = new DayType();
        dt.setId(dayTypeId);
        dt.setVersion("1");

        UicOperatingPeriod period = new UicOperatingPeriod();
        period.setId(periodId);
        period.setVersion("1");
        // A synthesis site, not a copy: from/to are computed (a min/max over a day set), so they
        // are built with XmlDateTime.from(), which retains no source lexical and re-emits
        // canonically.
        period.setFromDate(XmlDateTime.from(LocalDateTime.of(from, java.time.LocalTime.MIDNIGHT)));
        period.setToDate(XmlDateTime.from(LocalDateTime.of(to, java.time.LocalTime.of(23, 59, 59))));
        period.setValidDayBits(bits);

        OperatingPeriodRefStructure periodRef = new OperatingPeriodRefStructure();
        periodRef.setRef(periodId);
        periodRef.setVersion("1");
        DayTypeRefStructure dayTypeRef = new DayTypeRefStructure();
        dayTypeRef.setRef(dayTypeId);
        dayTypeRef.setVersion("1");

        DayTypeAssignment dta = new DayTypeAssignment();
        dta.setId(jid + ":DayTypeAssignment:dedup");
        dta.setVersion("1");
        dta.setDayTypeRef(FACTORY.createDayTypeRef(dayTypeRef));
        dta.setOperatingPeriodRef(FACTORY.createUicOperatingPeriodRef(periodRef));

        DayTypeRefStructure journeyRef = new DayTypeRefStructure();
        journeyRef.setRef(dayTypeId);
        journeyRef.setVersion("1");
        DayTypeRefs_RelStructure dayTypes = new DayTypeRefs_RelStructure();
        dayTypes.getDayTypeRef().add(FACTORY.createDayTypeRef(journeyRef));
        r.a.journey().setDayTypes(dayTypes);

        out.add(dt);
        out.add(period);
        out.add(dta);
    }

    // ------------------------------------------------------------------ definition identity

    /// A digest of a ServiceJourneyPattern's ScheduledStopPointRef sequence, in document order.
    /// Built for every pattern the store holds, because a journey on a Route-less (Verbund) feed
    /// reaches its pattern by ref without any Route to filter on.
    public static String stopSequenceDigest(ServiceJourneyPattern sjp) {
        if (sjp.getPointsInSequence() == null) return null;
        StringBuilder sb = new StringBuilder();
        List<PointInLinkSequence_VersionedChildStructure> pts = sjp.getPointsInSequence()
                .getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern();
        for (PointInLinkSequence_VersionedChildStructure p : pts) {
            if (!(p instanceof StopPointInJourneyPattern_VersionedChildStructure sp)) continue;
            JAXBElement<? extends ScheduledStopPointRefStructure> r = sp.getScheduledStopPointRef();
            if (r == null) continue;
            sb.append(r.getValue().getRef()).append(' ');
        }
        return sb.length() == 0 ? null : digest(sb.toString());
    }

    private static String stopSeqOf(ServiceJourney j, Map<String, String> stopSeqs) {
        if (j.getJourneyPatternRef() == null) return null;
        return stopSeqs.get(j.getJourneyPatternRef().getValue().getRef());
    }

    /// A digest of the journey's passing-time vector: per call, arrival and departure time and
    /// their explicit day offsets. Times are normalised through the carrier's components rather
    /// than its lexical form, so two spellings of one instant compare equal. The raw hour is used
    /// and never folded into a day offset, so a 24:20 written one way and 00:20+1 written another
    /// stay different definitions and are not merged.
    static String timesDigest(ServiceJourney j) {
        if (j.getPassingTimes() == null) return null;
        List<TimetabledPassingTime> tpts = j.getPassingTimes().getTimetabledPassingTime();
        if (tpts.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (TimetabledPassingTime t : tpts) {
            time(sb, t.getArrivalTime());
            sb.append('@').append(t.getArrivalDayOffset() == null ? "" : t.getArrivalDayOffset());
            sb.append('/');
            time(sb, t.getDepartureTime());
            sb.append('@').append(t.getDepartureDayOffset() == null ? "" : t.getDepartureDayOffset());
            sb.append(';');
        }
        return digest(sb.toString());
    }

    private static void time(StringBuilder sb, XmlTime t) {
        if (t == null) return;
        sb.append(t.getHour()).append(':').append(t.getMinute()).append(':').append(t.getSecond())
          .append('.').append(t.getNano());
        if (t.getOffset() != null) sb.append(t.getOffset().getId());
    }

    /// 128 bits of SHA-256, hex. The width has to hold at corpus scale: two definitions conflated
    /// by a collision would be merged inside the guard's own accounting, where nothing downstream
    /// could see it.
    private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    });

    private static String digest(String s) {
        byte[] d = SHA256.get().digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 16; i++) sb.append(Character.forDigit((d[i] >> 4) & 0xf, 16))
                                       .append(Character.forDigit(d[i] & 0xf, 16));
        return sb.toString();
    }

    // ------------------------------------------------------------------ version ranking

    /// version attribute -> the epoch day its first ValidBetween FromDate opens on.
    static Map<String, Long> versionRanks(List<Line> versions) {
        Map<String, Long> out = new HashMap<>();
        if (versions == null) return out;
        for (Line line : versions) {
            for (ValidBetween vb : line.getValidBetween()) {
                if (vb.getFromDate() == null) continue;
                out.put(nullToEmpty(line.getVersion()), epochDay(vb.getFromDate()));
                break;
            }
        }
        return out;
    }

    /// Numeric when both sides are, lexicographic otherwise. Only ever a tie-break.
    private static String versionKey(String v) {
        return v.matches("\\d{1,9}") ? String.format("%09d", Integer.parseInt(v)) : "z" + v;
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }
}
