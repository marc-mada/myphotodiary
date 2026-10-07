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

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Secrets generated once and kept in a file (06/10/2026, Docker image), so
 * that someone installing the app doesn't have to create the token secrets
 * by hand, yet share links and video tokens still survive a restart.
 *
 * <p>Only used when {@code app.secrets-file} ({@code MPD_SECRETS_FILE}) is
 * set - the Docker image points it at its data folder. A secret given
 * explicitly (e.g. {@code MPD_SHARE_TOKEN_SECRET}) always wins, and nothing
 * is written for it. Without a secrets file, {@link #get} returns
 * {@code null} and the token services keep their old behavior: a random key
 * per start.
 */
@Component
public class PersistedSecrets {

	private static final Logger log = LoggerFactory.getLogger(PersistedSecrets.class);

	private final Path file;

	public PersistedSecrets(@Value("${app.secrets-file:}") String secretsFile) {
		this.file = secretsFile == null || secretsFile.isBlank() ? null : Path.of(secretsFile);
	}

	/**
	 * @return the secret stored under {@code name}, generated and saved on
	 *         first use; {@code null} when no secrets file is configured.
	 */
	public synchronized String get(String name) {
		if (file == null) {
			return null;
		}
		Properties secrets = load();
		String value = secrets.getProperty(name);
		if (value == null || value.isBlank()) {
			byte[] bytes = new byte[32];
			new SecureRandom().nextBytes(bytes);
			value = HexFormat.of().formatHex(bytes);
			secrets.setProperty(name, value);
			save(secrets);
			log.info("Generated secret '{}' and saved it in {}", name, file);
		}
		return value;
	}

	private Properties load() {
		Properties secrets = new Properties();
		if (Files.isRegularFile(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				secrets.load(reader);
			} catch (IOException e) {
				throw new UncheckedIOException("Could not read secrets file " + file, e);
			}
		}
		return secrets;
	}

	// Written to a temporary file next to it, then moved into place, so a
	// crash mid-write never leaves a half-written secrets file behind.
	private void save(Properties secrets) {
		try {
			Path dir = file.toAbsolutePath().getParent();
			Files.createDirectories(dir);
			Path tmp = dir.resolve(file.getFileName() + ".tmp");
			try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				secrets.store(writer, "myPhotoDiary generated secrets - keep private, do not edit");
			}
			if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
				Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
			}
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not write secrets file " + file, e);
		}
	}
}
