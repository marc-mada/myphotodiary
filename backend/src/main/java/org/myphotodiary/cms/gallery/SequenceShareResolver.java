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

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a SEQUENCE share token gives access to - one place for ShareController
 * and ShareLinkPreviewController. Normally the live sequence's pictures; for
 * a sequence merged into another since the link was made (17/10/2026,
 * explicit ask), only the pictures that came from it (MergedSequence), not
 * the rest of the sequence it was merged into.
 */
@Component
public class SequenceShareResolver {

	/** A shared sequence as its link shows it: its comment and pictures (name order, videos included). */
	public record SharedSequence(Long id, String description, List<Image> images) {
	}

	private final DirectoryRepository directoryRepository;
	private final ImageRepository imageRepository;
	private final MergedSequenceRepository mergedSequenceRepository;

	public SequenceShareResolver(DirectoryRepository directoryRepository, ImageRepository imageRepository, MergedSequenceRepository mergedSequenceRepository) {
		this.directoryRepository = directoryRepository;
		this.imageRepository = imageRepository;
		this.mergedSequenceRepository = mergedSequenceRepository;
	}

	@Transactional(readOnly = true)
	public SharedSequence resolve(Long sequenceId) {
		Optional<Directory> directory = directoryRepository.findById(sequenceId);
		if (directory.isPresent()) {
			return new SharedSequence(sequenceId, directory.get().getDescription(), imageRepository.findSequenceInDisplayOrder(sequenceId));
		}
		MergedSequence merged = mergedSequenceRepository.findById(sequenceId).orElseThrow(() -> new DirectoryNotFoundException(sequenceId));
		List<Image> images = imageRepository.findAllById(merged.getImageIds()).stream()
				.sorted(ImageRepository.DISPLAY_ORDER)
				.toList();
		return new SharedSequence(sequenceId, merged.getDescription(), images);
	}

	/** Whether a SEQUENCE token for {@code sequenceId} opens this picture. */
	@Transactional(readOnly = true)
	public boolean grants(Long sequenceId, Image image) {
		if (sequenceId.equals(image.getDirectory().getId())) {
			return true;
		}
		return mergedSequenceRepository.findById(sequenceId).map(m -> m.getImageIds().contains(image.getId())).orElse(false);
	}
}
