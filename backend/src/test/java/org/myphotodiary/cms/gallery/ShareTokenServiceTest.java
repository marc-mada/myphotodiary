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
import org.myphotodiary.cms.gallery.ShareTokenService.ShareScope;
import org.myphotodiary.cms.gallery.ShareTokenService.ShareTokenClaims;

/**
 * Plain unit test, no Spring context needed - same shape as
 * VideoStreamTokenServiceTest, this class's own model.
 */
class ShareTokenServiceTest {

	private final ShareTokenService service = new ShareTokenService("test-secret-not-for-real-use", new PersistedSecrets(""));

	@Test
	void issuedImageToken_validatesBackToTheSameScopeAndId() {
		String token = service.issueToken(ShareScope.IMAGE, 42L);

		assertThat(service.validateToken(token)).isEqualTo(new ShareTokenClaims(ShareScope.IMAGE, 42L));
	}

	@Test
	void issuedSequenceToken_validatesBackToTheSameScopeAndId() {
		String token = service.issueToken(ShareScope.SEQUENCE, 7L);

		assertThat(service.validateToken(token)).isEqualTo(new ShareTokenClaims(ShareScope.SEQUENCE, 7L));
	}

	@Test
	void tamperedSignature_rejected() {
		String token = service.issueToken(ShareScope.IMAGE, 42L);
		String tampered = token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A");

		assertThatThrownBy(() -> service.validateToken(tampered)).isInstanceOf(InvalidShareTokenException.class);
	}

	@Test
	void tamperedPayload_rejected_evenReusingTheOriginalSignature() {
		String token = service.issueToken(ShareScope.IMAGE, 42L);
		String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString("SEQUENCE:999:9999999999".getBytes());
		String forged = forgedPayload + token.substring(token.indexOf('.'));

		assertThatThrownBy(() -> service.validateToken(forged)).isInstanceOf(InvalidShareTokenException.class);
	}

	@Test
	void malformedToken_rejected() {
		assertThatThrownBy(() -> service.validateToken("not-a-real-token")).isInstanceOf(InvalidShareTokenException.class);
	}

	@Test
	void expiredToken_rejected() {
		// A separate instance with a TTL already in the past (package-visible
		// constructor, test-only) - exercises the expiry check specifically,
		// same secret so the signature itself is still valid.
		ShareTokenService alreadyExpired = new ShareTokenService("test-secret-not-for-real-use", Duration.ofSeconds(-3600));
		String token = alreadyExpired.issueToken(ShareScope.IMAGE, 42L);

		assertThatThrownBy(() -> service.validateToken(token)).isInstanceOf(InvalidShareTokenException.class).hasMessageContaining("expired");
	}

	@Test
	void imageScopedToken_doesNotValidateAsSequenceScope_callerMustCheckScopeExplicitly() {
		// ShareTokenService itself doesn't enforce "this token must be a
		// SEQUENCE token" - that's ShareController's own job (requireAccessibleImage/
		// publicSequence). This test documents the boundary: validateToken
		// only proves genuineness/freshness, the scope check is on the caller.
		String token = service.issueToken(ShareScope.IMAGE, 42L);

		ShareTokenClaims claims = service.validateToken(token);

		assertThat(claims.scope()).isEqualTo(ShareScope.IMAGE);
		assertThat(claims.scope()).isNotEqualTo(ShareScope.SEQUENCE);
	}
}
