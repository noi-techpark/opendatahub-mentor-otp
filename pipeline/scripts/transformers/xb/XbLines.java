package transformers.xb;

// Which Line a journey belongs to.
//
// Moved out of the removed transformers.crossborder cone, where it sat beside the coupler but was
// never part of it: a feed whose journeys carry no direct LineRef reaches its Line only through
// pattern -> route -> line, and both the foreign-journey drop and the stop merge need that chain.

import noi.netex.model.Line;
import noi.netex.model.Route;
import noi.netex.model.ServiceJourney;
import noi.netex.model.ServiceJourneyPattern;
import toolkit.store.Store;
import toolkit.store.Txn;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public final class XbLines {

    private XbLines() {}

    /// `lineMap` — Line id → the extracted value, Lines with none skipped. `sjpLine` —
    /// ServiceJourneyPattern id → Line id, resolved pattern → route → line, for feeds whose
    /// journeys reference their line only through the pattern.
    public record LineMaps<V>(Map<String, V> lineMap, Map<String, String> sjpLine) {}

    /// A per-Line attribute lookup plus the pattern route that reaches it.
    ///
    /// `FlexibleLine` is not indexed: it is a sibling of `Line` under `Line_VersionStructure`
    /// rather than a subclass, so the class-exact scan below misses it and a journey on one
    /// resolves no line id. That is 1,361 Austrian AST journeys, whose FlexibleLines carry
    /// `TransportMode=bus`. Widening the scan also changes what `fix/DropForeignJourneys` sees.
    public static <V> LineMaps<V> lineValueMaps(Store db, Txn txn, Function<Line, V> lineValue) {
        Map<String, V> lineMap = new HashMap<>();
        for (Object o : db.iterOnlyObjects(txn, Line.class)) {
            Line line = (Line) o;
            V v = lineValue.apply(line);
            if (v != null) lineMap.put(line.getId(), v);
        }
        Map<String, String> routeLine = new HashMap<>();
        for (Object o : db.iterOnlyObjects(txn, Route.class)) {
            Route route = (Route) o;
            if (route.getLineRef() != null) routeLine.put(route.getId(), route.getLineRef().getValue().getRef());
        }
        Map<String, String> sjpLine = new HashMap<>();
        for (Object o : db.iterOnlyObjects(txn, ServiceJourneyPattern.class)) {
            ServiceJourneyPattern sjp = (ServiceJourneyPattern) o;
            // A route VIEW carries no ref, so only the ref side of the choice contributes.
            String routeRef = sjp.getRouteRef() != null ? sjp.getRouteRef().getRef() : null;
            if (routeRef != null && routeLine.containsKey(routeRef)) {
                sjpLine.put(sjp.getId(), routeLine.get(routeRef));
            }
        }
        return new LineMaps<>(lineMap, sjpLine);
    }

    /// The id of the Line a journey belongs to: the direct LineRef, else the pattern → route →
    /// line chain. Null when neither resolves. The view variants of the schema's choice carry no
    /// ref, so the ref accessor covers it.
    public static String journeyLineId(ServiceJourney sj, Map<String, String> sjpLine) {
        String lineId = sj.getLineRef() != null ? sj.getLineRef().getValue().getRef() : null;
        if (lineId == null && sj.getJourneyPatternRef() != null) {
            lineId = sjpLine.get(sj.getJourneyPatternRef().getValue().getRef());
        }
        return lineId;
    }
}
