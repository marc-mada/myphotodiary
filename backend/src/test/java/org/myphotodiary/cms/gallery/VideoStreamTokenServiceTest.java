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

package org.myphotodiary.cms.gallery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.myphotodiary.cms.config.PersistedSecrets;

/**
 * Plain unit test, no Spring context needed - VideoStreamTokenService has no
 * dependencies beyond the configured (or randomly generated) secret.
 */
class VideoStreamTokenServiceTest {

	private final VideoStreamTokenService service = new VideoStreamTokenService("test-secret-not-for-real-use", new PersistedSecrets(""));

	@Test
	void issuedToken_validatesBackToTheSameImageId() {
		String token = service.issueToken(42L);

		assertThat(service.validateAndGetImageId(token)).isEqualTo(42L);
	}

	@Test
	void tamperedSignature_rejected() {
		String token = service.issueToken(42L);
		String tampered = token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A");

		assertThatThrownBy(() -> service.validateAndGetImageId(tampered)).isInstanceOf(InvalidVideoTokenException.class);
	}

	@Test
	void tamperedPayload_rejected_evenReusingTheOriginalSignature() {
		String token = service.issueToken(42L);
		String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString("999:9999999999".getBytes());
		String forged = forgedPayload + token.substring(token.indexOf('.'));

		assertThatThrownBy(() -> service.validateAndGetImageId(forged)).isInstanceOf(InvalidVideoTokenException.class);
	}

	@Test
	void malformedToken_rejected() {
		assertThatThrownBy(() -> service.validateAndGetImageId("not-a-real-token")).isInstanceOf(InvalidVideoTokenException.class);
	}

	@Test
	void expiredToken_rejected() {
		// A separate instance with a TTL already in the past (package-visible
		// constructor, test-only) - exercises the expiry check specifically,
		// same secret so the signature itself is still valid.
		VideoStreamTokenService alreadyExpired = new VideoStreamTokenService("test-secret-not-for-real-use", Duration.ofSeconds(-3600));
		String token = alreadyExpired.issueToken(42L);

		assertThatThrownBy(() -> service.validateAndGetImageId(token)).isInstanceOf(InvalidVideoTokenException.class).hasMessageContaining("expired");
	}
}
