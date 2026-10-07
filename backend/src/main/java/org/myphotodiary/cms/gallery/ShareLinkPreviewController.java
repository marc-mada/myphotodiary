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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.myphotodiary.cms.gallery.ShareTokenService.ShareScope;
import org.myphotodiary.cms.gallery.ShareTokenService.ShareTokenClaims;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Serves a minimal, crawler-facing HTML document at the exact public share
 * URL (25/09/2026, explicit ask - "the icon presented with the link in
 * WhatsApp is not so nice, could we present the first picture or at least
 * its thumbnail?") - real Open Graph {@code og:image}/{@code og:title}/
 * {@code og:description} tags, so WhatsApp/iMessage/Telegram/etc. show the
 * actual shared photo instead of a generic app icon.
 *
 * <p><b>Why this exists as its own thing, not a tweak to the SPA's static
 * {@code index.html}</b>: a link-preview crawler fetches the raw HTML of
 * the URL it was given and reads {@code <meta>} tags straight out of it -
 * it never executes JavaScript, so it can never see the React app's own
 * client-side-rendered content, only whatever is already sitting in the
 * initial response. The static {@code index.html} Apache serves for every
 * {@code /share/*} path today is the exact same file regardless of which
 * image/sequence the link is actually for, so every crawler sees the same
 * generic tags - that's the actual root cause of the reported icon.
 *
 * <p><b>Why this is scoped to crawlers only, at the Apache layer, not
 * something every visitor hits</b>: a real human tapping the link already
 * gets the fully interactive {@code ShareScreen}/{@code MobileShareScreen}
 * experience today, unaffected by any of this - there was never a
 * complaint about that. Reusing this same simple, non-interactive HTML for
 * real visitors too would mean either duplicating the whole React viewer
 * server-side or bouncing a real visitor through an extra redirect for no
 * benefit. The web server is expected to route only known link-preview-bot
 * User-Agents to this controller (Installation Guide §4.2's Caddyfile, and
 * the Docker image's own) - everyone else keeps hitting the
 * static SPA exactly as before. A `<script>` redirect below is a cheap
 * defensive fallback for the unlikely case a real browser ever reaches
 * this controller anyway (misconfigured routing, a manual link click,
 * etc.) - crawlers never execute it, since they don't run JavaScript
 * either.
 *
 * <p>Deliberately generic {@code og:title}/{@code og:description} text
 * (not this image's/sequence's own {@code description} field) - the actual
 * photo shown *is* the point of a share link and was already an explicit,
 * deliberate disclosure by whoever generated it, but a caption typed
 * expecting only signed-in family members to ever see it is a different
 * kind of exposure once it's sitting in a plain-text tag that WhatsApp's/
 * Facebook's own infrastructure fetches and caches on their own servers -
 * not something to opt this project's own users into without being asked.
 */
@RestController
public class ShareLinkPreviewController {

	private final GalleryService galleryService;
	private final DirectoryRepository directoryRepository;
	private final ImageRepository imageRepository;
	private final ShareTokenService tokenService;
	private final SharedSearchService sharedSearchService;
	private final String configuredPublicBaseUrl;

	public ShareLinkPreviewController(
			GalleryService galleryService,
			DirectoryRepository directoryRepository,
			ImageRepository imageRepository,
			ShareTokenService tokenService,
			SharedSearchService sharedSearchService,
			@Value("${app.public-base-url:}") String configuredPublicBaseUrl) {
		this.galleryService = galleryService;
		this.directoryRepository = directoryRepository;
		this.imageRepository = imageRepository;
		this.tokenService = tokenService;
		this.sharedSearchService = sharedSearchService;
		this.configuredPublicBaseUrl = configuredPublicBaseUrl;
	}

	@GetMapping(value = "/share/image/{imageId}", produces = MediaType.TEXT_HTML_VALUE)
	@ResponseBody
	public String imagePreview(@PathVariable Long imageId, @RequestParam String token, HttpServletRequest request) {
		ShareTokenClaims claims = tokenService.validateToken(token);
		Image image = galleryService.requireImage(imageId);
		boolean allowed = switch (claims.scope()) {
			case IMAGE -> claims.id().equals(image.getId());
			case SEQUENCE -> claims.id().equals(image.getDirectory().getId());
			case SEARCH -> sharedSearchService.contains(claims.id(), image.getId());
		};
		if (!allowed) {
			throw new InvalidShareTokenException("Share token does not grant access to this image");
		}
		String base = publicBaseUrl(request);
		String encodedToken = URLEncoder.encode(token, StandardCharsets.UTF_8);
		String pageUrl = base + "/share/image/" + image.getId() + "?token=" + encodedToken;
		// A video has no "web" derivative to point og:image at (ShareController's
		// own javadoc on why video is out of scope here) - the generic app icon
		// stays, same as an image-less sequence below.
		String ogImageUrl = image.getMediaType() == org.myphotodiary.cms.gallery.MediaType.VIDEO
				? null
				: base + "/api/public/images/" + image.getId() + "/web?token=" + encodedToken;
		return renderHtml(pageUrl, ogImageUrl);
	}

	@GetMapping(value = "/share/sequence/{directoryId}", produces = MediaType.TEXT_HTML_VALUE)
	@ResponseBody
	public String sequencePreview(@PathVariable Long directoryId, @RequestParam String token, HttpServletRequest request) {
		ShareTokenClaims claims = tokenService.validateToken(token);
		if (claims.scope() != ShareScope.SEQUENCE || !claims.id().equals(directoryId)) {
			throw new InvalidShareTokenException("Share token does not match the requested sequence");
		}
		Directory directory = directoryRepository.findById(directoryId).orElseThrow(() -> new DirectoryNotFoundException(directoryId));
		String base = publicBaseUrl(request);
		String encodedToken = URLEncoder.encode(token, StandardCharsets.UTF_8);
		String pageUrl = base + "/share/sequence/" + directory.getId() + "?token=" + encodedToken;
		// The first image in the sequence (explicit ask - "present the first
		// picture"), same ordering/video-exclusion as the sequence's own
		// filmstrip (ShareController.publicSequence) - null (no og:image tag
		// at all, generic icon) only for a sequence with no photos of its own
		// yet, or one that's video-only.
		List<Image> images = imageRepository.findByDirectory_IdOrderByNameAsc(directoryId);
		Optional<Image> firstImage = images.stream().filter(img -> img.getMediaType() != org.myphotodiary.cms.gallery.MediaType.VIDEO).findFirst();
		String ogImageUrl = firstImage.map(img -> base + "/api/public/images/" + img.getId() + "/web?token=" + encodedToken).orElse(null);
		return renderHtml(pageUrl, ogImageUrl);
	}

	/** A shared search result's preview card shows its first picture still available (04/10/2026). */
	@GetMapping(value = "/share/search/{sharedSearchId}", produces = MediaType.TEXT_HTML_VALUE)
	@ResponseBody
	public String searchPreview(@PathVariable Long sharedSearchId, @RequestParam String token, HttpServletRequest request) {
		ShareTokenClaims claims = tokenService.validateToken(token);
		if (claims.scope() != ShareScope.SEARCH || !claims.id().equals(sharedSearchId)) {
			throw new InvalidShareTokenException("Share token does not match the requested search result");
		}
		String base = publicBaseUrl(request);
		String encodedToken = URLEncoder.encode(token, StandardCharsets.UTF_8);
		String pageUrl = base + "/share/search/" + sharedSearchId + "?token=" + encodedToken;
		String ogImageUrl = sharedSearchService.availableImages(sharedSearchId).stream()
				.findFirst()
				.map(img -> base + "/api/public/images/" + img.getId() + "/web?token=" + encodedToken)
				.orElse(null);
		return renderHtml(pageUrl, ogImageUrl);
	}

	private String publicBaseUrl(HttpServletRequest request) {
		if (!configuredPublicBaseUrl.isBlank()) {
			return configuredPublicBaseUrl;
		}
		String scheme = request.getScheme();
		String host = request.getServerName();
		int port = request.getServerPort();
		boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
		return scheme + "://" + host + (defaultPort ? "" : ":" + port);
	}

	private String renderHtml(String pageUrl, String ogImageUrl) {
		StringBuilder html = new StringBuilder();
		html.append("<!doctype html><html><head><meta charset=\"utf-8\">");
		html.append("<meta property=\"og:site_name\" content=\"myPhotoDiary\">");
		html.append("<meta property=\"og:type\" content=\"website\">");
		html.append("<meta property=\"og:title\" content=\"myPhotoDiary\">");
		html.append("<meta property=\"og:description\" content=\"A photo shared from myPhotoDiary\">");
		html.append("<meta property=\"og:url\" content=\"").append(escape(pageUrl)).append("\">");
		if (ogImageUrl != null) {
			html.append("<meta property=\"og:image\" content=\"").append(escape(ogImageUrl)).append("\">");
			// summary_large_image, not the default small-thumbnail "summary"
			// card - Twitter/X's own reader also drives a handful of other
			// apps' preview rendering (e.g. Slack) that check for this tag.
			html.append("<meta name=\"twitter:card\" content=\"summary_large_image\">");
		}
		html.append("</head><body>");
		// Never reached by a real crawler (they don't execute JavaScript, which
		// is the whole reason this controller exists) - only a defensive
		// fallback in case a real browser ever lands here despite Apache's own
		// crawler-only routing (see class javadoc).
		html.append("<script>location.replace(").append(jsStringLiteral(pageUrl)).append(");</script>");
		html.append("</body></html>");
		return html.toString();
	}

	private static String escape(String value) {
		return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static String jsStringLiteral(String value) {
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}
}
