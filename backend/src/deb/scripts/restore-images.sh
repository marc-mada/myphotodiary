#!/bin/sh
# Copyright 2014-2026 Marc Lamberton
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Restores original photos/videos from a backup-images.sh snapshot
# (18/09/2026). Same "shipped but never automated" discipline as
# restore-db.sh - see that script's own comment.
#
# Only originals were ever backed up (.thumbnails/.webimg are deliberately
# excluded - see backup-images.sh's own comment) - after a successful
# restore, re-index/Batch Publish the restored directories (Admin -> Index
# management) to regenerate their derivatives. This script does not do that
# for you.
#
# Dry-run by default (plain rsync -n, real changes only listed, nothing
# written) - same discipline as this project's other admin tools
# (LegacyDataImporter/DuplicateDirectoryMerger). Non-destructive merge by
# default even once confirmed: existing files in <storage-root> that aren't
# in the chosen snapshot are left alone, never deleted, unless --exact is
# also given - restoring from an *older* snapshot should not silently
# discard genuinely newer photos that were published since.
#
# Usage:
#   restore-images.sh <backup-root> <storage-root> <timestamp> [--yes] [--exact]
#
#   <backup-root>   the MPD_BACKUP_ROOT this snapshot was taken into
#   <storage-root>  the live MPD_STORAGE_ROOT to restore into
#   <timestamp>     one of the snapshot names under <backup-root>/images/
#                    (list them: ls <backup-root>/images/)
#   --yes           actually perform the restore (dry-run without it)
#   --exact         also remove anything under <storage-root> that isn't
#                    present in the chosen snapshot (rsync --delete) -
#                    omit unless you specifically want storage-root to end
#                    up an exact mirror of that snapshot

set -eu

if [ "$#" -lt 3 ]; then
	echo "Usage: $0 <backup-root> <storage-root> <timestamp> [--yes] [--exact]" >&2
	exit 2
fi

BACKUP_ROOT="$1"
STORAGE_ROOT="$2"
TIMESTAMP="$3"
shift 3

CONFIRM=""
EXACT=""
for arg in "$@"; do
	case "$arg" in
	--yes) CONFIRM="--yes" ;;
	--exact) EXACT="--delete" ;;
	*)
		echo "restore-images.sh: unrecognized argument: $arg" >&2
		exit 2
		;;
	esac
done

SNAPSHOT_DIR="$BACKUP_ROOT/images/$TIMESTAMP"

if [ ! -d "$SNAPSHOT_DIR" ]; then
	echo "restore-images.sh: no such snapshot: $SNAPSHOT_DIR" >&2
	echo "Available snapshots:" >&2
	ls "$BACKUP_ROOT/images" 2>/dev/null >&2 || echo "  (none - is --backup-root correct?)" >&2
	exit 1
fi

RSYNC_OPTS="-a"
if [ -n "$EXACT" ]; then
	RSYNC_OPTS="$RSYNC_OPTS --delete"
fi
if [ "$CONFIRM" != "--yes" ]; then
	RSYNC_OPTS="$RSYNC_OPTS -n -v"
	echo "DRY RUN - nothing will be written. Re-run with --yes at the end to actually restore."
	echo "(files rsync would touch are listed below)"
fi

echo "Restoring $SNAPSHOT_DIR -> $STORAGE_ROOT (mode: $([ -n "$EXACT" ] && echo "exact mirror, --delete" || echo "merge, existing extra files kept"))"
echo

# shellcheck disable=SC2086
rsync $RSYNC_OPTS "$SNAPSHOT_DIR/" "$STORAGE_ROOT/"

if [ "$CONFIRM" = "--yes" ]; then
	echo
	echo "restore-images.sh: done. Next: re-index/Batch Publish the restored directories (Admin -> Index management) to regenerate .thumbnails/.webimg."
fi
