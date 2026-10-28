package transformers.xb;

// Reading the corpus: the station index, one full ServiceJourney pass, and the keyed second pass
// that builds the nodes matching works on.
//
// The full pass retains eight bytes per journey: the row's full key, a long, which
// `loadObjectByFullKey` takes directly. No id is held that the second pass does not want.
//
// The properties this stage depends on are guaranteed by running after EpipToDb, and each is
// checked here rather than assumed: a silent violation of any of them would leave the coupling
// running and quietly not matching night trains.

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import jakarta.xml.bind.JAXBElement;
import noi.netex.model.JourneyPatternRefStructure;
import noi.netex.model.JourneyPattern_VersionStructure;
import noi.netex.model.PassengerStopAssignment;
import noi.netex.model.PointInJourneyPatternRefStructure;
import noi.netex.model.PointInLinkSequence_VersionedChildStructure;
import noi.netex.model.ScheduledStopPointRefStructure;
import noi.netex.model.ServiceJourney;
import noi.netex.model.StopPlace;
import noi.netex.model.StopPlaceRefStructure;
import noi.netex.model.StopPointInJourneyPattern_VersionedChildStructure;
import noi.netex.model.TimetabledPassingTime;
import noi.netex.model.TrainNumber;
import noi.netex.model.TrainNumberRefStructure;
import noi.netex.text.Mls;
import toolkit.store.ObjectRow;
import toolkit.store.Store;
import toolkit.store.Txn;
import toolkit.transform.common.ScheduledStopPoints;
import toolkit.util.Log;
import transformers.xb.XbCalendar.DaySet;
import transformers.xb.XbTypes.Dict;
import transformers.xb.XbTypes.Node;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class XbScan {

    /// How many patterns the second pass keeps decoded at once. Variants of one train share a
    /// pattern and the walk is grouped by number, so the hit rate is high and the ceiling is only
    /// there to stop a pathological corpus retaining all 564 k.
    private static final int PATTERN_CACHE = 4096;

    private XbScan() {}

    // ------------------------------------------------------------------------------------- //
    // Stations
    // ------------------------------------------------------------------------------------- //

    /// Every consolidated station, and the two things the rest of the stage asks about one: where it
    /// is, and which country it is in.
    ///
    /// @param ids     StopPlace id ↔ int.
    /// @param bySsp   ScheduledStopPoint ref → station int, from the PassengerStopAssignments.
    /// @param country station int → country int.
    /// @param lat     station int → latitude, NaN when the station has no centroid.
    /// @param lon     station int → longitude.
    public record Stations(Dict ids, Object2IntOpenHashMap<String> bySsp, int[] country,
            double[] lat, double[] lon) {

        public int of(String sspRef) {
            return bySsp.getInt(sspRef);   // defaultReturnValue is NONE
        }

        public boolean located(int station) {
            return station != XbTypes.NONE && !Double.isNaN(lat[station]);
        }

        /// Release the stop-point index once the nodes are built. It is the largest thing this
        /// record holds — one interned string per stop point, 418,619 of them — and everything
        /// after pass 2 works in station ints.
        public void releaseStopPointIndex() {
            bySsp.clear();
            bySsp.trim();
        }
    }

    /// Build the station index. Runs on the CONSOLIDATED corpus, so every feed's copy of a physical
    /// station is one StopPlace and two publishers' stop points resolve to the same int — which is
    /// what lets the shared-station test be an integer intersection instead of a geometry problem.
    ///
    /// A station's country is voted from the stop points assigned to it, not read off the StopPlace
    /// id, and the vote is two-tier. A stop point carrying a HAFAS `xx-` infix or a real UIC code
    /// says where the station is; one carrying only an id space says who published it. So a strong
    /// vote wins outright wherever there is one, and the id-space votes decide only when there is
    /// not. After consolidation every feed's stop points hang off the one survivor, including a
    /// foreign feed's explicitly-coded copy — ÖBB's
    /// `at:obb:ScheduledStopPoint:ch-23016-20302-0-2:` says Switzerland in as many words.
    public static Stations readStations(Store db, Txn txn, Dict countries) {
        // Interned and located in one pass: the station's int is known the moment it is interned,
        // so the two growable columns can be filled right there.
        Dict ids = new Dict();
        DoubleArrayList latCol = new DoubleArrayList();
        DoubleArrayList lonCol = new DoubleArrayList();
        for (Object o : db.iterOnlyObjects(txn, StopPlace.class)) {
            StopPlace sp = (StopPlace) o;
            int i = ids.intern(sp.getId());
            if (i != latCol.size()) continue;              // a duplicate id: first sighting wins
            double[] c = ScheduledStopPoints.stopplaceCoord(sp);
            latCol.add(c != null ? c[0] : Double.NaN);
            lonCol.add(c != null ? c[1] : Double.NaN);
        }
        int n = ids.size();

        Object2IntOpenHashMap<String> bySsp = new Object2IntOpenHashMap<>();
        bySsp.defaultReturnValue(XbTypes.NONE);
        // The vote, flattened: one open-addressed map keyed by (station, country) packed into a
        // long. Two maps rather than one because a strong vote and a weak vote never compete.
        Long2IntOpenHashMap strongVotes = new Long2IntOpenHashMap();
        Long2IntOpenHashMap weakVotes = new Long2IntOpenHashMap();
        for (Object o : db.iterOnlyObjects(txn, PassengerStopAssignment.class)) {
            PassengerStopAssignment psa = (PassengerStopAssignment) o;
            String ssp = sspRef(psa);
            String sp = stopPlaceRef(psa);
            if (ssp == null || sp == null) continue;
            int station = ids.lookup(sp);
            if (station == XbTypes.NONE) continue;   // an assignment to a StopPlace not in the store
            bySsp.putIfAbsent(ssp, station);
            String strong = strongCountry(ssp);
            String cc = strong != null ? strong : XbIds.stopSpaceCountry(ssp);
            if (cc == null) continue;
            long slot = ((long) station << 32) | (countries.intern(cc) & 0xffffffffL);
            (strong != null ? strongVotes : weakVotes).addTo(slot, 1);
        }

        int[] country = elect(n, strongVotes, weakVotes, countries);
        double[] lat = latCol.toDoubleArray();
        double[] lon = lonCol.toDoubleArray();
        int located = 0;
        int classified = 0;
        for (int i = 0; i < n; i++) {
            if (!Double.isNaN(lat[i])) located++;
            if (country[i] != XbTypes.NONE) classified++;
        }
        Log.info("[xb] stations: %d StopPlaces, %d located, %d with a country, %d stop points assigned",
                n, located, classified, bySsp.size());
        return new Stations(ids, bySsp, country, lat, lon);
    }

    /// The country a stop id STATES, as opposed to the one its id space implies: a HAFAS `xx-`
    /// infix, else a real UIC code. Null when the id only says who published it.
    static String strongCountry(String sspRef) {
        String viaSpace = XbIds.stopSpaceCountry(sspRef);
        String decided = XbIds.stopCountry(sspRef);
        if (decided == null) return null;
        // stopCountry falls through to the id space last; a decision that DIFFERS from the id space
        // came from one of the two strong arms, and one that agrees may have come from either — in
        // which case it does not matter, since both say the same thing.
        return decided.equals(viaSpace) ? null : decided;
    }

    /// Elect a country per station from the flattened votes.
    ///
    /// Strong votes decide; weak votes decide only among stations no strong vote named. Ties fall
    /// back to [XbProfile#BORDER_PRECEDENCE], so a border station two countries code natively lands
    /// on the same side every run.
    ///
    /// The running maximum is kept in two columns: a station's winner is decided by the entries as
    /// they are seen, and the comparison is total (count, then precedence), so the order they are
    /// seen in does not change the answer.
    private static int[] elect(int n, Long2IntOpenHashMap strongVotes, Long2IntOpenHashMap weakVotes,
            Dict countries) {
        int[] country = new int[n];
        Arrays.fill(country, XbTypes.NONE);
        int[] bestCount = new int[n];
        runningMax(strongVotes, country, bestCount, countries);
        if (!weakVotes.isEmpty()) {
            int[] weakCountry = new int[n];
            Arrays.fill(weakCountry, XbTypes.NONE);
            int[] weakCount = new int[n];
            runningMax(weakVotes, weakCountry, weakCount, countries);
            for (int i = 0; i < n; i++) {
                if (country[i] == XbTypes.NONE) country[i] = weakCountry[i];
            }
        }
        return country;
    }

    private static void runningMax(Long2IntOpenHashMap votes, int[] winner, int[] count,
            Dict countries) {
        for (Long2IntMap.Entry e : votes.long2IntEntrySet()) {
            int station = (int) (e.getLongKey() >>> 32);
            int cc = (int) e.getLongKey();
            int n = e.getIntValue();
            int cmp = Integer.compare(n, count[station]);
            if (cmp == 0) {
                cmp = -Integer.compare(precedence(cc, countries),
                        precedence(winner[station], countries));
            }
            if (cmp > 0) {
                winner[station] = cc;
                count[station] = n;
            }
        }
    }

    private static int precedence(int country, Dict countries) {
        int i = XbProfile.BORDER_PRECEDENCE.indexOf(countries.value(country));
        return i < 0 ? XbProfile.BORDER_PRECEDENCE.size() : i;
    }

    public static String sspRef(PassengerStopAssignment psa) {
        JAXBElement<? extends ScheduledStopPointRefStructure> el = psa.getScheduledStopPointRef();
        return el != null ? el.getValue().getRef() : null;
    }

    public static String stopPlaceRef(PassengerStopAssignment psa) {
        JAXBElement<? extends StopPlaceRefStructure> el = psa.getStopPlaceRef();
        return el != null ? el.getValue().getRef() : null;
    }

    // ------------------------------------------------------------------------------------- //
    // Pass 1
    // ------------------------------------------------------------------------------------- //

    /// What the full journey pass produces. Nothing here is O(journeys) in anything but longs.
    ///
    /// @param numbers      normalised train number ↔ int.
    /// @param byNum        number int → the full keys of the journeys carrying it, in cursor order.
    /// @param sharedPatterns which pattern ids more than one journey references.
    /// @param tnRefcount   TrainNumber id → how many journeys reference it, before coupling.
    /// @param gates        what the preconditions measured.
    /// @param rail         which stops a RAIL journey serves, collected on the way past.
    public record Pass1(Dict numbers, Int2ObjectLinkedOpenHashMap<LongArrayList> byNum,
            SharedPatterns sharedPatterns, Object2IntMap<String> tnRefcount, Gates gates,
            RailSeen rail) {}

    /// What a rail journey said about its stops, gathered during the pass that was already reading
    /// it, for [XbStations#railServed] to expand.
    ///
    /// What is retained is O(patterns), not O(journeys). A rail journey that carries a pattern
    /// contributes only its pattern id, expanded to stops afterwards against the pattern map — so
    /// the ~499 k pattern ids are the bound, and the 19 M stop refs that map holds are still built
    /// once, late, and thrown away. Only a journey carrying `<Calls>` (which post-EPIP is none, but
    /// the reader must not assume it) contributes stop refs directly.
    public record RailSeen(Set<String> patterns, Set<String> stopPoints) {}

    /// "Is this pattern used by more than one journey?" — the only question the truncation asks of
    /// the usage count. Two bitsets over a hash of the id answer it: set a bit the first time an id
    /// is seen, and if it was already set, record the id as shared.
    ///
    /// The error is one-sided. A collision makes an unseen pattern look already-seen, so it is
    /// reported shared when it is single-use, and the truncation then forks a pattern it could have
    /// sliced in place. The opposite error would slice a shared pattern out from under other
    /// journeys, and this structure cannot produce it: a pattern really seen twice always has its
    /// bit set. TestXbSharedPatterns pins both directions.
    public static final class SharedPatterns {

        private static final int BITS = 1 << 26;
        private static final int MASK = BITS - 1;

        private final long[] seen = new long[BITS >>> 6];
        private final long[] shared = new long[BITS >>> 6];
        private long distinct;
        private long collisions;

        public void note(String patternRef) {
            int h = slot(patternRef);
            if (get(seen, h)) {
                if (!get(shared, h)) collisions++;   // an upper bound on false positives, not exact
                set(shared, h);
            } else {
                set(seen, h);
                distinct++;
            }
        }

        /// Whether this pattern must be forked rather than sliced in place.
        public boolean isShared(String patternRef) {
            return get(shared, slot(patternRef));
        }

        public long distinctPatterns() {
            return distinct;
        }

        public long sharedPatterns() {
            return collisions;
        }

        private static int slot(String s) {
            // Fibonacci-mixed so that ids sharing a long common prefix do not cluster.
            int h = s.hashCode();
            h *= 0x9E3779B1;
            return (h ^ (h >>> 16)) & MASK;
        }

        private static boolean get(long[] bits, int i) {
            return (bits[i >>> 6] & (1L << i)) != 0;
        }

        private static void set(long[] bits, int i) {
            bits[i >>> 6] |= 1L << i;
        }
    }

    /// The precondition counters. Two of these refuse the run; the rest are exposure.
    public static final class Gates {
        /// Journeys still carrying `Calls`. EpipJourneys nulls it on every journey it emits, so a
        /// non-zero count means this stage is not running after the profile conversion and every
        /// stop-sequence reader here is looking at the wrong field. Refuses.
        public long withCalls;
        /// Passing-time values that are not legal `xs:time` — hour 24 and later. DayOffsetRewrite is
        /// EpipToDb phase D, so post-EPIP there are none; a non-zero count means it did not run, and
        /// then every after-midnight time compares as midnight and the night trains silently stop
        /// matching. Refuses.
        public long hour24Values;
        /// Journeys with no resolvable pattern. Counted, not fatal: the feed published them that way
        /// and the consumer already discards them.
        public long noPattern;
        /// Journeys whose DayTypeRef resolves to no operating period, so they run on no days and can
        /// never match. An EpipJourneys gap (it emits a ref to a DayType it did not construct);
        /// counted, because this stage neither caused it nor can repair it.
        public long noCalendar;
        /// Journeys whose id space no table knows, so their publisher — and therefore which part of
        /// the route is theirs — is unknown.
        public long noHome;
        /// Journeys carrying no train number at all, under either publisher's convention.
        public long noNumber;
        public long journeys;

        void refuseOnPreconditions() {
            if (withCalls > 0) {
                throw new IllegalStateException("[xb] PRECONDITION: " + withCalls
                        + " ServiceJourneys still carry <Calls>. This stage must run AFTER the EPIP "
                        + "profile conversion, which normalises every journey to passing times plus a "
                        + "pattern.");
            }
            if (hour24Values > 0) {
                throw new IllegalStateException("[xb] PRECONDITION: " + hour24Values
                        + " passing-time values are not legal xs:time (hour 24 or later). The "
                        + "after-midnight rewrite has not run, so every value past midnight would "
                        + "compare as midnight and the handover window would silently break on the "
                        + "night trains. Run EpipToDb with -Dtoolkit.epip.dayOffsetRewrite=on "
                        + "to normalise them.");
            }
        }

        void report() {
            Log.info("[xb] scanned %d journeys; gates: calls %d, hour-24 values %d, no pattern %d, "
                    + "no calendar %d, no publisher %d, no number %d",
                    journeys, withCalls, hour24Values, noPattern, noCalendar, noHome, noNumber);
        }
    }

    /// The one full pass over the corpus.
    ///
    /// Everything journey-derived that the rest of the stage needs comes out of this single
    /// iteration: the number index, the publisher-country mask per number, pattern usage, the
    /// pre-coupling train-number reference counts and the gate counters.
    ///
    /// It classifies nothing. Classification needs the stop sequence, the stop sequence needs the
    /// pattern, and doing that here would mean either retaining a stop sequence per journey or
    /// holding every pattern's point map.
    ///
    /// @param modes Line → transport mode and pattern → line, so a journey's mode can be resolved
    ///              here rather than in a scan of its own. Null skips the rail classification and
    ///              yields an empty [RailSeen].
    public static Pass1 pass1(Store db, Txn txn, Map<String, DaySet> dayTypeDates,
            XbLines.LineMaps<noi.netex.model.AllVehicleModesOfTransportEnumeration> modes) {
        Dict numbers = new Dict();
        Int2ObjectLinkedOpenHashMap<LongArrayList> byNum = new Int2ObjectLinkedOpenHashMap<>();
        Int2IntOpenHashMap numCountries = new Int2IntOpenHashMap();
        SharedPatterns sharedPatterns = new SharedPatterns();
        Object2IntOpenHashMap<String> tnRefcount = new Object2IntOpenHashMap<>();
        Gates gates = new Gates();

        Set<String> railPatterns = new HashSet<>();
        Set<String> railStopPoints = new HashSet<>();

        Object2ObjectOpenHashMap<String, String> tnValue = new Object2ObjectOpenHashMap<>();
        for (Object o : db.iterOnlyObjects(txn, TrainNumber.class)) {
            TrainNumber tn = (TrainNumber) o;
            tnValue.put(tn.getId(), XbIds.normalizeTrainNumber(tn.getForAdvertisement()));
        }
        Dict countryBits = new Dict();

        for (ObjectRow row : db.iterObjects(txn, ServiceJourney.class)) {
            ServiceJourney sj = (ServiceJourney) row.object();
            gates.journeys++;
            if (sj.getCalls() != null) gates.withCalls++;
            if (sj.getPassingTimes() != null) {
                for (TimetabledPassingTime t : sj.getPassingTimes().getTimetabledPassingTime()) {
                    if (illegal(t)) gates.hour24Values++;
                }
            }
            JAXBElement<? extends JourneyPatternRefStructure> pref = sj.getJourneyPatternRef();
            if (pref == null) {
                gates.noPattern++;
            } else {
                sharedPatterns.note(pref.getValue().getRef());
            }
            if (sj.getTrainNumbers() != null) {
                for (TrainNumberRefStructure r : sj.getTrainNumbers().getTrainNumberRef()) {
                    tnRefcount.addTo(r.getRef(), 1);
                }
            }
            if (XbCalendar.journeyDays(sj, dayTypeDates).isEmpty()) gates.noCalendar++;
            if (modes != null) {
                noteRailStops(sj, modes, railPatterns, railStopPoints);
            }

            String home = XbIds.feedCountry(sj.getId());
            if (home == null) {
                gates.noHome++;
                continue;
            }
            String num = journeyNumber(sj, tnValue);
            if (num == null) {
                gates.noNumber++;
                continue;
            }
            int ni = numbers.intern(num);
            byNum.computeIfAbsent(ni, k -> new LongArrayList())
                    .add(db.fullKeyOf(ServiceJourney.class, row.localKey()));
            numCountries.put(ni, numCountries.get(ni) | (1 << countryBits.intern(home)));
        }
        gates.report();
        gates.refuseOnPreconditions();

        // Pruned here rather than in pass 2. A number carried by one publisher country cannot
        // produce a link whatever its journeys look like, so its keys are dead the moment this pass
        // ends and need not be carried through the stage's peak.
        long droppedNumbers = 0;
        long droppedKeys = 0;
        for (var it = byNum.int2ObjectEntrySet().iterator(); it.hasNext();) {
            Int2ObjectMap.Entry<LongArrayList> e = it.next();
            if (Integer.bitCount(numCountries.get(e.getIntKey())) >= 2) {
                e.getValue().trim();       // the doubling growth left up to half the array unused
                continue;
            }
            droppedNumbers++;
            droppedKeys += e.getValue().size();
            it.remove();
        }
        byNum.trim();
        Log.info("[xb] pass 1: %d train numbers over %d journeys; %d numbers (%d journeys) are "
                + "carried by ONE country and were dropped, %d numbers retained",
                numbers.size(), gates.journeys, droppedNumbers, droppedKeys, byNum.size());
        Log.info("[xb] pass 1: %d distinct patterns, %d of them shared (an upper bound: the "
                + "shared-pattern filter over-reports on a hash collision, never under-reports)",
                sharedPatterns.distinctPatterns(), sharedPatterns.sharedPatterns());
        Log.info("[xb] pass 1: %d rail patterns and %d rail stop points seen on the way past, so "
                + "the rail/road classification needs no scan of its own",
                railPatterns.size(), railStopPoints.size());
        return new Pass1(numbers, byNum, sharedPatterns, tnRefcount, gates,
                new RailSeen(railPatterns, railStopPoints));
    }

    /// Record what one RAIL journey says about its stops, for [RailSeen]. A journey whose mode is
    /// anything but rail, or does not resolve at all, contributes nothing. The mode rule is
    /// [XbStations#isRail]'s.
    private static void noteRailStops(ServiceJourney sj,
            XbLines.LineMaps<noi.netex.model.AllVehicleModesOfTransportEnumeration> modes,
            Set<String> railPatterns, Set<String> railStopPoints) {
        if (!XbStations.isRail(
                XbStations.transportMode(sj, modes.lineMap(), modes.sjpLine()))) {
            return;
        }
        if (sj.getCalls() != null) {
            for (noi.netex.model.Call_VersionedChildStructure c
                    : noi.netex.calls.Calls.of(sj.getCalls())) {
                String r = XbStopRefs.callRef(c);
                if (r != null && !r.isEmpty()) railStopPoints.add(r);
            }
        } else if (sj.getJourneyPatternRef() != null) {
            railPatterns.add(sj.getJourneyPatternRef().getValue().getRef());
        }
    }

    /// A passing-time value the parser could not convert, or an hour past the one XSD admits.
    ///
    /// Hour 24 counts as illegal here even in its one legal form (`24:00:00`), deliberately: the
    /// rewrite converts that too, because the day-losing reading of it is what this stage would then
    /// be doing arithmetic on.
    private static boolean illegal(TimetabledPassingTime t) {
        return illegal(t.getDepartureTime()) || illegal(t.getArrivalTime());
    }

    private static boolean illegal(noi.netex.time.XmlTime v) {
        return v != null && (v.isUnparsed() || v.getHour() >= 24);
    }

    /// The journey's normalised train number: its first resolvable TrainNumberRef, else its Name.
    /// Publishers split between the two.
    public static String journeyNumber(ServiceJourney sj, Map<String, String> tnValue) {
        if (sj.getTrainNumbers() != null) {
            for (TrainNumberRefStructure r : sj.getTrainNumbers().getTrainNumberRef()) {
                String n = tnValue.get(r.getRef());
                if (n != null && !n.isEmpty()) return n;
            }
        }
        return Mls.hasText(sj.getName()) ? XbIds.normalizeTrainNumber(Mls.text(sj.getName())) : null;
    }

    // ------------------------------------------------------------------------------------- //
    // Pass 2
    // ------------------------------------------------------------------------------------- //

    /// The keyed second pass: number by number, load the journeys of every number carried by more
    /// than one publisher country and reduce each to a [Node].
    ///
    /// Returns the nodes grouped by number, each group in canonical `(id, version)` order: the store
    /// iterates in cursor order and everything downstream that emits in member order has to agree
    /// across runs.
    public static Int2ObjectMap<List<Node>> pass2(Store db, Txn txn, Pass1 p1, Stations stations,
            Map<String, DaySet> dayTypeDates, Dict countries, Dict publishers,
            XbLines.LineMaps<noi.netex.model.AllVehicleModesOfTransportEnumeration> modes) {
        Int2ObjectMap<List<Node>> out = new Int2ObjectLinkedOpenHashMap<>();
        PatternCache patterns = new PatternCache(db, txn);
        long loaded = 0;
        long unusable = 0;
        for (var it = p1.byNum().int2ObjectEntrySet().iterator(); it.hasNext();) {
            Int2ObjectMap.Entry<LongArrayList> e = it.next();
            int num = e.getIntKey();
            List<Node> nodes = new ArrayList<>();
            LongArrayList keys = e.getValue();
            for (int i = 0; i < keys.size(); i++) {
                long key = keys.getLong(i);
                Object o = db.loadObjectByFullKey(txn, key);
                if (!(o instanceof ServiceJourney sj)) continue;
                loaded++;
                Node node = node(sj, key, num, patterns, stations, dayTypeDates, countries,
                        publishers, modes);
                if (node == null) {
                    unusable++;
                    continue;
                }
                nodes.add(node);
            }
            it.remove();                              // the keys are spent
            if (nodes.size() < 2) continue;
            nodes.sort(Comparator.comparing((Node n) -> n.id));
            ((ArrayList<Node>) nodes).trimToSize();
            out.put(num, nodes);
        }
        Log.info("[xb] pass 2: %d journeys loaded, %d unusable, %d numbers retained over %d nodes",
                loaded, unusable, out.size(), countNodes(out));
        return out;
    }

    private static long countNodes(Int2ObjectMap<List<Node>> byNum) {
        long n = 0;
        for (List<Node> ns : byNum.values()) n += ns.size();
        return n;
    }

    /// One journey reduced to a node, or null when it carries nothing matchable: no passing times,
    /// no resolvable pattern, or a stop sequence that could not be aligned with them.
    static Node node(ServiceJourney sj, long key, int num, PatternCache patterns, Stations stations,
            Map<String, DaySet> dayTypeDates, Dict countries, Dict publishers,
            XbLines.LineMaps<noi.netex.model.AllVehicleModesOfTransportEnumeration> modes) {
        if (sj.getPassingTimes() == null) return null;
        List<TimetabledPassingTime> tt = sj.getPassingTimes().getTimetabledPassingTime();
        if (tt.isEmpty()) return null;
        List<String> refs = stopRefs(sj, tt, patterns);
        if (refs == null) return null;
        int home = countries.intern(XbIds.feedCountry(sj.getId()));
        int[] canon = new int[tt.size()];
        byte[] cc = new byte[tt.size()];
        int[] sec = new int[tt.size()];
        for (int i = 0; i < tt.size(); i++) {
            String ref = refs.get(i);
            int station = stations.of(ref);
            canon[i] = station;
            // An unresolved stop keeps a country, so classification does not lose the journey; it
            // loses only its ability to be a handover, which is what canon == NONE means.
            String country = station != XbTypes.NONE && stations.country()[station] != XbTypes.NONE
                    ? countries.value(stations.country()[station])
                    : XbIds.stopCountry(ref);
            int interned = countries.intern(country);
            // A byte per stop is only safe while the corpus stays inside one; a corridor that grew
            // past 127 countries would wrap silently and file stops in the wrong one.
            if (interned > Byte.MAX_VALUE) {
                throw new IllegalStateException("[xb] the country dictionary has outgrown the byte "
                        + "Node.cc stores: " + countries.size() + " entries. Widen the field.");
            }
            cc[i] = (byte) interned;
            Integer s = i == tt.size() - 1
                    ? XbCalendar.arrivalSeconds(tt.get(i)) : XbCalendar.seconds(tt.get(i));
            sec[i] = s == null ? Integer.MIN_VALUE : s;
        }
        noi.netex.model.AllVehicleModesOfTransportEnumeration mode = modes == null ? null
                : XbStations.transportMode(sj, modes.lineMap(), modes.sjpLine());
        return new Node(sj.getId(), key, home, publishers.intern(XbGroups.publisher(sj.getId())),
                num, canon, cc, sec, distinctSorted(canon),
                XbCalendar.journeyDays(sj, dayTypeDates),
                XbStations.modeByte(mode));
    }

    /// The journey's stop refs, aligned 1:1 with its passing times.
    ///
    /// By id, through each passing time's `StopPointInJourneyPatternRef`. A pattern is often a
    /// superset of its journey — the journey calls at some of its points, not all — and then no
    /// arithmetic on passing-time positions addresses the pattern's points at all. Falling back to
    /// raw pattern order is safe only when the two align 1:1, which is checked.
    public static List<String> stopRefs(ServiceJourney sj, List<TimetabledPassingTime> tt,
            PatternCache patterns) {
        JAXBElement<? extends JourneyPatternRefStructure> pref = sj.getJourneyPatternRef();
        if (pref == null) return null;
        PatternPoints pp = patterns.get(pref.getValue());
        if (pp == null) return null;
        List<String> out = new ArrayList<>(tt.size());
        boolean any = false;
        for (TimetabledPassingTime t : tt) {
            JAXBElement<? extends PointInJourneyPatternRefStructure> r = t.getPointInJourneyPatternRef();
            String ref = r == null ? null : pp.byPoint().get(r.getValue().getRef());
            if (ref != null) any = true;
            out.add(ref);
        }
        if (any) return out;
        return pp.order().size() == tt.size() ? pp.order() : null;
    }

    /// The distinct non-NONE stations of a stop sequence, sorted — a node's intersection key.
    static int[] distinctSorted(int[] canon) {
        int[] copy = canon.clone();
        Arrays.sort(copy);
        int n = 0;
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] == XbTypes.NONE) continue;
            if (n == 0 || copy[i] != copy[n - 1]) copy[n++] = copy[i];
        }
        return Arrays.copyOf(copy, n);
    }

    // ------------------------------------------------------------------------------------- //

    /// A pattern's points: the ordered stop refs, and the point id → stop ref map the
    /// passing times join on.
    public record PatternPoints(List<String> order, Map<String, String> byPoint) {}

    /// Bounded LRU of decoded pattern point maps. Insertion-ordered with access order on, so the
    /// eviction is least-recently-used; a null value is cached too, so a dangling ref costs one
    /// failed load rather than one per journey.
    public static final class PatternCache extends LinkedHashMap<String, PatternPoints> {

        private final Store db;
        private final Txn txn;

        public PatternCache(Store db, Txn txn) {
            super(64, 0.75f, true);
            this.db = db;
            this.txn = txn;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, PatternPoints> eldest) {
            return size() > PATTERN_CACHE;
        }

        PatternPoints get(JourneyPatternRefStructure ref) {
            String key = ref.getRef();
            if (containsKey(key)) return super.get(key);
            Object o = db.loadObjectByReference(txn, ref, /*missingOk=*/true);
            PatternPoints pp = o instanceof JourneyPattern_VersionStructure pat ? points(pat) : null;
            put(key, pp);
            return pp;
        }
    }

    /// The point map of one pattern. Only `StopPointInJourneyPattern` carries a stop ref; anything
    /// else in the sequence contributes a null, so positions stay aligned.
    public static PatternPoints points(JourneyPattern_VersionStructure pat) {
        var seq = pat.getPointsInSequence();
        List<PointInLinkSequence_VersionedChildStructure> pts = seq == null ? List.of()
                : seq.getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern();
        List<String> order = new ArrayList<>(pts.size());
        Map<String, String> byPoint = new HashMap<>(pts.size() * 2);
        for (PointInLinkSequence_VersionedChildStructure p : pts) {
            String ref = null;
            if (p instanceof StopPointInJourneyPattern_VersionedChildStructure sp
                    && sp.getScheduledStopPointRef() != null) {
                ref = sp.getScheduledStopPointRef().getValue().getRef();
            }
            order.add(ref);
            byPoint.put(p.getId(), ref);   // duplicate point ids: last wins, and truncation refuses them
        }
        return new PatternPoints(order, byPoint);
    }
}
