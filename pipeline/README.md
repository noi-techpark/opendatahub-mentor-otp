# noi-pipeline

NeTEx feeds → one EPIP zip → an OTP `graph.obj`. The `Makefile` is the whole pipeline;
`make help` lists every target and prints the resolved paths.

Feed set (`FEEDSET = it-ch-atm-sta-221`): 221 Italian RAP operator feeds across 14 regions, Trenitalia and STA/South
Tyrol; Switzerland; 9 Austrian Verbund exports.

## Requires

- On PATH: `java` (JDK 25), `curl`, `python3`, `unzip`, `zip`, `osmium`.
- Both jars fetch themselves next to the `Makefile` when missing. `netex-toolkit-shaded.jar` comes from a public
  release asset; `otp-shaded.jar` comes from GitHub Packages, which authenticates every read, so it needs
  `export GH_TOKEN=…` holding a token with the `read:packages` scope.
- The Austrian feeds need a data.mobilitaetsverbuende.at login, read from the environment and never stored here:
  `export MV_USERNAME=you@example.com MV_PASSWORD='…'`.
- `OTP_VERSION` defaults to what OTP's `dev-2.x` deploys, and that version is overwritten on every push. Pin a
  build with `OTP_SNAPSHOT=<version>`, or point `OTP_JAR_URL` at a `file://` path to run a patched OTP — the
  branch `noi-pipeline` in an `OpenTripPlanner` checkout carries NeTEx fixes `dev-2.x` has not merged.
- Room and RAM: ~1.6 GB of source feeds, ~140 GB of stores for a full chain, a 16 GB heap per country for the EPIP
  conversion (three run concurrently under `make -j`) and 50 GB for the transit graph build.

## Run

```
make help                # targets, artefact paths, the credentials note
make all                 # everything up to graph/graph.obj
make netex               # just graph/netex-epip-merged.zip
make serve               # OTP on :8080
make download-all        # refresh everything external: both jars and every source
make download-feeds      # refresh the sources (a plain `make all` never re-downloads)
make verify-inputs       # integrity-check input/ ; repair-inputs re-fetches what fails
make clean               # drop the stores and graphs, keep input/
make clean-feeds         # drop the downloaded source feeds (the AT re-fetch needs the DBP login)
make clean-osm           # drop the OSM + elevation downloads (~34 GB to re-fetch)
make clean-all           # all three: back to a bare checkout (the jars stay)
```

`ROOT` defaults to `$(CURDIR)`; `INPUT_DIR`, `DATA_DIR`, `STATE_DIR` and `OTP_DIR` hang off it, so
`make -C noi-pipeline ROOT=/path/to/workspace all` runs against a workspace elsewhere.
`make print-<NAME>` prints any variable.

## Flow

![NOI Pipeline](docs/pipeline.svg)

## Layout

| path                            | what                                                                                |
|---------------------------------|-------------------------------------------------------------------------------------|
| `Makefile`                      | every stage                                                                         |
| `scripts/conv/` `scripts/fix/`  | the drivers, source-launched under JEP 458 — one per stage                          |
| `scripts/transformers/`         | the transforms those drivers call (xb, feedfix, common)                             |
| `scripts/tests/`                | the suite `make test` runs                                                          |
| `scripts/fetch.sh`              | the download gate: verifies the bytes before the `mv`, re-fetches once if they fail |
| `scripts/feed-version.sh`       | publisher versions for the sources that send no `ETag` — see `docs/datasources.md`  |
| `graph/`                        | OTP's base dir — flat, so the configs put there name bare filenames                 |
| `geo/switzerland-italy.geojson` | the polygon `osmium extract` cuts the region PBF with                               |
| `input/` `data/` `state/`       | sources, stores, run timings and download validators                                |

A refresh keeps a source file, and its timestamp, when the bytes come back unchanged, so the stores
built from it are not rebuilt for nothing. `docs/datasources.md` lists what each source revalidates
by.

## Checks

`make test` runs `scripts/tests/AllTests.java` in one JVM; `make compile-gate` javac's every
`.java` under `scripts/`, and is the only check that reaches `ObbToDb` and `SwissToDb`.
