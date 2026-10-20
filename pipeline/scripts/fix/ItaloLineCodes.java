package fix;

// The Italo stage: splits the sold connections into the runs they are made of, then labels the
// Lines. The Makefile names this file by path and runs it in the Italo chain.
//
// Italo publishes one Line per pair of train numbers and names it after the pair -- Name and
// PublicCode both `9963_9944`. The split (`ItaloConnections`) removes those pairs, leaving Lines of
// one number each, a coach number's under its own Itabus Line. Every train Line then carries the
// operator as its public code, there being no line number for it to carry, and its name read as
// numbers. A trailing `_#N` is the variant counter the Line's id also carries, so it goes
// before the name is spelled.
//
// Italo publishes no TrainNumber, and its journey names are stop codes. Each journey the split
// leaves numbered -- a solo journey or a leg, its number as PublicCode -- is linked to a TrainNumber
// of that number. The id stays in `IT::`, apart from Trenitalia's `IT:TrainNumber:` in the rail
// merge. Coach legs are buses and get none.
//
// It runs after the sequence repair, which the split reads positionally, and ahead of the id
// normalisation: the split mints its ids in the feed's own `IT::` space and the normalisation moves
// them with the rest. It also runs ahead of `align-italo-stop-names`, which keeps the bus stops
// this pass creates out of its renaming.

import noi.netex.model.AllVehicleModesOfTransportEnumeration;
import noi.netex.model.Line;
import noi.netex.model.ServiceJourney;
import noi.netex.text.Codes;
import toolkit.harness.DbToDb;
import toolkit.store.Store;
import toolkit.store.Txn;
import transformers.common.TrainNumbers;
import transformers.feedfix.ItaloConnections;
import transformers.feedfix.LineCodes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public class ItaloLineCodes {

    private ItaloLineCodes() {}

    public static void main(String[] args) {
        DbToDb.driverMain("italo-line-codes", args, ItaloLineCodes::apply);
    }

    public static void apply(Store src, Txn stx, Store dst, Txn dtx) {
        ItaloConnections.Split split = ItaloConnections.split(src, stx);
        List<Line> labelled = LineCodes.repairLineCodes(
                src, stx, LineCodes.Policy.ITALO, new LinkedHashMap<>());
        labelled.removeIf(l -> split.removedLines().contains(l.getId()));
        dst.cloneFrom(src, stx, dtx, split.skip().isEmpty() ? null : split.skip()::contains, 0);
        List<Object> rest = new ArrayList<>();
        List<ServiceJourney> journeys = new ArrayList<>();
        for (Object o : split.objects()) {
            if (o instanceof ServiceJourney sj) journeys.add(sj);
            else rest.add(o);
        }
        dst.insertAnyObjects(dtx, rest.iterator());
        dst.insertAnyObjects(dtx, TrainNumbers.synthesizeTrainNumbers(journeys.iterator(),
                ItaloLineCodes::trainNumber, n -> "IT::TrainNumber:" + n, "italo train-numbers"));
        dst.insertAnyObjects(dtx, labelled.iterator());
    }

    static String trainNumber(ServiceJourney sj) {
        if (sj.getTransportMode() == AllVehicleModesOfTransportEnumeration.BUS) return null;
        return Codes.text(sj.getPublicCode());
    }
}
