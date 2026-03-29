#!/usr/bin/env bash
#
# vitruvius-merge.sh — Git custom merge driver for Vitruvius VSUM repositories.
#
# See BrakeCaseStudy/scripts/vitruvius-merge.sh for full documentation.
#
# Arguments: $1=%O $2=%A $3=%B $4=%L $5=%P
#
set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"

# Build the classpath from Maven (cached after first run)
CLASSPATH_FILE="${REPO_ROOT}/vsum/target/merge-driver-classpath.txt"
if [ ! -f "$CLASSPATH_FILE" ]; then
    echo "[vitruvius-merge] Building classpath (first run)..." >&2
    (cd "$REPO_ROOT" && ./mvnw -pl vsum dependency:build-classpath \
        -Dmdep.outputFile=target/merge-driver-classpath.txt \
        -Dmdep.includeScope=test -q) >&2
fi

CLASSPATH="$(cat "$CLASSPATH_FILE"):${REPO_ROOT}/vsum/target/classes:${REPO_ROOT}/vsum/target/test-classes"

java -cp "$CLASSPATH" \
    tools.vitruv.framework.vsum.branch.merge.GitMergeDriver \
    "$1" "$2" "$3" "$4" "$5"
