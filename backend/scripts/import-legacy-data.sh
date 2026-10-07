#!/usr/bin/env bash
# Terminal wrapper for LegacyDataImporter (28/08/2026) - converts a legacy
# myPhotoDiary HSQLDB database into this backend's own Flyway-managed schema.
# See LegacyDataImporter.java's own javadoc for the full "what changed and
# why" schema comparison and what this tool does NOT do (image/video files
# on disk, thumbnail regeneration - both handled separately, see that
# javadoc's own step-by-step for after this script finishes).
#
# Usage:
#   backend/scripts/import-legacy-data.sh <legacy-db-path> <new-db-path> [--dry-run]
#
# Both paths are HSQLDB file-mode database prefixes (no .script extension -
# e.g. /path/to/photoindex, matching jdbc:hsqldb:file:<path>).
#
# Prerequisites:
#   - Stop both the legacy Tomcat app and this backend first - HSQLDB
#     file-mode locks its database to one JVM process at a time.
#   - The target database must already have this backend's own schema
#     applied (start this backend once against it - Flyway runs
#     automatically - before running this script).
#   - Maven itself, obviously - on whichever machine runs this script.
#     On a machine that has never built this project before (a fresh
#     server, not the dev machine §3's own build already ran on), Maven
#     needs to resolve this project's parent POM/dependencies from Maven
#     Central the first time - hence no `-o`/offline flag below, on
#     purpose: this is a rare, one-off admin tool invocation, not a
#     repeated deploy build, so there's no real benefit to forcing
#     offline mode here, only the risk of it failing outright on a
#     machine with a cold ~/.m2 (30/08/2026, found exactly this way).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(dirname "$SCRIPT_DIR")"

if [ "$#" -lt 2 ]; then
	echo "Usage: $0 <legacy-db-path> <new-db-path> [--dry-run]" >&2
	exit 1
fi

# Resolve the two path arguments to absolute paths BEFORE the `cd
# "$BACKEND_DIR"` below (31/08/2026, a real bug found running this for
# real: a relative path like ./db/photoindex silently resolved against
# $BACKEND_DIR instead of the caller's own directory once that cd ran,
# leaving stray files inside this very repo - db/photoindex.script,
# new-db.script - instead of wherever the caller actually meant). Only
# the two positional path args need this; --dry-run/--legacy-user=/
# --legacy-password= pass through untouched.
resolve_prefix() {
	local input="$1" dir base resolved_dir
	dir="$(dirname "$input")"
	base="$(basename "$input")"
	if resolved_dir="$(cd "$dir" 2>/dev/null && pwd)"; then
		echo "$resolved_dir/$base"
	else
		# Parent directory doesn't exist (yet) - nothing to resolve against,
		# pass the original value through rather than guessing.
		echo "$input"
	fi
}

legacyPath="$(resolve_prefix "$1")"
newPath="$(resolve_prefix "$2")"
shift 2

cd "$BACKEND_DIR"
exec mvn -q exec:java \
	-Dexec.mainClass=org.myphotodiary.cms.migration.LegacyDataImporter \
	-Dexec.args="$legacyPath $newPath $*"
