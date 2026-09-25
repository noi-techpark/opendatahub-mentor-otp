# NeTEx feeds -> EPIP zip -> OTP graph.  `make help` lists every target.
# On PATH: java (JDK 25), curl, python3, unzip, zip, osmium. Both jars are external, built
# elsewhere and sitting next to this file: netex-toolkit-shaded.jar and otp-shaded.jar, both uber
# jars carrying their own dependencies. Neither is committed -- .gitignore covers *.jar.
#
# netex-toolkit-shaded.jar fetches itself when missing, from $(TOOLKIT_JAR_URL), a public release
# asset; `make download-toolkit` replaces one that is already present. otp-shaded.jar is put there
# by hand, or named by OTP_JAR where it lies.
#
# Every driver this runs lives in scripts/, so the jars are the only thing arriving from outside.

ROOT      ?= $(CURDIR)
INPUT_DIR ?= $(ROOT)/input
DATA_DIR  ?= $(ROOT)/data
STATE_DIR ?= $(ROOT)/state
# OTP's base dir: the three *-config.json, the files they name, and the saved graphs -- all flat,
# no data/ subdirectory, so build-config.json's "source" entries are bare filenames.
OTP_DIR   ?= $(ROOT)/graph

# ---- the toolkit ------------------------------------------------------------------------------
JAVA ?= java
# Warning suppression, not correctness: on JDK 25 the store loads, resolves and reads with no flags
# at all. They are here because a run launches hundreds of JVMs and each would otherwise put eight
# WARNING lines into log.txt -- jffi's System::load and Unsafe::putLong, plus lz4-java's
# System::loadLibrary, which is a second native caller unrelated to lmdbjava.
#
# No --add-opens: those are ByteBufferProxy's requirement, and the toolkit's store builds its Env on
# ByteArrayProxy.PROXY_BA, so that class never loads.
#
# --sun-misc-unsafe-memory-access stops being cosmetic on JDK 26: JEP 498 made `deny` the default
# there and jffi's putLong would throw.
JVM := --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow
# One source root. The JEP-458 launcher derives it from the launched file's own path and package
# and there is no --source-path to override it, so a driver under $(SCRIPTS) can see only .java
# under $(SCRIPTS) -- everything else comes compiled, out of the jar, which is where the shared
# transformation layer (toolkit.transform.*) lives. No driver can span two roots.
SCRIPTS := $(ROOT)/scripts
TOOLKIT_JAR ?= $(ROOT)/netex-toolkit-shaded.jar
JAVA_CP := $(TOOLKIT_JAR)

# Where a missing $(TOOLKIT_JAR) comes from: a release asset, anonymous and permanent, published
# under the version-free name below as the plain jar.
#
# TOOLKIT_VERSION picks between GitHub's two release URL shapes: a tag pins one release, `latest`
# tracks whatever the newest is -- `make TOOLKIT_VERSION=latest`. The default pins, because the
# drivers compile against this jar: the tag moves with the tree that needs a newer one. Setting
# TOOLKIT_JAR_URL outright overrides all three, which is the way to a mirror, a file:// path, or a
# jar built by hand.
TOOLKIT_REPO    ?= flaktack/netex-toolkit
TOOLKIT_ASSET   ?= netex-toolkit-shaded.jar
TOOLKIT_VERSION ?= v0.0.3
TOOLKIT_JAR_URL ?= https://github.com/$(TOOLKIT_REPO)/releases/$(if $(filter latest,$(TOOLKIT_VERSION)),latest/download,download/$(TOOLKIT_VERSION))/$(TOOLKIT_ASSET)

# Per-stage heaps, measured rather than chosen. Measure before lowering one: `make netex
# XMX_<STAGE>=<n>g` and read the peak back out of the log. The chain is cons-split ->
# epip-<country> x3 -> epip-merge -> xb.
#
# 32g is the ceiling for every EPIP heap below, no matter how much RAM the box has: at 32g the JVM
# drops compressed oops (every reference goes 4 -> 8 bytes) and the same live set grows ~37% --
# identical instance counts, bigger objects.
#
# XMX_MENTZ splits one call site out of XMX_TIER1. resolve-mentz-line-versions on the Austrian
# corpus compacts to 14,068 MB live, against <= 4,516 MB for the other three JVMs in the same chain
# and ~2,190 MB for both swiss ones; the same driver needs 251 MB on the STA feed, so the cost is in
# the data, not the code.
XMX_FEED     ?= 4g
XMX_TIER1    ?= 10g
XMX_MENTZ    ?= 20g
XMX_MERGE    ?= 2g
XMX_NATIONAL ?= 10g
XMX_EPIP_COUNTRY ?= 16g
XMX_EPIP_MERGE ?= 8g
XMX_XB       ?= 10g
XMX_EXPORT   ?= 12g

# Stage name -> driver and flags, relative to $(SCRIPTS).
#
# No --format anywhere. v2 is the only store format the toolkit has; the flag survives so older
# command lines keep parsing. On a db-to-db stage `--format v2` asserts the source is v2, whereas
# omitting it makes Stores.sniffFormat read the source's own _meta -- v2 or a loud refusal, never a
# silent fallback. The feed loaders (FeedToDb) default to v2 and reject anything else by name.
JDRV_netex-to-db                = conv/NetexToDb.java
JDRV_it-rap-db-to-db            = conv/ItRapDbToDb.java
JDRV_it-merge-db-to-db          = conv/ItMergeDbToDb.java
JDRV_obb-to-db                  = conv/ObbToDb.java
JDRV_swiss-to-db                = conv/SwissToDb.java
JDRV_trenitalia-db-to-db        = conv/TrenitaliaDbToDb.java
JDRV_feeds-to-shards-db-to-db   = conv/FeedsToConsolidatedDbToDb.java
JDRV_epip-db-to-db              = conv/EpipDbToDb.java
JDRV_merge-db-to-db             = conv/MergeDbToDb.java
JDRV_xb-db-to-db                = conv/XbDbToDb.java
JDRV_epip-db-to-xml             = conv/EpipDbToXml.java
JDRV_normalize-it-ids            = fix/NormalizeItIds.java
JDRV_resolve-mentz-line-versions = fix/ResolveMentzLineVersions.java
JDRV_rewrite-sta-ssp-ids         = fix/RewriteStaSspIds.java
JDRV_wire-sta-service-links      = fix/WireStaServiceLinks.java
JDRV_drop-foreign-journeys       = fix/DropForeignJourneys.java
JDRV_sanitize-stop-assignments   = fix/SanitizeStopAssignments.java
JDRV_fix-itc-trailing-link       = fix/FixItcTrailingLink.java
JDRV_stitch-split-journeys       = fix/StitchSplitJourneys.java

# -D knobs. Each one below differs from the jar's default and leaves the output byte-identical.
# Keyed by driver, each being read by exactly one code path. Restore the jar's defaults with
#   make ZIP_TXN_BATCH=1 ZIP_WORKERS=0 EPIP_JOURNEY_WORKERS=0
# ZIP_TXN_BATCH        (jar: 1) commit the loader's write txn every N zip members. A crash loses up
#                      to N members' work, and the loaders rebuild from source.
# ZIP_WORKERS          (jar: 0) parse N members concurrently, one writer. Saturates at 4.
# EPIP_JOURNEY_WORKERS (jar: 0) move the journey generator's JAXB decode/marshal off the write-txn
#                      thread. Saturates at 2.
#
# toolkit.xb.pass2Filter is not a knob: the jar still declares the flag, but setting it does
# nothing.
#
# toolkit.epip.dayOffsetRewrite is always on here, hardcoded on the epip line below rather than made
# a variable. It re-encodes the illegal after-midnight xs:time form (`24:20:00`) into the only one
# NeTEx defines (`00:20:00` + `<DepartureDayOffset>1</>`). Alone among these -D flags it is not
# byte-identical -- it changes the encoding the feed uses, and the jar defaults it off for that
# reason. It is on because consumers silently drop journeys over the illegal form and read the valid
# form correctly. toolkit.transform.feedfix.DayOffsetRewrite is the pass, EpipToDb phase D the hook.
# Editing it out needs `rm -rf $(EPIP_DB)` to take effect: make tracks mtimes, not this line.
ZIP_TXN_BATCH        ?= 512
ZIP_WORKERS          ?= 4
EPIP_JOURNEY_WORKERS ?= 2

JOPTS_obb-to-db              = -Dtoolkit.load.zipTxnBatch=$(ZIP_TXN_BATCH) -Dtoolkit.load.zipWorkers=$(ZIP_WORKERS)
JOPTS_swiss-to-db            = -Dtoolkit.load.zipTxnBatch=$(ZIP_TXN_BATCH) -Dtoolkit.load.zipWorkers=$(ZIP_WORKERS)
JOPTS_epip-db-to-db          = -Dtoolkit.epip.parallelJourneys=$(EPIP_JOURNEY_WORKERS) \
                                    -Dtoolkit.epip.dayOffsetRewrite=on

# $(call drv,<stage name>,<pinning>) -- joins the heap pinning and the -D knobs to the driver path.
drv = $(JAVA) -Xmx$(XMX_$(2)) $(JVM) $(JOPTS_$(1)) -cp "$(JAVA_CP)" $(SCRIPTS)/$(JDRV_$(1))

# Every store recipe builds into $@.tmp and renames, but deletes the old store first: epip.lmdb
# alone is 42 GB and a full chain holds ~96 GB, so keeping both generations would not fit.

# ---- sources ---------------------------------------------------------------------------------
# Swiss CKAN is per timetable year; /permalink redirects to the most recent export. The Austrian
# portal (DBP) is OAuth-protected: swap the login for a bearer token, then pull the file.
SWISS_PERMALINK  ?= https://data.opentransportdata.swiss/dataset/timetablenetex_2026/permalink
# Trenitalia is a cciss asset like the RAP feeds, and takes the same URL shape and the same
# catalogue lookup for its version.
TRENITALIA_ASSET ?= 1080596
TRENITALIA_URL   ?= $(call RAP_URL,$(TRENITALIA_ASSET))
MV_TOKEN_URL    ?= https://user.mobilitaetsverbuende.at/auth/realms/dbp-public/protocol/openid-connect/token
MV_CLIENT_ID    ?= dbp-public-ui
MV_YEAR         ?= 2026
MV_FILE_URL      = https://data.mobilitaetsverbuende.at/api/public/v1/data-sets/$(1)/$(MV_YEAR)/file
RAP_URL          = https://www.cciss.it/nap/mmtis/public/api/v1/download/blob/Asset/$(1)/checkedResource

# The 9 Austrian NeTEx data sets (<name>:<DBP data-set id>), all MENTZ exports for conv.obb_to_db.
# Data-set 71 (changeover) is excluded.
AT_FEEDS := obb:67 vor:59 stv:60 svv:61 ktn:62 ooe:63 vvt:64 vbg:65 linz:68
AT_NAMES := $(foreach f,$(AT_FEEDS),$(firstword $(subst :, ,$(f))))
at_id     = $(word 2,$(subst :, ,$(filter $(1):%,$(AT_FEEDS))))

# The portals reject non-browser user agents, hence the -A. --retry-all-errors makes --retry cover
# a body that died mid-transfer (plain --retry only retries connection setup), and the speed floor
# aborts a stalled transfer instead of hanging a whole feed run on one wedged socket.
# The -A value is unquoted: $(CURL) is exported to fetch.sh as one string and split there on
# whitespace, so a quoted value would arrive with its quotes attached.
CURL := curl -fsSL --retry 3 --retry-all-errors --speed-limit 1024 --speed-time 60 -A Mozilla/5.0
# Every download goes through this. curl exits 0 on a short read when the response carries no
# Content-Length, so `curl -o $@.tmp && mv` renames truncated feeds into input/ having checked
# nothing -- and since the $(INPUT_DIR) rules fire only when their target is missing, make never
# re-fetches them. fetch.sh verifies the .tmp before the mv, re-fetches once, and leaves input/
# untouched if both tries are bad. It owns the .tmp/mv dance, so the recipes below do not repeat it.
# $(CURL) travels to it as an exported variable rather than as a command prefix, so that $(FETCH)
# stays a bare program name and can be the target of an xargs -- which is what verify-inputs needs.
#
# It also holds an input's mtime whenever a re-fetch brings back the same bytes, which is what keeps
# the stores behind an unchanged source from rebuilding. The validators it remembers between runs
# live in $(STATE_DIR), one sidecar per downloaded file, so every download rule below takes
# $(STATE_DIR) as an order-only prerequisite.
FETCH := $(SCRIPTS)/fetch.sh
# Publisher versions for the cciss NAP and the Austrian DBP portal, whose hosts answer a conditional
# request with the whole body. $(FETCH) takes what this prints as an opaque --validator token.
FEEDVER := $(SCRIPTS)/feed-version.sh
export CURL STATE_DIR MV_YEAR

IT_SRC  := $(INPUT_DIR)/trenitalia-netex.xml.gz
CH_SRC  := $(INPUT_DIR)/swiss-netex.zip
STA_SRC := $(INPUT_DIR)/sta.netex.zip
AT_ZIPS := $(foreach n,$(AT_NAMES),$(INPUT_DIR)/austria-$(n)-netex.zip)

# it.lmdb is the national Italian db: the RAP region dbs + Trenitalia + STA, consolidated.
IT_DB   := $(DATA_DIR)/it.lmdb
IT_RAW  := $(DATA_DIR)/it-trenitalia-raw.lmdb
IT2_DB  := $(DATA_DIR)/it-trenitalia.lmdb
CH_DB   := $(DATA_DIR)/ch.lmdb
STA_DB  := $(DATA_DIR)/it-sta.lmdb
EPIP_DB := $(DATA_DIR)/epip.lmdb

# ---- per-country EPIP shards ------------------------------------------------------------------
# Every EPIP phase is one LMDB write transaction and a write txn is bound to the thread that opened
# it, so one store gets one writer however many cores are idle -- that is why EPIP_JOURNEY_WORKERS
# saturates at 2 (toolkit.harness.GenParallel: only the JAXB decode and marshal can leave the txn
# thread). Three stores get three writers.
#
# The station consolidation cannot be sharded. It makes two publishers' copies of one physical
# station into one object by clustering them geographically, and a border station's copies come from
# different countries by construction, so it has to see every feed at once. It stays a single pass
# and writes the shards, which is what cons-*.lmdb are.
#
# The names and their order track $(FEED_DBS) -- the split driver pairs source i with target i, so
# these two lists move together or a country's rows land in another country's store.
#
# Feed order is scheduling. Make queues a goal's prerequisite tree in declaration order and hands
# out job slots from a FIFO: what is runnable during the first descent is queued then, and anything
# that becomes runnable later goes behind the whole queue. IT's RAP loads fill that queue, so
# every stage not runnable at t=0 waits ~22 minutes for it to drain -- and the ones that wait are
# what then gates cons-split, rather than it-national. Three things hold it, and all three are
# needed -- the order here, plus:
#   - $(AT_DB) inlines the Verbund merge instead of depending on a separate at-merge target, so
#     at.lmdb is runnable at t=0 rather than at t=300 s. See the note on that rule.
#   - $(IT_DB) lists $(IT2_DB) and $(STA_DB) before $(RAP_REGIONDBS), so make descends into the two
#     stages whose sources already exist before it enumerates the RAPs. See the note on that rule.
# Check all three at once after a run: the at, trenitalia and sta-db banners must carry a timestamp
# within about a minute of the run's start.
#
# The consolidation itself does not care: it sorts into canonical (id, version) order, so the shards
# come out the same whichever order the feeds arrive in. What does follow the order is the key
# numbering of the shard merge, and through it the export's pattern-to-shard assignment -- same
# journeys, different g*.xml grouping.
#
# Disk: the consolidated shards (~32 GB in total, the merged corpus split three ways) and the
# converted shards (~46 GB) both exist while epip.lmdb is being written, so the peak is roughly
# +78 GB over `make netex`'s usual ~96 GB. Neither set is deleted afterwards; `make clean` takes
# them, the *.lmdb glob covers both.
COUNTRIES    := at ch it
CONS_DBS       := $(foreach c,$(COUNTRIES),$(DATA_DIR)/$(c)-cons.lmdb)
EPIP_DBS       := $(foreach c,$(COUNTRIES),$(DATA_DIR)/$(c)-epip.lmdb)
# The coupled store the export reads. It is a second full-size store (~46 GB beside epip.lmdb's
# ~46 GB): the coupling removes objects -- publications a kept leg already carries, and the
# TrainNumbers they orphan -- and a v2 store has no delete, only cloneFrom(skipFullKeys).
XB_DB   := $(DATA_DIR)/epip-xb.lmdb
# The 9 Verbund zips are merged into one archive and loaded as a single run, so the 1,525 border
# StopPlaces published by several Verbünde get their quays unioned instead of first-wins-merged.
AT_DB   := $(DATA_DIR)/at.lmdb
AT_MERGED_ZIP := $(DATA_DIR)/austria-merged-netex.zip
FEED_DBS := $(AT_DB) $(CH_DB) $(IT_DB)

# ---- Italian RAP feeds (cciss NAP), per region: <operator>:<cciss-assetId> --------------------
# Per operator: fetch -> load -> it_rap; per region: one consolidating merge. Operator names reach
# store filenames and --tags, so keep them exact when editing.
#
# Bolzano is absent deliberately, as a whole region rather than one operator because the province
# registered exactly one asset. `IT-ITH1:2219` is the STA feed republished under `IT:ITH1:` -- same
# MENTZ export, same `it:apb:Operator:` ids, journeys sharing an exact local id with $(STA_SRC) --
# one timetable year stale (frames `.25a` against STA's `.26a`), and carrying no rail and none of
# the cable cars. The STA chain below is authoritative for South Tyrol; do not re-add the asset
# without checking it has moved to `.26a`, and prefer STA even then -- daily FTP cadence, and it has
# the trains.
#
# The list is screened on content, not just on what a region registers. An asset carrying zero
# ServiceJourney and zero DatedServiceJourney -- the sharing, taxi, parking and EV fleets the RAPs
# publish alongside transit -- cannot reach the graph as anything but stops: OTP's NeTEx mapper has
# no shared-vehicle mapping at all, and Parking needs `ignoreParking: false`, which
# build-config.json sets only on the dedicated parking feed, never on the merged one. Its
# stops do land, and they land many times over: sharing feeds publish one location set per operator
# under an operator-suffixed id (`IT:ITI4:StopPlace:05000_bird` against `..._lime`), which the merge
# cannot fold together. Re-add one only against a fresh count, and note that a region can populate
# an empty feed later.
#
# It is also screened on the calendar, and the asset per source is picked for recency first, content
# second. A rescue often carries fewer journeys than the file it replaces, because a live asset
# covers the current window rather than a finished season. Sources with no live asset at any level
# are gone, and with them Liguria, Molise and Trento.
#
# And it is screened on the publisher, because the NAP carries Trenitalia twice. $(TRENITALIA_ASSET)
# is Trenitalia's own publication -- 16,480 journeys over 1,916 RFI stations, every product class --
# and seven regions republished a slice of it: campania IT-ITF3-TRENITALIA:428186, emilia-romagna
# IT-ITH5-TRENITALIA-RER:399807, lazio IT-ITI4-0083:586304, lombardia IT-ITC4-TRENITALIA_126:310527,
# puglia IT-ITF4-Trenitalia:307494, sardegna IT-ITG2-TRENITALIA:396943 and veneto
# IT-ITH3-TRENITALIA:181861. Matching journeys on their sequence of RFI station code and clock time,
# 5,558 of those feeds' 6,760 signatures were already in the national feed -- same stations, same
# minute, same order -- and nothing in the chain folds them together, because the codespaces differ
# and $(IT_DB)'s merge qualifies colliding ids rather than dropping duplicate service. Emilia-Romagna
# matched at 100%, campania 99.5%, veneto 97.6%, lazio 86.5%, sardegna 67.6%; lombardia's is the
# December 2025 edition of the same long-distance service, nine months stale, on its own stop
# numbering; puglia's carries no journeys at all. The national feed is authoritative; do not re-add
# one of the seven without re-measuring the overlap against it.
#
# IT-ITC4-TRENORD_336 stays. Trenord is a different company, not a republication -- 14,711 journeys,
# none of its 9,562 signatures in the national feed, and 418 of its 528 stop names with no
# counterpart there. It is the only publisher of Lombardy regional rail.
#
# There is no conversion screen: the exclusions are calendar, content and publisher only, and none of
# them is hiding a feed that would crash the chain.
#
# All three screens are point-in-time. An operator that ends a timetable and republishes late reads
# as expired for a few days, so re-run the check before concluding a source is dead.
# docs/datasources.md carries the per-region table, the legend for each exclusion code, and the
# method.
RAP_REGIONS := abruzzo aosta calabria campania emilia-romagna friuli lazio lombardia marche \
  piemonte puglia sardegna toscana veneto

RAP_abruzzo := IT-ITF1-13:427715
RAP_aosta := IT-ITC2-VAL:1425976
RAP_calabria := $(addprefix AZ-0,03:1513215 06:1513232 08:1513178 09:1513161 10:1513144 \
  12:1513121 16:1513090 18:1513084 20:1513107 23:1513249 27:1512973)
RAP_campania := $(addprefix IT-ITF3-,AIR:277388 ALILAURO:1075683 ANM_GOM_FER:647983 \
  ATC_CE:415981 COSAT:248989 EAV_FERRO:449468 EAV_GOMMA:159845 SITA_SUD:248944 \
  SITA_SUD_Sita_bus:1787866)
RAP_emilia-romagna := $(addprefix IT-ITH5-,MARCONIEXPRESS-BO:484092 SETA-MOREPC:487283 \
  START-FC:484145 START-RA:483846 START-RN:483891 TEP-PR:534612 TPER-BO:483936 TPER-FE:483981)
RAP_friuli := IT-ITH4-MICOTRA:204098
RAP_lazio := IT-ITI4-OP1:663301
RAP_lombardia := $(addprefix IT-ITC4-,AMSC_310:131116 APAM_202:132232 ARRIVA-ITA_892:131383 \
  ATM_47:191301 AUTOGUIDOVIE_201:686247 BGTRASP-EST_211:131790 BGTRASP-OV_212:131568 \
  BGTRASP-SUD_213:131531 BONOMI_54:131910 BRESCIATRASP_204:132312 CAL_240:131679 \
  COMO-FUNBUS_191:131346 CTB_139:132275 FNMA_268:131161 FOGLIANI_503:502800 FORTI_140:131753 \
  GELMI_87:131873 GIANOLINI_134:131947 LAVALLE_91:131457 LECCOTRASP_196:131716 \
  MOVIBUS_274:132195 NET_151:131272 PEREGO_100:132121 RAINOLDI_103:131198 SABBA_147:132158 \
  SAI_273:310444 SAV_264:131420 STAR-MOB_206:132349 STECAV_198:131984 STIE_116:132021 \
  STPS_117:132386 TEB_288:663958 TRASPBS-NORD_216:131605 TRASPBS-SUD_217:132058 \
  TRENORD_336:131494 VARESINE_119:1251813)
RAP_marche := $(addprefix IT-ITI3-,ADRIABUS:5929 ATMA:5966 START:6040 TRASFER:1784852)
RAP_piemonte := $(addprefix IT-ITC1-,CCA-AMG:627 CCA-AT:672 CCA-CN:389 CCA-EXTRATO:1162 \
  CCA-GTT:463 CCA-NO:218801 CCA-VCO:500 VOLI-TO:1184392)
RAP_puglia := $(addprefix IT-ITF4-,AMET:444957 AMTAB:302956 ATAF:296216 \
  COTRAP_AUTOSERVIZI_CHIFFI:388478 COTRAP_BORMAN:328679 COTRAP_CENTRA:328700 COTRAP_CTP:331360 \
  COTRAP_FDG:395576 COTRAP_SEAT:389204 COTRAP_SITASUD_GRUPPO_FOGGIA:467361 \
  COTRAP_SITASUD_LINEE_URBANE:467406 COTRAP_SITASUD_SEDE_REGIONALE_PUGLIA:467451 \
  COTRAP_STP-BARI:339329 COTRAP_STP-BRINDISI:339606 COTRAP_STP-Otranto:389249 FAL:353283 \
  FDG:352066 FNB:477332 Miccolis:421276 SGM:381986)
RAP_sardegna := $(addprefix IT-ITG2-,ARST:396926 ASARA:553803 ASPO:396960 ATP_NUORO:802136 \
  ATZENI:553032 AUTOLINEE_BAIRE:553951 AUTOSERVIZI_VACCA:553473 BAIRE_MARIO:554409 \
  BALESTRUCCI:554022 CARAMELLI:553840 CAREDDU:553638 COBUS:553675 CTM:396909 DEDONI:553766 \
  DELCOMAR:551535 DEPLANO_FLLI:560667 DEPLANU:554244 DIGITUR:553069 ENSAMAR:772181 \
  EUROSAR:552921 FAB:554170 FARA:553217 FATA:554298 FOLLESA:553254 FRAU:554059 GIAGHEDDU:553564 \
  GOLFO:553601 LOGUDORO:553527 MASALA:553180 MELIS:554335 MEREU:553419 MURGIA:552958 \
  NORDORIENTALE:552995 NUOVA_SUN_TRAVEL:565071 PANI:554096 PISANU:553106 PUSCEDDU:553914 \
  SANNA:553291 SARDABUS:553143 SAUT:553712 SENIS:554207 SERRA:554372 SEUNIS:553490 SINA:553328 \
  SOGEAAL:1517910 SUN_LINES:553456 TREXENTA:553985 TURMO_LINES:553749 \
  TURMO_TRAVEL:553365 TUVONI:553382 VACCA_VIAGGI:553968 ZENTILE:554133)
RAP_toscana := $(addprefix IT-ITI1-,1:1632988 123:156681 124:156718 125:156755 127:156792 \
  144:156829 2:1633147 20:156866 20524:156903 21:156940 22:156977 222:157091 23:157165 24:157218 \
  25:157255 26:157292 262:157359 27:157396 28:157433 285:157655 287:157729 29:157766 3:1633184 \
  30:157803 300:1633221 301:1633289 31:157869 4:1633326 40:157906 41:157943 42:157980 43:158017 \
  44:158057 45:158094 46:158131 47:158168 48:204328 49:158245 50:158282 51:158319 60:158356 \
  63:158465 64:158502 65:158539 66:158576 67:158613 68:158650 69:158687 70:158724 71:158761 \
  72:158798 73:158835 74:158873 76:158947 77:158984 78:159021 90:159058 98:159095)
RAP_veneto := $(addprefix IT-ITH3-,ACTVs.p.a:205025 ATV:204401 ATVO:205142 BUSITALIA:204890)

RAP_FEEDS := $(foreach r,$(RAP_REGIONS),$(foreach f,$(RAP_$(r)),$(r):$(f)))

e :=
space := $(e) $(e)
comma := ,

rap_region = $(word 1,$(subst :, ,$(1)))
rap_op     = $(word 2,$(subst :, ,$(1)))
rap_id     = $(word 3,$(subst :, ,$(1)))

rap_src      = $(INPUT_DIR)/it-rap-$(call rap_region,$(1))-$(call rap_op,$(1))-$(call rap_id,$(1)).xml.gz
rap_opdb     = $(DATA_DIR)/it-rap-$(call rap_region,$(1))-$(call rap_op,$(1)).lmdb
rap_regiondb = $(DATA_DIR)/it-rap-$(1).lmdb

RAP_SRCS      := $(foreach f,$(RAP_FEEDS),$(call rap_src,$(f)))
RAP_REGIONDBS := $(foreach r,$(RAP_REGIONS),$(call rap_regiondb,$(r)))
RAP_REFETCH   := $(foreach f,$(RAP_FEEDS),refetch-rap-$(call rap_region,$(f))-$(call rap_op,$(f)))

# `<assetId> <upload date>` for every cciss asset, written by one $(FEEDVER) pass.
RAP_VERSIONS := $(STATE_DIR)/.versions.rap
RAP_IDS      := $(TRENITALIA_ASSET) $(foreach f,$(RAP_FEEDS),$(call rap_id,$(f)))

# $(call rap_validator,<assetId>) -- the --validator argument for one asset. $(RAP_VERSIONS) answers
# when the run has built it; a single-feed target falls through to the catalogue for that one asset.
# The lookup must stay a shell substitution: $(shell ...) expands before this run writes the table.
rap_validator = $$(v=$$(awk '$$1=="$(1)"{print $$2}' $(RAP_VERSIONS) 2>/dev/null); \
	[ -n "$$v" ] || v=$$($(FEEDVER) rap $(1) | awk '{print $$2}'); \
	[ -n "$$v" ] && echo --validator "$$v")

# operator dbs / comma-joined operator tags for one region (same order -> aligned with --tags)
region_opdbs = $(foreach f,$(filter $(1):%,$(RAP_FEEDS)),$(call rap_opdb,$(f)))
region_tags  = $(subst $(space),$(comma),$(strip $(foreach f,$(filter $(1):%,$(RAP_FEEDS)),$(call rap_op,$(f)))))

# Marker whose name encodes the feed set: changing FEEDSET removes the old marker and creates a new
# one, dirtying the epip db (make tracks mtimes, not variable values). The RAP count is derived, so
# adding or removing a feed renames the marker on its own; swapping one for another does not, and
# still needs the epip db removed by hand.
FEEDSET        := it-ch-atm-sta-$(words $(RAP_FEEDS))
FEEDSET_MARKER := $(STATE_DIR)/.feedset.$(FEEDSET)

# ---- NeTEx export knobs ----------------------------------------------------------------------
# Both are pinned, not host-derived: they change the artefact, where thread counts change only wall
# time and leave the output byte-identical. The driver defaults to the same two values.
#
# The two knobs set the two halves of OTP's per-group memory. A zip entry lands at one of three
# levels (OTP's NetexBundle.loadFileEntries): the shared-* files, held for the whole build; a
# shard's gSS-shared.xml, held for that group; and a gSS-NNNN.xml, dropped as soon as it is mapped.
# So the netex peak is
#
#     shared-* (~1.4 GB) + one gSS-shared.xml + one gSS-NNNN.xml
#
# EPIP_SHARDS sets the middle term, EPIP_JOURNEYS_PER_FILE the last one.
#
# A shard is a set of whole line components, bin-packed by journey count, never a modulo of the
# pattern enumeration. A component holds together the lines joined by an interchange (OTP resolves
# an interchange at the end of its group) and the lines sharing a ServiceLink (a link is written
# once per shard). Raising this splits gSS-shared.xml further.
EPIP_SHARDS ?= 8

# A bound on the journeys in one gSS-NNNN.xml, not a target: nothing ties a file to a component
# boundary, and only a single pattern with more journeys than the bound can overshoot (the corpus
# mean is 8.3 journeys per pattern).
#
# It bounds the count, and journey size varies about 3x across the corpus -- a Trenitalia journey
# carries far more passing times than a regional bus one -- so the byte spread is much wider than
# the count spread. At 12500 the worst numbered entry is 379 MB against a 119 MB median, and the
# peak entry is already gSS-shared.xml (202-344 MB) in three of the eight shards, which only
# EPIP_SHARDS moves. Worst group-level pair (a shard's shared entry + its biggest numbered one):
# 656 MB.
EPIP_JOURNEYS_PER_FILE ?= 12500

# The PublicationDelivery envelope, repeated in every entry. The exporter's own defaults name the
# toolkit that produced the file ("netex-toolkit" / "epip-db-to-xml"); these name the publisher
# instead, which is what a consumer of this zip should see.
EPIP_PARTICIPANT_REF ?= NOI
EPIP_DESCRIPTION ?= South Tyrol and cross-border public transport

# Unset, the exporter reads the clock per export and two runs over one store differ in
# <PublicationTimestamp> and in every frame's version. Set both and the zip is byte-reproducible,
# which is what makes `make netex` idempotent over unchanged inputs -- but they have to come from
# the DATA, not from `date`, or they are just a slower clock. Left empty here because the
# feed-version table state/ holds is per feed and the merge has no single date; deriving one is the
# open half of this.
EPIP_PUBLICATION_TIMESTAMP ?=
EPIP_FRAME_VERSION ?=

# The flag list, empty-safe: an unset variable contributes no flag at all rather than an empty one.
EPIP_ENVELOPE_FLAGS := \
    $(if $(EPIP_PARTICIPANT_REF),--participant-ref "$(EPIP_PARTICIPANT_REF)") \
    $(if $(EPIP_DESCRIPTION),--description "$(EPIP_DESCRIPTION)") \
    $(if $(EPIP_PUBLICATION_TIMESTAMP),--publication-timestamp $(EPIP_PUBLICATION_TIMESTAMP)) \
    $(if $(EPIP_FRAME_VERSION),--frame-version $(EPIP_FRAME_VERSION))

# ---- OTP graph -------------------------------------------------------------------------------
# The OTP this loads is the `noi-pipeline` branch of an OpenTripPlanner checkout, which carries
# NeTEx fixes dev-2.x has not merged; some of them decide which journeys, stops and geometries
# survive the load.
#
# $(OTP_JAR) is an order-only prerequisite of $(STREET_GRAPH) and $(GRAPH): checked for existence,
# and its mtime dirties neither graph. Empty is a valid value, and the check goes with it:
# build-graph.sh runs OTP from a container image and overrides otp_run, so there is no jar there for
# the rule to find.
OTP_JAR ?= $(ROOT)/otp-shaded.jar

OTP_CONFIGS := $(addprefix $(OTP_DIR)/,build-config.json router-config.json otp-config.json)
# Measured heaps: street (OSM+DEM), transit layering, serving. Transit below 54G G1-death-spirals
# and above 58G swaps on a 61G box; 56G builds entirely in RAM.
STREET_HEAP  ?= 40G
TRANSIT_HEAP ?= 50G
SERVE_HEAP   ?= 31G
# Used for the build phases only
OTP_BUILD_JVM ?= -XX:ObjectAlignmentInBytes=16 -XX:MaxGCPauseMillis=500
# $(1) = -Xmx, $(2) = OTP args. The base dir is the last argument.
otp_run = $(JAVA) -Xmx$(1) -Xms$(1) $(3) -jar $(OTP_JAR) $(2) $(OTP_DIR)

EUROPE_URL     ?= https://download.geofabrik.de/europe-latest.osm.pbf
ELEVATION_URL  ?= https://leonard.io/srtm/srtm_39_03.zip
TRANSMODEL_HOST?= https://transmodel.api.opendatahub.com
PARKING_URL    ?= $(TRANSMODEL_HOST)/netex/parking
# The STA FTP filename is date-stamped: `make download-sta` fetches today's.
STA_URL        ?= ftp://ftp.sta.bz.it/netex/2026/plan/EU_profil/NX-PI_01_it_apb_LINE_apb__$(shell date +%Y%m%d).xml.zip
GEOJSON        := $(ROOT)/geo/switzerland-italy.geojson

# Raw downloads live in input/ (kept across `make clean`); what OTP loads sits in $(OTP_DIR)
# itself, whose names must match the "source" entries in build-config.json.
EUROPE_PBF    := $(INPUT_DIR)/europe.osm.pbf
ELEVATION_ZIP := $(INPUT_DIR)/srtm_39_03.zip
PARKING_SRC   := $(INPUT_DIR)/parking-netex.xml
REGION_PBF    := $(OTP_DIR)/regional.osm.pbf
DEM_TIF       := $(OTP_DIR)/srtm_39_03.tif
PARKING_ZIP   := $(OTP_DIR)/parking-netex.xml.zip
NETEX_ZIP     := $(OTP_DIR)/netex-epip-merged.zip
STREET_GRAPH  := $(OTP_DIR)/streetGraph.obj
GRAPH         := $(OTP_DIR)/graph.obj

# USE -j6. Measured over four full national runs on 2026-09-06 (../log-j1.txt, log-j6.txt,
# log-j6-2.txt, log-j16.txt): -j1 4 h 09 m, -j6 2 h 26 m, -j6-2 2 h 14 m, -j16 2 h 24 m. Past six
# jobs there is nothing left to win and a real price to pay -- -j16 is 2 % SLOWER in wall clock than
# -j6 while burning 67 % more CPU (34,073 against 20,375 stage-seconds), because the per-stage times
# inflate 2.28x from contention alone. The floor is the critical path: at-merge -> at -> cons-split
# -> the slowest EPIP shard -> epip-merge -> xb -> export -> graph, which is 7,433 s of the -j1
# (uncontended) times, and the best observed run is already within 8 % of it. Only the serial tail
# can get shorter from here, and after `graph` (OTP, ~2,400 s) the biggest thing on it is `xb`.
#
# Stage banners: these steps run for hours. Per-stage timing files keep it safe under `make -j`.
define stage
@mkdir -p $(STATE_DIR)
@echo "=== $$(date '+%F %T') [$(1)] $(2)"
@date +%s > $(STATE_DIR)/.t.$(1)
endef
define stage_done
@echo "=== $$(date '+%F %T') [$(1)] done in $$(( $$(date +%s) - $$(cat $(STATE_DIR)/.t.$(1)) ))s"
endef

# Unlink a failed recipe's target so a partial file never passes the next up-to-date check. The
# *.lmdb directory targets get the same guarantee from the build-into-$@.tmp + mv pattern.
.DELETE_ON_ERROR:

.PHONY: all netex feeds xb graph street serve data help test compile-gate \
	download-all download-toolkit \
	download-feeds download-swiss download-trenitalia download-austria download-sta download-rap \
	rap-versions $(RAP_REFETCH) \
	download-osm osm-extract download-parking verify-inputs repair-inputs \
	clean clean-netex clean-feeds clean-osm clean-all
.DEFAULT_GOAL := help

help: ## Show this help
	@echo "Unified NeTEx + OTP graph pipeline. Targets:"
	@echo
	@grep -hE '^[a-zA-Z][a-zA-Z0-9_-]*:.*## ' $(MAKEFILE_LIST) | \
		awk -F':.*## ' '{printf "  %-20s %s\n", $$1, $$2}'
	@echo
	@echo "  make netex   -> $(NETEX_ZIP)"
	@echo "  make all     -> $(GRAPH)"
	@echo
	@echo "  toolkit   = $(TOOLKIT_JAR)"
	@echo "              <- $(TOOLKIT_JAR_URL)  (fetched when missing)"
	@echo "  scripts   = $(SCRIPTS)"
	@echo "  otp       = $(OTP_JAR)  (base dir $(OTP_DIR))"
	@echo "              <- put there by hand, or named by OTP_JAR=<path>"
	@echo "  FEEDSET   = $(FEEDSET)   ($(words $(RAP_FEEDS)) RAP feeds + Trenitalia + STA + CH + $(words $(AT_FEEDS))x AT)"
	@echo "  EPIP_SHARDS = $(EPIP_SHARDS)   EPIP_JOURNEYS_PER_FILE = $(EPIP_JOURNEYS_PER_FILE)"
	@echo "  envelope  = $(EPIP_ENVELOPE_FLAGS)"
	@echo "              set EPIP_PUBLICATION_TIMESTAMP and EPIP_FRAME_VERSION for a reproducible zip"
	@echo
	@echo "One credential, taken from the environment and never stored here: the Austrian feeds"
	@echo "need a data.mobilitaetsverbuende.at login."
	@echo "  export MV_USERNAME=you@example.com MV_PASSWORD='...'"
	@echo
	@echo "Any variable: make print-<NAME>"

print-%: ## Print a variable's value (make print-JAVA_CP)
	@echo '$($*)'

# ---- checks ------------------------------------------------------------------------------------
# This repository has no CI, so these run when somebody runs them. Both compile against $(JAVA_CP),
# so both need the jar, which they fetch rather than demand. If a symbol that plainly exists cannot
# be found, the jar is older than the tree it is being asked to satisfy: `make download-toolkit` for
# the current release.
test: | $(TOOLKIT_JAR) ## Run this repository's test suite (scripts/tests/AllTests.java) in one JVM
	$(JAVA) -Xmx4g $(JVM) -Dtoolkit.tests.dir=$(SCRIPTS)/tests \
		-cp "$(JAVA_CP)" $(SCRIPTS)/tests/AllTests.java

compile-gate: | $(TOOLKIT_JAR) ## javac every .java under scripts/ -- the only check that sees ObbToDb and SwissToDb
	TOOLKIT_JAR=$(TOOLKIT_JAR) $(SCRIPTS)/compile-gate.sh

all: $(GRAPH) ## Build everything up to graph.obj (street + transit)

# ===== sources ================================================================================
# The file rules fire only when the input is missing (a normal `make all` never re-downloads); the
# download-* targets re-fetch over the file already in input/, which is what lets $(FETCH)
# revalidate against it. Everything lands in a .tmp and is renamed on success.

# $(call fetch_src,<stage>,<message>,<url>,<dest>[,<$(FETCH) options>]) -- one body for the rule
# that fills a missing input and for the target that refreshes it, so the two cannot drift. Deleting
# the file first would throw away the validator the refresh is there to use.
#
# The options go before the URL. Everything after the destination is handed to curl, which is what
# carries the Austrian bearer header, and curl rejects a --validator it was never meant to see.
define fetch_src
$(call stage,$(1),$(2))
$(FETCH) $(5) "$(3)" $(4)
$(call stage_done,$(1))
endef

# Every artefact that arrives from outside. $(ELEVATION_ZIP) is the one download absent from it: its
# file rule fetches the tile when it is missing, and nothing re-fetches a static SRTM tile.
download-all: download-toolkit download-feeds download-parking download-osm ## Re-fetch every external artefact: the toolkit jar and every source

download-feeds: download-swiss download-trenitalia download-austria download-sta download-rap ## Re-fetch the source feeds, keeping any whose bytes are unchanged

# The toolkit itself, on the same terms as a feed: no prerequisites, so it fires only when the jar
# is missing and a newer release never lands mid-run. Every rule that launches a driver carries it
# as an order-only prerequisite, which is what makes a fresh checkout build instead of dying inside
# a JVM -- order-only rather than ordinary because the jar's mtime must not dirty ~96 GB of stores.
#
# $(FETCH) verifies before it renames, and it knows a jar is a zip, so a truncated download cannot
# land as a jar that javac then fails on obscurely.
$(TOOLKIT_JAR):
	$(call stage,toolkit-dl,fetching $(notdir $@) <- $(TOOLKIT_JAR_URL))
	@$(FETCH) "$(TOOLKIT_JAR_URL)" $@ || { echo >&2; \
	  echo "$(notdir $@) could not be fetched from $(TOOLKIT_JAR_URL)." >&2; \
	  echo "Point somewhere else with TOOLKIT_JAR_URL=<url> (a mirror or a file:// path), pin a" >&2; \
	  echo "release with TOOLKIT_VERSION=<tag>, or build it: 'mvn -B -ntp package' in the" >&2; \
	  echo "netex-toolkit checkout, then copy toolkit/target/$(TOOLKIT_ASSET) to $@." >&2; exit 1; }
	$(call stage_done,toolkit-dl)

# No prerequisites, so this fires when the jar is missing, and stops the build before a JVM starts.
$(OTP_JAR):
	@echo "$(notdir $@) is missing." >&2; \
	 echo "Run 'mvn -B -ntp package -DskipTests' in an OpenTripPlanner checkout on the" >&2; \
	 echo "noi-pipeline branch, then copy otp-shaded/target/otp-shaded-*.jar to $@," >&2; \
	 echo "or point OTP_JAR=<path> at one built elsewhere." >&2; exit 1

$(CH_SRC): | $(INPUT_DIR) $(STATE_DIR)
	$(call fetch_src,swiss-dl,fetching Swiss NeTEx export,$(SWISS_PERMALINK),$@)

# The cciss NAP sends no ETag and no Last-Modified, so the version comes out of its catalogue.
$(IT_SRC): | $(INPUT_DIR) $(STATE_DIR)
	$(call fetch_src,trenitalia-dl,fetching Trenitalia NeTEx feed,$(TRENITALIA_URL),$@,$(call rap_validator,$(TRENITALIA_ASSET)))

$(STA_SRC): | $(INPUT_DIR) $(STATE_DIR)
	$(call fetch_src,sta-dl,fetching today's STA NeTEx export,$(STA_URL),$@)

# The rm and the $(MAKE) must be separate recipe lines. GNU make runs any line containing $(MAKE)
# even under -n, so that a dry run propagates into the sub-make, and it judges that per line, not
# per command. Written as one `rm -f X && $(MAKE) X`, the whole line qualifies and `make -n
# download-toolkit` really deletes the jar it was only supposed to describe.
#
# A replacement jar is wanted whole and wanted new, so there is no validator for the delete to
# throw away.
download-toolkit: ## Replace the toolkit jar from TOOLKIT_JAR_URL (TOOLKIT_VERSION=<tag> pins a release)
	rm -f $(TOOLKIT_JAR)
	$(MAKE) $(TOOLKIT_JAR)

# What each source answers a refresh with is tabulated in docs/datasources.md.
download-swiss: | $(INPUT_DIR) $(STATE_DIR) ## Re-fetch the most recent Swiss NeTEx export
	$(call fetch_src,swiss-dl,re-fetching Swiss NeTEx export,$(SWISS_PERMALINK),$(CH_SRC))

download-trenitalia: | $(INPUT_DIR) $(STATE_DIR) ## Re-fetch the most recent Trenitalia NeTEx feed
	$(call fetch_src,trenitalia-dl,re-fetching Trenitalia NeTEx feed,$(TRENITALIA_URL),$(IT_SRC),$(call rap_validator,$(TRENITALIA_ASSET)))

download-sta: | $(INPUT_DIR) $(STATE_DIR) ## Re-fetch today's STA NeTEx export (date-stamped URL)
	$(call fetch_src,sta-dl,re-fetching today's STA NeTEx export,$(STA_URL),$(STA_SRC))

download-austria: $(foreach n,$(AT_NAMES),download-austria-$(n)) ## Re-fetch the Austrian feeds (needs MV_USERNAME/MV_PASSWORD)

# The cciss NAP answers a conditional request with the whole body, so the upload date out of its
# catalogue is what decides whether a feed is fetched at all. The table is rebuilt on every run, and
# a feed missing from it falls through to an unconditional download.
rap-versions: | $(STATE_DIR) ## Rebuild the cciss upload-date table the RAP re-fetches consult
	@$(FEEDVER) rap $(RAP_IDS) > $(RAP_VERSIONS).tmp && mv $(RAP_VERSIONS).tmp $(RAP_VERSIONS)
	@echo "=== $$(date '+%F %T') [rap-versions] $$(wc -l < $(RAP_VERSIONS)) of $(words $(RAP_IDS)) cciss assets carry a version"

# Two recipe lines, so that the table is complete before any feed consults it. rap-versions must
# stay off $(RAP_REFETCH) as a prerequisite: a single `make refetch-rap-<feed>` would then read the
# whole catalogue for one asset.
download-rap: ## Re-fetch every RAP operator feed, keeping the ones the NAP has not re-published
	$(MAKE) rap-versions
	$(MAKE) $(RAP_REFETCH)

# Reads the export, not the stores, so it runs against any artefact -- including one whose data/ is
# long gone -- and answers "is each corridor actually linked in the file OTP loads". That is a
# different question from the coupling's own log line, which counts interchanges built: an id
# collision loses some between there and here. FULL=1 adds the completeness pass, one sequential
# read of every numbered journey group; without it the run touches only shared-02-sites,
# shared-03-network and the link entries (shared-04-links plus the per-shard gSS-shared, taken on
# one walk).
XB_REPORT := $(OTP_DIR)/report/crossborder.md

verify-crossborder: | $(TOOLKIT_JAR) ## Verify the IT/AT/CH cross-border links in the merged NeTEx zip (FULL=1 adds the completeness pass)
	@test -f $(NETEX_ZIP) || { echo "$(NETEX_ZIP) is missing -- run 'make netex' first" >&2; exit 1; }
	$(JAVA) -Xmx8g $(JVM) -cp "$(JAVA_CP)" $(SCRIPTS)/tools/VerifyCrossborder.java \
		$(NETEX_ZIP) $(if $(FULL),--full) --out $(XB_REPORT) \
		$(if $(wildcard $(OTP_DIR)/report/StopPointsMismatch1.html),--otp-report $(OTP_DIR)/report)

XB_ATLAS := $(OTP_DIR)/report/xb

# Reads epip.lmdb, not epip-xb.lmdb, and re-runs the coupling to draw it. A refused group leaves no
# trace in the output store -- nothing truncated, dropped or linked -- so the built database cannot
# answer "what was refused". ~6 min, read-only, writes nothing but SVG. NUM=1084 draws one train.
xb-svg: | $(TOOLKIT_JAR) ## Draw the cross-border coupling: map + per-group sheets (NUM=<train> for one)
	@test -d $(EPIP_DB) || { echo "$(EPIP_DB) is missing -- run 'make netex' first" >&2; exit 1; }
	$(JAVA) -Xmx$(XMX_XB) $(JVM) -cp "$(JAVA_CP)" $(SCRIPTS)/tools/XbCoupleAtlas.java \
		$(EPIP_DB) --out $(XB_ATLAS) $(if $(NUM),--num $(NUM))

# $(FETCH) cannot help a file that already landed corrupt, because the rules above fire only when
# their target is missing. These two are the way out of that state: one truncated feed kills every
# `make netex` until somebody deletes it, and the load stage reports it as "parse failed" deep
# inside a JAXB trace rather than as "bad download". Checking and deleting are separate targets, so
# verify-inputs is safe to run at any time, including mid-pipeline.
verify-inputs: ## Integrity-check every file in input/ (reports; changes nothing)
	@n=$$(find $(INPUT_DIR) -maxdepth 1 -type f ! -name '*.tmp' | wc -l); \
	 test "$$n" -gt 0 || { echo "$(INPUT_DIR) holds no source files -- nothing to verify" >&2; exit 1; }; \
	 echo "=== verifying $$n files in $(INPUT_DIR)"
	@find $(INPUT_DIR) -maxdepth 1 -type f ! -name '*.tmp' -print0 | sort -z | xargs -0 $(FETCH) --check

# Two recipe lines for the reason given above download-swiss: the scan-and-delete half must not
# share a line with $(MAKE), or `make -n repair-inputs` would delete for real. The bad list travels
# between the two lines through $(STATE_DIR) because a shell variable cannot: each recipe line is
# its own shell. Line 1 always rewrites the list, so line 2 can never act on a stale one.
BAD_INPUTS := $(STATE_DIR)/.bad-inputs

repair-inputs: | $(STATE_DIR) ## Delete every input/ file that fails verify-inputs, then re-fetch it
	@find $(INPUT_DIR) -maxdepth 1 -type f ! -name '*.tmp' -print0 | sort -z \
	   | xargs -0 -n1 sh -c '$(FETCH) --check "$$1" >/dev/null 2>&1 || echo "$$1"' _ > $(BAD_INPUTS); \
	 test -s $(BAD_INPUTS) || { echo "every file in $(INPUT_DIR) verifies -- nothing to repair"; exit 0; }; \
	 sed 's|^|  removing |' $(BAD_INPUTS); \
	 xargs rm -f < $(BAD_INPUTS)
	@test -s $(BAD_INPUTS) && $(MAKE) $$(cat $(BAD_INPUTS)) || true

# $(1) = DBP data-set id, $(2) = destination. Credentials come from the environment and are never
# echoed (hence the @); --data-urlencode keeps a password containing &, =, + or * intact.
#
# The one token serves both requests. DBP sends no ETag and no Last-Modified and ignores a range
# request, so the data set's own version id out of its public API is what says whether the zip
# changed; it reaches $(FEEDVER) on stdin, where argv would show it to every user on the box.
define fetch_austria
@token=$$(curl -fsS -X POST -d client_id=$(MV_CLIENT_ID) -d grant_type=password -d scope=openid \
		--data-urlencode "username=$${MV_USERNAME:?export MV_USERNAME and MV_PASSWORD}" \
		--data-urlencode "password=$${MV_PASSWORD:?export MV_USERNAME and MV_PASSWORD}" \
		"$(MV_TOKEN_URL)" \
	  | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])') \
	&& ver=$$(printf '%s\n' "$$token" | $(FEEDVER) dbp $(1) | awk '{print $$2}') \
	&& $(FETCH) $${ver:+--validator "$$ver"} "$(call MV_FILE_URL,$(1))" $(2) \
	     -H "Authorization: Bearer $$token"
endef

$(INPUT_DIR)/austria-%-netex.zip: | $(INPUT_DIR) $(STATE_DIR)
	$(call stage,at-$*-dl,fetching Austrian $* NeTEx (DBP data-set $(call at_id,$*)/$(MV_YEAR)))
	$(call fetch_austria,$(call at_id,$*),$@)
	$(call stage_done,at-$*-dl)

download-austria-%: | $(INPUT_DIR) $(STATE_DIR)
	$(call stage,at-$*-dl,re-fetching Austrian $* NeTEx (DBP data-set $(call at_id,$*)/$(MV_YEAR)))
	$(call fetch_austria,$(call at_id,$*),$(INPUT_DIR)/austria-$*-netex.zip)
	$(call stage_done,at-$*-dl)

# Per RAP operator: fetch the feed, load it, correct its id spaces, then apply the RAP transform.
#
# normalize-it-ids runs before it-rap-db-to-db. The whole chain below -- that pass, the regional and
# national merges, the corridor tables -- then sees the space the feed was meant to publish,
# `IT:<NUTS>:`; and it-rap-db-to-db synthesises Authorities from Operator ids, so running after it
# would leave the derived Authority carrying the space the Operator had just been corrected out of.
# The six deviations and their measured counts are in scripts/transformers/feedfix/ItIdSpaces.java;
# the largest is Marche, whose five feeds prefix every id and every ref with an undeclared `epd:`
# token -- 270,166 stored objects.
#
# It runs on every RAP feed: its probe is an id-index scan with no decode, so a conforming feed costs
# one cloneFrom.
define rap_op_rules
$$(INPUT_DIR)/it-rap-$(1)-$(2)-$(3).xml.gz: | $$(INPUT_DIR) $$(STATE_DIR)
	$$(call fetch_src,rap-$(1)-$(2)-dl,fetching RAP $(1)/$(2) (cciss asset $(3)),$$(call RAP_URL,$(3)),$$@,$$(call rap_validator,$(3)))

refetch-rap-$(1)-$(2): | $$(INPUT_DIR) $$(STATE_DIR)
	$$(call fetch_src,rap-$(1)-$(2)-dl,re-fetching RAP $(1)/$(2) (cciss asset $(3)),$$(call RAP_URL,$(3)),$$(INPUT_DIR)/it-rap-$(1)-$(2)-$(3).xml.gz,$$(call rap_validator,$(3)))

$$(DATA_DIR)/it-rap-$(1)-$(2).lmdb: $$(INPUT_DIR)/it-rap-$(1)-$(2)-$(3).xml.gz | $$(DATA_DIR) $$(TOOLKIT_JAR)
	$$(call stage,rap-$(1)-$(2),loading RAP $(1)/$(2) -> $$(notdir $$@))
	rm -rf $$@ $$@.tmp $$@.raw.tmp $$@.ids.tmp
	$$(call drv,netex-to-db,FEED) $$< $$@.raw.tmp
	$$(call drv,normalize-it-ids,FEED) $$@.raw.tmp $$@.ids.tmp
	$$(call drv,it-rap-db-to-db,FEED) $$@.ids.tmp $$@.tmp
	rm -rf $$@.raw.tmp $$@.ids.tmp && mv $$@.tmp $$@
	$$(call stage_done,rap-$(1)-$(2))
endef
$(foreach f,$(RAP_FEEDS),$(eval $(call rap_op_rules,$(call rap_region,$(f)),$(call rap_op,$(f)),$(call rap_id,$(f)))))

# Per region: merge of its operator dbs (colliding ids get qualified).
#
# --no-consolidate-stops: the duplicate-stop merge runs once, nationally. Running it here too is
# lossy -- a drop at one tier removes the member a later stop would have chained through -- and with
# it off StopPlace and PassengerStopAssignment stay in the raw byte clone instead of going
# object-level.
define rap_region_rules
$$(DATA_DIR)/it-rap-$(1).lmdb: $$(call region_opdbs,$(1)) | $$(DATA_DIR) $$(TOOLKIT_JAR)
	$$(call stage,rap-merge-$(1),merging $$(words $$(call region_opdbs,$(1))) $(1) operators -> $$(notdir $$@))
	rm -rf $$@ $$@.tmp
	$$(call drv,it-merge-db-to-db,MERGE) $$(call region_opdbs,$(1)) $$@.tmp --no-consolidate-stops --tags $$(call region_tags,$(1))
	mv $$@.tmp $$@
	$$(call stage_done,rap-merge-$(1))
endef
$(foreach r,$(RAP_REGIONS),$(eval $(call rap_region_rules,$(r))))

# ===== NeTEx pipeline =========================================================================

netex: $(NETEX_ZIP) ## Build only the merged NeTEx zip
feeds: $(FEED_DBS) ## Load + transform all feeds (9x AT, CH, IT national [RAPs+Trenitalia+STA])

$(FEEDSET_MARKER): | $(STATE_DIR)
	rm -f $(STATE_DIR)/.feedset.*
	touch $@

$(IT_RAW): $(IT_SRC) | $(DATA_DIR) $(TOOLKIT_JAR)
	$(call stage,trenitalia,loading $(notdir $(IT_SRC)) -> $(notdir $@))
	rm -rf $@ $@.tmp && $(call drv,netex-to-db,FEED) $< $@.tmp && mv $@.tmp $@
	$(call stage_done,trenitalia)

$(IT2_DB): $(IT_RAW) | $(TOOLKIT_JAR)
	$(call stage,train-numbers,Trenitalia TrainNumber synthesis -> $(notdir $@))
	rm -rf $@ $@.tmp && $(call drv,trenitalia-db-to-db,FEED) $< $@.tmp && mv $@.tmp $@
	$(call stage_done,train-numbers)

# The South-Tyrol feed is a Mentz EPIP export like the AT ones: generic load, the republication
# drop, the per-version Mentz resolve, then the SSP id rewrite to the SIRI format.
#
# The drop runs first in both Mentz chains, so nothing downstream pays for journeys that are about
# to be deleted. It commutes with the SSP rewrite -- the drop matches on operator and responsibility
# set, the rewrite touches only ScheduledStopPoint and RoutePoint refs -- but it does not commute
# with the Mentz resolve: that pass synthesises a DayType per versioned journey and its (definition,
# day) dedup arbitrates over whichever journeys it can see, so dropping first yields fewer
# synthesised calendar objects.
#
# wire-sta-service-links comes after the Mentz resolve, and the order is load-bearing: that pass
# peels orphans and counts ServiceLink among the classes it peels, sparing this feed's links only
# because they arrive with no referrer at all. Wired first, they have one, and the first pattern
# whose journeys the resolve drops takes its links down with it.
#
# The AT and IT chains below end on sanitize-stop-assignments, which strips the coordinate-less
# placeholder quays and repairs the assignments that referenced them. It runs per feed, last within
# a chain so that it sees every quay the feed's own merges have unioned in, and before $(EPIP_DB),
# because EpipToDb phase C2 clears LevelRefs that do not resolve within their own Site. The CH chain
# does not run it -- see there.
$(STA_DB): $(STA_SRC) | $(DATA_DIR) $(TOOLKIT_JAR)
	$(call stage,sta-db,loading $(notdir $<) -> $(notdir $@) (+ dedup + mentz + SSP rewrite + links))
	rm -rf $@ $@.tmp $@.raw.tmp $@.drop.tmp $@.mentz.tmp $@.ssp.tmp
	$(call drv,netex-to-db,FEED) $< $@.raw.tmp
	$(call drv,drop-foreign-journeys,FEED) $@.raw.tmp $@.drop.tmp
	$(call drv,resolve-mentz-line-versions,FEED) $@.drop.tmp $@.mentz.tmp
	$(call drv,rewrite-sta-ssp-ids,FEED) $@.mentz.tmp $@.ssp.tmp
	$(call drv,wire-sta-service-links,FEED) $@.ssp.tmp $@.tmp
	rm -rf $@.raw.tmp $@.drop.tmp $@.mentz.tmp $@.ssp.tmp && mv $@.tmp $@
	$(call stage_done,sta-db)

# The stitch step is last, and it is Austrian-only because of the feed rather than the country: ÖBB
# splits an international train into two ServiceJourneys and joins them with a JourneyMeeting only
# when both halves are published on one Line. Where they are not -- train 13478's halves are on
# lines 10A11 and 10CH1 -- nothing joins them, and the cross-border coupling then sees two unrelated
# journeys whose shared station is a terminus of both. It runs after the mentz resolve because it
# reads the synthesised DayTypes, and before $(EPIP_DB) because EpipToDb is what converts a
# JourneyMeeting into the ServiceJourneyInterchange everything downstream reads.
#
# The 9-Verbund merge is the first step of this recipe rather than a rule of its own, so that
# at.lmdb is runnable at t=0 and takes a slot in the first descent -- see the feed-order note above
# $(COUNTRIES). The merged zip is therefore not a checkpoint: a failed `at` re-merges.
# Member names embed their Verbund (NX-PI_01_at_<vb>_...) so the 9 archives merge without
# collisions; the loader sorts members by name, making duplicate-id precedence alphabetical.
$(AT_DB): $(AT_ZIPS) | $(DATA_DIR) $(TOOLKIT_JAR)
	$(call stage,at-merge,merging $(words $(AT_ZIPS)) Verbund zips -> $(notdir $(AT_MERGED_ZIP)))
	rm -rf $(AT_MERGED_ZIP) $(AT_MERGED_ZIP).tmp $(AT_MERGED_ZIP).d && mkdir -p $(AT_MERGED_ZIP).d
	set -e; for z in $(AT_ZIPS); do unzip -q -n $$z -d $(AT_MERGED_ZIP).d; done
	cd $(AT_MERGED_ZIP).d && zip -q -1 -X -r $(AT_MERGED_ZIP).tmp .
	rm -rf $(AT_MERGED_ZIP).d && mv $(AT_MERGED_ZIP).tmp $(AT_MERGED_ZIP)
	$(call stage_done,at-merge)
	$(call stage,at,loading $(notdir $(AT_MERGED_ZIP)) -> $(notdir $@) (+ dedup + mentz resolve + stop sanitize + stitch))
	rm -rf $@ $@.tmp $@.raw.tmp $@.drop.tmp $@.mentz.tmp $@.sane.tmp
	$(call drv,obb-to-db,TIER1) $(AT_MERGED_ZIP) $@.raw.tmp
	$(call drv,drop-foreign-journeys,TIER1) $@.raw.tmp $@.drop.tmp
	$(call drv,resolve-mentz-line-versions,MENTZ) $@.drop.tmp $@.mentz.tmp
	$(call drv,sanitize-stop-assignments,TIER1) $@.mentz.tmp $@.sane.tmp
	$(call drv,stitch-split-journeys,TIER1) $@.sane.tmp $@.tmp
	rm -rf $@.raw.tmp $@.drop.tmp $@.mentz.tmp $@.sane.tmp && mv $@.tmp $@
	$(call stage_done,at)

# The drop step removes the whole-network re-import of VVV (operator 81_VVV) -- at-vbg is
# authoritative for those ~12.4k journeys.
#
# No sanitize-stop-assignments here, unlike the other two chains: the Swiss export embeds no
# coordinate-less placeholder quays, so the pass has nothing to strip. On the 2026-08-19 export,
# 46,482 embedded Quays, 0 of them without a coordinate, against 26,065 in at.lmdb (24.4%) and
# 32,091 in it.lmdb (12.1%). Re-add it if a future export starts carrying them.
$(CH_DB): $(CH_SRC) | $(DATA_DIR) $(TOOLKIT_JAR)
	$(call stage,swiss,loading $(notdir $<) -> $(notdir $@) (+ drop republished journeys))
	rm -rf $@ $@.tmp $@.raw.tmp
	$(call drv,swiss-to-db,TIER1) $< $@.raw.tmp
	$(call drv,drop-foreign-journeys,TIER1) $@.raw.tmp $@.tmp
	rm -rf $@.raw.tmp && mv $@.tmp $@
	$(call stage_done,swiss)

# National Italian db: the RAP region dbs + Trenitalia + STA in one (co-located same-name stops
# merged). Cross-source ids don't collide, so qualification is a no-op here.
#
# --synthesize-stops mints the StopPlace + Quay + PassengerStopAssignment for every SSP-only feed
# here rather than per operator, because the ids it derives are substitutions on a
# ScheduledStopPoint id and so are unique only once qualification has settled those. STA and
# Trenitalia contribute 0 -- every one of their SSPs is already PSA-bound.
#
# Prerequisite order here is scheduling, not merge order: make queues job slots FIFO, so whatever it
# descends into first is what runs first, and $(IT2_DB) and $(STA_DB) can run at t=0 where the RAP
# loads fill the queue. The recipe below names its inputs explicitly, in the order the merge wants
# ($(RAP_REGIONDBS) first, matching --tags), so this list is free to differ; do not replace either
# with $^.
$(IT_DB): $(IT2_DB) $(STA_DB) $(RAP_REGIONDBS) | $(DATA_DIR) $(TOOLKIT_JAR)
	$(call stage,it-national,merging $(words $(RAP_REGIONDBS)) RAPs + Trenitalia + STA -> $(notdir $@) (+ stop sanitize))
	rm -rf $@ $@.tmp $@.merged.tmp
	$(call drv,it-merge-db-to-db,NATIONAL) $(RAP_REGIONDBS) $(IT2_DB) $(STA_DB) $@.merged.tmp --synthesize-stops --tags $(subst $(space),$(comma),$(strip $(RAP_REGIONS) trenitalia sta))
	$(call drv,fix-itc-trailing-link,NATIONAL) $@.merged.tmp $@.itc.tmp
	$(call drv,sanitize-stop-assignments,NATIONAL) $@.itc.tmp $@.tmp
	rm -rf $@.merged.tmp $@.itc.tmp && mv $@.tmp $@
	$(call stage_done,it-national)

# Journey concurrency comes from EPIP_JOURNEY_WORKERS above; the driver takes no
# --parallel-journeys.
#
# The station consolidation is here and not in the coupling stage because EpipToDb phase C2 clears
# LevelRefs that do not resolve within their own Site, and consolidation moves Quays between Sites.
# C2 is a conformance pass over the final site population, so it has to follow the consolidation.
#
# One consolidation pass over every feed, N shards out. A grouped target (&:, make 4.3+): the recipe
# writes all three and make must run it once, not once per shard -- with a plain rule, `make -j`
# would start three consolidations that each rebuild the same work and race on the same outputs.
$(CONS_DBS) &: $(FEED_DBS) $(FEEDSET_MARKER) | $(TOOLKIT_JAR)
	$(call stage,cons-split,consolidating $(words $(FEED_DBS)) feeds -> $(words $(CONS_DBS)) per-country stores)
	rm -rf $(CONS_DBS) $(addsuffix .tmp,$(CONS_DBS))
	$(call drv,feeds-to-shards-db-to-db,NATIONAL) $(FEED_DBS) \
		--split $(subst $(space),$(comma),$(strip $(addsuffix .tmp,$(CONS_DBS))))
	set -e; for c in $(COUNTRIES); do mv $(DATA_DIR)/$$c-cons.lmdb.tmp $(DATA_DIR)/$$c-cons.lmdb; done
	$(call stage_done,cons-split)

# The conversion, per country, concurrent under `make -j`.
# The stem is the country, so the pattern reads it-cons.lmdb -> it-epip.lmdb and cannot match
# epip.lmdb or epip-xb.lmdb, neither of which ends in `-epip.lmdb`.
$(DATA_DIR)/%-epip.lmdb: $(DATA_DIR)/%-cons.lmdb | $(TOOLKIT_JAR)
	$(call stage,epip-$*,converting $(notdir $<) -> $(notdir $@) ($(EPIP_JOURNEY_WORKERS) journey workers))
	rm -rf $@ $@.tmp
	$(call drv,epip-db-to-db,EPIP_COUNTRY) $< $@.tmp
	mv $@.tmp $@
	$(call stage_done,epip-$*)

# Back to one store, because the coupling reads one: a raw keep-first clone, no resolve. Each shard
# arrives coherent -- it resolved before its conversion, and EpipToDb phase E resolved it after.
$(EPIP_DB): $(EPIP_DBS) | $(TOOLKIT_JAR)
	$(call stage,epip-merge,merging $(words $(EPIP_DBS)) converted countries -> $(notdir $@))
	rm -rf $@ $@.tmp
	$(call drv,merge-db-to-db,EPIP_MERGE) $(EPIP_DBS) $@.tmp
	mv $@.tmp $@
	$(call stage_done,epip-merge)

# The cross-border coupling, after the EPIP steps. Running here is what gives it one journey shape
# (the conversion normalises every journey to passing times plus a pattern) and correct times (the
# after-midnight rewrite is EpipToDb phase D). Both are checked, not assumed: the stage refuses to
# run on a corpus that still carries <Calls> or an hour-24 value.
#
# epip.lmdb is not deleted afterwards, unlike every other intermediate: re-running just this stage
# against it is ~15 minutes where rebuilding the chain is ~55.
$(XB_DB): $(EPIP_DB) | $(TOOLKIT_JAR)
	$(call stage,xb,cross-border coupling $(notdir $<) -> $(notdir $@))
	rm -rf $@ $@.tmp && $(call drv,xb-db-to-db,XB) $< $@.tmp && mv $@.tmp $@
	$(call stage_done,xb)

xb: $(XB_DB) ## Re-run only the cross-border coupling against the existing epip.lmdb

# Split export: many zip entries instead of one giant XML, laid out on OTP's three file levels, all
# three of which build-config.json's netex feed has to name:
#
#   sharedFilePattern       shared-*.xml       indexed for the whole build
#   sharedGroupFilePattern  gSS-shared.xml     the shard's ServiceLinks + interchanges,
#                                              indexed for the whole group
#   groupFilePattern        gSS-NNNN.xml       patterns + journeys, index dropped per file
#
# Both group patterns capture the same key (gSS = the shard), which is what puts the shared entry in
# the same OTP group as its numbered files. gSS-shared.xml matches only the middle pattern -- and
# OTP's own default for it, (\w{3})-.*-shared\.xml, would not match, so it has to be set: an entry
# matching none of the four patterns is skipped with nothing but a WARN (see the parking note
# below).
$(NETEX_ZIP): $(XB_DB) | $(OTP_DIR) $(TOOLKIT_JAR)
	$(call stage,export,split-serialising $(notdir $<) -> $(notdir $@) ($(EPIP_SHARDS) shards, $(EPIP_JOURNEYS_PER_FILE) journeys/file))
	$(call drv,epip-db-to-xml,EXPORT) $< $@ --shards $(EPIP_SHARDS) --journeys-per-file $(EPIP_JOURNEYS_PER_FILE) $(EPIP_ENVELOPE_FLAGS)
	$(call stage_done,export)

# ===== graph inputs ===========================================================================

data: $(REGION_PBF) $(DEM_TIF) $(PARKING_ZIP) $(NETEX_ZIP) ## Fetch/prepare all OTP graph inputs

# The region extract is the authoritative input and is never rebuilt implicitly -- delete it to
# re-cut. The 34 GB Europe extract is fetched inside the recipe, not as a prerequisite, so an
# ordinary `make data` never touches it.
$(REGION_PBF): | $(OTP_DIR)
	@test -f $(EUROPE_PBF) || $(MAKE) $(EUROPE_PBF)
	$(call stage,osm-extract,osmium extract -> $(notdir $@))
	osmium extract $(EUROPE_PBF) --polygon $(GEOJSON) -o $@ --overwrite
	$(call stage_done,osm-extract)

$(EUROPE_PBF): | $(INPUT_DIR) $(STATE_DIR)
	$(call fetch_src,osm-europe,downloading $(EUROPE_URL),$(EUROPE_URL),$@)

# The mirror geofabrik redirects to answers If-None-Match with a 304 and If-Modified-Since with the
# whole 34 GB, so the ETag $(FETCH) stored on the last download is what makes this cheap. The region
# extract is re-cut whatever the answer: osmium reads $(EUROPE_PBF) rather than depending on it.
download-osm: | $(INPUT_DIR) $(STATE_DIR) ## Re-fetch the ~34 GB Europe OSM extract, then re-cut the region extract
	$(call fetch_src,osm-europe,re-fetching $(EUROPE_URL),$(EUROPE_URL),$(EUROPE_PBF))
	$(MAKE) osm-extract

osm-extract: ## Re-cut the region extract from the existing Europe OSM download
	rm -f $(REGION_PBF)
	$(MAKE) $(REGION_PBF)

$(ELEVATION_ZIP): | $(INPUT_DIR) $(STATE_DIR)
	$(call fetch_src,dem-dl,downloading elevation tile,$(ELEVATION_URL),$@)

$(DEM_TIF): $(ELEVATION_ZIP) | $(OTP_DIR)
	$(call stage,dem,unzip -> $(notdir $@))
	unzip -o $< -d $(OTP_DIR) && touch $@
	$(call stage_done,dem)

# The Transmodel API carries no ETag and stamps every response with a PublicationTimestamp read at
# request time, so the bytes always differ and $(FETCH) always replaces the file. $(PARKING_ZIP)
# below is an ordinary prerequisite of $(GRAPH): every refresh of this feed costs a transit graph
# build.
$(PARKING_SRC): | $(INPUT_DIR) $(STATE_DIR)
	$(call fetch_src,parking-dl,downloading parking NeTEx,$(PARKING_URL),$@)

# --junk-paths so the single member is named `parking-netex.xml`, with no input/ prefix. That name
# is load-bearing: OTP matches zip entries against the four file patterns of its feed entry and
# silently skips anything that matches none, so the feed's `sharedFilePattern` in build-config.json
# spells this name out. It has to be set, because none of OTP's defaults match a one-file archive --
# shared is `shared-data\.xml` and group is `(\w{3})-.*\.xml`, which wants exactly three word
# characters before the dash. Shared rather than group is right for a file with no timetable in it:
# NetexBundle maps the shared level to the transit repository before it walks the groups, so a
# bundle with zero groups still lands its objects. If this name changes, change the pattern with it,
# and check that the next graph build carries no `Netex file ignored: parking-netex.xml` warning.
$(PARKING_ZIP): $(PARKING_SRC) | $(OTP_DIR)
	$(call stage,parking,zip -> $(notdir $@))
	rm -f $@ && zip --junk-paths $@ $<
	$(call stage_done,parking)

download-parking: | $(INPUT_DIR) $(STATE_DIR) ## Re-fetch the parking NeTEx feed and rebuild the zip OTP loads
	$(call fetch_src,parking-dl,re-fetching parking NeTEx,$(PARKING_URL),$(PARKING_SRC))
	$(MAKE) $(PARKING_ZIP)

# ===== OTP graph ==============================================================================

# Phase 1: street graph from OSM + DEM only. --buildStreet implies --save and writes
# streetGraph.obj, which is reused across transit-feed refreshes.
$(STREET_GRAPH): $(OTP_DIR)/build-config.json $(REGION_PBF) $(DEM_TIF) | $(OTP_JAR)
	$(call stage,street,--buildStreet -> $(notdir $@))
	$(call otp_run,$(STREET_HEAP),--abortOnUnknownConfig --buildStreet,$(OTP_BUILD_JVM))
	$(call stage_done,street)

street: $(STREET_GRAPH) ## Build only the street graph (phase 1: OSM + DEM)

# Phase 2: layer transit onto the saved street graph. A transit-feed-only change reruns just this.
$(GRAPH): $(STREET_GRAPH) $(OTP_CONFIGS) $(NETEX_ZIP) $(PARKING_ZIP) | $(OTP_JAR)
	$(call stage,graph,--loadStreet --save -> $(notdir $@))
	$(call otp_run,$(TRANSIT_HEAP),--abortOnUnknownConfig --loadStreet --save,$(OTP_BUILD_JVM))
	$(call stage_done,graph)

graph: $(GRAPH) ## Build graph.obj (phase 1 + phase 2)

serve: $(GRAPH) ## Serve graph.obj with OTP on :8080 (Ctrl-C to stop)
	$(call otp_run,$(SERVE_HEAP),--load --serve)

# ===== relative goal names ====================================================================
# Every target above is absolute, because $(ROOT) is $(CURDIR) and that is load-bearing: the AT
# merge cds into $(AT_MERGED_ZIP).d before naming $(AT_MERGED_ZIP).tmp, which only works because
# that path is absolute. Make matches goals as strings and never resolves a path, so `make
# data/epip.lmdb` finds no rule -- only the full /home/.../data/epip.lmdb does. These three forward
# a relative goal to its absolute target and build nothing themselves; the trailing `;` is the empty
# recipe that stops make reporting the alias as unmade. Prefixed patterns, not match-anything, so
# they are never tried outside these directories, and an absolute goal cannot match one -- no
# recursion. Make strips a leading ./ from a goal, so ./data/epip.lmdb arrives here too. Must stay
# after every real rule.
data/%:  $(DATA_DIR)/%  ;
input/%: $(INPUT_DIR)/% ;
graph/%: $(OTP_DIR)/%   ;

# ===== housekeeping ===========================================================================

$(INPUT_DIR) $(STATE_DIR) $(DATA_DIR) $(OTP_DIR):
	mkdir -p $@

# input/ splits in two, hence clean-feeds and clean-osm rather than one clean-inputs: the transit
# feeds refetch in minutes (the 9 Austrian ones needing a DBP login), where the OSM half is 34 GB
# and geofabrik's europe-latest moves -- a re-fetch is a different extract, not the same bytes. Both
# lists live here and not next to the sources they name, because := has to see $(RAP_SRCS),
# $(PARKING_SRC) and the two OSM paths already expanded.
FEED_SRCS := $(IT_SRC) $(CH_SRC) $(STA_SRC) $(AT_ZIPS) $(RAP_SRCS) $(PARKING_SRC)
OSM_SRCS  := $(EUROPE_PBF) $(ELEVATION_ZIP)

# $(call validators,<downloaded files>) -- the sidecars $(FETCH) keeps for them. They sit in
# $(STATE_DIR) rather than beside the files, so that the verify-inputs scan over $(INPUT_DIR) does
# not try to integrity-check one.
validators = $(addprefix $(STATE_DIR)/.validator.,$(notdir $(1))) $(addsuffix .tmp,$(addprefix $(STATE_DIR)/.validator.,$(notdir $(1))))

# unzip -o drops FOUR files from the elevation zip into $(OTP_DIR); only the .tif is a make target,
# so the sidecars have to be named or they outlive every clean.
DEM_UNPACK := $(DEM_TIF) $(OTP_DIR)/srtm_39_03.hdr $(OTP_DIR)/srtm_39_03.tfw $(OTP_DIR)/readme.txt

# Glob every intermediate, not just the finished stores: a killed run leaves a coherent
# .raw/.mentz/.ssp.tmp store with more rows than a finished one, which nothing downstream catches.
# $(AT_MERGED_ZIP) is named separately because it is the one artefact in $(DATA_DIR) that is not a
# store, so no *.lmdb glob reaches it, and an $(AT_FEEDS) edit stays invisible to `make clean`
# without it. Dropping it means the next run re-merges the 9 Verbund zips (unzip + rezip, ~842 MB).
clean: ## Remove the generated databases, graphs and derived graph inputs (keeps input/)
	rm -rf $(STATE_DIR)
	rm -rf $(DATA_DIR)/*.lmdb $(DATA_DIR)/*.lmdb.tmp $(DATA_DIR)/*.lmdb.*.tmp $(DATA_DIR)/*.lmdb.d
	rm -rf $(AT_MERGED_ZIP) $(AT_MERGED_ZIP).tmp $(AT_MERGED_ZIP).d
	rm -f $(STREET_GRAPH) $(GRAPH) $(DEM_UNPACK) $(PARKING_ZIP) $(NETEX_ZIP) $(REGION_PBF)

# The NeTEx half of `clean`, without the OTP half. Everything here re-derives from input/ alone, so
# the 34 GB osmium extract and the 40G-heap $(STREET_GRAPH) are not paid for again. Same globs as
# `clean`, for the same reason.
# $(GRAPH) is not removed: it depends on $(NETEX_ZIP), so make relinks it from the surviving
# $(STREET_GRAPH) once the new zip lands, and until then the old graph still serves.
# Only .feedset.* goes from $(STATE_DIR) -- .t.* are timing files a stage rewrites before it reads
# them, and .bad-inputs belongs to repair-inputs.
clean-netex: ## Remove only the NeTEx state (stores + merged zip) -- re-derives from input/, keeps the street graph
	rm -rf $(DATA_DIR)/*.lmdb $(DATA_DIR)/*.lmdb.tmp $(DATA_DIR)/*.lmdb.*.tmp $(DATA_DIR)/*.lmdb.d
	rm -rf $(AT_MERGED_ZIP) $(AT_MERGED_ZIP).tmp $(AT_MERGED_ZIP).d
	rm -f $(NETEX_ZIP) $(STATE_DIR)/.feedset.*

# The .tmp siblings go too: $(FETCH) removes its own on failure, but a killed download leaves one,
# and verify-inputs skips *.tmp -- so nothing else would ever report it. The validator sidecars go
# with the bytes they describe: one left behind draws a 304 against a file that is no longer there.
clean-feeds: ## Delete the downloaded source feeds in input/ (~1.6 GB; the AT refetch needs MV_USERNAME/MV_PASSWORD)
	rm -f $(FEED_SRCS) $(addsuffix .tmp,$(FEED_SRCS)) $(call validators,$(FEED_SRCS)) $(RAP_VERSIONS)

# Repeats the two derived paths `clean` already removes, so that it is complete on its own.
clean-osm: ## Delete the OSM + elevation downloads and their extracts (~34 GB to re-download)
	rm -f $(OSM_SRCS) $(addsuffix .tmp,$(OSM_SRCS)) $(call validators,$(OSM_SRCS)) $(REGION_PBF) $(DEM_UNPACK)

clean-all: clean clean-feeds clean-osm ## clean + clean-feeds + clean-osm -- back to a bare checkout (the jars stay)
