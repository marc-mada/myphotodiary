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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.myphotodiary.cms.gallery.ShareTokenService.ShareScope;
import org.myphotodiary.cms.gallery.dto.SearchShareRequest;
import org.myphotodiary.cms.gallery.dto.SearchShareResponse;
import org.myphotodiary.cms.settings.AppSettingsService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shared search results (04/10/2026, explicit ask) - see SharedSearch and
 * V14 for the storage. Decisions behind it:
 * <ul>
 * <li><b>Frozen, not re-run:</b> the server re-runs the sharer's search
 * itself (same {@link GalleryService#searchMatches}, so the same criteria,
 * access rights and order - never a list of ids trusted from the browser)
 * and stores the resulting picture ids. The recipient sees exactly that,
 * minus any picture deleted since.</li>
 * <li><b>At most {@code maxShareSize} pictures</b> (app-wide setting,
 * default 50): above it, the first ones in search order are shared and the
 * response says the result was cut, so the sharer can be warned.</li>
 * <li><b>Videos left out</b>, as for sequence links (no public video
 * streaming).</li>
 * <li>Same 30-day lifetime as the other share links (also in the token).
 * Expired rows are cleaned up whenever a new result is shared.</li>
 * </ul>
 */
@Service
public class SharedSearchService {

	static final Duration TTL = Duration.ofDays(30);

	private final GalleryService galleryService;
	private final ImageRepository imageRepository;
	private final SharedSearchRepository sharedSearchRepository;
	private final ShareTokenService tokenService;
	private final AppSettingsService appSettingsService;

	public SharedSearchService(GalleryService galleryService, ImageRepository imageRepository, SharedSearchRepository sharedSearchRepository,
			ShareTokenService tokenService, AppSettingsService appSettingsService) {
		this.galleryService = galleryService;
		this.imageRepository = imageRepository;
		this.sharedSearchRepository = sharedSearchRepository;
		this.tokenService = tokenService;
		this.appSettingsService = appSettingsService;
	}

	@Transactional
	public SearchShareResponse share(Authentication authentication, SearchShareRequest request) {
		if (!GalleryService.hasSearchCriteria(request.attributeNames(), request.minRating(), request.fromDate(), request.toDate(), request.text())) {
			throw new IllegalArgumentException("A search result can only be shared once at least one search criterion is set");
		}
		List<Long> shareable = galleryService
				.searchMatches(authentication, request.attributeNames(), request.minRating(), request.fromDate(), request.toDate(), request.text())
				.stream()
				.filter(img -> img.getMediaType() != MediaType.VIDEO)
				.map(Image::getId)
				.toList();
		if (shareable.isEmpty()) {
			throw new IllegalArgumentException("This search has no picture to share (videos can't be shared)");
		}
		int max = appSettingsService.get().getMaxShareSize();
		List<Long> frozen = shareable.size() > max ? shareable.subList(0, max) : shareable;

		LocalDateTime now = LocalDateTime.now();
		sharedSearchRepository.deleteExpired(now);
		SharedSearch saved = sharedSearchRepository.save(new SharedSearch(now, now.plus(TTL), authentication.getName(), shareable.size(), frozen));
		String token = tokenService.issueToken(ShareScope.SEARCH, saved.getId());
		return new SearchShareResponse(saved.getId(), token, shareable.size(), frozen.size(), frozen.size() < shareable.size(), max);
	}

	/**
	 * The shared pictures still available, in their original order - deleted
	 * ones skipped (V14 cascade leaves a null at their position, or the id no
	 * longer resolves). Throws when the result doesn't exist or has expired.
	 */
	@Transactional(readOnly = true)
	public List<Image> availableImages(Long sharedSearchId) {
		SharedSearch shared = requireLive(sharedSearchId);
		List<Long> ids = shared.getImageIds().stream().filter(Objects::nonNull).toList();
		Map<Long, Image> byId = imageRepository.findAllById(ids).stream().collect(Collectors.toMap(Image::getId, Function.identity()));
		return ids.stream()
				.map(byId::get)
				.filter(Objects::nonNull)
				.filter(img -> img.getMediaType() != MediaType.VIDEO)
				.toList();
	}

	@Transactional(readOnly = true)
	public boolean contains(Long sharedSearchId, Long imageId) {
		return requireLive(sharedSearchId).getImageIds().contains(imageId);
	}

	private SharedSearch requireLive(Long sharedSearchId) {
		SharedSearch shared = sharedSearchRepository.findById(sharedSearchId)
				.orElseThrow(() -> new InvalidShareTokenException("This shared search result no longer exists"));
		if (shared.getExpiresAt().isBefore(LocalDateTime.now())) {
			throw new InvalidShareTokenException("Share link has expired");
		}
		return shared;
	}
}
