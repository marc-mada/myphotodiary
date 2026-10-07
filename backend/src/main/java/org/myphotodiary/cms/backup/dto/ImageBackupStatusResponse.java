/*******************************************************************************
 * Copyright 2014-2026 Marc Lamberton
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package org.myphotodiary.cms.backup.dto;

/**
 * Reflects {@code backup-images.sh}'s own {@code status.json} - the image
 * tree is backed up by a systemd-timer-scheduled shell script, not the
 * backend itself (see that script's own comment for why), so this is
 * read-only visibility into something this Java process doesn't do, not a
 * trigger. {@code null} fields mean "the script has never run yet" (no
 * status file on disk at all), not "it ran and reported nothing".
 */
public record ImageBackupStatusResponse(String timestamp, Boolean success, Long sizeBytes, String message) {

	public static ImageBackupStatusResponse none() {
		return new ImageBackupStatusResponse(null, null, null, null);
	}
}
