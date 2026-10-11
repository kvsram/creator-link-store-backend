#!/usr/bin/env bash
# Smoke test: proves a running instance can sign up, log in, read the profile, rotate the refresh token,
# log out, and that the admin login works. No phone step (that needs the SMS link; the integration
# test covers it). Used locally (`make smoke`) and by the deploy pipeline against dev.
#
#   BASE_URL        where the API is (default http://localhost:8080)
#   ADMIN_EMAIL     admin to log in as (default matches docker-compose.yml)
#   ADMIN_PASSWORD
#
# Needs only bash, curl and python3 (for reading JSON fields).
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@example.test}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-local-admin-password-123}"

suffix="$(date +%s)"
username="smoke${suffix}"
email="${username}@example.test"
password="SmokeTest-${suffix}"

step=0
pass() { step=$((step + 1)); printf '  ok  %2d. %s\n' "$step" "$1"; }
fail() { printf '  FAIL    %s\n' "$1" >&2; exit 1; }

# json_field <field>   reads a top-level field from JSON on stdin
json_field() { python3 -c 'import sys, json; d = json.load(sys.stdin); v = d.get(sys.argv[1], ""); print(v if v is not None else "")' "$1"; }

# request <method> <path> [json-body] [bearer]   -> prints "<status>\n<body>"
request() {
  local method="$1" path="$2" body="${3:-}" bearer="${4:-}"
  local args=(-s -o /tmp/smoke-body.$$ -w '%{http_code}' -X "$method" -H 'Content-Type: application/json')
  [[ -n "$bearer" ]] && args+=(-H "Authorization: Bearer $bearer")
  [[ -n "$body" ]] && args+=(--data "$body")
  local status
  status="$(curl "${args[@]}" "$BASE_URL$path")"
  printf '%s\n' "$status"
  cat /tmp/smoke-body.$$
  rm -f /tmp/smoke-body.$$
}

# expect <status> <method> <path> [body] [bearer]  -> prints the response body, fails on another status
expect() {
  local want="$1"; shift
  local out status body
  out="$(request "$@")"
  status="${out%%$'\n'*}"
  body="${out#*$'\n'}"
  [[ "$status" == "$want" ]] || fail "$1 $2 returned $status, expected $want: $body"
  printf '%s' "$body"
}

echo "Smoke test against $BASE_URL"

# 0. wait for health
for i in $(seq 1 60); do
  if curl -sf "$BASE_URL/actuator/health" | grep -q '"UP"'; then break; fi
  [[ "$i" == 60 ]] && fail "API not healthy after 120 s"
  sleep 2
done
pass "health is UP"

# 1. username availability
avail="$(expect 200 GET "/api/auth/username-availability?username=$username" | json_field available)"
[[ "$avail" == "True" ]] || fail "username $username reported unavailable"
pass "username availability"

# 2. signup (no phone: the SMS link is covered by the integration test)
creator_id="$(expect 201 POST /api/auth/signup \
  "{\"fullName\":\"Smoke Test\",\"email\":\"$email\",\"username\":\"$username\",\"password\":\"$password\"}" \
  | json_field creatorId)"
[[ -n "$creator_id" ]] || fail "signup returned no creatorId"
pass "signup -> creator $creator_id"

# 3. duplicate signup is a clean 409
code="$(expect 409 POST /api/auth/signup \
  "{\"fullName\":\"Smoke Test\",\"email\":\"$email\",\"username\":\"${username}x\",\"password\":\"$password\"}" \
  | json_field code)"
[[ "$code" == "EMAIL_TAKEN" ]] || fail "duplicate signup code was $code"
pass "duplicate signup -> 409 EMAIL_TAKEN"

# 4. login
login="$(expect 200 POST /api/auth/login "{\"identifier\":\"$username\",\"password\":\"$password\"}")"
access="$(printf '%s' "$login" | json_field accessToken)"
refresh="$(printf '%s' "$login" | json_field refreshToken)"
[[ -n "$access" && -n "$refresh" ]] || fail "login returned no tokens"
pass "login -> access + refresh token"

# 5. profile needs the token
me="$(expect 200 GET /api/creators/me "" "$access" | json_field username)"
[[ "$me" == "$username" ]] || fail "/me returned username '$me'"
expect 401 GET /api/creators/me >/dev/null
pass "profile with token 200, without token 401"

# 6. refresh rotates
rotated="$(expect 200 POST /api/auth/refresh "{\"refreshToken\":\"$refresh\"}")"
access2="$(printf '%s' "$rotated" | json_field accessToken)"
refresh2="$(printf '%s' "$rotated" | json_field refreshToken)"
[[ -n "$refresh2" && "$refresh2" != "$refresh" ]] || fail "refresh did not rotate the token"
expect 200 GET /api/creators/me "" "$access2" >/dev/null
pass "refresh rotates, new access token works"

# 7. logout revokes; the revoked token cannot refresh
expect 204 POST /api/auth/logout "{\"refreshToken\":\"$refresh2\"}" >/dev/null
expect 401 POST /api/auth/refresh "{\"refreshToken\":\"$refresh2\"}" >/dev/null
pass "logout revokes the refresh token"

# 8. admin login and admin profile
admin_access="$(expect 200 POST /api/admin/auth/login \
  "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" | json_field accessToken)"
[[ -n "$admin_access" ]] || fail "admin login returned no token"
expect 200 GET /api/admin/me "" "$admin_access" >/dev/null
expect 403 GET /api/admin/me "" "$access2" >/dev/null
pass "admin login works; a creator token is refused on /api/admin"

echo "PASS: $step checks"
