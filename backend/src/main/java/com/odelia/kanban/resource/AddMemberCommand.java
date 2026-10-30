package com.odelia.kanban.resource;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/boards/{id}/members}: who to share the board with.
 *
 * @param username the Keycloak {@code preferred_username} of the new member
 */
public record AddMemberCommand(@NotBlank @Size(max = 120) String username) {
}
