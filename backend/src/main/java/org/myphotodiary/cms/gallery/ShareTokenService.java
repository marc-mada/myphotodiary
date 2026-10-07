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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.myphotodiary.cms.config.PersistedSecrets;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Signed, time-limited tokens for external (unauthenticated) sharing of a
 * single image or a whole sequence (02/09/2026, explicit ask - the
 * image-link/sequence-link toolbar icons). Same HMAC-SHA256-over-a-colon
 * -joined-payload shape as {@link VideoStreamTokenService}, deliberately not
 * a JWT library here either, for the same reason - a token only ever needs
 * three claims (scope, target id, expiry), not a general claims framework.
 * Kept as a genuinely separate class/signing key from the video token
 * service rather than generalizing that one to cover this too: the two have
 * different lifetimes (4 hours vs. 30 days here) and different threat
 * surfaces (a video token only ever unlocks streaming for the same
 * already-authenticated session that requested it; a share token is
 * designed from the outset to leave this app's own auth boundary
 * entirely and be handed to someone with no account at all) - conflating
 * them would make a future change to one's own TTL/secret rotation policy
 * silently affect the other.
 *
 * No database row backs a share link (explicit constraint: no schema
 * change) - purely a stateless, self-verifying token. The direct
 * consequence, worth stating plainly rather than leaving implicit: a link
 * cannot be individually revoked before it expires. Deleting the underlying
 * image/directory does stop it working (the target no longer resolves), but
 * there is no "unshare this one link early" operation - the same trade-off
 * every stateless-token share-link scheme makes (S3 presigned URLs, etc.).
 *
 * Payload: {@code scope:id:expiryEpochSeconds}, where {@code scope} is
 * {@link ShareScope#IMAGE} or {@link ShareScope#SEQUENCE} and {@code id} is
 * the image id or the directory id respectively. Validating a token only
 * proves the token itself is genuine and unexpired - it does NOT prove the
 * requested resource still exists or that a given image actually belongs to
 * a given sequence; callers (ShareController) check that separately against
 * the real data, the same way VideoController double-checks a validated
 * video token's own imageId against the path variable rather than trusting
 * either alone.
 */
@Component
public class ShareTokenService {

	// 30 days (explicit ask, 02/09/2026) - long enough to still work if the
	// recipient opens it well after receiving it (unlike the video token's
	// much shorter 4-hour TTL, sized for one active viewing session, not
	// for sitting in someone's inbox).
	private static final Duration DEFAULT_TTL = Duration.ofDays(30);

	public enum ShareScope {
		IMAGE,
		SEQUENCE,
		/** A frozen search result (SharedSearch, 04/10/2026) - the id is the shared_search row's. */
		SEARCH,
	}

	public record ShareTokenClaims(ShareScope scope, Long id) {
	}

	private final SecretKeySpec key;
	private final Duration ttl;

	@Autowired
	public ShareTokenService(@Value("${app.share.token-secret:}") String configuredSecret, PersistedSecrets persistedSecrets) {
		this(configuredSecret == null || configuredSecret.isBlank() ? persistedSecrets.get("share-token-secret") : configuredSecret, DEFAULT_TTL);
	}

	/** Package-visible so tests can exercise expiry without waiting 30 days or reflecting into a private constant. */
	ShareTokenService(String configuredSecret, Duration ttl) {
		byte[] secretBytes;
		if (configuredSecret != null && !configuredSecret.isBlank()) {
			secretBytes = configuredSecret.getBytes(StandardCharsets.UTF_8);
		} else {
			secretBytes = new byte[32];
			new SecureRandom().nextBytes(secretBytes);
		}
		this.key = new SecretKeySpec(secretBytes, "HmacSHA256");
		this.ttl = ttl;
	}

	public String issueToken(ShareScope scope, Long id) {
		String payload = scope.name() + ":" + id + ":" + Instant.now().plus(ttl).getEpochSecond();
		return encode(payload) + "." + sign(payload);
	}

	/** @throws InvalidShareTokenException if the token is malformed, forged, or expired. */
	public ShareTokenClaims validateToken(String token) {
		int dot = token.indexOf('.');
		if (dot < 0) {
			throw new InvalidShareTokenException("Malformed share token");
		}
		String encodedPayload = token.substring(0, dot);
		String signature = token.substring(dot + 1);
		String payload;
		try {
			payload = decode(encodedPayload);
		} catch (IllegalArgumentException e) {
			throw new InvalidShareTokenException("Malformed share token");
		}
		// Constant-time comparison (MessageDigest.isEqual), same reasoning
		// as VideoStreamTokenService - avoids a timing side-channel on
		// secret comparison.
		if (!MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
			throw new InvalidShareTokenException("Invalid share token signature");
		}
		String[] parts = payload.split(":", 3);
		if (parts.length != 3) {
			throw new InvalidShareTokenException("Malformed share token");
		}
		ShareScope scope;
		Long id;
		long expiry;
		try {
			scope = ShareScope.valueOf(parts[0]);
			id = Long.parseLong(parts[1]);
			expiry = Long.parseLong(parts[2]);
		} catch (IllegalArgumentException e) {
			throw new InvalidShareTokenException("Malformed share token");
		}
		if (Instant.now().getEpochSecond() > expiry) {
			throw new InvalidShareTokenException("Share link has expired");
		}
		return new ShareTokenClaims(scope, id);
	}

	private String sign(String payload) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(key);
			return encode(new String(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)), StandardCharsets.ISO_8859_1));
		} catch (java.security.GeneralSecurityException e) {
			throw new IllegalStateException("Could not sign share token", e);
		}
	}

	private static String encode(String value) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.ISO_8859_1));
	}

	private static String decode(String value) {
		return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.ISO_8859_1);
	}
}
