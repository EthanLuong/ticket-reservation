#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${SMOKE_BASE_URL:-http://localhost:8080}"
EVENT_ID="11111111-1111-1111-1111-111111111111"

fail() {
    # TODO(you): print "FAIL: $1"; dump the last /api/reservations/me JSON if you
    #  have it; `docker compose logs --tail 20 app payment`; exit 1
    echo "FAIL: ${1:-unkown failure}"
    docker compose logs --tail 20 app payment
    exit 1

}

wait_for_health() {
    # TODO(you): bounded loop — curl -sf "$BASE_URL/actuator/health", grep for UP,
    #  return on success, fall through to fail "app never became healthy"

    for i in {1..30}; do
      if curl -sf "$BASE_URL/actuator/health" | grep -q "UP"; then
        return 0
      fi
      sleep 1
    done

    fail "app not healthy after 30s"

}

register_smoke_user() {
    # TODO(you): POST /api/auth/register with a fresh random email; capture
    #  .accessToken into TOKEN (command substitution + jq -r); guard non-empty
  EMAIL="smoketest-$RANDOM@test.local"
  BODY="{\"email\":\"$EMAIL\",\"password\":\"smoketest123\",\"displayName\":\"Smoke\"}"
  TOKEN=$(curl -s -X POST \
                -H "Content-Type: application/json" \
                -d "$BODY" \
                "$BASE_URL/api/auth/register" | jq -r '.accessToken')
  [[ -n "$TOKEN" && "$TOKEN" != "null" ]] || fail "register: no accessToken in response"

}

pick_available_seat() {
    # TODO(you): GET /api/seats?eventId=$EVENT_ID with the bearer token;
    #  jq-select the first AVAILABLE id into SEAT_ID; fail if none left
    SEAT_ID=$(curl -s -X GET\
                    -H "Authorization: Bearer $TOKEN"\
                    "$BASE_URL/api/seats?eventId=$EVENT_ID" | jq -r  '.[] | select(.status == "AVAILABLE") | .id' | head -1)

    [[ -n "$SEAT_ID" ]] || fail "no AVAILABLE seats left"
}

reserve_seat() {
    # TODO(you): generate KEY (powershell guid + non-empty guard);
    #  POST /api/reservations with Authorization + Idempotency-Key headers
    KEY=$(powershell -Command "[guid]::NewGuid().Guid")
    [[ -n "$KEY" ]] || fail "idempotency key generation failed"

    RESERVATION_ID=$(curl -s -X POST\
                           -H "Content-Type: application/json"\
                           -H "Authorization: Bearer $TOKEN"\
                           -H "Idempotency-Key: $KEY"\
                           -d "{\"seatId\":\"$SEAT_ID\"}"\
                           "$BASE_URL/api/reservations" | jq -r '.id')

    [[ -n "$RESERVATION_ID" && "$RESERVATION_ID" != "null" ]] || fail "reserve: no reservation id in response"
}

await_confirmed() {
    # TODO(you): 30×1s loop polling /api/reservations/me; PASS when this run's
    #  reservation shows CONFIRMED; fail with the last JSON when time runs out
    for i in {1..30}; do
          RESERVATION_STATUS=$(curl -s -H "Authorization: Bearer $TOKEN"\
                                           "$BASE_URL/api/reservations/me" | jq -r --arg id "$RESERVATION_ID" '.[] | select(.id==$id) | .status')
          if [[ "$RESERVATION_STATUS" == "CONFIRMED" ]]; then
            return 0
          fi
          sleep 1
    done
    fail "reservation $RESERVATION_ID not CONFIRMED after 30s (last status: ${RESERVATION_STATUS:-none seen})"
}

wait_for_health
register_smoke_user
pick_available_seat
reserve_seat
await_confirmed
echo "PASS"