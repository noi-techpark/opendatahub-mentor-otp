package transformers.feedfix;

// Resolves the Mentz line versions into actual operating days. The Mentz EPIP exporter writes
// variations of a journey as completely new *versions* of a line. Per line version this step slices
// the existing UicOperatingPeriod bits to the version's validity window and re-points each
// versioned journey at a freshly created DayType/period.
//
// Phase 2, the (definition, day) dedup, runs after the resolve and before anything is written.
// Slicing each version's calendar to its own window is not enough where two versions' windows
// overlap: the producer re-issues the same journey under both, both survive, and the rider sees the
// departure twice. The dedup gives every contested (definition, day) to exactly one line version.
//
// Neither phase emits an empty calendar. A slice with no active day is not emitted at all, and a
// journey the dedup leaves with none is removed — OTP reports a DayType with an empty schedule and
// publishes a trip that runs on no date as a phantom. The removal is why this pass clones with a
// skip set rather than verbatim.
//
// No date is hardcoded here — the end of the feed's calendar comes from the feed, see #frameEnd —
// and every shape this pass cannot read is refused rather than guessed at. Phase 2's guard measures
// the day sets phase 1 synthesised, not the source they came from, so it catches neither a window
// arithmetic that quietly produces nothing nor a source calendar this code mis-reads.
// `emptyCalendars` in the declined line is the counter to watch: it is 0 on a feed whose versions
// all meet their own periods.

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import jakarta.xml.bind.JAXBElement;
import noi.netex.model.DayType;
import noi.netex.model.DayTypeAssignment;
import noi.netex.model.DayTypeRefStructure;
import noi.netex.model.DayTypeRefs_RelStructure;
import noi.netex.model.JourneyMeeting;
import noi.netex.model.JourneyPatternRefStructure;
import noi.netex.model.JourneyRefStructure;
import noi.netex.model.Line;
import noi.netex.model.LineRefStructure;
import noi.netex.model.ObjectFactory;
import noi.netex.model.OperatingPeriodRefStructure;
import noi.netex.model.Route;
import noi.netex.model.RouteRefStructure;
import noi.netex.model.ServiceJourney;
import noi.netex.model.ServiceJourneyPattern;
import noi.netex.model.UicOperatingPeriod;
import noi.netex.model.ValidBetween;
import noi.netex.time.XmlDateTime;
import toolkit.graph.Csr;
import toolkit.keycodec.NulKeyCodec;
import toolkit.store.ObjectRow;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.util.Log;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

public class MentzLineVersions {

    private static final ObjectFactory FACTORY = new ObjectFactory();

    /// A line id and version; the version may be null.
    record LineVersion(String lineId, String version) {}

    /// Everything this pass declined to do, counted instead of passed over in silence.
    ///
    /// Every one of these leaves its journeys on their original shared calendar, which is the
    /// duplication this pass exists to remove. [#startsPastFrame] is the signature of the whole
    /// pass ceasing to work when a feed rolls into its next period, and is fatal for that reason;
    /// the rest are legitimate feed shapes and only need to be visible.
    static final class Skips {
        /// A LineRef version attribute naming no version of that Line.
        long unknownVersion;
        /// A ToDate-less version opening at or after the feed's frame end. FATAL — see [#apply].
        long startsPastFrame;
        /// A ToDate-less version whose window is wholly covered by override windows.
        long swallowed;
        /// A Line version carrying no ValidBetween with a FromDate.
        long noValidity;
        /// A source period not meeting its line version's window; see [#makeCalendarObjects].
        long disjointPeriods;
        /// A slice with no active day, so no calendar triple was emitted for it. A superset of
        /// [#disjointPeriods]: a period that does meet the window can still contribute no day.
        long emptyCalendars;

        boolean any() {
            return unknownVersion + startsPastFrame + swallowed + noValidity + disjointPeriods
                    + emptyCalendars > 0;
        }

        @Override public String toString() {
            return String.format("unknownVersion=%d startsPastFrame=%d swallowed=%d "
                    + "noValidity=%d disjointPeriods=%d emptyCalendars=%d",
                    unknownVersion, startsPastFrame, swallowed, noValidity, disjointPeriods,
                    emptyCalendars);
        }
    }

    /// The created-DayType dedup key: line id, safe version, existing DayType id.
    record CacheKey(String lineId, String safeVersion, String dayTypeId) {}

    /// (version, from, to) — one line version's first ValidBetween with a FromDate.
    ///
    /// from/to are carriers, not LocalDateTimes: the XmlDateTime objects read off the input are
    /// handed straight into the constructed ValidBetween for the override case, a pure copy that
    /// re-emits the source lexical. Normalising here would canonicalise those values and change the
    /// output bytes. The arithmetic paths call toLocalDateTime() at the point of use.
    record RawValidity(String version, XmlDateTime from, XmlDateTime to) {}

    /// Everything [#readIndexes] collects in one pass.
    ///
    /// `stopSeqs` is a digest of every ServiceJourneyPattern's ScheduledStopPointRef sequence,
    /// which phase 2 needs to decide whether two journeys are the same journey. It is populated
    /// only when the store actually holds a duplicate-id line, and is empty otherwise.
    record Indexes(
            Map<String, List<Line>> lines,
            Map<String, List<Route>> routes,
            Map<String, List<ServiceJourneyPattern>> sjps,
            Map<String, List<ServiceJourney>> journeys,
            Map<LineVersion, List<ServiceJourney>> directJourneys,
            Map<String, DayTypeAssignment> dayTypeAssignments,
            Map<String, UicOperatingPeriod> uicPeriods,
            Map<String, String> stopSeqs) {}

    /// Both phases, over the whole store. Named so tests can run it via TestStore.runDbToDb,
    /// without the CLI sandwich.
    public static void apply(Store src, Txn stx, Store dst, Txn dtx) {
        Skips skips = new Skips();
        LocalDateTime frameEnd = frameEnd(src, stx);
        Indexes ix = readIndexes(src, stx);
        Map<LineVersion, List<ValidBetween>> versionValidity =
                iterVersionValidity(ix.lines(), frameEnd, skips);

        List<DedupDefinitionDays.Affected> affected = new ArrayList<>();
        // Both phases run before the clone, and neither touches the store: they read the in-memory
        // indexes and mutate detached beans. The clone has to see the drop set, and the guards
        // below abort before anything is written.
        List<ServiceJourney> dropped = new ArrayList<>();
        List<Object> overlay = resolveJourneys(ix, versionValidity, affected, skips, dropped);
        if (skips.any()) Log.info("[mentz] declined: %s", skips);
        // The rollover guard. A version opening at or after its own feed's frame end can never be
        // given a calendar, so its journeys keep the shared one they came with and every duplicate
        // departure survives — silently, because phase 2 only ever sees the journeys phase 1 did
        // re-point.
        if (skips.startsPastFrame > 0) {
            throw new IllegalStateException("[mentz] GUARD: " + skips.startsPastFrame
                    + " line version(s) open at or after the feed's own frame end " + frameEnd
                    + ", so they can be given no calendar and their journeys would stay on the "
                    + "shared one — the duplication this pass exists to remove. Refusing to write.");
        }
        // Phase 2, after the resolve and before anything is written. It mutates the journeys
        // already in `overlay` in place and appends a private calendar triple per journey whose
        // days actually thinned, so the emission order of everything phase 1 produced is
        // untouched. It carries its own guard and throws rather than writing a store that lost
        // service. Journeys it empties leave the overlay and join `dropped`.
        DedupDefinitionDays.dedupe(ix.lines(), affected, ix.stopSeqs(), overlay, dropped);

        // Raw-clone every class map + both reference indexes verbatim, minus the drop set — a v2
        // store has no delete, and cloneFrom(skipFullKeys) is the only way to express one. The
        // overlay below overwrites only the updated journeys (by id) and appends the new calendar
        // objects; the harness's trailing resolve()/resolveEmbeddings() wire everything.
        LongOpenHashSet skipKeys = dropKeys(src, stx, dropped);
        dst.cloneFrom(src, stx, dtx, skipKeys.isEmpty() ? null : skipKeys::contains, 0);
        dst.insertAnyObjects(dtx, overlay.iterator());
        Log.info("[mentz] %d overlay objects (new calendar objects + updated journeys), "
                + "%d keys not cloned", overlay.size(), skipKeys.size());
    }

    /// The source-space full keys the clone must not copy: every journey left with no operating
    /// day, the JourneyMeetings that named one, and whatever the drop leaves unreferenced.
    ///
    /// A meeting is dropped for the same reason [fix.DropForeignJourneys] drops one — EpipToDb
    /// turns every JourneyMeeting into the ServiceJourneyInterchange the rest of the pipeline
    /// reads, and one naming a journey that is no longer there survives into the graph build.
    static LongOpenHashSet dropKeys(Store db, Txn txn, List<ServiceJourney> dropped) {
        LongOpenHashSet skip = new LongOpenHashSet();
        if (dropped.isEmpty()) return skip;
        Set<String> droppedIds = new HashSet<>();
        LongArrayList seeds = new LongArrayList();
        for (ServiceJourney sj : dropped) {
            // (id, version, class) is exactly the key the store was written under, so a miss is a
            // shape this pass cannot read rather than a journey it may skip.
            long fk = db.lookupFullKey(txn, sj.getId(), sj.getVersion(), ServiceJourney.class);
            if (fk == -1) {
                throw new IllegalStateException("[mentz] journey " + sj.getId() + " version "
                        + sj.getVersion() + " runs on no day but has no id-index row, so it cannot "
                        + "be removed and would be exported on its unsliced source calendar");
            }
            skip.add(fk);
            seeds.add(fk);
            droppedIds.add(sj.getId());
        }
        long journeys = skip.size();
        long meetings = 0;
        if (db.dbNames(txn).contains(JourneyMeeting.class)) {
            for (ObjectRow row : db.iterObjects(txn, JourneyMeeting.class)) {
                JourneyMeeting jm = (JourneyMeeting) row.object();
                if (namesDropped(jm.getFromJourneyRef(), droppedIds)
                        || namesDropped(jm.getToJourneyRef(), droppedIds)) {
                    skip.add(db.fullKeyOf(JourneyMeeting.class, row.localKey()));
                    meetings++;
                }
            }
        }
        Log.info("[mentz] dropped %d journeys that run on no day, and %d meetings on one",
                journeys, meetings);
        // Seeded from the journeys only. A meeting is in `skip` — so the peel counts it as gone
        // when it weighs a referrer — but it is not walked from: its targets are journeys, and a
        // journey with no referrer is an ordinary journey.
        peelOrphans(db, txn, seeds, skip);
        return skip;
    }

    /// Classes an object of which exists only to be pointed at. A pattern, route, line, link or
    /// display that nothing references any longer describes nothing, where a ServiceJourney or a
    /// PassengerStopAssignment is meaningful with no referrer at all and a zero in-degree says
    /// nothing about it.
    ///
    /// The list is the measured closure of this drop: on the two Mentz feeds the peel reaches
    /// ServiceJourneyPattern, Route, Line, ServiceLink and DestinationDisplay and stops. Anything
    /// else it reaches is left in place and logged, so a feed that grows a new shape shows up as a
    /// number rather than as a silent deletion.
    private static final Set<String> PEELABLE = Set.of(
            "ServiceJourneyPattern", "Route", "Line", "ServiceLink", "DestinationDisplay");
    /// Transitive orphan peel: an object every one of whose referrers is being dropped had exactly
    /// one reason to exist and no longer has it.
    ///
    /// A pattern whose last journey went is an OTP `ServiceJourneyPatternIsEmpty`, and the export
    /// enumerates the pattern class map rather than walking journeys, so an unreferenced one does
    /// reach the graph build. Measured on the 2026-09-07 corpus: 6,119 patterns, 837 routes, 99
    /// lines, 21 links, 2 displays.
    ///
    /// It only ever removes an object that had a referrer, so the feed's own orphans are untouched.
    /// Reads the inverse half of the store's CSR, ~30 bytes per edge (18.2 M edges on the merged
    /// Austrian store), built once and held for the clone that follows.
    private static void peelOrphans(Store db, Txn txn, LongArrayList seeds, LongOpenHashSet drop) {
        Csr g = db.csr(txn);
        Set<Integer> peelable = new HashSet<>();
        for (Class<?> c : db.dbNames(txn)) {
            if (PEELABLE.contains(c.getSimpleName())) peelable.add(db.classIdxInt(c));
        }
        Map<String, Long> peeled = new TreeMap<>();
        Map<String, Long> kept = new TreeMap<>();
        LongArrayList work = seeds;
        while (!work.isEmpty()) {
            LongArrayList next = new LongArrayList();
            for (long k : work) {
                int i = Arrays.binarySearch(g.nodes, k);
                if (i < 0) continue;   // no outward edge at all
                for (long e = g.fwdOff[i]; e < g.fwdOff[i + 1]; e++) {
                    long t = g.fwdKeys[(int) e];
                    if (drop.contains(t)) continue;
                    int ti = Arrays.binarySearch(g.nodes, t);
                    // t is an edge target, so it is in the node table and has >= 1 referrer; the
                    // "had a reason to exist" half of the rule needs no separate test.
                    boolean orphaned = true;
                    for (long r = g.invOff[ti]; r < g.invOff[ti + 1] && orphaned; r++) {
                        orphaned = drop.contains(g.invKeys[(int) r]);
                    }
                    if (!orphaned) continue;
                    Class<?> c = db.classForIdx(NulKeyCodec.fullKeyClassIdx(t));
                    String name = c == null ? "?" : c.getSimpleName();
                    if (c == null || !peelable.contains(NulKeyCodec.fullKeyClassIdx(t))) {
                        kept.merge(name, 1L, Long::sum);
                        continue;
                    }
                    drop.add(t);
                    next.add(t);
                    peeled.merge(name, 1L, Long::sum);
                }
            }
            work = next;
        }
        if (!peeled.isEmpty()) Log.info("[mentz] peeled %s left unreferenced by the drop", peeled);
        if (!kept.isEmpty()) {
            Log.warn("[mentz] %s are now unreferenced but are not peelable classes, and stay", kept);
        }
    }

    /// True when the ref names a journey this pass dropped. A null ref is not one.
    private static boolean namesDropped(JourneyRefStructure ref, Set<String> droppedIds) {
        return ref != null && ref.getRef() != null && droppedIds.contains(ref.getRef());
    }

    // ------------------------------------------------------------------ the frame end

    /// The feed's end date: the latest ToDate over every first-class ValidBetween in the store.
    ///
    /// A CompositeFrame's validity is stored first-class. A frame is an ordinary element to the
    /// loader, so its `<ValidBetween>` child is not swallowed by any consumed subtree; it reaches
    /// the interesting-element arm and is stored, inheriting the frame's id and version through
    /// CopyingCapture's denormalisation. A Line's own ValidBetween never lands here — the Line is
    /// interesting, so its whole subtree is consumed by the unmarshaller. The Austrian store holds
    /// 3,640 of these, one per frame across all ten producer codespaces.
    ///
    /// The producers of one feed disagree: the merged Austrian feed carries two frame windows, nine
    /// producers from 2025-12-14 and `at:esg` from 2026-01-02, which end at the same instant. It
    /// has to be the max over them. This value only ever supplies the missing end of a ToDate-less
    /// "default" version, and [#makeCalendarObjects] then clips that window against the source
    /// period's own ToDate: over-reaching is clamped there, under-reaching silently drops real
    /// service.
    ///
    /// The FromDate side is not read. It varies by producer for the same feed and would be a
    /// fiction as a single number.
    ///
    /// @throws IllegalStateException if the store holds no ValidBetween carrying a ToDate, leaving
    ///         every ToDate-less line version in need of an invented end date.
    static LocalDateTime frameEnd(Store db, Txn txn) {
        LocalDateTime max = null;
        long seen = 0;
        for (Object o : db.iterOnlyObjects(txn, ValidBetween.class)) {
            XmlDateTime to = ((ValidBetween) o).getToDate();
            if (to == null) continue;
            seen++;
            LocalDateTime dt = to.toLocalDateTime();
            if (max == null || dt.isAfter(max)) max = dt;
        }
        if (max == null) {
            throw new IllegalStateException(
                    "[mentz] no ValidBetween with a ToDate in the store, so the feed's frame end "
                    + "is unknown; every ToDate-less line version would need an invented end date. "
                    + "Refusing to guess.");
        }
        Log.info("[mentz] frame end %s (latest of %d ValidBetween ToDates)", max, seen);
        return max;
    }

    // ------------------------------------------------------------------ index reading

    static Indexes readIndexes(Store db, Txn txn) {
        Map<String, List<Line>> lines = new LinkedHashMap<>();
        for (Object o : db.iterOnlyObjects(txn, Line.class)) {
            Line line = (Line) o;
            lines.computeIfAbsent(line.getId(), k -> new ArrayList<>()).add(line);
        }
        Set<String> duplicateLineIds = new HashSet<>();
        for (Map.Entry<String, List<Line>> e : lines.entrySet()) {
            if (e.getValue().size() > 1) duplicateLineIds.add(e.getKey());
        }
        Log.info("[mentz] %d of %d lines have duplicate versions", duplicateLineIds.size(), lines.size());

        Map<String, List<Route>> routes = new LinkedHashMap<>();
        for (Object o : db.iterOnlyObjects(txn, Route.class)) {
            Route route = (Route) o;
            JAXBElement<? extends LineRefStructure> lr = route.getLineRef();
            if (lr != null && duplicateLineIds.contains(lr.getValue().getRef())) {
                routes.computeIfAbsent(lr.getValue().getRef(), k -> new ArrayList<>()).add(route);
            }
        }
        Set<String> duplicateRouteIds = new HashSet<>();
        for (List<Route> routeList : routes.values()) {
            for (Route route : routeList) duplicateRouteIds.add(route.getId());
        }

        Map<String, List<ServiceJourneyPattern>> sjps = new LinkedHashMap<>();
        // Every pattern's stop sequence, including the ones no Route reaches. A Verbund feed ships
        // no Routes at all, so `sjps` is empty there while its journeys still name a pattern by
        // ref, and that pattern is where the stop sequence lives.
        Map<String, String> stopSeqs = new HashMap<>();
        boolean needStopSeqs = !duplicateLineIds.isEmpty();
        for (Object o : db.iterOnlyObjects(txn, ServiceJourneyPattern.class)) {
            ServiceJourneyPattern sjp = (ServiceJourneyPattern) o;
            if (needStopSeqs) {
                String seq = DedupDefinitionDays.stopSequenceDigest(sjp);
                if (seq != null) stopSeqs.put(sjp.getId(), seq);
            }
            // route_ref_or_route_view: the choice binds as getRouteRef()/getRouteView(); only the
            // ref member carries a .ref, a RouteView has none.
            RouteRefStructure rr = sjp.getRouteRef();
            if (rr != null && duplicateRouteIds.contains(rr.getRef())) {
                sjps.computeIfAbsent(rr.getRef(), k -> new ArrayList<>()).add(sjp);
            }
        }
        Set<String> duplicateSjpIds = new HashSet<>();
        for (List<ServiceJourneyPattern> sjpList : sjps.values()) {
            for (ServiceJourneyPattern sjp : sjpList) duplicateSjpIds.add(sjp.getId());
        }

        // journeys[sjp_id] keys the Route-shaped feeds; directJourneys keys the shape with no Route
        // objects, where journeys reference their line version directly via a versioned LineRef and
        // the Line->Route->SJP->Journey walk finds nothing.
        Map<String, List<ServiceJourney>> journeys = new LinkedHashMap<>();
        Map<LineVersion, List<ServiceJourney>> directJourneys = new LinkedHashMap<>();
        Set<String> dayTypeIds = new HashSet<>();
        for (Object o : db.iterOnlyObjects(txn, ServiceJourney.class)) {
            ServiceJourney journey = (ServiceJourney) o;
            // flexible_line_ref_or_line_ref_or_line_view_or_flexible_line_view: the two *Ref
            // members land in getLineRef() (JAXBElement<? extends LineRefStructure>); the two *View
            // members have no .ref, so the ref probe never matches them.
            JAXBElement<? extends LineRefStructure> lr = journey.getLineRef();
            String lineRefRef = lr == null ? null : lr.getValue().getRef();
            JAXBElement<? extends JourneyPatternRefStructure> jpr = journey.getJourneyPatternRef();
            if (jpr != null && duplicateSjpIds.contains(jpr.getValue().getRef())) {
                journeys.computeIfAbsent(jpr.getValue().getRef(), k -> new ArrayList<>()).add(journey);
            } else if (lineRefRef != null && duplicateLineIds.contains(lineRefRef)) {
                directJourneys.computeIfAbsent(new LineVersion(lineRefRef, lr.getValue().getVersion()),
                        k -> new ArrayList<>()).add(journey);
            } else {
                continue;
            }
            if (journey.getDayTypes() != null) {
                for (JAXBElement<? extends DayTypeRefStructure> ref : journey.getDayTypes().getDayTypeRef()) {
                    dayTypeIds.add(ref.getValue().getRef());
                }
            }
        }

        // DayTypeAssignments / UicOperatingPeriods are first-class (the loader treats
        // <ServiceCalendar> as a transparent container), so read them directly.
        //
        // The three refusals below are shape assertions on the input. Every shape they refuse is
        // measured absent from both Mentz feeds: 297:297:297 on STA and 9,460:9,460:9,460 on the
        // merged Austrian corpus, 100% OperatingPeriodRef, no isAvailable, no bare <Date>, no
        // DayType without an assignment.
        Set<String> uicPeriodIds = new LinkedHashSet<>();
        Map<String, DayTypeAssignment> dayTypeAssignments = new LinkedHashMap<>();
        for (Object o : db.iterOnlyObjects(txn, DayTypeAssignment.class)) {
            DayTypeAssignment dta = (DayTypeAssignment) o;
            if (dta.getDayTypeRef() != null && dayTypeIds.contains(dta.getDayTypeRef().getValue().getRef())) {
                String dayTypeId = dta.getDayTypeRef().getValue().getRef();
                // B1. The map keeps one assignment per DayType; NeTEx permits many, and a second
                // one would overwrite the first, leaving that DayType's day set short.
                DayTypeAssignment prior = dayTypeAssignments.put(dayTypeId, dta);
                if (prior != null) {
                    throw new IllegalStateException("[mentz] DayType " + dayTypeId
                            + " has more than one DayTypeAssignment (" + prior.getId() + ", "
                            + dta.getId() + "); this pass keeps one per DayType and the others' "
                            + "days would be lost");
                }
                // B3. isAvailable=false makes an assignment an exclusion. Nothing here subtracts.
                if (Boolean.FALSE.equals(dta.isIsAvailable())) {
                    throw new IllegalStateException("[mentz] DayTypeAssignment " + dta.getId()
                            + " has isAvailable=false; this pass has no way to subtract days and "
                            + "would silently treat the exclusion as service");
                }
                String ref = periodRefOf(dta);
                // B3. The bare-<Date> arm of the choice yields no ref, so the assignment would be
                // dropped from the index and its DayType left with no period at all.
                if (ref == null) {
                    throw new IllegalStateException("[mentz] DayTypeAssignment " + dta.getId()
                            + " names neither an operating period nor an operating day (a bare "
                            + "<Date>); its DayType would resolve to no calendar");
                }
                uicPeriodIds.add(ref);
            }
        }

        Map<String, UicOperatingPeriod> uicPeriods = new LinkedHashMap<>();
        for (Object o : db.iterOnlyObjects(txn, UicOperatingPeriod.class)) {
            UicOperatingPeriod uop = (UicOperatingPeriod) o;
            if (uicPeriodIds.contains(uop.getId())) uicPeriods.put(uop.getId(), uop);
        }
        // B2. Every ref must land on a UicOperatingPeriod. One check covering three shapes this
        // code cannot read: an OperatingDayRef (a single day), a plain OperatingPeriod, and a
        // dangling ref. Without it each of them reaches makeCalendarObjects as a null period.
        for (String ref : uicPeriodIds) {
            if (!uicPeriods.containsKey(ref)) {
                throw new IllegalStateException("[mentz] DayTypeAssignment period ref " + ref
                        + " resolves to no UicOperatingPeriod; it is an OperatingDayRef, a plain "
                        + "OperatingPeriod or dangling, and the journeys on that DayType would be "
                        + "given every day of their line version's window instead");
            }
        }
        // B5. The one lossy line in this file right-pads a short bit string with '0' (see
        // makeCalendarObjects), which erases real service. The check is not-shorter rather than
        // equal because STA ships bits one char longer than its window; the slice never reads the
        // tail.
        for (UicOperatingPeriod uop : uicPeriods.values()) {
            if (uop.getFromDate() == null || uop.getToDate() == null) continue;
            long days = ChronoUnit.DAYS.between(
                    uop.getFromDate().toLocalDateTime().toLocalDate(),
                    uop.getToDate().toLocalDateTime().toLocalDate()) + 1;
            int len = uop.getValidDayBits() == null ? 0 : uop.getValidDayBits().length();
            if (len < days) {
                throw new IllegalStateException("[mentz] UicOperatingPeriod " + uop.getId()
                        + " declares " + days + " days but carries " + len + " ValidDayBits; the "
                        + "missing days would be zero-padded into 'does not run'");
            }
        }

        return new Indexes(lines, routes, sjps, journeys, directJourneys, dayTypeAssignments,
                uicPeriods, stopSeqs);
    }

    /// The ref of a DayTypeAssignment's 4-way choice
    /// uic_operating_period_ref_or_operating_period_ref_or_operating_day_ref_or_date, or null.
    /// The choice binds as getOperatingPeriodRef() (a JAXBElement covering both
    /// UicOperatingPeriodRef and OperatingPeriodRef — the UicOperatingPeriodRef element is
    /// declared with type OperatingPeriodRefStructure), getOperatingDayRef(), and getDate()
    /// (a bare LocalDateTime). The guard is the presence of a ref: both ref members qualify, the
    /// bare date does not.
    private static String periodRefOf(DayTypeAssignment dta) {
        if (dta.getOperatingPeriodRef() != null) return dta.getOperatingPeriodRef().getValue().getRef();
        if (dta.getOperatingDayRef() != null) return dta.getOperatingDayRef().getRef();
        return null;
    }

    /// A null version attribute is a legal key here (see [LineVersion]), so it needs a stable
    /// stand-in wherever versions are compared as strings.
    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    // ------------------------------------------------------------------ version validity

    /// Truncate to whole seconds (XmlDateTime(y,m,d,h,m,s) drops fractions).
    ///
    /// A synthesis boundary: [XmlDateTime#from] builds a value with no source lexical and no
    /// offset, which serialises canonically. Only for datetimes that are computed here — segment
    /// boundaries derived by `minusSeconds(1)` arithmetic. [#makeCalendarObjects] copies input
    /// carriers untouched instead.
    private static XmlDateTime xmlDt(LocalDateTime dt) {
        return XmlDateTime.from(truncSeconds(dt));
    }

    /// The truncation half of [#xmlDt], without the carrier.
    private static LocalDateTime truncSeconds(LocalDateTime dt) {
        return dt.withNano(0);
    }

    /// The non-overlapping ValidBetween segments of a default version, excluding override windows.
    static List<ValidBetween> computeDefaultSegments(
            LocalDateTime fromDt, LocalDateTime defaultEnd, List<LocalDateTime[]> overrides) {
        List<ValidBetween> segments = new ArrayList<>();
        LocalDateTime current = fromDt;
        LocalDateTime end = defaultEnd;

        List<LocalDateTime[]> sorted = new ArrayList<>(overrides);
        sorted.sort((a, b) -> a[0].compareTo(b[0])); // stable, keyed on the from date
        for (LocalDateTime[] od : sorted) {
            LocalDateTime odStart = od[0];
            LocalDateTime odEnd = od[1];
            if (!odStart.isBefore(end)) break;      // od_start >= end
            if (!odEnd.isAfter(current)) continue;  // od_end <= current
            if (current.isBefore(odStart)) {
                segments.add(validBetween(xmlDt(current), xmlDt(odStart.minusSeconds(1))));
            }
            current = odEnd.plusSeconds(1);
        }

        if (current.isBefore(end)) {
            segments.add(validBetween(xmlDt(current), xmlDt(end)));
        }
        return segments;
    }

    private static ValidBetween validBetween(XmlDateTime from, XmlDateTime to) {
        ValidBetween vb = new ValidBetween();
        vb.setFromDate(from);
        vb.setToDate(to);
        return vb;
    }

    /// (line_id, version) -> list of ValidBetween segments.
    ///
    /// Versions without a ToDate are "defaults": they run continuously from their FromDate (until
    /// the next default's FromDate, or frameEnd), interrupted only by "override" versions that
    /// carry an explicit ToDate. Versions with a ToDate are "overrides": they run exactly during
    /// their window. Insertion order is preserved.
    ///
    /// The two shapes B6 below asserts hold across STA and the merged Austrian corpus — 1,103 and
    /// 7,365 Line keys, every one with exactly one dated ValidBetween, no repeated (line id,
    /// version) pair.
    static Map<LineVersion, List<ValidBetween>> iterVersionValidity(
            Map<String, List<Line>> lines, LocalDateTime frameEnd, Skips skips) {
        Map<LineVersion, List<ValidBetween>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Line>> entry : lines.entrySet()) {
            String lineId = entry.getKey();
            List<Line> lineVersions = entry.getValue();
            if (lineVersions.size() <= 1) continue;

            List<RawValidity> raw = new ArrayList<>();
            Set<String> seenVersions = new HashSet<>();
            for (Line line : lineVersions) {
                // B6a. Two Line objects under one id with the same version attribute: the store
                // keys on (id, version, class), so this means the feed shipped a genuine
                // collision, and every keyed map below would keep one and drop the other.
                if (!seenVersions.add(nullToEmpty(line.getVersion()))) {
                    throw new IllegalStateException("[mentz] line " + lineId + " has two versions "
                            + "both attributed version=" + line.getVersion() + "; their validity "
                            + "windows cannot be told apart and one would silently win");
                }
                // validity_conditions_or_valid_between: the choice binds as getValidityConditions()
                // and getValidBetween(); only ValidBetween carries a fromDate, which is what
                // selects this arm.
                ValidBetween dated = null;
                for (ValidBetween vb : line.getValidBetween()) {
                    if (vb.getFromDate() == null) continue;
                    // B6b. The first dated ValidBetween is taken and the rest dropped. A second
                    // one is a second window this pass would never slice a calendar to.
                    if (dated != null) {
                        throw new IllegalStateException("[mentz] line " + lineId + " version "
                                + line.getVersion() + " has more than one dated ValidBetween; only "
                                + "the first would be honoured and the rest silently dropped");
                    }
                    dated = vb;
                }
                if (dated != null) {
                    raw.add(new RawValidity(line.getVersion(), dated.getFromDate(), dated.getToDate()));
                } else {
                    // No window to slice anything to. Recorded as an empty entry rather than an
                    // absent one so resolveJourneys can tell this apart from a version attribute
                    // that names no Line version at all.
                    skips.noValidity++;
                    out.put(new LineVersion(lineId, line.getVersion()), List.of());
                }
            }

            List<RawValidity> defaults = new ArrayList<>();
            List<RawValidity> overrides = new ArrayList<>();
            for (RawValidity rv : raw) {
                (rv.to() == null ? defaults : overrides).add(rv);
            }
            // Sorted on the normalised value; the carrier has no ordering at all.
            defaults.sort((a, b) -> a.from().toLocalDateTime().compareTo(b.from().toLocalDateTime()));

            for (RawValidity rv : raw) {
                if (rv.to() != null) {
                    // A pure copy of both carriers: the constructed ValidBetween takes the objects
                    // as they are, so each re-emits its own source lexical.
                    out.put(new LineVersion(lineId, rv.version()), List.of(validBetween(rv.from(), rv.to())));
                } else {
                    int idx = -1;
                    for (int i = 0; i < defaults.size(); i++) {
                        if (Objects.equals(defaults.get(i).version(), rv.version())) { idx = i; break; }
                    }
                    LocalDateTime defaultEnd = idx + 1 < defaults.size()
                            ? truncSeconds(defaults.get(idx + 1).from().toLocalDateTime().minusSeconds(1))
                            : frameEnd;
                    List<LocalDateTime[]> overrideWindows = new ArrayList<>();
                    for (RawValidity od : overrides) {
                        overrideWindows.add(new LocalDateTime[] {
                                od.from().toLocalDateTime(), od.to().toLocalDateTime()});
                    }
                    List<ValidBetween> segments = computeDefaultSegments(
                            rv.from().toLocalDateTime(), defaultEnd, overrideWindows);
                    if (segments.isEmpty()) {
                        // Two reasons for an empty segment list. A window wholly covered by
                        // overrides is ordinary feed shape. A window opening at or after the feed's
                        // own frame end means this version can never be given a calendar.
                        boolean lastDefault = idx + 1 >= defaults.size();
                        if (lastDefault && !rv.from().toLocalDateTime().isBefore(defaultEnd)) {
                            skips.startsPastFrame++;
                        } else {
                            skips.swallowed++;
                        }
                    }
                    out.put(new LineVersion(lineId, rv.version()), segments);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ calendar synthesis

    /// Appends the new DayType, UicOperatingPeriod and DayTypeAssignment to `out` and returns the
    /// new DayType id, or null when the slice carries no active day. An all-zero calendar is a
    /// DayType with an empty schedule, which OTP reports and no journey can run on, so it is not
    /// emitted at all and [#rewriteJourney] drops the ref instead.
    ///
    /// One output shape, always a UicOperatingPeriod with explicit bits, at least one of them set.
    /// B2 in [#readIndexes] and B4 in [#rewriteJourney] keep "no existing period" out of here.
    /// [DedupDefinitionDays] reads the overlay on that basis and has no arm for any other shape.
    static String makeCalendarObjects(
            String lineId, String existingDtId, String safeVersion,
            UicOperatingPeriod existingPeriod, ValidBetween validity, List<Object> out,
            Skips skips) {
        String newDayTypeId = lineId + ":" + existingDtId + ":" + safeVersion;
        String newDtaId = lineId + ":" + existingDtId + ":DayTypeAssignment:" + safeVersion;

        // A normalisation boundary: the validity's carriers, normalised to dates.
        LocalDate lineFromDate = validity.getFromDate().toLocalDateTime().toLocalDate();
        LocalDate lineToDate = validity.getToDate().toLocalDateTime().toLocalDate();

        // from_operating_day_ref_or_from_date: the choice binds as getFromOperatingDayRef() /
        // getFromDate(), and getFromDate() != null is the discriminator. A FromOperatingDayRef
        // gives this method no date to slice against and no bit offset to count from.
        if (existingPeriod.getFromDate() == null) {
            throw new IllegalStateException("[mentz] UicOperatingPeriod " + existingPeriod.getId()
                    + " has no FromDate (a FromOperatingDayRef); its ValidDayBits cannot be "
                    + "aligned to a calendar and every day of the window would be assumed active");
        }
        LocalDate periodFromDate = existingPeriod.getFromDate().toLocalDateTime().toLocalDate();
        // to_operating_day_ref_or_to_date likewise; a day-ref (or absent) ToDate falls back
        // to lineToDate.
        XmlDateTime periodToDt = existingPeriod.getToDate();
        LocalDate periodToDate = periodToDt != null
                ? periodToDt.toLocalDateTime().toLocalDate() : lineToDate;

        LocalDate newFromDate = lineFromDate.isAfter(periodFromDate) ? lineFromDate : periodFromDate;
        LocalDate newToDate = lineToDate.isBefore(periodToDate) ? lineToDate : periodToDate;

        String bits;
        XmlDateTime fromXml;
        XmlDateTime toXml;
        if (newFromDate.isAfter(newToDate)) {
            // Disjoint: the source period and this line version's window do not meet, so the
            // journey runs on no day of this segment. An all-zero bit string over the line window
            // says exactly that, and is the same encoding the overlapping-but-empty slice below
            // already produces. Both are refused below rather than emitted.
            long nDays = ChronoUnit.DAYS.between(lineFromDate, lineToDate) + 1;
            bits = "0".repeat((int) Math.max(1, nDays));
            fromXml = validity.getFromDate();
            toXml = validity.getToDate();
            skips.disjointPeriods++;
        } else {
            long offset = ChronoUnit.DAYS.between(periodFromDate, newFromDate); // >= 0 by max()
            long nDays = ChronoUnit.DAYS.between(newFromDate, newToDate) + 1;
            String existingBits = existingPeriod.getValidDayBits() != null ? existingPeriod.getValidDayBits() : "";
            // The existing bits sliced to [offset, offset + nDays); an out-of-range slice comes
            // back empty or short and is right-padded with '0'. B5 bounds the padding: a period
            // may carry more bits than its window, never fewer.
            int start = (int) Math.min(offset, existingBits.length());
            int end = (int) Math.min(offset + nDays, existingBits.length());
            StringBuilder sb = new StringBuilder(existingBits.substring(start, end));
            while (sb.length() < nDays) sb.append('0');
            bits = sb.toString();
            // Pure copies: the carrier objects themselves are assigned, so each travels intact and
            // re-emits its own source lexical. Do not route these through XmlDateTime.from(), which
            // would canonicalise values meant to be copied.
            fromXml = newFromDate.equals(lineFromDate)
                    ? validity.getFromDate() : existingPeriod.getFromDate();
            toXml = newToDate.equals(lineToDate)
                    ? validity.getToDate() : periodToDt;
        }

        if (bits.indexOf('1') < 0) {
            skips.emptyCalendars++;
            return null;
        }

        String newPeriodId = lineId + ":" + existingDtId + ":UicOperatingPeriod:" + safeVersion;
        UicOperatingPeriod period = new UicOperatingPeriod();
        period.setId(newPeriodId);
        period.setVersion("1");
        period.setFromDate(fromXml);
        period.setToDate(toXml);
        period.setValidDayBits(bits);

        out.add(dayType(newDayTypeId));
        out.add(period);
        // UicOperatingPeriodRef: the element is declared with type OperatingPeriodRefStructure (see
        // createUicOperatingPeriodRef), so a plain OperatingPeriodRefStructure under the Uic
        // element name marshals without xsi:type.
        out.add(dayTypeAssignment(newDtaId, newDayTypeId,
                FACTORY.createUicOperatingPeriodRef(periodRef(newPeriodId))));
        return newDayTypeId;
    }

    private static DayType dayType(String id) {
        DayType dt = new DayType();
        dt.setId(id);
        dt.setVersion("1");
        return dt;
    }

    private static OperatingPeriodRefStructure periodRef(String id) {
        OperatingPeriodRefStructure ref = new OperatingPeriodRefStructure();
        ref.setRef(id);
        ref.setVersion("1");
        return ref;
    }

    private static DayTypeAssignment dayTypeAssignment(
            String id, String dayTypeId, JAXBElement<OperatingPeriodRefStructure> periodRef) {
        DayTypeAssignment dta = new DayTypeAssignment();
        dta.setId(id);
        dta.setVersion("1");
        dta.setDayTypeRef(FACTORY.createDayTypeRef(dayTypeRef(dayTypeId)));
        dta.setOperatingPeriodRef(periodRef);
        return dta;
    }

    private static DayTypeRefStructure dayTypeRef(String id) {
        DayTypeRefStructure ref = new DayTypeRefStructure();
        ref.setRef(id);
        ref.setVersion("1");
        return ref;
    }

    // ------------------------------------------------------------------ the overlay stream

    /// The overlay, in the exact order it is generated: per validity segment, each synthesised
    /// calendar object before the journey that references it, then the re-pointed journey.
    /// Journeys every segment leaves without a day go to `dropped` instead of into the overlay.
    static List<Object> resolveJourneys(Indexes ix, Map<LineVersion, List<ValidBetween>> versionValidity,
            List<DedupDefinitionDays.Affected> affected, Skips skips, List<ServiceJourney> dropped) {
        List<Object> out = new ArrayList<>();
        Map<CacheKey, String> created = new HashMap<>();

        // Route shape: journeys reach their line version through SJP -> Route -> LineRef.
        for (Map.Entry<String, List<Line>> entry : ix.lines().entrySet()) {
            String lineId = entry.getKey();
            if (entry.getValue().size() <= 1) continue;
            for (Route route : ix.routes().getOrDefault(lineId, List.of())) {
                String routeLineVersion = route.getLineRef().getValue().getVersion();
                List<ValidBetween> validities = versionValidity.get(new LineVersion(lineId, routeLineVersion));
                // A null is a version attribute naming no Line version at all; an empty list is a
                // version iterVersionValidity considered and could give no window, already counted
                // there with its reason.
                if (validities == null) { skips.unknownVersion++; continue; }
                if (validities.isEmpty()) continue;
                String baseSafe = (routeLineVersion != null ? routeLineVersion : "unknown").replace(':', '_');
                for (ServiceJourneyPattern sjp : ix.sjps().getOrDefault(route.getId(), List.of())) {
                    for (ServiceJourney journey : ix.journeys().getOrDefault(sjp.getId(), List.of())) {
                        rewriteJourney(journey, lineId, routeLineVersion, baseSafe, validities,
                                ix, created, out, affected, skips, dropped);
                    }
                }
            }
        }

        // Verbund shape: journeys tied to their line version directly (no Route objects).
        for (Map.Entry<LineVersion, List<ServiceJourney>> entry : ix.directJourneys().entrySet()) {
            LineVersion lv = entry.getKey();
            List<ValidBetween> validities = versionValidity.get(lv);
            if (validities == null) { skips.unknownVersion++; continue; }
            if (validities.isEmpty()) continue;
            String baseSafe = (lv.version() != null ? lv.version() : "unknown").replace(':', '_');
            for (ServiceJourney journey : entry.getValue()) {
                rewriteJourney(journey, lv.lineId(), lv.version(), baseSafe, validities,
                        ix, created, out, affected, skips, dropped);
            }
        }
        return out;
    }

    /// Emits the calendar objects synthesised for one journey, one batch per validity segment, then
    /// the journey itself, re-pointed at its new DayTypeRefs.
    ///
    /// A (segment, DayType) pair whose slice carries no day contributes no ref; an all-zero
    /// calendar adds no day to the union either way. A journey every pair leaves empty runs on no
    /// date at all and goes to `dropped`.
    private static void rewriteJourney(
            ServiceJourney journey, String lineId, String lineVersion, String baseSafe,
            List<ValidBetween> validities, Indexes ix, Map<CacheKey, String> created,
            List<Object> out, List<DedupDefinitionDays.Affected> affected, Skips skips,
            List<ServiceJourney> dropped) {
        if (journey.getDayTypes() == null) return;
        List<JAXBElement<? extends DayTypeRefStructure>> allNewRefs = new ArrayList<>();
        long pairs = 0;
        for (int segIdx = 0; segIdx < validities.size(); segIdx++) {
            ValidBetween validity = validities.get(segIdx);
            String safeVersion = baseSafe + "_" + segIdx;
            // Per DayTypeRef, reuse or synthesise the (line, version, dayType) calendar triple.
            for (JAXBElement<? extends DayTypeRefStructure> dtRef : journey.getDayTypes().getDayTypeRef()) {
                pairs++;
                String existingDtId = dtRef.getValue().getRef();
                CacheKey cacheKey = new CacheKey(lineId, safeVersion, existingDtId);

                // A null value is the cached verdict "this slice carries no day", so the membership
                // test has to be containsKey: re-deriving it would count the same empty calendar
                // twice.
                if (!created.containsKey(cacheKey)) {
                    // B4. A DayType this journey names but that carries no assignment would reach
                    // makeCalendarObjects as a null period. readIndexes has already refused the
                    // assignments it cannot read, so a miss here means the ref points at no DayType
                    // the feed assigns at all.
                    DayTypeAssignment dta = ix.dayTypeAssignments().get(existingDtId);
                    if (dta == null) {
                        throw new IllegalStateException("[mentz] journey " + journey.getId()
                                + " references DayType " + existingDtId + ", which has no "
                                + "DayTypeAssignment; its days are unknown and would be taken as "
                                + "the whole of line version " + lineId + "/" + lineVersion);
                    }
                    // Non-null by B3 (a bare <Date> was refused) and resolvable by B2.
                    UicOperatingPeriod existingPeriod = ix.uicPeriods().get(periodRefOf(dta));
                    created.put(cacheKey, makeCalendarObjects(
                            lineId, existingDtId, safeVersion, existingPeriod, validity, out, skips));
                }
                String newId = created.get(cacheKey);
                if (newId != null) allNewRefs.add(FACTORY.createDayTypeRef(dayTypeRef(newId)));
            }
        }
        if (!allNewRefs.isEmpty()) {
            DayTypeRefs_RelStructure dayTypes = new DayTypeRefs_RelStructure();
            dayTypes.getDayTypeRef().addAll(allNewRefs);
            journey.setDayTypes(dayTypes);
            out.add(journey);
            // Exactly the population phase 2 dedups: every journey that now carries a per-version
            // synthesised calendar, with the line version it was re-pointed for.
            affected.add(new DedupDefinitionDays.Affected(journey, lineId, lineVersion));
        } else if (pairs > 0) {
            // Every calendar it could have had is empty. A journey whose <dayTypes> holds no ref
            // at all reaches neither arm and is left as it was: it is day-less in the source,
            // which is a different defect.
            dropped.add(journey);
        }
    }
}
