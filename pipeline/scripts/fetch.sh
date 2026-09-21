#!/bin/bash
# The download gate — a feed that arrives truncated must never reach input/, and a feed whose bytes
# have not changed must never get a new mtime.
#
# `curl -fsSL` exits 0 on a short read whenever the response carries no Content-Length: curl can
# only diagnose a truncated transfer against a length it was told. A plain
# `curl -o $@.tmp "$url" && mv $@.tmp $@` therefore renames partial files into input/ having looked
# at nothing but the exit status. And because every $(INPUT_DIR) rule has NO prerequisites — it
# fires only when its target is MISSING — make never re-fetches the broken file; it re-runs the
# load against the same broken bytes for ever.
#
# So the bytes are checked BEFORE the mv, on the .tmp, and a failed check re-fetches once before
# giving up. --retry-all-errors and the speed floor in the Makefile's $(CURL) make truncation
# rarer; this is what makes it non-fatal.
#
# Each source file is an ordinary prerequisite of the store loaded from it, so its mtime is the
# only signal make has that new data must be loaded. The direction must hold in both senses: a
# fresh mtime over identical bytes rebuilds every store behind the file, and an old mtime over new
# bytes drops the new data with no error anywhere.
#
# RUN IT:
#   fetch.sh [--validator <token>] <url> <dest> [extra curl args...]
#                                                download, verify, retry once, rename into place
#   fetch.sh --check <file>                      run the integrity check alone (no download)
#
#   CURL       the fetch command. Defaults to the Makefile's own flags; the Makefile passes its
#              $(CURL) through the environment so the two cannot drift.
#   STATE_DIR  where the validator sidecars live. Without it the gate still revalidates against the
#              destination's mtime and still compares bytes, and remembers nothing between runs.
#
# EXIT: 0 verified, unchanged, or not modified; 1 the file is bad (or the download failed); 2 the
# gate could not run — a missing checker is a failure, not a skip, because a check that silently
# no-ops reports green over exactly the defect this file exists to catch.
set -uo pipefail

CURL="${CURL:-curl -fsSL --retry 3 --retry-all-errors --speed-limit 1024 --speed-time 60 -A Mozilla/5.0}"

fail_inoperative() { echo "FETCH GATE INOPERATIVE: $*" >&2; exit 2; }

# ---------------------------------------------------------------- the checks
# Chosen to catch TRUNCATION without paying to decompress. Every one of these reads to (or seeks
# to) the end of the file, which is the only place a short transfer shows up.
#
#   .gz    gzip -t walks the whole deflate stream and verifies the trailing CRC/ISIZE.
#   .zip   the central directory lives at the END of a zip, so a truncated one has none and
#   .jar   ZipFile() raises. Full CRC verification (testzip) would decompress hundreds of MB on
#          every check for no extra truncation coverage. A jar IS a zip, and it shares this arm
#          rather than falling through to `*)`: the Makefile fetches netex-toolkit-shaded.jar
#          through here, and a size-only gate on a 47 MB jar would pass a half one to javac.
#   .xml   streaming well-formedness: a cut-off document ends inside an unclosed element.
#   .pbf   osmium walks the blob chain; without osmium there is no cheap structural check, so this
#          degrades to a size check and SAYS SO rather than pretending.
#   *      size only, and it says so.
#
# $1 is the file to READ, $2 the name to CLASSIFY BY. They differ during a download, where the
# bytes sit in <dest>.tmp but the check has to be chosen from <dest>: classifying by the file's own
# name there matches `*)` on the .tmp suffix and degrades every download to a size check. The
# `note:` line in the `*)` arm is what makes that visible.
check_file() {
  local f=$1 as=${2:-$1} out
  [ -f "$f" ] || { echo "  missing: $f" >&2; return 1; }
  [ -s "$f" ] || { echo "  empty: $f" >&2; return 1; }
  case "$as" in
    *.gz)
      command -v gzip >/dev/null || fail_inoperative "no gzip on PATH; cannot verify $f"
      out=$(gzip -t "$f" 2>&1) || { echo "  not a complete gzip stream: ${out:-truncated}" >&2; return 1; } ;;
    *.zip|*.jar)
      command -v python3 >/dev/null || fail_inoperative "no python3 on PATH; cannot verify $f"
      out=$(python3 -c 'import sys,zipfile; zipfile.ZipFile(sys.argv[1]).namelist()' "$f" 2>&1) \
        || { echo "  no readable zip central directory: $(echo "$out" | tail -1)" >&2; return 1; } ;;
    *.xml)
      command -v python3 >/dev/null || fail_inoperative "no python3 on PATH; cannot verify $f"
      out=$(python3 -c 'import sys,xml.etree.ElementTree as E
for _ in E.iterparse(sys.argv[1], events=("end",)): pass' "$f" 2>&1) \
        || { echo "  not well-formed XML: $(echo "$out" | tail -1)" >&2; return 1; } ;;
    *.pbf)
      if command -v osmium >/dev/null; then
        # -F pbf because osmium classifies by filename as well, and during a download the bytes are
        # in <dest>.tmp: left to itself it refuses the name before reading a byte, and every .pbf
        # fetch fails its check twice and reports the body as truncated.
        out=$(osmium fileinfo -F pbf "$f" 2>&1) || { echo "  osmium refused it: $(echo "$out" | tail -1)" >&2; return 1; }
      else
        echo "  note: no osmium on PATH — $f checked for size only" >&2
      fi ;;
    *)
      echo "  note: no structural check for ${as##*.} — $f checked for size only" >&2 ;;
  esac
  return 0
}

# Whether two local files hold the same bytes. `cmp` is absent from some of the machines this runs
# on, so the compare is python3, which the checks above already require.
same_bytes() {
  local a=$1 b=$2
  [ -f "$b" ] || return 1
  [ "$(wc -c < "$a")" = "$(wc -c < "$b")" ] || return 1
  command -v python3 >/dev/null || fail_inoperative "no python3 on PATH; cannot compare $a with $b"
  python3 -c 'import sys
a, b = open(sys.argv[1], "rb"), open(sys.argv[2], "rb")
while True:
    x, y = a.read(1 << 20), b.read(1 << 20)
    if x != y: sys.exit(1)
    if not x: sys.exit(0)' "$a" "$b"
}

# ---------------------------------------------------------------- --check
if [ "${1:-}" = "--check" ]; then
  [ "$#" -ge 2 ] || fail_inoperative "usage: fetch.sh --check <file> [file...]"
  shift
  rc=0
  for f in "$@"; do
    if check_file "$f"; then
      printf '  [ok]   %s (%s bytes)\n' "$f" "$(wc -c < "$f")"
    else
      printf '  [BAD]  %s (%s bytes)\n' "$f" "$([ -f "$f" ] && wc -c < "$f" || echo 0)"
      rc=1
    fi
  done
  exit $rc
fi

# ---------------------------------------------------------------- download
VALIDATOR=
while [ "$#" -gt 0 ]; do
  case "$1" in
    --validator) VALIDATOR=${2:-}; [ -n "$VALIDATOR" ] || fail_inoperative "--validator needs a token"; shift 2 ;;
    *) break ;;
  esac
done

[ "$#" -ge 2 ] || fail_inoperative "usage: fetch.sh [--validator <token>] <url> <dest> [extra curl args...]"
URL=$1; DEST=$2; shift 2

# One sidecar per destination, holding whichever kind of token the call chose:
#
#   --validator <token>   an opaque string the caller read from the publisher's catalogue. The
#                         cciss NAP and the Austrian DBP portal send neither ETag nor Last-Modified
#                         and answer a conditional request with the whole body; each publishes a
#                         per-file version through its own API instead. A token equal to the stored
#                         one skips the request.
#   (default)             the HTTP and FTP validators. Some of these hosts answer If-Modified-Since
#                         with 200 and the entire body while honouring If-None-Match, so a stored
#                         ETag is preferred over the mtime, and --etag-save runs on the first
#                         download too. --etag-save writes an empty file when the response carries
#                         no ETag, so an empty sidecar reads as none stored and the request falls
#                         back to -z.
#
# A sidecar speaks for the bytes in $DEST. Once $DEST is gone — repair-inputs deletes what fails
# its check, then re-fetches — a stored token would draw a 304 that writes nothing at all, so the
# sidecar goes with the file.
SIDECAR=
if [ -n "${STATE_DIR:-}" ] && [ -d "${STATE_DIR:-}" ]; then
  SIDECAR="$STATE_DIR/.validator.${DEST##*/}"
fi
HAVE=
[ -f "$DEST" ] && HAVE=1
[ -n "$HAVE" ] || { [ -n "$SIDECAR" ] && rm -f "$SIDECAR" "$SIDECAR.tmp"; }

store_validator() {
  [ -n "$SIDECAR" ] || return 0
  if [ -n "$VALIDATOR" ]; then
    printf '%s' "$VALIDATOR" > "$SIDECAR"
  elif [ -f "$SIDECAR.tmp" ]; then
    mv "$SIDECAR.tmp" "$SIDECAR"
  fi
}

if [ -n "$HAVE" ] && [ -n "$VALIDATOR" ] && [ -n "$SIDECAR" ] && [ -f "$SIDECAR" ] \
   && [ "$(cat "$SIDECAR")" = "$VALIDATOR" ]; then
  echo "  not modified: $(basename "$DEST") (publisher version $VALIDATOR)"
  exit 0
fi

COND=()
if [ -z "$VALIDATOR" ]; then
  [ -n "$SIDECAR" ] && COND+=(--etag-save "$SIDECAR.tmp")
  if [ -n "$HAVE" ]; then
    if [ -n "$SIDECAR" ] && [ -s "$SIDECAR" ]; then
      COND+=(--etag-compare "$SIDECAR")
    else
      COND+=(-z "$DEST")
    fi
  fi
fi

# One retry, no more: a URL that serves a broken body twice is broken at the source, and the
# 301-feed run should say so rather than sit in a loop.
for attempt in 1 2; do
  rm -f "$DEST.tmp"
  # CURL is a deliberate space-separated command line and must word-split.
  # shellcheck disable=SC2086
  code=$($CURL -o "$DEST.tmp" -w '%{http_code}' "${COND[@]}" "$@" "$URL")
  rc=$?
  if [ $rc -eq 0 ]; then
    # curl writes no output file for a 304 and exits 0, and skips an FTP transfer whose MDTM is no
    # newer the same way, so an absent .tmp after a successful run is the not-modified signal in
    # both protocols. %{http_code} carries an FTP reply code over FTP, which is why it reaches the
    # log line and nothing else.
    if [ ! -e "$DEST.tmp" ]; then
      # Only when there is a file for it to mean something. The conditional headers above go out
      # only when $DEST exists, so a body-less response with nothing on disk cannot be a 304 -- it
      # is a transfer that reported success and delivered nothing, and taking it as not-modified
      # stores a validator and exits 0 leaving no file, which surfaces much later as the next
      # reader failing to open it.
      if [ -n "$HAVE" ]; then
        store_validator
        echo "  not modified: $(basename "$DEST") ($code)"
        exit 0
      fi
      echo "fetch: attempt $attempt of 2 for $(basename "$DEST") sent no body ($code) and there is" \
           "no local copy for that to mean unchanged" >&2
      continue
    fi
    if check_file "$DEST.tmp" "$DEST"; then
      if same_bytes "$DEST.tmp" "$DEST"; then
        rm -f "$DEST.tmp"
        store_validator
        echo "  unchanged: $(basename "$DEST") ($(wc -c < "$DEST") bytes, timestamp kept)"
        exit 0
      fi
      mv "$DEST.tmp" "$DEST"
      store_validator
      exit 0
    fi
    echo "fetch: attempt $attempt of 2 for $(basename "$DEST") arrived incomplete" \
         "($(wc -c < "$DEST.tmp") bytes)" >&2
  else
    echo "fetch: attempt $attempt of 2 for $(basename "$DEST") failed to download" >&2
  fi
done

# Take the half file with us: leaving it invites the next reader to mistake it for something
# resumable. The sidecar goes with it, under the rule above.
got=$([ -f "$DEST.tmp" ] && wc -c < "$DEST.tmp" || echo 0)
rm -f "$DEST.tmp"
[ -n "$SIDECAR" ] && rm -f "$SIDECAR.tmp"
cat >&2 <<EOF

FETCH FAILED — $DEST was NOT written.
  url    $URL
  got    $got bytes, twice, and neither passed its integrity check (see above)
The source is serving a truncated or broken body. Nothing was left behind, so the next
\`make\` will try this download again rather than using a half file.
EOF
exit 1
