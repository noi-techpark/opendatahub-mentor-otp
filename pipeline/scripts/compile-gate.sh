#!/bin/bash
# The JEP-458 compile gate — whatever ships must at least compile, and must at least launch.
#
# Subject: scripts/ — this repository's only source tree, holding conv/, fix/, transformers/ and
# tests/. netex-toolkit runs the same two arms over its own tree in tools/gate/compile.sh; this
# copy differs only in the tree and the classpath source.
#
# Nothing else compiles this tree: there is no Maven module, no CI, and `make test` reaches only
# what AllTests names — conv/ObbToDb.java and conv/SwissToDb.java have no test at all.
#
# ARM 1 — LAUNCHABLE. The JEP-458 rule for an INITIAL source file: if the file declares a package,
# its directory must end with that package's directory path, and the source root is what remains.
# There is no --source-path on the `java` launcher to override it. A file whose package and path
# disagree fails at the FIRST LINE of its documented invocation, and javac does not reproduce that
# — it accepts any file you name, wherever it sits. This arm is also what pins the repository
# split: a driver here can only see .java under scripts/, everything else must come from the jar.
#
# ARM 2 — COMPILES. One javac per implied source root with -sourcepath set to THAT root, so a file
# is compiled the way it will actually be launched rather than against the whole tree.
#
# RUN IT:  ./scripts/compile-gate.sh          (or `make compile-gate`)
#
#   P        this repository's root. Defaults to the parent of this script's directory.
#   T        the trees to gate. Defaults to $P/scripts.
#   TOOLKIT_JAR   the shaded toolkit jar. Defaults to $P/netex-toolkit-shaded.jar, which is where
#            the Makefile expects it too — next to otp-shaded.jar.
#   JAVAC    the compiler. Defaults to `javac`; pass a wrapper if the host has no JDK on PATH.
#
# EXIT: 0 all files launchable and compiling; 1 otherwise; 2 the gate could not run (which is a
# failure, not a skip — see the operative-first block below).
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# $HERE is scripts/, so the repo root is one level up. The subject is this repository's single
# source tree: scripts/, holding conv/, fix/, transformers/ and tests/.
P="${P:-$(cd "$HERE/.." && pwd)}"
T="${T:-$P/scripts}"
# One uber jar next to otp-shaded.jar at the repo root, exactly as the Makefile expects it.
# External build product; not committed.
TOOLKIT_JAR="${TOOLKIT_JAR:-$P/netex-toolkit-shaded.jar}"
JAVAC="${JAVAC:-javac}"
# The subject is 45 files. The number is a tripwire for "a tree moved or a commit removed it",
# not a target — set just under the true count so losing a whole directory (tests/ alone is 17)
# fails loudly instead of passing on a shrunken subject.
MIN_FILES="${MIN_FILES:-40}"

fail_inoperative() { echo "GATE INOPERATIVE: $*" >&2; exit 2; }

# ---------------------------------------------------------------- preconditions
# Every precondition here is checked and every failure is loud: `grep -r` on a missing directory
# warns, exits non-zero, and `|| true` swallows it, leaving a green over nothing at all. Every
# named tree must exist — a missing one is a moved tree, not a smaller subject.
# T is a deliberate space-separated list of trees and must word-split. (The explanation goes ABOVE
# the directive: shellcheck parses no trailing text after `disable=`, and a malformed directive is
# an SC1073 error that stops it reading the file -- which is what this line used to be.)
# shellcheck disable=SC2086
set -- $T
[ "$#" -ge 1 ] || fail_inoperative "T is empty (set T, or P to the source root)"
for t in "$@"; do
  [ -d "$t" ] || fail_inoperative "no tree at $t (set T, or P to the source root)"
done

files=()
while IFS= read -r -d '' f; do files+=("$f"); done \
  < <(find "$@" -name '*.java' -type f -print0 | sort -z)
n=${#files[@]}
[ "$n" -ge "$MIN_FILES" ] || fail_inoperative \
  "only $n .java files under $T (expect >= $MIN_FILES). A tree moved, or a commit removed it."

JAR="$TOOLKIT_JAR"
[ -f "$JAR" ] || fail_inoperative \
  "no jar at $JAR — run 'make download-toolkit' to fetch the current release, or build it in the
   netex-toolkit checkout with 'mvn -B -ntp package'"
# The likely mistake is copying netex-toolkit.jar (the thin one, useless without its deps/)
# instead of netex-toolkit-shaded.jar, so check the size rather than trust the name. The jar's
# CONTENT is gated where it is built, by assert-shaded-jar-is-complete in
# netex-toolkit/toolkit/pom.xml.
jarkb=$(( $(wc -c < "$JAR") / 1024 ))
[ "$jarkb" -ge 5120 ] || fail_inoperative \
  "$JAR is only ${jarkb} KB — that is the thin netex-toolkit.jar, not the shaded one (expect >= 5 MB)"
command -v "${JAVAC%% *}" >/dev/null 2>&1 || [ -x "${JAVAC%% *}" ] \
  || fail_inoperative "no javac ($JAVAC). Install a JDK, or pass JAVAC=<wrapper>."

CP="$JAR"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

echo "P=$P"
echo "T=$T"
echo "  ($n .java files)"
echo "CP=$JAR (${jarkb} KB, shaded)"

# ---------------------------------------------------------------- arm 1: launchable
# The JEP-458 rule for an INITIAL source file, from JDK 25's SourceLauncher: if the file declares
# a package, the file's directory must end with the package's directory path, and the source root
# is what remains. With no package declaration the root is the file's own directory. Nothing else
# is accepted — the `java` launcher has no --source-path to override it (both `--source-path` and
# `-sourcepath` are rejected as unrecognized JVM options).
declare -A GROUP=()
bad_root=0
for f in "${files[@]}"; do
  d=$(dirname "$f")
  pkg=$(sed -n 's/^[[:space:]]*package[[:space:]]\{1,\}\([A-Za-z0-9_.]\{1,\}\)[[:space:]]*;.*/\1/p' "$f" | head -1)
  if [ -z "$pkg" ]; then
    root=$d
  else
    rel=${pkg//.//}
    case "$d" in
      */"$rel") root=${d%"/$rel"} ;;
      "$rel")   root=. ;;
      *)
        if [ "$bad_root" = 0 ]; then
          echo
          echo "NOT LAUNCHABLE — the JEP-458 source launcher refuses these as initial files:"
        fi
        bad_root=$((bad_root+1))
        echo "  ${f#"$P"/}  declares 'package $pkg' but does not sit under .../$rel"
        continue ;;
    esac
  fi
  GROUP["$root"]="${GROUP["$root"]:-} $f"
done

if [ "$bad_root" != 0 ]; then
  echo
  echo "Each of the $bad_root files above fails at the FIRST LINE of its documented invocation:"
  echo "    error: end of path to source file does not match its package name"
  echo "javac does NOT reproduce this — it accepts any file you name, wherever it sits — so a"
  echo "compile-only gate reports green over exactly this defect. That is why this arm exists."
  echo "Fix the package declaration to match the shipped path, or ship the file at the path its"
  echo "package names. Do not delete this arm."
fi

# ---------------------------------------------------------------- arm 2: compiles
rc=0
i=0
for root in "${!GROUP[@]}"; do
  i=$((i+1))
  # GROUP holds a space-separated list of find-produced paths and must word-split. Explanation
  # above the directive, for the SC1073 reason given at the first one of these.
  # shellcheck disable=SC2086
  set -- ${GROUP["$root"]}
  shown=${root#"$P"}; shown=${shown#/}; shown=${shown:-.}
  d="$OUT/$i"; mkdir -p "$d"
  log="$OUT/$i.log"
  if $JAVAC -nowarn -proc:none -Xmaxerrs 200 -d "$d" -cp "$CP" -sourcepath "$root" "$@" > "$log" 2>&1; then
    produced=$(find "$d" -name '*.class' | wc -l)
    # javac exiting 0 having emitted nothing would mean this gate compiled nothing. Say so.
    if [ "$produced" -lt "$#" ]; then
      echo "GATE INOPERATIVE: javac reported success for root $shown but emitted $produced" >&2
      echo "  class files for $# sources. It did not compile what it was given." >&2
      rc=2
    else
      printf '  [ok]   %-28s %2d sources -> %3d classes\n' "$shown/" "$#" "$produced"
    fi
  else
    echo
    echo "COMPILE FAILURE under source root $shown ($# sources):"
    grep -E '\.java:[0-9]+: error:|^[0-9]+ error' "$log" | head -40
    rc=1
  fi
done

echo
if [ "$bad_root" != 0 ] && [ "$rc" = 0 ]; then rc=1; fi
if [ "$rc" = 0 ]; then
  echo "[gate] OK — $n sources across ${#GROUP[@]} source roots: all launchable, all compile"
else
  echo "[gate] FAILED — $bad_root not launchable; see compile errors above"
fi
exit $rc
