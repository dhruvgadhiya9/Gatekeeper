#!/usr/bin/env bash
# Runs one test: fetch the job, clone repos, run Claude Code, upload results, report back.
# It always tries to report, because the backend treats silence as a crash.
set -uo pipefail

: "${JOB_URL:?JOB_URL is required}"
: "${JOB_TOKEN:?JOB_TOKEN is required}"

WORKSPACE=/workspace
RESULTS="$WORKSPACE/results"
JOB="$WORKSPACE/job.json"
AGENT_RESULT="$WORKSPACE/agent-result.json"
MAX_FILES=500
MAX_FILE_BYTES=209715200
# Leaves time to upload results when the agent uses its whole time limit.
UPLOAD_RESERVE_MINUTES=5

OUTCOME=FAILED
EXIT_CODE=1
REPORTED=0

log() { echo "[runner] $*"; }

api() {
    curl -fsS --retry 3 --retry-all-errors --retry-delay 2 --max-time 120 \
        -H "Authorization: Bearer $JOB_TOKEN" "$@"
}

content_type() {
    case "$1" in
        *.json) echo application/json ;;
        *.png) echo image/png ;;
        *.jpg | *.jpeg) echo image/jpeg ;;
        *.webm) echo video/webm ;;
        *.zip) echo application/zip ;;
        *.html) echo text/html ;;
        *.txt | *.log | *.md) echo text/plain ;;
        *) echo application/octet-stream ;;
    esac
}

# Regular files only. A symlink could otherwise upload something outside the results folder.
list_results() {
    (cd "$WORKSPACE" && find results -type f -size +0c -size "-${MAX_FILE_BYTES}c" | LC_ALL=C grep -E '^results/[A-Za-z0-9._/-]+$' | grep -v '\.\.' | head -n "$MAX_FILES")
}

upload_results() {
    local files request targets path url
    files="$(list_results)"
    [ -n "$files" ] || return 0

    request="$(while IFS= read -r path; do
        jq -n --arg path "$path" --argjson size "$(stat -c %s "$WORKSPACE/$path")" --arg type "$(content_type "$path")" \
            '{path: $path, sizeBytes: $size, contentType: $type}'
    done <<< "$files" | jq -s '{files: .}')"

    targets="$(api -X POST -H 'Content-Type: application/json' -d "$request" "$JOB_URL/uploads")" || {
        log "Could not get upload addresses"
        return 1
    }
    while IFS=$'\t' read -r path url; do
        curl -fsS --retry 3 --retry-all-errors --max-time 600 -X PUT -T "$WORKSPACE/$path" \
            -H "Content-Type: $(content_type "$path")" "$url" -o /dev/null \
            || log "Could not upload $path"
    done < <(echo "$targets" | jq -r '.uploads[] | [.path, .url] | @tsv')
}

report() {
    [ "$REPORTED" = 0 ] || return 0
    REPORTED=1
    local summary='' body
    if [ -s "$AGENT_RESULT" ]; then
        summary="$(jq -c '{subtype, is_error, num_turns, duration_ms, total_cost_usd}' "$AGENT_RESULT" 2>/dev/null || true)"
    fi
    body="$(list_results | jq -R . | jq -s \
        --arg outcome "$OUTCOME" --argjson exitCode "$EXIT_CODE" --arg summary "$summary" \
        '{outcome: $outcome, exitCode: $exitCode, files: ., agentSummary: $summary}')"
    api -X POST -H 'Content-Type: application/json' -d "$body" "$JOB_URL/callback" -o /dev/null \
        && log "Reported $OUTCOME" \
        || log "Could not report the outcome"
}

finish() {
    upload_results || true
    report
}
trap finish EXIT
trap 'OUTCOME=FAILED; exit 1' INT TERM

mkdir -p "$RESULTS/tests" "$RESULTS/evidence" "$WORKSPACE/repos" "$WORKSPACE/design" "$HOME"
umask 077
cd "$WORKSPACE" || exit 1

# Tools that don't read the standard proxy variables.
if [ -n "${HTTPS_PROXY:-}" ]; then
    PROXY_HOST="$(echo "$HTTPS_PROXY" | sed -E 's#^https?://([^:/]+).*#\1#')"
    PROXY_PORT="$(echo "$HTTPS_PROXY" | sed -E 's#^https?://[^:/]+:([0-9]+).*#\1#')"
    JAVA_PROXY="-Dhttp.proxyHost=$PROXY_HOST -Dhttp.proxyPort=$PROXY_PORT -Dhttps.proxyHost=$PROXY_HOST -Dhttps.proxyPort=$PROXY_PORT"
    export MAVEN_OPTS="$JAVA_PROXY" GRADLE_OPTS="$JAVA_PROXY"
    export SEMGREP_SEND_METRICS=off
fi

log "Fetching the job"
api "$JOB_URL/job" -o "$JOB" || { log "Could not fetch the job"; exit 1; }

log "Cloning repositories"
CLONED='{}'
while IFS= read -r repo; do
    [ -n "$repo" ] || continue
    name="$(echo "$repo" | jq -r '.fullName')"
    url="$(echo "$repo" | jq -r '.cloneUrl')"
    branch="$(echo "$repo" | jq -r '.defaultBranch')"
    token="$(echo "$repo" | jq -r '.cloneToken // empty')"
    target="$WORKSPACE/repos/$(echo "$name" | tr '/' '_')"
    # The token goes in a header for this one command, so it's never stored in .git/config.
    auth=()
    if [ -n "$token" ]; then
        auth=(-c "http.extraHeader=Authorization: Basic $(printf 'x-access-token:%s' "$token" | base64 -w0)")
    fi
    if git "${auth[@]}" clone --quiet --depth 50 --branch "$branch" "$url" "$target"; then
        CLONED="$(echo "$CLONED" | jq --arg name "$name" --arg path "$target" '. + {($name): $path}')"
    else
        log "Could not clone $name; testing continues without it"
    fi
done < <(jq -c '.repos[]' "$JOB")

log "Downloading design frames"
index=0
FRAMES='[]'
while IFS= read -r frame; do
    [ -n "$frame" ] || continue
    index=$((index + 1))
    file="$WORKSPACE/design/$index.png"
    if curl -fsS --retry 3 --retry-all-errors --max-time 300 "$(echo "$frame" | jq -r '.downloadUrl')" -o "$file"; then
        FRAMES="$(echo "$FRAMES" | jq --arg name "$(echo "$frame" | jq -r '.fileName')" --arg path "$file" '. + [{fileName: $name, path: $path}]')"
    fi
done < <(jq -c '.designFrames[]' "$JOB")

# The agent needs logins but has no need for clone tokens or signed URLs, so they're removed.
UPDATED="$(jq --argjson cloned "$CLONED" --argjson frames "$FRAMES" \
    '.repos |= map(del(.cloneToken) | . + {path: $cloned[.fullName]}) | .designFrames = $frames' "$JOB")"
echo "$UPDATED" > "$JOB"

START_COMMAND="$(jq -r '.startCommand // empty' "$JOB")"
if [ -n "$START_COMMAND" ]; then
    FIRST_REPO="$(jq -r '[.repos[].path | select(. != null)][0] // empty' "$JOB")"
    log "Starting the app"
    (cd "${FIRST_REPO:-$WORKSPACE}" && nohup bash -c "$START_COMMAND" > "$RESULTS/app.log" 2>&1 &)
    sleep 10
fi

MINUTES="$(jq -r '.budget.maxMinutes' "$JOB")"
AGENT_MINUTES=$((MINUTES > UPLOAD_RESERVE_MINUTES * 2 ? MINUTES - UPLOAD_RESERVE_MINUTES : MINUTES))

# --bare is required for safety: without it, Claude Code loads hooks, settings and MCP servers from
# the working directory and from anything it reads there, and these repositories are untrusted.
# Skipping permission prompts is safe only because this container is the security boundary.
log "Running the agent for up to $AGENT_MINUTES minutes"
timeout --signal=TERM --kill-after=60 "${AGENT_MINUTES}m" \
    env -u JOB_TOKEN claude --bare -p \
    "Begin the test run described in /workspace/job.json. Your working directory is /workspace." \
    --append-system-prompt-file /opt/runner/prompts/qa-agent.md \
    --output-format json \
    --dangerously-skip-permissions \
    --model "${ANTHROPIC_MODEL:-claude-opus-5-5}" \
    > "$AGENT_RESULT" 2> "$RESULTS/agent-stderr.log" < /dev/null
EXIT_CODE=$?
log "The agent exited with code $EXIT_CODE"

case "$EXIT_CODE" in
    0) OUTCOME=COMPLETED ;;
    124 | 137) OUTCOME=TIMED_OUT ;;
    *) OUTCOME=FAILED ;;
esac

[ -s "$AGENT_RESULT" ] && cp "$AGENT_RESULT" "$RESULTS/agent-result.json"
exit 0
