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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Serves a stored video with HTTP Range support (206 Partial Content), so
 * the browser can start playing and seek without downloading it all. Shared
 * by the signed-in stream (VideoController) and, since 14/10/2026, the
 * public one behind a share link (ShareController).
 */
final class VideoRangeResponses {

	private VideoRangeResponses() {
	}

	static ResponseEntity<ResourceRegion> serve(Path path, HttpHeaders headers) {
		FileSystemResource resource = new FileSystemResource(path);
		long contentLength;
		try {
			contentLength = resource.contentLength();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		// Always video/mp4, never derived from the original file's own
		// extension (MediaTypeFactory.getMediaType, removed 03/09/2026) - a
		// real bug found live on iPad: a `.mov` upload (see ImportService's
		// own VIDEO_EXTENSIONS comment) was served as `video/quicktime`
		// instead, which Safari plays natively but Chrome/Firefox generally
		// don't, regardless of the actual codec inside. VideoFormatValidator
		// already guarantees every stored video is H.264(+AAC) - universally
		// playable as `video/mp4` - independent of whatever container
		// extension the original upload happened to arrive with, so the
		// original extension was never the right signal for this header to
		// begin with.
		MediaType contentType = MediaType.valueOf("video/mp4");

		List<HttpRange> ranges = headers.getRange();
		if (ranges.isEmpty()) {
			// No Range header yet - the browser's very first request for this
			// URL. Serve the whole thing but advertise Range support, so it
			// knows to send byte-range requests for subsequent seeks instead
			// of re-downloading from the start.
			ResourceRegion region = new ResourceRegion(resource, 0, contentLength);
			return ResponseEntity.ok().header("Accept-Ranges", "bytes").contentType(contentType).body(region);
		}
		ResourceRegion region = ranges.get(0).toResourceRegion(resource);
		return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).header("Accept-Ranges", "bytes").contentType(contentType).body(region);
	}
}
