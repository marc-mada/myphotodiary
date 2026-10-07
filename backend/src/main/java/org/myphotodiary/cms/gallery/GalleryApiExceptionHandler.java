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

import java.io.UncheckedIOException;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GalleryApiExceptionHandler {

	@ExceptionHandler({ DirectoryNotFoundException.class, ImageNotFoundException.class, AttributeNotFoundException.class, GroupNotFoundException.class })
	public ResponseEntity<?> notFound(RuntimeException ex) {
		return problem(HttpStatus.NOT_FOUND, ex.getMessage());
	}

	@ExceptionHandler({ DirectoryAlreadyExistsException.class, ImageAlreadyExistsException.class, AttributeAlreadyExistsException.class })
	public ResponseEntity<?> conflict(RuntimeException ex) {
		return problem(HttpStatus.CONFLICT, ex.getMessage());
	}

	@ExceptionHandler({ IllegalArgumentException.class, IllegalStateException.class })
	public ResponseEntity<?> badRequest(RuntimeException ex) {
		return problem(HttpStatus.BAD_REQUEST, ex.getMessage());
	}

	/** A bad/forged/expired video stream token (VideoStreamTokenService) - 403, not 401: there's no login prompt that would help here, the client just needs a fresh token (VideoController's own /token endpoint). */
	@ExceptionHandler(InvalidVideoTokenException.class)
	public ResponseEntity<?> invalidVideoToken(InvalidVideoTokenException ex) {
		return problem(HttpStatus.FORBIDDEN, ex.getMessage());
	}

	/** Same reasoning as invalidVideoToken above, applied to external share links (ShareTokenService/ShareController) - 403, not 401: there's no login prompt for someone with no account at all. */
	@ExceptionHandler(InvalidShareTokenException.class)
	public ResponseEntity<?> invalidShareToken(InvalidShareTokenException ex) {
		return problem(HttpStatus.FORBIDDEN, ex.getMessage());
	}

	@ExceptionHandler(UncheckedIOException.class)
	public ResponseEntity<?> ioFailure(UncheckedIOException ex) {
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Storage error: " + ex.getMessage());
	}

	private ResponseEntity<?> problem(HttpStatus status, String message) {
		return ResponseEntity.status(status).body(Map.of("error", message));
	}
}
