package com.odelia.kanban.resource;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

/**
 * Turns the {@link BadRequestException}s thrown by the membership endpoints
 * (already a member, adding or removing the owner) into a {@code 400} with the
 * same JSON shape as the 403s of
 * {@link com.odelia.kanban.security.ForbiddenExceptionMapper}:
 * {@code {"error": "..."}}. The frontend can then show the server's reason as-is.
 */
@Provider
public class BadRequestExceptionMapper implements ExceptionMapper<BadRequestException> {

    @Override
    public Response toResponse(BadRequestException exception) {
        return Response.status(Response.Status.BAD_REQUEST)
                       .type(MediaType.APPLICATION_JSON)
                       .entity(Map.of("error", exception.getMessage()))
                       .build();
    }
}
