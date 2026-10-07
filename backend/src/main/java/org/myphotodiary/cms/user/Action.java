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

/**
 * Ported from the legacy {@code org.myphotodiary.model.Action} (Design.md
 * §16.4) - only the subset actually checked by this backend so far. Legacy also
 * has {@code createUser}/{@code deleteUser}/{@code assignUserRole} (already
 * covered here by {@code UserController}'s blanket
 * {@code @PreAuthorize("hasRole('ADMIN')")}, a coarser but equivalent gate
 * for that ADMIN-only screen) and {@code changePwd}/{@code changeConfiguration}
 * (covered by {@code MeController}'s own deliberately-broader self-service
 * semantics - see its javadoc - rather than this per-group matrix).
 */
public enum Action {
	BROWSE,
	CREATE_SEQUENCE,
	EDIT_SEQUENCE,
	DELETE_SEQUENCE,
	EDIT_IMAGE,
	DELETE_IMAGE,
}
