package fix;

import noi.netex.model.JourneyMeeting;
import noi.netex.model.JourneyRefStructure;
import noi.netex.model.ServiceJourney;
import transformers.xb.XbLines;
import java.util.HashMap;
import java.util.function.Function;
import noi.netex.model.ServiceJourneyPattern;
import noi.netex.model.Route;
import noi.netex.model.Line;
import toolkit.harness.DbToDb;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Predicate;

/// Drops journeys a feed republishes from another feed's network — a db-to-db transform despite
/// living in fix/.
///
/// The national/regional aggregators re-import each other's networks wholesale, so the fused
/// multi-feed pipeline would publish those trips twice:
///
/// - the Swiss dataset carries the entire Verkehrsverbund Vorarlberg network under the
///    UIC-country-prefixed operator `ch:1:Operator:81_VVV` (188 lines / ~12.4k journeys,
///    of which only ~240 are on border-crossing lines) — the at-vbg feed is authoritative for it;
/// - the vbg feed republishes the LIEmobil network (responsibility set
///    `at:vvv:ResponsibilitySet:VA_LIEm:`, ~1.2k journeys) — LIEmobil's official publisher
///    is the Swiss NAP (operator `ch:1:Operator:805`);
/// - the STA feed republishes the Tyrolean Reschen-corridor lines 273/210
///    (`it:apb:Operator:070:` Tiroler Linien Bus GmbH, ~300 journeys) — owned by the vvt feed.
///
/// Journeys match on their own operator ref or responsibility set, or on their line's operator —
/// resolved through the direct LineRef (Verbund shape) or SJP → Route → Line (OeBB/STA shape).
/// Deliberately not dropped: `81____` (OeBB rail in the Swiss feed — the cross-border coupling
/// pairs those legs) and the `80_*` German operators (with DELFI outside this pipeline, the Swiss
/// feed is their only source).
///
/// Usage: `fix/DropForeignJourneys.java <source> <target> [--log-file F] [--format v2]`
public class DropForeignJourneys {

    /// (kind, id) — kind selects what the id is compared against.
    static final String[][] DROP_RULES = {
            {"operator", "ch:1:Operator:81_VVV"},                        // ch: VVV network re-import
            {"responsibility_set", "at:vvv:ResponsibilitySet:VA_LIEm:"}, // vbg: LIEmobil republication
            {"operator", "it:apb:Operator:070:"},                        // sta: Tiroler Linien Bus lines
    };

    // HashSet, not Set.of: the lookups probe possibly-null values, where a null is simply not a
    // match — Set.of would throw on contains(null).
    private static final Set<String> DROP_OPERATORS = new HashSet<>();
    private static final Set<String> DROP_RESP_SETS = new HashSet<>();
    static {
        for (String[] rule : DROP_RULES) {
            if ("operator".equals(rule[0])) DROP_OPERATORS.add(rule[1]);
            if ("responsibility_set".equals(rule[0])) DROP_RESP_SETS.add(rule[1]);
        }
    }

    public static void main(String[] args) {
        DbToDb.driverMain("drop-foreign-journeys", args, DropForeignJourneys::apply);
    }

    /// Copy every class verbatim except ServiceJourney and JourneyMeeting, which are re-emitted
    /// filtered. The dropped journeys' patterns/calendars stay behind unreferenced — inert
    /// downstream, exactly like the feeds' unreferenced originals in the fused pipeline.
    ///
    /// A meeting is not inert, which is why it is filtered and a pattern is not. EpipToDb turns
    /// every JourneyMeeting into the ServiceJourneyInterchange the rest of the pipeline reads, and
    /// an interchange naming a journey that is no longer there survives all the way into the graph
    /// build.
    ///
    /// Only meetings pointing at a journey this pass dropped are removed. A meeting naming a
    /// journey that was never in the feed is a different defect, and leaving it visible is what
    /// keeps this pass from masking it.
    public static void apply(Store source, Txn sourceTxn, Store target, Txn targetTxn) {
        XbLines.LineMaps<String> maps = XbLines.lineValueMaps(source, sourceTxn,
                line -> line.getOperatorRef() != null ? line.getOperatorRef().getRef() : null);

        List<Class<?>> classes = new ArrayList<>(source.dbNames(sourceTxn));
        for (Class<?> clazz : classes) {
            if (clazz == ServiceJourney.class || clazz == JourneyMeeting.class) continue;
            source.copyMap(sourceTxn, target, targetTxn, clazz);
        }

        // The ids this pass drops, for the meeting sweep below: 12,383 on the largest store (ch),
        // 1,699 on at, 401 on sta.
        Set<String> droppedIds = new HashSet<>();

        long[] journeys = {0, 0}; // {dropped, kept}
        target.insertAnyObjects(targetTxn, dropping(
                source.iterOnlyObjects(sourceTxn, ServiceJourney.class).iterator(), journeys,
                o -> {
                    ServiceJourney sj = (ServiceJourney) o;
                    if (!journeyMatches(sj, maps.lineMap(), maps.sjpLine())) return false;
                    droppedIds.add(sj.getId());
                    return true;
                }));
        Log.info("[drop_foreign] dropped %d republished journeys, kept %d", journeys[0], journeys[1]);

        // After the journeys, not beside them: insertAnyObjects drains its iterator before it
        // returns, so droppedIds is complete by the time the predicate below reads it.
        if (classes.contains(JourneyMeeting.class)) {
            long[] meetings = {0, 0};
            target.insertAnyObjects(targetTxn, dropping(
                    source.iterOnlyObjects(sourceTxn, JourneyMeeting.class).iterator(), meetings,
                    o -> meetingIsOrphaned((JourneyMeeting) o, droppedIds)));
            Log.info("[drop_foreign] dropped %d meetings on a dropped journey, kept %d",
                    meetings[0], meetings[1]);
        }
    }

    /// Streaming filter: `drop` decides, `stats` counts {dropped, kept}. The largest input here is
    /// the Swiss store's 2.07 M journeys.
    private static Iterator<Object> dropping(Iterator<Object> src, long[] stats,
            Predicate<Object> drop) {
        return new Iterator<>() {
            private Object next;

            @Override
            public boolean hasNext() {
                while (next == null && src.hasNext()) {
                    Object o = src.next();
                    if (drop.test(o)) {
                        stats[0]++;
                    } else {
                        stats[1]++;
                        next = o;
                    }
                }
                return next != null;
            }

            @Override
            public Object next() {
                if (!hasNext()) throw new NoSuchElementException();
                Object out = next;
                next = null;
                return out;
            }
        };
    }

    /// True when either end of the meeting names a journey this pass just dropped. A null ref on
    /// either end cannot be orphaned by us, so it is kept and stays whatever it already was.
    static boolean meetingIsOrphaned(JourneyMeeting jm, Set<String> droppedIds) {
        return namesDropped(jm.getFromJourneyRef(), droppedIds)
                || namesDropped(jm.getToJourneyRef(), droppedIds);
    }

    private static boolean namesDropped(JourneyRefStructure ref, Set<String> droppedIds) {
        return ref != null && ref.getRef() != null && droppedIds.contains(ref.getRef());
    }

    /// The 3-way match — own responsibility set, own operator, line operator.
    static boolean journeyMatches(ServiceJourney sj, Map<String, String> lineOperator, Map<String, String> sjpLine) {
        // (1) the responsibilitySetRef attribute.
        if (DROP_RESP_SETS.contains(sj.getResponsibilitySetRef())) return true;
        // (2) the journey's own operator ref, read off the operator-ref-or-view union: an
        // OperatorView carries no ref, so only getOperatorRef() can contribute one.
        String ownOp = sj.getOperatorRef() != null ? sj.getOperatorRef().getRef() : null;
        if (DROP_OPERATORS.contains(ownOp)) return true;
        // (3) the line's operator, via the direct LineRef or the SJP -> Route -> Line chain.
        return DROP_OPERATORS.contains(lineOperator.get(XbLines.journeyLineId(sj, sjpLine)));
    }

}
