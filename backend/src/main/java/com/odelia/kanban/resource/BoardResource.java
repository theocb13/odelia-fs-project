package com.odelia.kanban.resource;

import com.odelia.kanban.entity.Board;
import com.odelia.kanban.entity.BoardList;
import com.odelia.kanban.entity.BoardMember;
import com.odelia.kanban.repository.BoardListRepository;
import com.odelia.kanban.repository.BoardMemberRepository;
import com.odelia.kanban.repository.BoardRepository;
import com.odelia.kanban.repository.CardRepository;
import com.odelia.kanban.security.BoardAccess;
import com.odelia.kanban.security.CurrentUser;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * CRUD over boards, plus the lists (columns) they contain.
 *
 * <p>Every endpoint needs a valid Keycloak token: {@code @RolesAllowed}
 * turns an anonymous call into a 401 and a token without the {@code user} role
 * into a 403. On top of that a board belongs to the person who created it - the
 * {@code preferred_username} from the JWT - so the listing is filtered and the
 * per-board endpoints go through {@link BoardAccess}.</p>
 *
 * <p>A board can be shared with other users through {@code /boards/{id}/members}.
 * Members get the "collaborator" level of {@link BoardAccess}: they can read the
 * board and work on its columns and cards, but renaming or deleting the board
 * and managing its members stay with the owner (and admins).</p>
 */
@Path("/boards")
@RequestScoped
@RolesAllowed({ CurrentUser.USER, CurrentUser.ADMIN })
@SecurityRequirement(name = "bearerAuth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BoardResource {

    private static final List<String> DEFAULT_LISTS = List.of("To do", "Doing", "Done");

    @Inject
    CurrentUser currentUser;

    @Inject
    BoardAccess access;

    @Inject
    BoardRepository boards;

    @Inject
    BoardListRepository lists;

    @Inject
    CardRepository cards;

    @Inject
    BoardMemberRepository members;

    @Context
    UriInfo uriInfo;

    /**
     * The boards the caller owns plus the boards shared with them, sorted by
     * name - or every board, for an admin.
     */
    @GET
    @Operation(operationId = "getBoards")
    public List<Board> listBoards() {
        if (currentUser.isAdmin()) {
            return boards.findAllSortedByName();
        }
        List<Board> visible = new ArrayList<>(boards.findByOwnerOrderByNameAsc(currentUser.name()));
        for (BoardMember membership : members.findByUsername(currentUser.name())) {
            boards.findById(membership.getBoardId())
                  .filter(board -> visible.stream().noneMatch(b -> b.getId().equals(board.getId())))
                  .ifPresent(visible::add);
        }
        visible.sort(Comparator.comparing(Board::getName));
        return visible;
    }

    @GET
    @Path("/{id}")
    public Board getBoard(@PathParam("id") long id) {
        return accessibleBoard(id);
    }

    /**
     * Creates a board and seeds it with the three usual columns, so a new board
     * is immediately usable from the UI. The owner always comes from the token:
     * an {@code owner} sent by the client is ignored.
     */
    @POST
    @Transactional
    public Response createBoard(@Valid Board board) {
        board.setId(null);
        board.setCreatedAt(LocalDateTime.now());
        board.setOwner(currentUser.name());
        Board created = boards.insert(board);

        for (int i = 0; i < DEFAULT_LISTS.size(); i++) {
            lists.insert(new BoardList(created.getId(), DEFAULT_LISTS.get(i), i));
        }

        return Response.created(uriInfo.getAbsolutePathBuilder().path(String.valueOf(created.getId())).build())
                       .entity(created)
                       .build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public Board updateBoard(@PathParam("id") long id, @Valid Board board) {
        Board existing = ownedBoard(id);
        existing.setName(board.getName());
        return boards.save(existing);
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response deleteBoard(@PathParam("id") long id) {
        Board existing = ownedBoard(id);
        members.deleteByBoardId(id);
        for (BoardList column : lists.findByBoardIdOrderByPositionAsc(id)) {
            cards.deleteByListId(column.getId());
        }
        lists.deleteByBoardId(id);
        boards.delete(existing);
        return Response.noContent().build();
    }

    @GET
    @Path("/{id}/lists")
    public List<BoardList> listColumns(@PathParam("id") long id) {
        accessibleBoard(id);
        return lists.findByBoardIdOrderByPositionAsc(id);
    }

    @POST
    @Path("/{id}/lists")
    @Transactional
    public Response addColumn(@PathParam("id") long id, @Valid BoardList column) {
        accessibleBoard(id);
        column.setId(null);
        column.setBoardId(id);
        BoardList created = lists.insert(column);
        return Response.created(uriInfo.getBaseUriBuilder().path("lists").path(String.valueOf(created.getId())).build())
                       .entity(created)
                       .build();
    }

    /** Who the board is shared with. Any collaborator (owner, member, admin) may look. */
    @GET
    @Path("/{id}/members")
    public List<BoardMember> listMembers(@PathParam("id") long id) {
        accessibleBoard(id);
        return members.findByBoardIdOrderByUsernameAsc(id);
    }

    /**
     * Shares the board with one more user. Owner (or admin) only - checked
     * <em>before</em> the body is looked at, so a member trying this gets a 403
     * whatever username they send, never a 400 that would leak who is a member.
     */
    @POST
    @Path("/{id}/members")
    @Transactional
    public Response addMember(@PathParam("id") long id, @Valid AddMemberCommand command) {
        Board board = ownedBoard(id);
        String username = normalize(command.username());

        if (username.equals(board.getOwner())) {
            throw badRequest(username + " owns this board and cannot also be a member");
        }
        if (members.existsByBoardIdAndUsername(id, username)) {
            throw badRequest(username + " is already a member of this board");
        }

        BoardMember created = members.insert(new BoardMember(id, username));
        return Response.created(uriInfo.getAbsolutePathBuilder().path(username).build())
                       .entity(created)
                       .build();
    }

    /**
     * Stops sharing the board with a user. Owner (or admin) only. Because
     * {@link BoardAccess} reads memberships on every request, the removed
     * member's very next call is denied.
     */
    @DELETE
    @Path("/{id}/members/{username}")
    @Transactional
    public Response removeMember(@PathParam("id") long id, @PathParam("username") String rawUsername) {
        Board board = ownedBoard(id);
        String username = normalize(rawUsername);

        if (username.equals(board.getOwner())) {
            throw badRequest(username + " owns this board; ownership cannot be removed");
        }
        if (members.deleteByBoardIdAndUsername(id, username) == 0) {
            throw new WebApplicationException(username + " is not a member of board " + id,
                                              Response.Status.NOT_FOUND);
        }
        return Response.noContent().build();
    }

    /** Loads a board the caller may work on (owner, member, admin): 404 if it is gone, 403 otherwise. */
    private Board accessibleBoard(long id) {
        Board board = boards.findById(id).orElseThrow(() -> notFound(id));
        access.requireBoardAccess(board);
        return board;
    }

    /** Loads a board the caller may manage (owner, admin): 404 if it is gone, 403 for a mere member. */
    private Board ownedBoard(long id) {
        Board board = boards.findById(id).orElseThrow(() -> notFound(id));
        access.requireBoardOwner(board);
        return board;
    }

    /**
     * Keycloak stores usernames in lower case, so "Bob" and "bob" are the same
     * person: normalizing here stops a differently-cased name from slipping past
     * the duplicate and owner checks.
     */
    private static String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private static BadRequestException badRequest(String message) {
        return new BadRequestException(message);
    }

    private WebApplicationException notFound(long id) {
        return new WebApplicationException("No board with id " + id, Response.Status.NOT_FOUND);
    }
}
