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

import org.myphotodiary.cms.user.dto.CreateUserRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Creates a single default admin user on first startup (empty database
 * only) - otherwise nothing could ever authenticate to create the first
 * user, since every /api/users endpoint requires ROLE_ADMIN. The legacy
 * app's equivalent (ModelFactory.initDatabase, Design.md §16.3) bootstraps a
 * whole configurable set of users/roles from an init-param string
 * (rbacInitRules); this is deliberately simpler for this first slice - one
 * hardcoded bootstrap admin, not a general mechanism - since real RBAC
 * bootstrap configuration is a separate concern from proving out the
 * User/Group/RoleAssignment CRUD itself.
 *
 * <p>The password is "admin" unless {@code app.initial-admin-password}
 * ({@code MPD_INITIAL_ADMIN_PASSWORD}) is set (06/10/2026, Docker image) -
 * so an instance reachable from the Internet never has to start with a
 * guessable admin account. Only read on that very first start; changing it
 * later has no effect on an existing account.
 */
@Component
public class BootstrapAdminInitializer implements CommandLineRunner {

	private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);

	private final UserRepository userRepository;
	private final UserService userService;
	private final String initialPassword;

	public BootstrapAdminInitializer(UserRepository userRepository, UserService userService,
			@Value("${app.initial-admin-password:}") String initialPassword) {
		this.userRepository = userRepository;
		this.userService = userService;
		this.initialPassword = initialPassword;
	}

	@Override
	public void run(String... args) {
		if (userRepository.count() > 0) {
			return;
		}
		boolean passwordGiven = initialPassword != null && !initialPassword.isBlank();
		userService.createUser(new CreateUserRequest("admin", "Administrator", passwordGiven ? initialPassword : "admin", "public", "ADMIN"));
		if (passwordGiven) {
			log.info("No users existed - created admin user 'admin' with the password from MPD_INITIAL_ADMIN_PASSWORD.");
		} else {
			log.warn("No users existed - created default admin user (username 'admin', password 'admin'). "
					+ "Change this password immediately (Admin -> Users), or set MPD_INITIAL_ADMIN_PASSWORD before the first start.");
		}
	}
}
