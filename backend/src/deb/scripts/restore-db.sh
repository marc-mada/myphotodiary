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

# Restores a DB snapshot taken by DatabaseBackupService (18/09/2026). Shipped
# in the package (see pom.xml's deb profile) so it's available on the server
# without a git checkout at the exact moment it's needed - but deliberately
# NEVER wired to any systemd unit/automation: restore is a rare, supervised,
# human decision, same discipline this project already applies to
# LegacyDataImporter/DuplicateDirectoryMerger (dry-run first, explicit
# confirmation, never a one-click web action - see BackupController's own
# javadoc for why there is no restore *endpoint* at all).
#
# Restoring a "BACKUP DATABASE ... AS FILES" snapshot needs no special
# HSQLDB command - it's a plain copy of the database's own native files
# (confirmed live while building this feature: AS FILES preserves the
# original file basenames, e.g. photoindex.properties/photoindex.script,
# inside the snapshot directory) - so restore is just "stop the service,
# replace the live db files with the chosen snapshot's, start it again".
#
# Usage:
#   restore-db.sh <backup-root> <db-directory> <timestamp> [--yes]
#
#   <backup-root>   the MPD_BACKUP_ROOT this snapshot was taken into
#   <db-directory>  the directory *containing* the live database files
#                   (i.e. the directory part of MPD_DB_PATH, not the prefix
#                   itself - e.g. /var/lib/myphotodiary-backend/db)
#   <timestamp>     one of the snapshot names under <backup-root>/db/
#                   (list them: ls <backup-root>/db/)
#   --yes           actually perform the restore; without it, this only
#                   prints what it *would* do (dry-run by default, same
#                   discipline as this project's other admin tools)
#
# After a successful restore: sudo systemctl start myphotodiary-backend,
# then verify the app looks right (Admin screen, a few real sequences)
# before considering the incident closed.

set -eu

if [ "$#" -lt 3 ]; then
	echo "Usage: $0 <backup-root> <db-directory> <timestamp> [--yes]" >&2
	exit 2
fi

BACKUP_ROOT="$1"
DB_DIR="$2"
TIMESTAMP="$3"
CONFIRM="${4:-}"

SNAPSHOT_DIR="$BACKUP_ROOT/db/$TIMESTAMP"

if [ ! -d "$SNAPSHOT_DIR" ]; then
	echo "restore-db.sh: no such snapshot: $SNAPSHOT_DIR" >&2
	echo "Available snapshots:" >&2
	ls "$BACKUP_ROOT/db" 2>/dev/null >&2 || echo "  (none - is --backup-root correct?)" >&2
	exit 1
fi

if command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet myphotodiary-backend 2>/dev/null; then
	echo "restore-db.sh: myphotodiary-backend is still running - stop it first:" >&2
	echo "  sudo systemctl stop myphotodiary-backend" >&2
	exit 1
fi

SAFETY_COPY="$DB_DIR/.pre-restore-$(date -u +%Y-%m-%dT%H-%M-%SZ)"

echo "Restore plan:"
echo "  snapshot:        $SNAPSHOT_DIR"
echo "  live db dir:     $DB_DIR"
echo "  safety copy of the CURRENT live db files (in case this restore turns out to be wrong) -> $SAFETY_COPY"
echo

if [ "$CONFIRM" != "--yes" ]; then
	echo "Dry run only - nothing changed. Re-run with --yes at the end to actually restore."
	exit 0
fi

mkdir -p "$SAFETY_COPY"
if [ -d "$DB_DIR" ]; then
	# find+cp rather than a directory-level move: DB_DIR may contain
	# other unrelated files this tool has no business touching, and only
	# the top level matters here (HSQLDB file-mode databases are a flat
	# set of files, never subdirectories).
	find "$DB_DIR" -maxdepth 1 -type f -exec cp -a {} "$SAFETY_COPY/" \;
fi

mkdir -p "$DB_DIR"
find "$SNAPSHOT_DIR" -maxdepth 1 -type f -exec cp -a {} "$DB_DIR/" \;

echo "restore-db.sh: done. Pre-restore copy of the previous live files kept at $SAFETY_COPY (delete it by hand once you're confident this restore was correct)."
echo "Next: start myPhotoDiary again (sudo systemctl start myphotodiary-backend, or docker start <container>), then verify the app."
