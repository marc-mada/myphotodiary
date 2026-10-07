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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.myphotodiary.cms.settings.dto.UpdateAppSettingsRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

/** Target upload time per file (V13, 26/09/2026) - default, update, bounds, ADMIN-only write. */
@SpringBootTest
@Transactional
class AppSettingsControllerTest {

	@Autowired
	private AppSettingsController controller;

	@Test
	@WithMockUser(roles = "ADMIN")
	void targetUploadSeconds_defaultsTo10_andCanBeUpdatedWithoutTouchingVideoCap() {
		assertThat(controller.get().targetUploadSeconds()).isEqualTo(10);
		long videoCap = controller.get().maxVideoSizeBytes();

		assertThat(controller.update(new UpdateAppSettingsRequest(null, 30, null)).targetUploadSeconds()).isEqualTo(30);
		assertThat(controller.get().targetUploadSeconds()).isEqualTo(30);
		assertThat(controller.get().maxVideoSizeBytes()).isEqualTo(videoCap);
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	void targetUploadSeconds_outOfBounds_rejected() {
		assertThatThrownBy(() -> controller.update(new UpdateAppSettingsRequest(null, 0, null))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> controller.update(new UpdateAppSettingsRequest(null, 601, null))).isInstanceOf(IllegalArgumentException.class);
		assertThat(controller.get().targetUploadSeconds()).isEqualTo(10);
	}

	@Test
	@WithMockUser(roles = "ADMIN")
	void maxShareSize_defaultsTo50_boundsChecked() {
		assertThat(controller.get().maxShareSize()).isEqualTo(50);
		assertThat(controller.update(new UpdateAppSettingsRequest(null, null, 120)).maxShareSize()).isEqualTo(120);
		assertThatThrownBy(() -> controller.update(new UpdateAppSettingsRequest(null, null, 0))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> controller.update(new UpdateAppSettingsRequest(null, null, 1001))).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@WithMockUser(roles = "WRITER")
	void update_deniedForNonAdmin_butReadAllowed() {
		assertThat(controller.get().targetUploadSeconds()).isEqualTo(10);
		assertThatThrownBy(() -> controller.update(new UpdateAppSettingsRequest(null, 30, null))).isInstanceOf(AccessDeniedException.class);
	}
}
