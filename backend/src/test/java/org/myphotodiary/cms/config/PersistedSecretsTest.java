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

package org.myphotodiary.cms.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.myphotodiary.cms.gallery.ShareTokenService.ShareScope;
import org.myphotodiary.cms.gallery.ShareTokenService;

class PersistedSecretsTest {

	@Test
	void get_returnsNull_whenNoSecretsFileIsConfigured() {
		assertThat(new PersistedSecrets("").get("share-token-secret")).isNull();
	}

	@Test
	void get_generatesOnFirstUse_andANewInstanceReadsTheSameValueBack(@TempDir Path tempDir) throws Exception {
		Path file = tempDir.resolve("config").resolve("secrets.properties");

		String first = new PersistedSecrets(file.toString()).get("share-token-secret");
		String afterRestart = new PersistedSecrets(file.toString()).get("share-token-secret");

		assertThat(first).hasSize(64).matches("[0-9a-f]+");
		assertThat(afterRestart).isEqualTo(first);
		assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
	}

	@Test
	void get_keepsEachNameSeparate(@TempDir Path tempDir) {
		PersistedSecrets secrets = new PersistedSecrets(tempDir.resolve("secrets.properties").toString());

		String share = secrets.get("share-token-secret");
		String video = secrets.get("video-token-secret");

		assertThat(video).isNotEqualTo(share);
		assertThat(secrets.get("share-token-secret")).isEqualTo(share);
	}

	@Test
	void shareLink_survivesARestart_whenSecretsFileIsConfigured(@TempDir Path tempDir) {
		String file = tempDir.resolve("secrets.properties").toString();
		String token = new ShareTokenService("", new PersistedSecrets(file)).issueToken(ShareScope.IMAGE, 42L);

		ShareTokenService afterRestart = new ShareTokenService("", new PersistedSecrets(file));

		assertThat(afterRestart.validateToken(token).id()).isEqualTo(42L);
	}

	@Test
	void explicitSecret_winsAndNothingIsWritten(@TempDir Path tempDir) {
		Path file = tempDir.resolve("secrets.properties");

		new ShareTokenService("explicit-secret", new PersistedSecrets(file.toString()));

		assertThat(file).doesNotExist();
	}
}
