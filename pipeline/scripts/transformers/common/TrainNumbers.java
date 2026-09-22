package transformers.common;

// TrainNumber synthesis, with the Trenitalia normalisation rules.
//
// One streaming pass re-emits every ServiceJourney, minting one first-class TrainNumber per
// distinct normalized number. Journeys already carrying trainNumbers are skipped; only the small
// per-distinct-number map is retained, so the journeys themselves are never held in memory.

import noi.netex.text.Mls;
import noi.netex.model.MultilingualString;
import noi.netex.model.ServiceJourney;
import noi.netex.model.TrainNumber;
import noi.netex.model.TrainNumberRefStructure;
import noi.netex.model.TrainNumberRefs_RelStructure;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.util.Log;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TrainNumbers {

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private TrainNumbers() {}

    /// Normalise a train number: trim, take the first run of digits, drop leading zeros.
    /// BigInteger handles arbitrarily long runs.
    public static String normalizeTrainNumber(String num) {
        if (num == null) return null;
        Matcher m = DIGITS.matcher(num.strip());
        return m.find() ? new BigInteger(m.group()).toString() : null;
    }

    /// The journey's train number, read off the first content item of its name. The name is a
    /// mixed-content list, so [Mls#hasText] tests that the list is non-empty and [Mls#text] reads
    /// it. This site means the first content item literally, while `Mls.text` joins a multi-part
    /// carrier: the same result only where every carrier has exactly one part.
    public static String trainNumberForJourney(ServiceJourney sj) {
        MultilingualString name = sj.getName();
        if (Mls.hasText(name)) {
            return normalizeTrainNumber(Mls.text(name));
        }
        return null;
    }

    private static String tnId(String num) {
        return "IT:TrainNumber:" + num;
    }

    /// One TrainNumber per distinct number found in a journey's NAME.
    public static Iterator<Object> updates(Store db, Txn txn) {
        return synthesizeTrainNumbers(db, txn, TrainNumbers::trainNumberForJourney,
                TrainNumbers::tnId, "trenitalia train-numbers");
    }

    /// Mint the missing TrainNumbers, as a pull iterator: for each journey, a newly minted
    /// TrainNumber first, then the journey itself.
    public static Iterator<Object> synthesizeTrainNumbers(Store db, Txn txn,
            Function<ServiceJourney, String> numberForJourney, Function<String, String> tnId,
            String logTag) {
        Iterator<Object> journeys = db.iterOnlyObjects(txn, ServiceJourney.class).iterator();
        return new Iterator<>() {
            final Map<String, TrainNumber> trainNumbers = new LinkedHashMap<>();
            final Deque<Object> ready = new ArrayDeque<>(2);
            long linked = 0;
            boolean loggedDone;

            private void pump() {
                while (ready.isEmpty() && journeys.hasNext()) {
                    ServiceJourney sj = (ServiceJourney) journeys.next();
                    String num = sj.getTrainNumbers() == null ? numberForJourney.apply(sj) : null;
                    if (num != null && !num.isEmpty()) {
                        TrainNumber tn = trainNumbers.get(num);
                        if (tn == null) {
                            tn = new TrainNumber();
                            tn.setId(tnId.apply(num));
                            tn.setVersion(sj.getVersion());
                            tn.setForAdvertisement(num);
                            trainNumbers.put(num, tn);
                            ready.addLast(tn);
                        }
                        TrainNumberRefStructure ref = new TrainNumberRefStructure();
                        ref.setRef(tn.getId());
                        ref.setVersion(tn.getVersion());
                        TrainNumberRefs_RelStructure rel = new TrainNumberRefs_RelStructure();
                        rel.getTrainNumberRef().add(ref);
                        sj.setTrainNumbers(rel);
                        linked++;
                    }
                    ready.addLast(sj);
                }
                if (ready.isEmpty() && !loggedDone) {
                    loggedDone = true;
                    Log.info("[%s] linked %d journeys -> %d distinct train numbers",
                            logTag, linked, trainNumbers.size());
                }
            }

            @Override
            public boolean hasNext() {
                pump();
                return !ready.isEmpty();
            }

            @Override
            public Object next() {
                if (!hasNext()) throw new NoSuchElementException();
                return ready.removeFirst();
            }
        };
    }
}
