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

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Session-cookie auth (18/09/2026, replaces HTTP Basic - explicit ask: "Time
 * to fix the basic authentication weakness and use session cookies"). The
 * real weakness being fixed: {@code AuthContext.jsx} used to keep the raw
 * username/password in {@code sessionStorage} and re-send them as a Basic
 * header on *every* API call - any future XSS would leak the literal
 * password, not just a session. An {@code HttpOnly} session cookie removes
 * the password from JS-reachable memory entirely after login.
 *
 * <p><b>Backed by the embedded Tomcat's own {@code HttpSession}</b> -
 * Spring Security's default ({@code HttpSessionSecurityContextRepository}),
 * not a custom stateless/JWT scheme. Deliberate, not a default left
 * unexamined: this app is already explicitly single-instance (HSQLDB
 * file-mode precludes clustering - Design.md decision #4), so the one real downside of
 * an in-memory session (doesn't survive multiple JVM instances, or a
 * restart of this one) isn't a real constraint here - the same "everyone
 * re-authenticates, a rare event" trade-off this project already accepted
 * for the video/share signed-token secrets' own random-per-restart-key
 * fallback. A custom token scheme would be solving a scaling problem this
 * app doesn't have, for real extra code to get right and maintain.
 *
 * <p><b>Login/logout have no dedicated {@code @Controller}</b> -
 * {@code formLogin()}'s built-in {@code UsernamePasswordAuthenticationFilter}
 * already does exactly this (extract {@code username}/{@code password}
 * form parameters, authenticate via the same {@code AuthenticationManager}/
 * {@code DomainUserDetailsService}/bcrypt {@code PasswordEncoder} HTTP Basic
 * already used), persisting the resulting {@code Authentication} into the
 * session automatically; the success/failure handlers just respond with a
 * bare status instead of the default redirect-to-a-page behavior, since
 * this is a JSON API, not server-rendered pages. The frontend still calls
 * {@code GET /api/me} right after a successful login for the actual
 * profile/role payload - already the established "who am I" endpoint,
 * not duplicated here. {@code logout()} similarly reuses Spring Security's
 * built-in handler (invalidates the session, clears the cookie) with a
 * plain-200 success handler.
 *
 * <p><b>CSRF is back on</b> (was {@code .disable()}'d under Basic auth,
 * correctly - a forged cross-site request can't attach a custom
 * {@code Authorization} header, so there was nothing to protect against
 * that a cookie doesn't now reintroduce: a cookie *is* sent automatically
 * by the browser on a cross-site request). The classic SPA pattern from
 * Spring's own reference docs: {@code CookieCsrfTokenRepository
 * .withHttpOnlyFalse()} exposes the token to JS via an {@code XSRF-TOKEN}
 * cookie (frontend echoes it back as the {@code X-XSRF-TOKEN} header on
 * every mutating request - {@code client.js}), plus a plain (non-BREACH-
 * masked) {@link CsrfTokenRequestAttributeHandler} - Spring Security 6's
 * *default* request handler XOR-masks the token per request
 * ({@code XorCsrfTokenRequestAttributeHandler}), which breaks the simple
 * "read the cookie, echo it back verbatim" SPA pattern this project uses;
 * confirmed empirically (not assumed) via a real end-to-end integration
 * test before settling on this override. {@link CsrfCookieFilter} below
 * forces the deferred token to actually resolve on every request - without
 * it, the very first response (e.g. the login page's own preflight) can
 * leave the cookie unset until some other code happens to read
 * {@link CsrfToken}, which nothing else in this app ever does on its own.
 *
 * <p>The video-stream and public share-link endpoints are entirely
 * unaffected by any of this - both already bypass Spring Security's normal
 * auth path with their own short-lived signed tokens in the URL
 * ({@code VideoStreamTokenService}/{@code ShareTokenService}), precisely
 * because neither a plain {@code <video src>} nor an anonymous visitor can
 * carry a cookie or header either.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

	@Bean
	public PasswordEncoder passwordEncoder() {
		// Same algorithm as the legacy fix's PasswordHasher (Design.md
		// §16.8.1) - both are plain bcrypt, so hashes are compatible if this
		// backend ever reads rows migrated from the legacy database.
		return new BCryptPasswordEncoder();
	}

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		http
				.authorizeHttpRequests(auth -> auth
						// The one deliberate exception to session auth
						// everywhere (28/08/2026, video support) - a plain
						// <video src> can't carry a cookie set for a
						// cross-context request the way XHR/fetch can, so
						// this endpoint validates its own short-lived signed
						// token instead (VideoStreamTokenService/
						// VideoController) rather than going through Spring
						// Security's normal auth here.
						.requestMatchers(HttpMethod.GET, "/api/videos/*/stream").permitAll()
						// External share links (02/09/2026, explicit ask) - same
						// reasoning as the video stream exception above, but the
						// whole point this time is working for someone with no
						// account at all, not just a plain <video src>'s own
						// header limitation. ShareController validates the
						// query-param token by hand. The token-*issuing*
						// endpoints (/api/images/*/share-token,
						// /api/directories/*/share-token) are deliberately NOT
						// listed here - those stay behind normal session auth,
						// only an already-authenticated user can mint a new
						// share link in the first place.
						.requestMatchers(HttpMethod.GET, "/api/public/**").permitAll()
						// ShareLinkPreviewController (25/09/2026) - crawler-facing HTML
						// with real per-share og:image tags, at the exact public share
						// URL. Same "no account at all" reasoning as /api/public/**
						// above, and same token-validated-by-hand pattern - Apache is
						// expected to route only known link-preview-bot User-Agents
						// here (that controller's own javadoc), everyone else keeps
						// hitting the static SPA, but this still has to be reachable
						// without a session either way.
						.requestMatchers(HttpMethod.GET, "/share/image/*", "/share/sequence/*", "/share/search/*").permitAll()
						// The login endpoint itself must be reachable by
						// someone who isn't authenticated yet - formLogin()
						// normally permits this automatically, but listed
						// explicitly rather than relying on that alone.
						.requestMatchers(HttpMethod.POST, "/api/login").permitAll()
						.anyRequest().authenticated())
				.formLogin(form -> form
						.loginProcessingUrl("/api/login")
						// Plain 200/401, never Spring Security's own default
						// redirect-to-a-page behavior - this is a JSON API
						// with no server-rendered login page to redirect to.
						.successHandler((request, response, authentication) -> response.setStatus(HttpStatus.OK.value()))
						.failureHandler((request, response, exception) -> response.setStatus(HttpStatus.UNAUTHORIZED.value())))
				.logout(logout -> logout
						.logoutUrl("/api/logout")
						.logoutSuccessHandler((request, response, authentication) -> response.setStatus(HttpStatus.OK.value())))
				// formLogin()'s own default entry point for an unauthenticated
				// request is a 302 redirect to a login *page* - correct for a
				// server-rendered app, wrong for this JSON API (confirmed by a
				// real test before this override was added, not assumed to be
				// needed). A plain 401 lets the SPA's own fetch() calls detect
				// "not logged in" without following a redirect into an HTML
				// page it has nowhere to render.
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint((request, response, authException) -> response.sendError(HttpStatus.UNAUTHORIZED.value())))
				.csrf(csrf -> csrf
						.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
						.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
						// The login POST itself has to be exempt - there's no
						// prior authenticated session yet to have handed out a
						// meaningful CSRF token tied to, and (found live, not
						// assumed - a real 401 on a real login attempt) a CSRF
						// rejection on an *unauthenticated* request gets routed
						// through the authenticationEntryPoint above instead of
						// a 403, which otherwise made login itself
						// indistinguishable from "wrong password". Not a real
						// CSRF exposure: a forged login POST using attacker-
						// supplied credentials only ever logs the victim into
						// the *attacker's* account, gaining the attacker
						// nothing - the standard justification for exempting
						// login forms from CSRF. /api/logout stays protected -
						// by the time it's called there's already a real
						// session with a real CSRF token to require.
						.ignoringRequestMatchers("/api/login"))
				.addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class);
		return http.build();
	}

	/**
	 * Forces the deferred {@link CsrfToken} supplier to actually resolve on
	 * every request, so {@code CookieCsrfTokenRepository} always writes the
	 * {@code XSRF-TOKEN} cookie - without this, Spring Security's lazy
	 * token loading can leave it unset until *something* reads
	 * {@code CsrfToken} off the request, which nothing else here does on
	 * its own. Exactly the filter Spring's own reference docs recommend for
	 * this pattern, not an invention of this project.
	 */
	private static final class CsrfCookieFilter extends OncePerRequestFilter {
		@Override
		protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,
				jakarta.servlet.http.HttpServletResponse response, jakarta.servlet.FilterChain filterChain)
				throws jakarta.servlet.ServletException, java.io.IOException {
			CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
			if (csrfToken != null) {
				csrfToken.getToken();
			}
			filterChain.doFilter(request, response);
		}
	}
}
