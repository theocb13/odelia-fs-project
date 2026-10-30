#!/usr/bin/env bash
# End-to-end check of board sharing (exercise 17) against a running stack.
#
#   ./scripts/test-board-sharing.sh
#
# Needs curl and jq and the backend on localhost:9080. Uses the realm's test
# accounts: alice (owner), bob (member, then removed) and carol (admin).
#
# Works from the Dev Container's terminal as well as from the host. The backend
# only accepts tokens whose issuer is http://localhost:8081, and Keycloak builds
# the issuer from the Host header of the token request. Inside the Dev Container
# Keycloak is reached as keycloak:8080, so the request is sent there with
# "Host: localhost:8081" to get a token the backend accepts.

set -u
command -v jq > /dev/null || { echo "jq is missing: sudo apt-get install -y jq"; exit 1; }

if getent hosts keycloak > /dev/null; then          # inside the compose network
  KC=${KC:-http://keycloak:8080/realms/kanban/protocol/openid-connect/token}
  KC_HOST=${KC_HOST:-localhost:8081}
else                                                 # on the host
  KC=${KC:-http://localhost:8081/realms/kanban/protocol/openid-connect/token}
  KC_HOST=${KC_HOST:-}
fi
API=${API:-http://localhost:9080/api}
pass=0; fail=0

token() {
  local host=()
  [ -n "$KC_HOST" ] && host=(-H "Host: $KC_HOST")
  curl -s "$KC" "${host[@]}" -d grant_type=password -d client_id=kanban-app \
       -d username="$1" -d password="$1" | jq -r '.access_token // empty'
}

# call <user-token> <method> <path> [json-body] -> prints the HTTP status
call() {
  local args=(-s -o /tmp/sharing-body -w '%{http_code}' -X "$2" "$API$3" -H "Authorization: Bearer $1")
  [ $# -ge 4 ] && args+=(-H 'Content-Type: application/json' -d "$4")
  curl "${args[@]}"
}

expect() {  # expect <description> <expected status> <actual status>
  if [ "$2" = "$3" ]; then echo "  ok   $1 ($3)"; pass=$((pass+1));
  else echo "  FAIL $1: expected $2, got $3 - $(cat /tmp/sharing-body)"; fail=$((fail+1)); fi
}

ALICE=$(token alice); BOB=$(token bob); CAROL=$(token carol)
[ -z "$ALICE" ] || [ -z "$BOB" ] || [ -z "$CAROL" ] && { echo "Could not get tokens from $KC"; exit 1; }

echo "alice creates a board"
curl -s -X POST "$API/boards" -H "Authorization: Bearer $ALICE" \
     -H 'Content-Type: application/json' -d '{"name":"Sharing test"}' > /tmp/sharing-board
BOARD=$(jq -r .id /tmp/sharing-board)
COLUMN=$(curl -s "$API/boards/$BOARD/lists" -H "Authorization: Bearer $ALICE" | jq -r '.[0].id')
echo "  board $BOARD, first column $COLUMN"

echo "Before sharing"
expect "bob cannot open alice's board"            403 "$(call "$BOB" GET "/boards/$BOARD")"

echo "Membership (R2, R5, R10)"
expect "alice adds bob"                            201 "$(call "$ALICE" POST "/boards/$BOARD/members" '{"username":"bob"}')"
expect "alice adds bob again"                      400 "$(call "$ALICE" POST "/boards/$BOARD/members" '{"username":"bob"}')"
expect "alice adds BOB (other case)"               400 "$(call "$ALICE" POST "/boards/$BOARD/members" '{"username":"BOB"}')"
expect "alice adds herself"                        400 "$(call "$ALICE" POST "/boards/$BOARD/members" '{"username":"alice"}')"
expect "alice removes herself"                     400 "$(call "$ALICE" DELETE "/boards/$BOARD/members/alice")"

echo "Member rights (R3, R6, R7)"
expect "bob lists the members"                     200 "$(call "$BOB" GET "/boards/$BOARD/members")"
expect "bob opens the board"                       200 "$(call "$BOB" GET "/boards/$BOARD")"
expect "bob reads the columns"                     200 "$(call "$BOB" GET "/boards/$BOARD/lists")"
expect "bob adds a card"                           201 "$(call "$BOB" POST "/lists/$COLUMN/cards" '{"title":"Added by bob"}')"
CARD=$(jq -r .id /tmp/sharing-body)
expect "bob edits the card"                        200 "$(call "$BOB" PUT "/cards/$CARD" '{"title":"Edited by bob"}')"
expect "bob moves the card"                        200 "$(call "$BOB" PATCH "/cards/$CARD/move" "{\"targetListId\":$COLUMN,\"position\":0}")"
expect "bob adds a column"                         201 "$(call "$BOB" POST "/boards/$BOARD/lists" '{"name":"Review","position":3}')"
call "$BOB" GET /boards > /dev/null
if jq -e "map(.id) | index($BOARD)" /tmp/sharing-body > /dev/null; then
  echo "  ok   GET /boards lists the shared board for bob"; pass=$((pass+1))
else
  echo "  FAIL GET /boards does not list board $BOARD for bob"; fail=$((fail+1))
fi

echo "Owner-only actions are 403 for a member (R8)"
expect "bob renames the board"                     403 "$(call "$BOB" PUT "/boards/$BOARD" '{"name":"Hijacked"}')"
expect "bob deletes the board"                     403 "$(call "$BOB" DELETE "/boards/$BOARD")"
expect "bob adds carol"                            403 "$(call "$BOB" POST "/boards/$BOARD/members" '{"username":"carol"}')"
expect "bob removes himself"                       403 "$(call "$BOB" DELETE "/boards/$BOARD/members/bob")"

echo "Admin bypass"
expect "carol renames the board"                   200 "$(call "$CAROL" PUT "/boards/$BOARD" '{"name":"Sharing test (admin)"}')"
expect "carol lists the members"                   200 "$(call "$CAROL" GET "/boards/$BOARD/members")"

echo "Removal takes effect immediately (R4)"
expect "alice removes bob"                         204 "$(call "$ALICE" DELETE "/boards/$BOARD/members/bob")"
expect "bob's next request is denied"              403 "$(call "$BOB" GET "/boards/$BOARD")"
expect "removing bob again"                        404 "$(call "$ALICE" DELETE "/boards/$BOARD/members/bob")"

echo "No relationship to the board any more (R9)"
expect "bob lists the members"                     403 "$(call "$BOB" GET "/boards/$BOARD/members")"
expect "bob reads the columns"                     403 "$(call "$BOB" GET "/boards/$BOARD/lists")"
expect "bob edits his old card"                    403 "$(call "$BOB" PUT "/cards/$CARD" '{"title":"x"}')"

echo "Clean-up"
expect "alice deletes the board"                   204 "$(call "$ALICE" DELETE "/boards/$BOARD")"

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
