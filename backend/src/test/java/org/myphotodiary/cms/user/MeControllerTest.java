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

package org.myphotodiary.cms.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.myphotodiary.cms.user.dto.CreateRoleAssignmentRequest;
import org.myphotodiary.cms.user.dto.CreateUserRequest;
import org.myphotodiary.cms.user.dto.MeResponse;
import org.myphotodiary.cms.user.dto.MyGroupResponse;
import org.myphotodiary.cms.user.dto.UpdateMeRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-service settings (Design.md §16.5's SessionConfigurationSvr
 * equivalent) - any authenticated user, not gated by role, same as the
 * legacy servlet (see MeController's javadoc). No dedicated test existed for
 * this controller before slideShowInterval's 2..60 bound was added here -
 * maxQueryLength's own "at least 1" check was likewise never covered at this
 * layer; both are exercised now since real validation logic, not just
 * pass-through, belongs behind a test per this project's usual discipline.
 */
@SpringBootTest
@Transactional
class MeControllerTest {

	@Autowired
	private MeController meController;
	@Autowired
	private UserService userService;

	@Test
	void me_defaultsMatchLegacyUserConfigurationDefaults() {
		userService.createUser(new CreateUserRequest("dana", "Dana D.", "pw", null, null));

		MeResponse response = meController.me(authenticationFor("dana"));

		assertThat(response.maxQueryLength()).isEqualTo(10);
		assertThat(response.slideShowInterval()).isEqualTo(5);
		// UserConfiguration.java's own defaults (Paris) - not admin.jsp's
		// config form placeholder ("0"), which only ever displays an
		// already-loaded value in practice - see V7 migration's comment.
		assertThat(response.defaultLatitude()).isEqualTo(48.8567);
		assertThat(response.defaultLongitude()).isEqualTo(2.3508);
		// No legacy default to match here - see V8 migration's comment.
		assertThat(response.postItFadeDelay()).isEqualTo(8);
	}

	@Test
	void update_setsAllPreferences() {
		userService.createUser(new CreateUserRequest("erin", "Erin E.", "pw", null, null));

		MeResponse response = meController.update(authenticationFor("erin"), new UpdateMeRequest(25, 12, 40.0, -3.5, 15));

		assertThat(response.maxQueryLength()).isEqualTo(25);
		assertThat(response.slideShowInterval()).isEqualTo(12);
		assertThat(response.defaultLatitude()).isEqualTo(40.0);
		assertThat(response.defaultLongitude()).isEqualTo(-3.5);
		assertThat(response.postItFadeDelay()).isEqualTo(15);
	}

	@Test
	void update_slideShowIntervalOutOfLegacyBounds_throws() {
		userService.createUser(new CreateUserRequest("frank", "Frank F.", "pw", null, null));
		Authentication auth = authenticationFor("frank");

		// Same 2..60 second bound as the legacy admin.jsp config form.
		assertThatThrownBy(() -> meController.update(auth, new UpdateMeRequest(null, 1, null, null, null))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> meController.update(auth, new UpdateMeRequest(null, 61, null, null, null))).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void update_defaultLocationOutOfLegacyBounds_throws() {
		userService.createUser(new CreateUserRequest("holly", "Holly H.", "pw", null, null));
		Authentication auth = authenticationFor("holly");

		// Same -90..90 / -180..180 bounds as the legacy admin.jsp config form.
		assertThatThrownBy(() -> meController.update(auth, new UpdateMeRequest(null, null, 91.0, null, null))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> meController.update(auth, new UpdateMeRequest(null, null, -91.0, null, null))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> meController.update(auth, new UpdateMeRequest(null, null, null, 181.0, null))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> meController.update(auth, new UpdateMeRequest(null, null, null, -181.0, null))).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void update_postItFadeDelayOutOfBounds_throws() {
		userService.createUser(new CreateUserRequest("ivan", "Ivan I.", "pw", null, null));
		Authentication auth = authenticationFor("ivan");

		// No legacy bound to match - same 2..60 range as slideShowInterval
		// (see MeController's own comment on why).
		assertThatThrownBy(() -> meController.update(auth, new UpdateMeRequest(null, null, null, null, 1))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> meController.update(auth, new UpdateMeRequest(null, null, null, null, 61))).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void update_onlyTouchesFieldsPresentInRequest() {
		userService.createUser(new CreateUserRequest("gina", "Gina G.", "pw", null, null));
		Authentication auth = authenticationFor("gina");
		meController.update(auth, new UpdateMeRequest(30, null, 10.0, 20.0, null));

		MeResponse response = meController.update(auth, new UpdateMeRequest(null, 8, null, null, null));

		assertThat(response.maxQueryLength()).isEqualTo(30);
		assertThat(response.slideShowInterval()).isEqualTo(8);
		assertThat(response.defaultLatitude()).isEqualTo(10.0);
		assertThat(response.defaultLongitude()).isEqualTo(20.0);
	}

	@Test
	void myGroups_returnsExactlyTheCallersOwnRoleAssignments() {
		// Two different roles in two different groups - the sequence group
		// picker's WRITER dropdown filters this list to the "WRITER" rows
		// client-side, so the response has to carry the role per group, not
		// just the group name (explicit ask, 12/09/2026 - "one of its own
		// groups as a WRITER").
		userService.createUser(new CreateUserRequest("jack", "Jack J.", "pw", "family-photos", "WRITER"));
		userService.addRoleAssignment("jack", new CreateRoleAssignmentRequest("friends-photos", "READER"));
		Authentication auth = authenticationFor("jack");

		List<MyGroupResponse> groups = meController.myGroups(auth);

		assertThat(groups).containsExactlyInAnyOrder(new MyGroupResponse("family-photos", "WRITER"), new MyGroupResponse("friends-photos", "READER"));
	}

	private Authentication authenticationFor(String userName) {
		return new UsernamePasswordAuthenticationToken(userName, null);
	}
}
