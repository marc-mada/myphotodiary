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

/**
 * Which file plays a video (27/10/2026, V19) - see
 * {@link VideoProcessingService}. A {@code null} on a video row means
 * {@link #ORIGINAL}: every video stored before V19 was already H.264.
 */
public enum VideoPlayback {
	/** The original is already playable everywhere (H.264, at most 1080p/30 fps) - no copy. */
	ORIGINAL,
	/** A playback copy is waiting in the queue (or being made) - the original is tried meanwhile. */
	PENDING,
	/** The H.264 copy in {@code .webimg/<name>.mp4} plays. */
	COPY,
	/** The copy could not be made - the original is still offered, which may not play. */
	FAILED,
}
