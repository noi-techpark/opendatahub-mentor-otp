#!/bin/bash
# Publisher versions for the feeds whose hosts carry no HTTP validator, as `<id> <token>` lines for
# `fetch.sh --validator`.
#
# The cciss NAP and the Austrian DBP portal send neither ETag nor Last-Modified, and answer a
# conditional request with the whole body. Each publishes a per-file version through its catalogue
# instead.
#
# RUN IT:
#   feed-version.sh rap <assetId>...     cciss NAP, the checkedResource upload date
#   feed-version.sh dbp <dataSetId>...   Austrian DBP, the data set version id
#
#   CURL           the fetch command, as fetch.sh takes it.
#   NAP_BUILD      pins the cciss Next.js buildId; discovered from a catalogue page when unset.
#   PROBE_TIMEOUT  seconds any one catalogue request may take (30).
#   DBP_TOKEN      the bearer token for `dbp`, read from the environment or from stdin. It never
#                  goes on argv, where ps would show it to every user on the box.
#
# An id whose version cannot be read is omitted from the output, which leaves its caller to fetch
# unconditionally. Nothing here is allowed to block a download.
#
# EXIT: 0 whenever the run completed, whatever it managed to resolve.
set -uo pipefail

CURL="${CURL:-curl -fsSL --retry 3 --retry-all-errors --speed-limit 1024 --speed-time 60 -A Mozilla/5.0}"
PARALLEL_MAX="${PARALLEL_MAX:-6}"

# A catalogue answers an unknown id with a 404, and under the shared $(CURL) --retry-all-errors
# backs that off and honours Retry-After — minutes of waiting over a version this script is allowed
# to give up on. Later curl flags win, so these bound every request made here.
PROBE="--retry 0 --max-time ${PROBE_TIMEOUT:-30}"

NAP=https://www.cciss.it/nap/mmtis/public
DBP=https://data.mobilitaetsverbuende.at/api/public/v1
export MV_YEAR="${MV_YEAR:-2026}"

usage() { echo "usage: feed-version.sh rap|dbp <id>..." >&2; exit 2; }
[ "$#" -ge 2 ] || usage
MODE=$1; shift

WORK=$(mktemp -d) || exit 2
trap 'rm -rf "$WORK"' EXIT

# One curl for the whole batch. The ids travel through a --config file, which keeps the DBP bearer
# token out of argv and holds a batch of every RAP feed at once, past what ARG_MAX would take on a
# command line.
fetch_batch() {
  local dir=$1 cfg=$2
  mkdir -p "$dir"
  # shellcheck disable=SC2086
  $CURL $PROBE --parallel --parallel-max "$PARALLEL_MAX" --config "$cfg" >/dev/null 2>&1
}

# Reads every JSON file in $1 and prints `<id> <token>`. $2 selects the shape:
#
#   rap  entityData.attributes[], the Blob named checkedResource, its uploadDate. The record's own
#        lastModificationDate moves when the unvalidated `resource` Blob is re-uploaded, which
#        happens nightly for feeds whose checked file has not changed.
#   dbp  activeVersions[] for the timetable year, dataSetVersion.id.
emit() {
  python3 - "$1" "$2" <<'PY'
import json, os, sys
d, mode = sys.argv[1], sys.argv[2]
for name in sorted(os.listdir(d)):
    fid = name[:-5] if name.endswith(".json") else name
    try:
        with open(os.path.join(d, name), encoding="utf-8") as fh:
            doc = json.load(fh)
    except Exception:
        continue
    tok = None
    try:
        if mode == "rap":
            # The data route answers {pageProps:{entityData}}; the page embeds
            # {props:{pageProps:{entityData}}}.
            e = doc
            for key in ("props", "pageProps", "entityData"):
                if isinstance(e, dict) and key in e:
                    e = e[key]
            for a in e.get("attributes", []):
                if a.get("type") == "Blob" and a.get("name") == "checkedResource":
                    tok = a["value"].get("uploadDate")
        else:
            year = os.environ.get("MV_YEAR", "")
            for v in doc.get("activeVersions", []):
                if v.get("year") == year:
                    tok = v["dataSetVersion"]["id"]
    except Exception:
        tok = None
    if tok:
        print(fid, tok)
PY
}

case "$MODE" in
  rap)
    # The buildId rotates on every portal deploy, so it is read from a catalogue page each run.
    BUILD="${NAP_BUILD:-}"
    # shellcheck disable=SC2086
    [ -n "$BUILD" ] || BUILD=$($CURL $PROBE "$NAP/en/catalog/Asset/$1" 2>/dev/null | python3 -c 'import sys, re, json
m = re.search(r"id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", sys.stdin.read(), re.S)
print(json.loads(m.group(1))["buildId"] if m else "")' 2>/dev/null)

    if [ -n "$BUILD" ]; then
      : > "$WORK/data.cfg"
      for id in "$@"; do
        printf 'url = "%s/_next/data/%s/en/catalog/Asset/%s.json"\noutput = "%s/data/%s.json"\n' \
          "$NAP" "$BUILD" "$id" "$WORK" "$id" >> "$WORK/data.cfg"
      done
      fetch_batch "$WORK/data" "$WORK/data.cfg"
      emit "$WORK/data" rap > "$WORK/out"
    else
      : > "$WORK/out"
    fi

    # Whatever the data route did not answer for — a rotated buildId, a 404 — comes back off the
    # public page, which carries the same __NEXT_DATA__ block and needs no buildId.
    : > "$WORK/page.cfg"
    missing=0
    for id in "$@"; do
      grep -q "^$id " "$WORK/out" && continue
      printf 'url = "%s/en/catalog/Asset/%s"\noutput = "%s/page/%s.html"\n' \
        "$NAP" "$id" "$WORK" "$id" >> "$WORK/page.cfg"
      missing=1
    done
    if [ "$missing" = 1 ]; then
      fetch_batch "$WORK/page" "$WORK/page.cfg"
      mkdir -p "$WORK/page-json"
      for f in "$WORK"/page/*.html; do
        [ -e "$f" ] || continue
        id=$(basename "$f" .html)
        python3 -c 'import sys, re
m = re.search(r"id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", open(sys.argv[1], encoding="utf-8", errors="replace").read(), re.S)
sys.stdout.write(m.group(1) if m else "")' "$f" > "$WORK/page-json/$id.json" 2>/dev/null
      done
      emit "$WORK/page-json" rap >> "$WORK/out"
    fi
    sort -u "$WORK/out"
    ;;

  dbp)
    TOKEN="${DBP_TOKEN:-}"
    [ -n "$TOKEN" ] || { [ -t 0 ] || read -r TOKEN; }
    [ -n "$TOKEN" ] || { echo "feed-version.sh dbp: no DBP_TOKEN" >&2; exit 0; }
    : > "$WORK/dbp.cfg"
    printf 'header = "Authorization: Bearer %s"\n' "$TOKEN" >> "$WORK/dbp.cfg"
    for id in "$@"; do
      printf 'url = "%s/data-sets/%s"\noutput = "%s/dbp/%s.json"\n' "$DBP" "$id" "$WORK" "$id" >> "$WORK/dbp.cfg"
    done
    fetch_batch "$WORK/dbp" "$WORK/dbp.cfg"
    emit "$WORK/dbp" dbp
    ;;

  *) usage ;;
esac
