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

package org.myphotodiary.cms.backup;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.stereotype.Component;

/**
 * The safety check both {@link DatabaseBackupService} and (via the same
 * logic reproduced in shell for {@code backup-images.sh} - see that
 * script's own comment) the image-tree backup rely on: is the configured
 * backup root actually a distinct filesystem from its parent, i.e. really a
 * mount point (the NAS share this feature is meant for), rather than just a
 * plain directory on the same disk everything else already lives on?
 *
 * <p>Standard Unix technique (the same one the {@code mountpoint} command
 * uses): a directory's device/filesystem identity ({@link FileStore})
 * differs from its parent's exactly when something is mounted on it. An
 * empty directory sitting on local disk, waiting for a NAS mount that
 * hasn't happened yet, shares its parent's {@code FileStore} - this comes
 * back {@code false}, on purpose (see {@link BackupProperties}'s own doc
 * comment for why that matters here specifically).
 *
 * <p>Inside a container, that check proves nothing: every folder mounted
 * into it from the host is a distinct filesystem from the container's own
 * root, NAS or not. {@link #markerPresent} is the alternative the Docker
 * image relies on (06/10/2026): the operator places a file of a known name
 * once on the real backup target (the NAS share itself, not the empty
 * local folder it gets mounted on), so a missing mount shows up as a
 * missing marker. The same check is reproduced in {@code backup-images.sh}.
 */
@Component
public class BackupMountGuard {

	/**
	 * @return {@code true} if {@code path} exists and is a distinct
	 *         filesystem from its parent directory; {@code false} if it
	 *         doesn't exist yet, has no parent, or shares its parent's
	 *         filesystem (i.e. is not actually mounted).
	 */
	public boolean isMounted(Path path) {
		try {
			if (!Files.isDirectory(path)) {
				return false;
			}
			Path parent = path.toAbsolutePath().normalize().getParent();
			if (parent == null || !Files.isDirectory(parent)) {
				return false;
			}
			FileStore pathStore = Files.getFileStore(path);
			FileStore parentStore = Files.getFileStore(parent);
			return !pathStore.equals(parentStore);
		} catch (IOException e) {
			return false;
		}
	}

	/**
	 * @return {@code true} if {@code markerFile} exists as a regular file
	 *         directly in {@code root}.
	 */
	public boolean markerPresent(Path root, String markerFile) {
		return Files.isRegularFile(root.resolve(markerFile));
	}
}
