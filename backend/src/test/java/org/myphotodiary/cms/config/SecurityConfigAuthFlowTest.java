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

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.myphotodiary.cms.user.UserService;
import org.myphotodiary.cms.user.dto.CreateUserRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Real end-to-end HTTP verification of the session-cookie login/logout/CSRF
 * flow (18/09/2026, replacing HTTP Basic - see {@code SecurityConfig}'s own
 * javadoc for the full rationale). Deliberately {@code WebEnvironment
 * .RANDOM_PORT} + a real {@link TestRestTemplate} rather than {@code
 * @WithMockUser}/direct method calls (the pattern the rest of this test
 * suite uses) - login/logout/CSRF are filter-chain behaviors that don't
 * exist at all when a controller method is called directly, bypassing the
 * servlet filter chain entirely. Cookies are captured from one response's
 * {@code Set-Cookie} and threaded into the next request's {@code Cookie}
 * header by hand, not via any automatic cookie-jar magic - deliberate: it
 * proves exactly what's actually happening at the HTTP level, the same
 * thing this project's own "verify, don't assume" discipline already
 * applies to EXIF/rsync/mount-guard behavior elsewhere.
 *
 * <p>Two real things were found and fixed by writing this test, not
 * assumed correct from documentation alone:
 * <ol>
 * <li>{@code formLogin()}'s default entry point for an unauthenticated
 * request is a 302 redirect to a login page, not a plain 401 - wrong for
 * this JSON API, fixed with an explicit {@code exceptionHandling(...)}
 * override in {@code SecurityConfig}.</li>
 * <li>Spring Security 6's default CSRF request handler ({@code
 * XorCsrfTokenRequestAttributeHandler}) BREACH-masks the token value it
 * hands out, which breaks the simple "read the {@code XSRF-TOKEN} cookie,
 * echo it back verbatim as a header" SPA pattern - fixed by explicitly
 * configuring the plain {@code CsrfTokenRequestAttributeHandler} instead.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class SecurityConfigAuthFlowTest {

	@LocalServerPort
	private int port;

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private UserService userService;

	private String baseUrl() {
		return "http://localhost:" + port;
	}

	private String createTestUser() {
		String userName = "auth-flow-test-" + System.nanoTime();
		userService.createUser(new CreateUserRequest(userName, "Auth Flow Test", "pw123456", null, null));
		return userName;
	}

	/** Every {@code Set-Cookie} header on a response, parsed down to just {@code name=value} (dropping Path/HttpOnly/etc. attributes) - real cookie parsing, not a hand-picked substring. */
	private static MultiValueMap<String, String> parseCookies(ResponseEntity<?> response, MultiValueMap<String, String> existing) {
		MultiValueMap<String, String> cookies = new LinkedMultiValueMap<>(existing);
		List<String> setCookieHeaders = response.getHeaders().get(HttpHeaders.SET_COOKIE);
		if (setCookieHeaders != null) {
			for (String header : setCookieHeaders) {
				Matcher m = Pattern.compile("^([^=]+)=([^;]*)").matcher(header);
				if (m.find()) {
					cookies.put(m.group(1), List.of(m.group(2)));
				}
			}
		}
		return cookies;
	}

	private static String cookieHeader(MultiValueMap<String, String> cookies) {
		StringBuilder sb = new StringBuilder();
		cookies.forEach((name, values) -> {
			if (sb.length() > 0) sb.append("; ");
			sb.append(name).append('=').append(values.get(0));
		});
		return sb.toString();
	}

	@Test
	void unauthenticatedRequest_getsPlain401_notARedirectToALoginPage() {
		ResponseEntity<String> response = restTemplate.getForEntity(baseUrl() + "/api/me", String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void everyKindOfShareLink_isReachableWithoutSigningIn() {
		// Found live (04/10/2026): /share/search/* was missing from the
		// permitAll list, so link previews of shared search results got a 401
		// - the controller-level tests call the controller directly and never
		// go through the security filter. A bogus token must reach the
		// controller and be refused there (403), never stopped at sign-in (401).
		for (String path : new String[] { "/share/image/1", "/share/sequence/1", "/share/search/1",
				"/api/public/images/1", "/api/public/sequences/1", "/api/public/search-shares/1" }) {
			ResponseEntity<String> response = restTemplate.getForEntity(baseUrl() + path + "?token=bogus", String.class);
			assertThat(response.getStatusCode().value()).as(path).isNotEqualTo(401);
		}
	}

	@Test
	void login_setsSessionCookie_thenSubsequentRequestWorksWithNoCredentialsAtAll() {
		String userName = createTestUser();

		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("username", userName);
		form.add("password", "pw123456");
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
		ResponseEntity<String> loginResponse = restTemplate.postForEntity(baseUrl() + "/api/login", new HttpEntity<>(form, headers), String.class);

		assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		MultiValueMap<String, String> cookies = parseCookies(loginResponse, new LinkedMultiValueMap<>());
		assertThat(cookies.toSingleValueMap()).containsKey("JSESSIONID");

		// The real proof: /api/me succeeds carrying ONLY the session cookie -
		// no Authorization header, no credentials anywhere in this request.
		HttpHeaders meHeaders = new HttpHeaders();
		meHeaders.add(HttpHeaders.COOKIE, cookieHeader(cookies));
		ResponseEntity<String> meResponse = restTemplate.exchange(baseUrl() + "/api/me", HttpMethod.GET, new HttpEntity<>(meHeaders), String.class);

		assertThat(meResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(meResponse.getBody()).contains(userName);
	}

	@Test
	void login_withWrongPassword_returns401_noSessionCookieIssued() {
		String userName = createTestUser();

		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("username", userName);
		form.add("password", "definitely-not-the-real-password");
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
		ResponseEntity<String> response = restTemplate.postForEntity(baseUrl() + "/api/login", new HttpEntity<>(form, headers), String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		List<String> setCookie = response.getHeaders().get(HttpHeaders.SET_COOKIE);
		boolean issuedSessionCookie = setCookie != null && setCookie.stream().anyMatch(c -> c.startsWith("JSESSIONID"));
		assertThat(issuedSessionCookie).isFalse();
	}

	@Test
	void mutatingRequest_withoutCsrfHeader_isRejected_withCorrectHeaderSucceeds() {
		String userName = createTestUser();

		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("username", userName);
		form.add("password", "pw123456");
		HttpHeaders loginHeaders = new HttpHeaders();
		loginHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
		ResponseEntity<String> loginResponse = restTemplate.postForEntity(baseUrl() + "/api/login", new HttpEntity<>(form, loginHeaders), String.class);
		MultiValueMap<String, String> cookies = parseCookies(loginResponse, new LinkedMultiValueMap<>());

		// A GET first, purely to let the CsrfCookieFilter actually render the
		// XSRF-TOKEN cookie (CookieCsrfTokenRepository's deferred loading) -
		// the login response above is handled by formLogin's own filter
		// before CsrfCookieFilter's position in the chain, so it doesn't
		// necessarily carry that cookie itself.
		HttpHeaders getHeaders = new HttpHeaders();
		getHeaders.add(HttpHeaders.COOKIE, cookieHeader(cookies));
		ResponseEntity<String> meResponse = restTemplate.exchange(baseUrl() + "/api/me", HttpMethod.GET, new HttpEntity<>(getHeaders), String.class);
		cookies = parseCookies(meResponse, cookies);
		assertThat(cookies.toSingleValueMap()).containsKey("XSRF-TOKEN");
		String csrfToken = cookies.getFirst("XSRF-TOKEN");

		// Without the CSRF header: rejected, even with a perfectly valid session.
		HttpHeaders noCsrfHeaders = new HttpHeaders();
		noCsrfHeaders.setContentType(MediaType.APPLICATION_JSON);
		noCsrfHeaders.add(HttpHeaders.COOKIE, cookieHeader(cookies));
		ResponseEntity<String> rejected = restTemplate.exchange(
				baseUrl() + "/api/me", HttpMethod.PATCH, new HttpEntity<>("{\"slideShowInterval\":7}", noCsrfHeaders), String.class);
		assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

		// With the CSRF header echoing the cookie value verbatim: succeeds -
		// the real proof the plain (non-BREACH-masked) request handler is
		// actually wired in, not just declared.
		HttpHeaders withCsrfHeaders = new HttpHeaders();
		withCsrfHeaders.setContentType(MediaType.APPLICATION_JSON);
		withCsrfHeaders.add(HttpHeaders.COOKIE, cookieHeader(cookies));
		withCsrfHeaders.add("X-XSRF-TOKEN", csrfToken);
		ResponseEntity<String> accepted = restTemplate.exchange(
				baseUrl() + "/api/me", HttpMethod.PATCH, new HttpEntity<>("{\"slideShowInterval\":7}", withCsrfHeaders), String.class);
		assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(accepted.getBody()).contains("\"slideShowInterval\":7");
	}

	@Test
	void logout_invalidatesTheSession_soTheSameCookieNoLongerWorks() {
		String userName = createTestUser();

		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("username", userName);
		form.add("password", "pw123456");
		HttpHeaders loginHeaders = new HttpHeaders();
		loginHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
		ResponseEntity<String> loginResponse = restTemplate.postForEntity(baseUrl() + "/api/login", new HttpEntity<>(form, loginHeaders), String.class);
		MultiValueMap<String, String> cookies = parseCookies(loginResponse, new LinkedMultiValueMap<>());

		HttpHeaders meHeaders = new HttpHeaders();
		meHeaders.add(HttpHeaders.COOKIE, cookieHeader(cookies));
		ResponseEntity<String> meResponse = restTemplate.exchange(baseUrl() + "/api/me", HttpMethod.GET, new HttpEntity<>(meHeaders), String.class);
		assertThat(meResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		// The GET above is also what renders the XSRF-TOKEN cookie
		// (CsrfCookieFilter) - logout stays CSRF-protected on purpose (see
		// SecurityConfig's own comment on why login, specifically, is the
		// one exemption), so it needs this header too, same as any other
		// mutating request.
		cookies = parseCookies(meResponse, cookies);
		String csrfToken = cookies.getFirst("XSRF-TOKEN");

		HttpHeaders logoutHeaders = new HttpHeaders();
		logoutHeaders.add(HttpHeaders.COOKIE, cookieHeader(cookies));
		logoutHeaders.add("X-XSRF-TOKEN", csrfToken);
		ResponseEntity<String> logoutResponse = restTemplate.exchange(baseUrl() + "/api/logout", HttpMethod.POST, new HttpEntity<>(logoutHeaders), String.class);
		assertThat(logoutResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

		// Same cookie, sent again, after logout - must be rejected now.
		ResponseEntity<String> afterLogout = restTemplate.exchange(baseUrl() + "/api/me", HttpMethod.GET, new HttpEntity<>(meHeaders), String.class);
		assertThat(afterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}
}
