import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { AuthService } from '../auth/auth.service';
import { Board } from '../models/board';
import { apiReason } from '../services/api-error';
import { BoardService } from '../services/board.service';

/**
 * Lists the boards, creates one, deletes one. Each board name links to the
 * drag-and-drop board detail view ({@code /boards/:id}).
 *
 * The list holds the boards the user owns and the boards shared with them; a
 * shared board is flagged and has no "Delete" button, since only its owner (or
 * an admin) may delete it.
 */
@Component({
  selector: 'app-board-list',
  imports: [FormsModule, DatePipe, RouterLink],
  templateUrl: './board-list.component.html',
  styleUrl: './board-list.component.css'
})
export class BoardListComponent implements OnInit {
  private readonly boardService = inject(BoardService);
  private readonly auth = inject(AuthService);

  readonly boards = signal<Board[]>([]);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  newBoardName = '';

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.loading.set(true);
    this.error.set(null);
    this.boardService.getBoards().subscribe({
      next: boards => {
        this.boards.set(boards);
        this.loading.set(false);
      },
      error: err => this.fail('Could not load the boards', err)
    });
  }

  createBoard(): void {
    const name = this.newBoardName.trim();
    if (!name) {
      return;
    }
    this.error.set(null);
    this.boardService.createBoard(name).subscribe({
      next: board => {
        this.boards.update(boards => [...boards, board].sort((a, b) => a.name.localeCompare(b.name)));
        this.newBoardName = '';
      },
      error: err => this.fail(`Could not create the board "${name}"`, err)
    });
  }

  /** True when the signed-in user created this board. */
  isMine(board: Board): boolean {
    return board.owner === this.auth.username();
  }

  /** Owner-only actions (delete) are also open to admins, mirroring BoardAccess on the backend. */
  canManage(board: Board): boolean {
    return this.isMine(board) || this.auth.isAdmin();
  }

  deleteBoard(board: Board): void {
    if (board.id === undefined) {
      return;
    }
    if (!confirm(`Delete board "${board.name}"? This cannot be undone.`)) {
      return;
    }
    this.error.set(null);
    this.boardService.deleteBoard(board.id).subscribe({
      next: () => this.boards.update(boards => boards.filter(b => b.id !== board.id)),
      error: err => this.fail(`Could not delete the board "${board.name}"`, err)
    });
  }

  /** Shows `message`, followed by the backend's own reason when it sent one (400/403). */
  private fail(message: string, err: unknown): void {
    console.error(message, err);
    const reason = apiReason(err);
    this.error.set(reason ? `${message}: ${reason}` : message);
    this.loading.set(false);
  }
}
