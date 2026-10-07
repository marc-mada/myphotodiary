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

# Backs up original photos/videos (18/09/2026 - see BackupProperties'
# own javadoc for the full feature design). Deliberately a plain shell
# script run by systemd on a timer, not backend Java code:
#   - Bulk binary file copying belongs to a real filesystem tool (rsync),
#     not a request-serving JVM - reimplementing rsync's own hardlink/
#     delta logic in Java would be slower and riskier for no benefit.
#   - It has to run independently of whether the backend process is up -
#     a stuck/restarting JVM shouldn't also stop nightly backups.
#
# Excludes .thumbnails/ and .webimg/ on purpose - both are regenerable from
# the originals (Admin -> Index management -> Batch Publish / re-index),
# so backing them up would burn real disk/network space on the NAS for
# zero actual data-loss protection.
#
# Same mount-guard reasoning as DatabaseBackupService/BackupMountGuard
# (Java) - reproduced here in shell rather than shared code, since this
# script has no JVM to call into: refuses to write into MPD_BACKUP_ROOT
# unless it is genuinely a distinct mounted filesystem (via `mountpoint`),
# not just an ordinary local directory sitting at that path waiting for a
# NAS mount that hasn't happened yet. Without this check, a "backup" run
# before the NAS is mounted would silently write onto the very disk it's
# supposed to protect against - indistinguishable from a real backup until
# the day that disk actually fails.
#
# Usage: backup-images.sh
# Configuration via environment (same names as myphotodiary-backend.env):
#   MPD_STORAGE_ROOT          gallery originals root (required)
#   MPD_BACKUP_ROOT           backup destination root (required)
#   MPD_BACKUP_RETENTION_DAYS how many days of snapshots to keep (default 30)
#   MPD_BACKUP_REQUIRE_MOUNT  "true" (default) or "false" - see above
#   MPD_BACKUP_MARKER_FILE    optional - when set, a file of this name must
#                             exist in MPD_BACKUP_ROOT (same check as the
#                             backend's; used with the Docker image, where
#                             the mount check can't tell a NAS from a plain
#                             folder)

set -eu

STORAGE_ROOT="${MPD_STORAGE_ROOT:-./data/images}"
BACKUP_ROOT="${MPD_BACKUP_ROOT:-./data/backups}"
RETENTION_DAYS="${MPD_BACKUP_RETENTION_DAYS:-30}"
REQUIRE_MOUNT="${MPD_BACKUP_REQUIRE_MOUNT:-true}"
MARKER_FILE="${MPD_BACKUP_MARKER_FILE:-}"

IMAGES_BACKUP_ROOT="$BACKUP_ROOT/images"
STATUS_FILE="$IMAGES_BACKUP_ROOT/status.json"
TIMESTAMP="$(date -u +%Y-%m-%dT%H-%M-%SZ)"
SNAPSHOT_DIR="$IMAGES_BACKUP_ROOT/$TIMESTAMP"

# Read by BackupController (Java) to show image-backup status in the Admin
# Backup panel - see ImageBackupStatusResponse's own javadoc for the shape
# this JSON must match. $1: human-readable message (JSON-escaped by hand,
# deliberately simple - no embedded quotes/backslashes in any message this
# script ever produces).
write_status() {
	success="$1"
	size_bytes="$2"
	message="$3"
	mkdir -p "$IMAGES_BACKUP_ROOT" 2>/dev/null || true
	printf '{"timestamp":"%s","success":%s,"sizeBytes":%s,"message":"%s"}\n' \
		"$TIMESTAMP" "$success" "$size_bytes" "$message" > "$STATUS_FILE" 2>/dev/null || true
}

fail() {
	# A partial snapshot from a failed rsync (found live testing this
	# script - a genuinely full destination mid-copy) is worse than none:
	# it wastes space, was never counted as a real backup, and would sit
	# there un-pruned by retention (it's not "old" yet) until the next
	# --link-dest run treats it as the reference snapshot by accident. A
	# no-op if $SNAPSHOT_DIR was never created (e.g. the storage/backup
	# root checks above failed before rsync ever ran).
	rm -rf "$SNAPSHOT_DIR" 2>/dev/null || true
	write_status false 0 "$1"
	echo "backup-images.sh: FAILED - $1" >&2
	exit 1
}

if [ ! -d "$STORAGE_ROOT" ]; then
	fail "storage root $STORAGE_ROOT does not exist"
fi

if [ "$REQUIRE_MOUNT" = "true" ]; then
	if [ ! -d "$BACKUP_ROOT" ]; then
		fail "backup root $BACKUP_ROOT does not exist - is the NAS mounted?"
	fi
	if ! mountpoint -q "$BACKUP_ROOT"; then
		fail "backup root $BACKUP_ROOT is not a mount point - refusing to back up onto local disk silently (set MPD_BACKUP_REQUIRE_MOUNT=false to override deliberately)"
	fi
fi

if [ -n "$MARKER_FILE" ] && [ ! -f "$BACKUP_ROOT/$MARKER_FILE" ]; then
	fail "backup root $BACKUP_ROOT has no marker file $MARKER_FILE - is the backup disk/NAS mounted? (create that file once on the real backup target)"
fi

mkdir -p "$IMAGES_BACKUP_ROOT"

# Most recent existing snapshot (if any), for --link-dest: rsync hardlinks
# every file that's unchanged since that snapshot instead of copying it
# again - the standard "rsync snapshot" pattern, essential here given a
# photo library that's mostly append-only and can run into the hundreds of
# GB (see Migration Procedure.md's own disk-capacity figures).
LATEST_SNAPSHOT="$(find "$IMAGES_BACKUP_ROOT" -maxdepth 1 -mindepth 1 -type d -name '????-??-??T??-??-??Z' 2>/dev/null | sort | tail -n1 || true)"

RSYNC_OPTS="-a --delete"
if [ -n "$LATEST_SNAPSHOT" ]; then
	RSYNC_OPTS="$RSYNC_OPTS --link-dest=$LATEST_SNAPSHOT"
fi

# shellcheck disable=SC2086 # RSYNC_OPTS is intentionally word-split (a
# small, controlled set of flags this script itself built above).
if ! rsync $RSYNC_OPTS \
	--exclude='.thumbnails/' \
	--exclude='.webimg/' \
	"$STORAGE_ROOT/" "$SNAPSHOT_DIR/"; then
	fail "rsync failed (see stderr above)"
fi

# Real bug found live (23/09/2026, against the real production library -
# never reproduced by earlier testing, which only ever used a small,
# freshly-created test fixture as $STORAGE_ROOT): `rsync -a` (which
# implies `-t`, preserve modification times) sets *this freshly-created
# top-level snapshot directory's own* mtime to match $STORAGE_ROOT's own
# mtime - not "now". A directory's mtime only changes when an entry is
# added/removed *directly inside it*, so $STORAGE_ROOT's own mtime
# reflects "when was a year/ folder last added there", not the moment
# this rsync ran - months or years old for a long-lived library. The
# retention step below used to assume otherwise ("the top-level snapshot
# directory is freshly created every run" => fresh mtime); that
# assumption was simply wrong, and retention deleted the snapshot this
# very run had just finished writing, moments after writing it -
# write_status below still correctly reported success (the rsync itself
# really did succeed, measured before the deletion), which is exactly why
# status.json could survive announcing a backup that no longer existed.
# Forced to "now" explicitly here, independent of whatever rsync decided.
touch "$SNAPSHOT_DIR"

# `du -sk` (kilobytes) is portable across GNU and BSD/macOS du - unlike
# `-b`, which only GNU supports - matters for testing this script on the
# maintainer's own dev Mac, not just its real Debian/Ubuntu target.
SIZE_KB="$(du -sk "$SNAPSHOT_DIR" 2>/dev/null | cut -f1 || echo 0)"
SIZE_BYTES=$((SIZE_KB * 1024))

# Retention: prune snapshot directories older than RETENTION_DAYS - by
# directory name/mtime, now that the `touch` above makes that mtime
# trustworthy again. `! -name "$TIMESTAMP"` is a second, independent
# guard on top - this run's own snapshot is never a retention candidate
# no matter what its mtime says, the same belt-and-suspenders discipline
# DatabaseBackupService.pruneOldSnapshots already uses in Java (it
# excludes the just-created timestamp by name, not by trusting mtime
# alone either).
find "$IMAGES_BACKUP_ROOT" -maxdepth 1 -mindepth 1 -type d -name '????-??-??T??-??-??Z' ! -name "$TIMESTAMP" -mtime "+$RETENTION_DAYS" -exec rm -rf {} \; 2>/dev/null || true

write_status true "$SIZE_BYTES" "ok"
echo "backup-images.sh: snapshot $SNAPSHOT_DIR ($SIZE_BYTES bytes) - ok"
