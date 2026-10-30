package com.odelia.kanban.security;

import com.odelia.kanban.entity.Board;
import com.odelia.kanban.repository.BoardMemberRepository;
import jakarta.ws.rs.ForbiddenException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The two access levels of {@link BoardAccess}: "collaborator"
 * ({@code requireBoardAccess}) and "owner" ({@code requireBoardOwner}), for an
 * owner, a member, a stranger and an admin.
 */
class BoardAccessTest {

    private static final long BOARD_ID = 42L;

    /** A {@link CurrentUser} with a canned answer instead of a real JWT. */
    private static class StubCurrentUser extends CurrentUser {
        private final String name;
        private final boolean admin;

        StubCurrentUser(String name, boolean admin) {
            this.name = name;
            this.admin = admin;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean isAdmin() {
            return admin;
        }
    }

    /**
     * A {@link BoardMemberRepository} that only knows the given members of
     * board {@link #BOARD_ID}. Built with a dynamic proxy so the test does not
     * have to implement every {@code CrudRepository} method; any other call fails loudly.
     */
    private static BoardMemberRepository membersOfBoard(String... usernames) {
        Set<String> members = Set.of(usernames);
        return (BoardMemberRepository) Proxy.newProxyInstance(
                BoardMemberRepository.class.getClassLoader(),
                new Class<?>[] { BoardMemberRepository.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("existsByBoardIdAndUsername")) {
                        return (long) args[0] == BOARD_ID && members.contains((String) args[1]);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static BoardAccess accessFor(String name, boolean admin, String... members) {
        BoardAccess access = new BoardAccess();
        access.currentUser = new StubCurrentUser(name, admin);
        access.members = membersOfBoard(members);
        return access;
    }

    private static Board aliceBoard() {
        Board board = new Board("Sprint Planning", "alice");
        board.setId(BOARD_ID);
        return board;
    }

    // --- owner -------------------------------------------------------------

    @Test
    void ownerMayAccessTheirOwnBoard() {
        BoardAccess access = accessFor("alice", false);
        assertDoesNotThrow(() -> access.requireBoardAccess(aliceBoard()));
    }

    @Test
    void ownerMayManageTheirOwnBoard() {
        BoardAccess access = accessFor("alice", false);
        assertDoesNotThrow(() -> access.requireBoardOwner(aliceBoard()));
    }

    // --- member ------------------------------------------------------------

    @Test
    void memberMayAccessASharedBoard() {
        BoardAccess access = accessFor("bob", false, "bob");
        assertDoesNotThrow(() -> access.requireBoardAccess(aliceBoard()));
    }

    @Test
    void memberMayNotManageASharedBoard() {
        BoardAccess access = accessFor("bob", false, "bob");
        assertThrows(ForbiddenException.class, () -> access.requireBoardOwner(aliceBoard()));
    }

    @Test
    void removedMemberIsDeniedOnTheNextCheck() {
        BoardAccess access = accessFor("bob", false /* no longer in the member list */);
        assertThrows(ForbiddenException.class, () -> access.requireBoardAccess(aliceBoard()));
    }

    // --- stranger ----------------------------------------------------------

    @Test
    void nonOwnerNonMemberIsDenied() {
        BoardAccess access = accessFor("dave", false, "bob");
        assertThrows(ForbiddenException.class, () -> access.requireBoardAccess(aliceBoard()));
        assertThrows(ForbiddenException.class, () -> access.requireBoardOwner(aliceBoard()));
    }

    @Test
    void membershipOfAnotherBoardGivesNoAccess() {
        BoardAccess access = accessFor("bob", false, "bob");
        Board otherBoard = new Board("Other", "alice");
        otherBoard.setId(BOARD_ID + 1);
        assertThrows(ForbiddenException.class, () -> access.requireBoardAccess(otherBoard));
    }

    // --- admin -------------------------------------------------------------

    @Test
    void adminMayAccessAndManageAnyBoard() {
        BoardAccess access = accessFor("carol", true);
        assertDoesNotThrow(() -> access.requireBoardAccess(aliceBoard()));
        assertDoesNotThrow(() -> access.requireBoardOwner(aliceBoard()));
    }
}
