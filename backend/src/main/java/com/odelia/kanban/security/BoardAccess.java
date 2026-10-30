package com.odelia.kanban.security;

import com.odelia.kanban.entity.Board;
import com.odelia.kanban.entity.BoardList;
import com.odelia.kanban.entity.Card;
import com.odelia.kanban.repository.BoardListRepository;
import com.odelia.kanban.repository.BoardMemberRepository;
import com.odelia.kanban.repository.BoardRepository;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;

/**
 * Authorization for boards and everything inside them.
 *
 * <p>{@code @RolesAllowed} alone only answers "is this a signed-in Kanban
 * user?", which is not enough: every user has the {@code user} role, so without
 * these checks anyone could read someone else's board by guessing its id. The
 * coarse role check and this fine-grained one are complementary.</p>
 *
 * <p>A board has two levels of access:</p>
 * <ul>
 *   <li><b>collaborator</b> ({@link #requireBoardAccess}) - the owner, any
 *       {@code BoardMember} of the board, or an admin. Enough to read the board
 *       and to create, edit, move and delete its columns and cards.</li>
 *   <li><b>owner</b> ({@link #requireBoardOwner}) - the owner or an admin only.
 *       Needed to rename or delete the board and to manage its members.</li>
 * </ul>
 *
 * <p>Columns and cards store no owner of their own; they inherit the
 * collaborator check by walking up to their board, so every card and column
 * endpoint accepts members without having been changed.</p>
 */
@RequestScoped
public class BoardAccess {

    @Inject
    CurrentUser currentUser;

    @Inject
    BoardRepository boards;

    @Inject
    BoardListRepository lists;

    @Inject
    BoardMemberRepository members;

    /** Fails with 403 unless the caller owns the board, is one of its members, or is an admin. */
    public void requireBoardAccess(Board board) {
        if (currentUser.isAdmin() || isOwner(board) || isMember(board)) {
            return;
        }
        throw new ForbiddenException("Board " + board.getId() + " is not shared with you");
    }

    /**
     * Fails with 403 unless the caller owns the board or is an admin. A member
     * passes {@link #requireBoardAccess} but not this one.
     */
    public void requireBoardOwner(Board board) {
        if (currentUser.isAdmin() || isOwner(board)) {
            return;
        }
        throw new ForbiddenException("Only the owner of board " + board.getId() + " can do this");
    }

    /** The collaborator check, reached through the column's board. */
    public void requireColumnAccess(BoardList column) {
        requireBoardAccess(boardOf(column));
    }

    /** The collaborator check, reached through the card's column and then its board. */
    public void requireCardAccess(Card card) {
        requireColumnAccess(columnOf(card));
    }

    /** True when the caller is the board's owner (being an admin does not make you the owner). */
    public boolean isOwner(Board board) {
        return currentUser.name().equals(board.getOwner());
    }

    /**
     * Read from the database on every call rather than cached, so removing a
     * member takes effect on that member's very next request.
     */
    private boolean isMember(Board board) {
        return board.getId() != null
                && members.existsByBoardIdAndUsername(board.getId(), currentUser.name());
    }

    private Board boardOf(BoardList column) {
        return boards.findById(column.getBoardId())
                     .orElseThrow(() -> orphan("column " + column.getId()));
    }

    private BoardList columnOf(Card card) {
        return lists.findById(card.getListId())
                    .orElseThrow(() -> orphan("card " + card.getId()));
    }

    /** With no board to check against, nobody can be shown to own the row: deny. */
    private ForbiddenException orphan(String what) {
        return new ForbiddenException("Cannot establish who owns " + what);
    }
}
