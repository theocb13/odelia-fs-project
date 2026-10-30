package com.odelia.kanban.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Records that {@code username} has {@code MEMBER} access to a board someone
 * else owns.
 *
 * <p>The owner is never stored here: {@link Board#getOwner()} already answers
 * "who owns this board?", and keeping a single source of truth for ownership
 * means a membership row can never grant - or take away - owner rights.</p>
 *
 * <p>Like {@link BoardList}, the board is referenced by its identifier rather
 * than by a {@code @ManyToOne}, which keeps the repository and the JSON flat.
 * The unique constraint is the database's own guarantee that a user cannot be
 * a member of the same board twice, behind the 400 the API already returns.</p>
 */
@Entity
@Table(name = "BOARD_MEMBER",
       uniqueConstraints = @UniqueConstraint(columnNames = { "BOARD_ID", "USERNAME" }))
public class BoardMember {

    /** The only role a membership row can carry; the owner role lives on {@link Board}. */
    public static final String MEMBER = "MEMBER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "BOARD_ID", nullable = false)
    private long boardId;

    @NotBlank
    @Size(max = 120)
    @Column(name = "USERNAME", nullable = false, length = 120)
    private String username;

    /** "ROLE" is a keyword in several databases, hence the explicit column name. */
    @Column(name = "MEMBER_ROLE", nullable = false, length = 20)
    private String role = MEMBER;

    public BoardMember() {
    }

    public BoardMember(long boardId, String username) {
        this.boardId = boardId;
        this.username = username;
        this.role = MEMBER;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getBoardId() {
        return boardId;
    }

    public void setBoardId(long boardId) {
        this.boardId = boardId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
