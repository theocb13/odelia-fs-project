package com.odelia.kanban.repository;

import com.odelia.kanban.entity.BoardMember;
import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.Repository;

import java.util.List;

/**
 * Jakarta Data repository for {@link BoardMember}: who a board is shared with.
 *
 * <p>Every query is derived from its method name, like the other repositories
 * of the project - no hand-written JPQL.</p>
 */
@Repository(dataStore = "jdbc/kanban")
public interface BoardMemberRepository extends CrudRepository<BoardMember, Long> {

    /** The members of one board - what {@code GET /api/boards/{id}/members} returns. */
    List<BoardMember> findByBoardIdOrderByUsernameAsc(long boardId);

    /** Every board a user has been added to - used by {@code GET /api/boards}. */
    List<BoardMember> findByUsername(String username);

    /** The membership check behind {@code BoardAccess}. */
    boolean existsByBoardIdAndUsername(long boardId, String username);

    long deleteByBoardIdAndUsername(long boardId, String username);

    /** Clean-up when a board is deleted, so no membership row outlives its board. */
    long deleteByBoardId(long boardId);
}
