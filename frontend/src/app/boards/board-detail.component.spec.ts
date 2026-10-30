import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute } from '@angular/router';

import { AuthService } from '../auth/auth.service';
import { BoardDetailComponent } from './board-detail.component';

describe('BoardDetailComponent', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [BoardDetailComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: new Map([['id', '7']]) } } }
      ]
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads the board and its columns on init', () => {
    const fixture = TestBed.createComponent(BoardDetailComponent);
    fixture.detectChanges();

    http.expectOne('/api/boards/7').flush({ id: 7, name: 'Sprint 1' });

    http.expectOne('/api/boards/7/members').flush([]);

    const listsReq = http.expectOne('/api/boards/7/lists');
    listsReq.flush([{ id: 1, boardId: 7, name: 'To do', position: 0 }]);

    http.expectOne('/api/lists/1/cards').flush([
      { id: 10, listId: 1, title: 'First card', position: 0 }
    ]);

    expect(fixture.componentInstance.board()?.name).toBe('Sprint 1');
    expect(fixture.componentInstance.cardsFor(1).length).toBe(1);
  });

  it('PATCHes the card when it is dropped into another column', () => {
    const fixture = TestBed.createComponent(BoardDetailComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    http.expectOne('/api/boards/7').flush({ id: 7, name: 'Sprint 1' });

    http.expectOne('/api/boards/7/members').flush([]);
    http.expectOne('/api/boards/7/lists').flush([
      { id: 1, boardId: 7, name: 'To do', position: 0 },
      { id: 2, boardId: 7, name: 'Doing', position: 1 }
    ]);
    http.expectOne('/api/lists/1/cards').flush([{ id: 10, listId: 1, title: 'Card', position: 0 }]);
    http.expectOne('/api/lists/2/cards').flush([]);

    const card = component.cardsFor(1)[0];
    component.drop({
      item: { data: card },
      container: { data: { id: 2 } },
      previousContainer: { data: { id: 1 } },
      previousIndex: 0,
      currentIndex: 0
    } as never);

    const move = http.expectOne('/api/cards/10/move');
    expect(move.request.method).toBe('PATCH');
    expect(move.request.body).toEqual({ targetListId: 2, position: 0 });
    move.flush({ id: 10, listId: 2, title: 'Card', position: 0 });

    expect(component.cardsFor(1).length).toBe(0);
    expect(component.cardsFor(2).length).toBe(1);
  });

  it('PUTs the card and updates it in place when the inline editor is saved', () => {
    const fixture = TestBed.createComponent(BoardDetailComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    http.expectOne('/api/boards/7').flush({ id: 7, name: 'Sprint 1' });

    http.expectOne('/api/boards/7/members').flush([]);
    http.expectOne('/api/boards/7/lists').flush([
      { id: 1, boardId: 7, name: 'To do', position: 0 }
    ]);
    http.expectOne('/api/lists/1/cards').flush([
      { id: 10, listId: 1, title: 'Card', position: 0 }
    ]);

    const card = component.cardsFor(1)[0];
    component.startEdit(card);
    expect(component.editingCardId()).toBe(10);

    component.editDraft.title = 'Card renamed';
    component.editDraft.description = 'Now with details';
    component.saveEdit(card);

    const put = http.expectOne('/api/cards/10');
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toEqual({ title: 'Card renamed', description: 'Now with details' });
    put.flush({ id: 10, listId: 1, title: 'Card renamed', description: 'Now with details', position: 0 });

    expect(component.editingCardId()).toBeNull();
    expect(component.cardsFor(1)[0].description).toBe('Now with details');
  });

  it('opens and closes the "add a card" composer one column at a time', () => {
    const fixture = TestBed.createComponent(BoardDetailComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    http.expectOne('/api/boards/7').flush({ id: 7, name: 'Sprint 1' });

    http.expectOne('/api/boards/7/members').flush([]);
    http.expectOne('/api/boards/7/lists').flush([
      { id: 1, boardId: 7, name: 'To do', position: 0 }
    ]);
    http.expectOne('/api/lists/1/cards').flush([]);

    expect(component.composingColumnId()).toBeNull();

    component.startComposing(1);
    expect(component.composingColumnId()).toBe(1);

    component.cancelComposing();
    expect(component.composingColumnId()).toBeNull();
  });

  it('POSTs title and description from the "add a card" form', () => {
    const fixture = TestBed.createComponent(BoardDetailComponent);
    const component = fixture.componentInstance;
    fixture.detectChanges();

    http.expectOne('/api/boards/7').flush({ id: 7, name: 'Sprint 1' });

    http.expectOne('/api/boards/7/members').flush([]);
    http.expectOne('/api/boards/7/lists').flush([
      { id: 1, boardId: 7, name: 'To do', position: 0 }
    ]);
    http.expectOne('/api/lists/1/cards').flush([]);

    const column = component.columns()[0];
    const draft = component.draftFor(1);
    draft.title = 'New card';
    draft.description = 'With a description';
    component.addCard(column);

    const post = http.expectOne('/api/lists/1/cards');
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual({ title: 'New card', description: 'With a description' });
    post.flush({ id: 20, listId: 1, title: 'New card', description: 'With a description', position: 0 });

    expect(component.cardsFor(1).length).toBe(1);
    expect(draft.title).toBe('');
    expect(draft.description).toBe('');
  });
  describe('members panel', () => {
    /** Signs in as `username` and loads board 7 (owned by alice), shared with bob. */
    function openBoardAs(username: string, roles: string[] = ['user']) {
      const auth = TestBed.inject(AuthService);
      auth.username.set(username);
      auth.roles.set(roles);

      const fixture = TestBed.createComponent(BoardDetailComponent);
      fixture.detectChanges();
      http.expectOne('/api/boards/7').flush({ id: 7, name: 'Sprint 1', owner: 'alice' });
      http.expectOne('/api/boards/7/members').flush([{ id: 1, boardId: 7, username: 'bob' }]);
      http.expectOne('/api/boards/7/lists').flush([]);
      fixture.detectChanges();
      return fixture;
    }

    const page = (fixture: { nativeElement: HTMLElement }) => fixture.nativeElement;

    it('lists the members to a member, without the owner controls', () => {
      const fixture = openBoardAs('bob');

      expect(fixture.componentInstance.members().map(m => m.username)).toEqual(['bob']);
      expect(fixture.componentInstance.canManage()).toBeFalse();
      expect(page(fixture).querySelector('.members')?.textContent).toContain('bob');
      expect(page(fixture).querySelector('.add-member')).toBeNull();
      expect(page(fixture).querySelector('.chip-remove')).toBeNull();
    });

    it('shows the add/remove controls to the owner', () => {
      const fixture = openBoardAs('alice');

      expect(fixture.componentInstance.canManage()).toBeTrue();
      expect(page(fixture).querySelector('.add-member')).not.toBeNull();
      expect(page(fixture).querySelector('.chip-remove')).not.toBeNull();
    });

    it('shows the add/remove controls to an admin', () => {
      const fixture = openBoardAs('carol', ['user', 'admin']);
      expect(fixture.componentInstance.canManage()).toBeTrue();
    });

    it('POSTs the username and adds the new member to the list', () => {
      const fixture = openBoardAs('alice');
      const component = fixture.componentInstance;

      component.newMemberName = ' dave ';
      component.addMember();

      const post = http.expectOne('/api/boards/7/members');
      expect(post.request.method).toBe('POST');
      expect(post.request.body).toEqual({ username: 'dave' });
      post.flush({ id: 2, boardId: 7, username: 'dave' });

      expect(component.members().map(m => m.username)).toEqual(['bob', 'dave']);
      expect(component.newMemberName).toBe('');
    });

    it("shows the backend's reason when adding a member is rejected", () => {
      const fixture = openBoardAs('alice');
      const component = fixture.componentInstance;

      component.newMemberName = 'bob';
      component.addMember();
      http.expectOne('/api/boards/7/members').flush(
        { error: 'bob is already a member of this board' },
        { status: 400, statusText: 'Bad Request' });

      expect(component.error()).toBe('Could not add "bob": bob is already a member of this board');
      expect(component.members().length).toBe(1);
    });

    it('DELETEs a member and drops them from the list', () => {
      const fixture = openBoardAs('alice');
      const component = fixture.componentInstance;
      spyOn(window, 'confirm').and.returnValue(true);

      component.removeMember(component.members()[0]);

      const del = http.expectOne('/api/boards/7/members/bob');
      expect(del.request.method).toBe('DELETE');
      del.flush(null);

      expect(component.members().length).toBe(0);
    });
  });
});
