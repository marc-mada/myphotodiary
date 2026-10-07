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

package org.myphotodiary.cms.settings;

import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The version of the backend actually running on the server (26/09/2026,
 * explicit ask - shown under Admin -> Configure's "App-wide settings"), not
 * the version the frontend bundle was built with: the two can diverge on a
 * partial deployment (a real stale-jar incident, 03/09/2026), and it's
 * the server's that matters for diagnosing one. Read from Spring Boot's
 * {@link BuildProperties} ({@code build-info} goal in pom.xml, i.e. the
 * POM's own {@code <version>}), so it can never drift from the jar name.
 * Any authenticated user may read it (default {@code authenticated()} rule).
 * "unknown" if the jar was built without build-info (e.g. an IDE run that
 * skipped Maven's generate-resources phase) rather than failing to start.
 */
@RestController
public class VersionController {

	private final ObjectProvider<BuildProperties> buildProperties;

	public VersionController(ObjectProvider<BuildProperties> buildProperties) {
		this.buildProperties = buildProperties;
	}

	@GetMapping("/api/version")
	public Map<String, String> version() {
		BuildProperties props = buildProperties.getIfAvailable();
		return Map.of("version", props != null ? props.getVersion() : "unknown");
	}
}
