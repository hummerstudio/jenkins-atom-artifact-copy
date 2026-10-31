#!/usr/bin/env bash
# A Jenkins-shaped fake: listing, byte-for-byte artifacts, the glob, and the ways it can go wrong.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

log="$(mktemp)"; port=$((27000 + RANDOM % 900))
start() {
  python3 tools/fake_jenkins.py "$port" "$log" "$@" & server=$!
  for _ in $(seq 1 40); do curl -s -o /dev/null "http://127.0.0.1:$port/job/x/api/json" && break; sleep 0.1; done
}
stop() { kill $server 2>/dev/null; wait $server 2>/dev/null; }
start; trap 'stop' EXIT

run_copy() { # run_copy <secrets-json> [key=value ...]
  local secrets="$1"; shift
  local dir; dir="$(mktemp -d)"
  python3 - "$dir/input.json" "$port" "$@" <<'PY'
import json, sys
path, port = sys.argv[1], sys.argv[2]
doc = {"sourceUrl": "http://127.0.0.1:" + port, "sourceJob": "upstream-app"}
for pair in sys.argv[3:]:
    key, _, value = pair.partition("=")
    doc[key] = value
json.dump(doc, open(path, "w"))
PY
  printf '%s' "$secrets" >"$dir/.secrets.json"
  run "$dir" >"$dir/atom.log" 2>&1
  echo "$dir"
}

# --- the whole build's artifacts, paths preserved
dir=$(run_copy '{}')
status "$dir" | grep -q success || fail "copy: $(cat "$dir/output.json")"
[ "$(output "$dir" filesCopied)" = "4" ] || fail "file count: $(cat "$dir/output.json")"
[ "$(output "$dir" sourceBuild)" = "42" ] || fail "source build number: $(cat "$dir/output.json")"
cmp -s <(printf 'jar-bytes\n') "$dir/copied/dist/app.jar" || fail "app.jar content"
cmp -s <(printf 'deep\n') "$dir/copied/reports/nested/deep.txt" || fail "the nested path was flattened"
cmp -s <(printf 'spaced\n') "$dir/copied/reports/a file with spaces.txt" || fail "a name with spaces broke the copy"
grep -q "/job/upstream-app/lastSuccessfulBuild/api/json" "$log" || fail "the listing URL: $(tail -1 "$log")"
grep -q "/artifact/reports/a%20file%20with%20spaces.txt" "$log" || fail "artifact paths must be encoded: $(tail -2 "$log")"

# --- a narrower glob, and the dot in the pattern must not act as a wildcard
: >"$log"
dir=$(run_copy '{}' artifacts="**/*.jar")
[ "$(output "$dir" filesCopied)" = "1" ] || fail "*.jar should select one file: $(cat "$dir/output.json")"
dir=$(run_copy '{}' artifacts="dist/appXjar")
status "$dir" | grep -q error || fail "a dot in the pattern must be literal, not a wildcard"

# --- a specific build number
: >"$log"
dir=$(run_copy '{}' buildSelector=number buildNumber=7)
status "$dir" | grep -q success || fail "a pinned build: $(cat "$dir/output.json")"
grep -q "/job/upstream-app/number/7/api/json" "$log" || fail "build number URL: $(tail -1 "$log")"

# --- matching nothing names what the build actually has
dir=$(run_copy '{}' artifacts="**/*.zip")
status "$dir" | grep -q error || fail "matching nothing must fail"
grep -q "dist/app.jar" "$dir/output.json" || fail "the available artifacts should be listed: $(cat "$dir/output.json")"

# --- auth goes out as basic
: >"$log"
dir=$(run_copy '{"token": "api-token"}' username=ci)
python3 - "$log" <<'PY' || fail "the token did not reach the server as basic auth: $(tail -1 "$log")"
import base64, json, sys
lines = [json.loads(l) for l in open(sys.argv[1]).read().strip().splitlines()]
assert lines[0]["authorization"] == "Basic " + base64.b64encode(b"ci:api-token").decode(), lines[0]
PY

# --- 401 says the token is the problem and is a USER error
stop; start --status 401
dir=$(run_copy '{"token": "bad"}')
status "$dir" | grep -q error || fail "401 must fail"
grep -q "read access" "$dir/output.json" || fail "401 hint: $(cat "$dir/output.json")"
grep -q '"errorType":1' "$dir/output.json" || fail "401 should be USER(1)"

# --- bad input
stop; start
dir=$(run_copy '{}' sourceJob="")
status "$dir" | grep -q error || fail "a missing job must be refused"
grep -q "sourceJob is required" "$dir/output.json" || fail "message: $(cat "$dir/output.json")"
dir=$(run_copy '{}' buildSelector=number)
status "$dir" | grep -q error || fail "number without a number must be refused"
grep -q "buildNumber is required" "$dir/output.json" || fail "message: $(cat "$dir/output.json")"

echo "smoke ok: listing, byte-identical artifacts, nested and spaced names, glob narrowing, build pinning, auth, 401, bad input"
