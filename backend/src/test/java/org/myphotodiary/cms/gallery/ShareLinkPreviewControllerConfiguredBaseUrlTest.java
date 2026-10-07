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

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.myphotodiary.cms.gallery.dto.CreateDirectoryRequest;
import org.myphotodiary.cms.gallery.dto.DirectoryResponse;
import org.myphotodiary.cms.gallery.dto.ImageResponse;
import org.myphotodiary.cms.gallery.dto.ShareLinkResponse;
import org.myphotodiary.cms.user.UserService;
import org.myphotodiary.cms.user.dto.CreateUserRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one branch {@link ShareLinkPreviewControllerTest} deliberately leaves
 * untested in its own context (see that class's own doc) - {@code
 * app.public-base-url} set explicitly (the real production setting,
 * MPD_PUBLIC_BASE_URL) must win over whatever host the request itself
 * happened to arrive on, e.g. Apache proxying to 127.0.0.1 internally. A
 * separate test class, not a second `@Test` in the other one, because a
 * different `@DynamicPropertySource` value means a genuinely different
 * cached Spring context.
 */
@SpringBootTest
@Transactional
class ShareLinkPreviewControllerConfiguredBaseUrlTest {

	@TempDir
	static Path storageRoot;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("storage.root", () -> storageRoot.toString());
		registry.add("app.public-base-url", () -> "https://configured.example.com");
	}

	@Autowired
	private ShareLinkPreviewController previewController;
	@Autowired
	private ShareController shareController;
	@Autowired
	private GalleryService galleryService;
	@Autowired
	private UserService userService;

	private Long directoryId;
	private Authentication auth;

	@BeforeEach
	void setUp() {
		userService.createUser(new CreateUserRequest("share-preview-cfg-admin", "Share Preview Cfg Admin", "x", "public", "ADMIN"));
		auth = new UsernamePasswordAuthenticationToken("share-preview-cfg-admin", null);
		DirectoryResponse dir = galleryService.createDirectory(auth, new CreateDirectoryRequest("2026/08/share-preview-cfg-" + Long.toString(System.nanoTime(), 36), null));
		directoryId = dir.id();
	}

	@Test
	void imagePreview_usesTheConfiguredBaseUrl_notTheRequestsOwnHost() throws IOException {
		ImageResponse uploaded = galleryService.uploadImage(auth, directoryId, jpegFile("shared.jpg"));
		ShareLinkResponse link = shareController.imageShareToken(auth, uploaded.id());

		// A request that arrived on a completely different host (e.g. Apache
		// proxying to the backend's own loopback address) - the configured
		// property must win regardless.
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setScheme("http");
		request.setServerName("127.0.0.1");
		request.setServerPort(8090);

		String html = previewController.imagePreview(uploaded.id(), link.token(), request);

		assertThat(html).contains("https://configured.example.com/api/public/images/");
		assertThat(html).doesNotContain("127.0.0.1");
	}

	private static MockMultipartFile jpegFile(String name) throws IOException {
		BufferedImage img = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, "jpg", out);
		return new MockMultipartFile("file", name, "image/jpeg", out.toByteArray());
	}
}
