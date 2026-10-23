#!/usr/bin/env bash
#
# Upstream sync helper for .github/workflows/sync-upstream.yml.
#
#   check         compare upstream main with this fork's main and report the delta
#   sync-main     open the upstream -> main PR; merge it when it is a clean
#                 fast-forward
#   plan-adapter  open the main -> ios-adapter PR (never merged here) and report
#                 whether the merge is clean or conflicting
#   handoff       file the conflict work on the Multica platform, or comment on the
#                 PR when the platform token is absent
#
# Network mutations (push, PR create/merge/comment, issue create, CLI download)
# go through run(), so DRY_RUN=1 only prints them.

# shellcheck disable=SC2016 # printf formats carry Markdown backticks on purpose
set -euo pipefail

MAIN_BRANCH="${MAIN_BRANCH:-main}"
ADAPTER_BRANCH="${ADAPTER_BRANCH:-ios-adapter}"
SYNC_BRANCH_PREFIX="${SYNC_BRANCH_PREFIX:-sync/upstream-main}"
UPSTREAM_REMOTE="${UPSTREAM_REMOTE:-upstream}"
AUTO_MERGE_MAIN="${AUTO_MERGE_MAIN:-true}"
DRY_RUN="${DRY_RUN:-0}"
HANDOFF_ASSIGNEE="${HANDOFF_ASSIGNEE:-Daniel}"
HANDOFF_PROJECT="${HANDOFF_PROJECT:-Kimo-store}"

log() { printf '%s\n' "$*"; }
warn() { printf '::warning::%s\n' "$*"; }
fail() {
  printf '::error::%s\n' "$*" >&2
  exit 1
}

set_output() {
  local name="$1" value="${2-}"
  if [[ -z "${GITHUB_OUTPUT:-}" ]]; then
    printf '%s=%s\n' "$name" "$value"
    return 0
  fi
  local delim="ghadelim_${RANDOM}${RANDOM}"
  {
    printf '%s<<%s\n' "$name" "$delim"
    printf '%s\n' "$value"
    printf '%s\n' "$delim"
  } >>"$GITHUB_OUTPUT"
}

run() {
  if [[ "$DRY_RUN" == "1" ]]; then
    log "[dry-run] $*"
    return 0
  fi
  "$@"
}

work_file() { printf '%s/upstream-sync-%s' "${RUNNER_TEMP:-/tmp}" "$1"; }

require_repo() {
  git rev-parse --is-inside-work-tree >/dev/null 2>&1 ||
    fail "run this from the komi-store checkout"
}

resolve_upstream() {
  if [[ -n "${UPSTREAM_REPO:-}" ]]; then
    printf '%s' "$UPSTREAM_REPO"
    return 0
  fi
  local repo="${GITHUB_REPOSITORY:-}" parent
  if [[ -z "$repo" ]]; then
    repo=$(gh repo view --json nameWithOwner --jq .nameWithOwner)
  fi
  parent=$(gh api "repos/${repo}" --jq '.parent.full_name // empty' 2>/dev/null || true)
  [[ -n "$parent" ]] ||
    fail "cannot resolve the upstream repository of ${repo}; pass upstream_repo"
  printf '%s' "$parent"
}

sync_upstream_remote() {
  local upstream="$1" url="https://github.com/${1}.git"
  if git remote get-url "$UPSTREAM_REMOTE" >/dev/null 2>&1; then
    git remote set-url "$UPSTREAM_REMOTE" "$url"
  else
    git remote add "$UPSTREAM_REMOTE" "$url"
  fi
  git fetch --no-tags --prune "$UPSTREAM_REMOTE" \
    "+refs/heads/${MAIN_BRANCH}:refs/remotes/${UPSTREAM_REMOTE}/${MAIN_BRANCH}"
}

fetch_origin() {
  # Depth is explicitly dropped: the ancestor probe must see the full shared
  # history of both branches, not just the tips a shallow checkout keeps.
  local refspecs=()
  local fetch_ref
  for fetch_ref in "$@"; do
    refspecs+=("+refs/heads/${fetch_ref}:refs/remotes/origin/${fetch_ref}")
  done
  git fetch --no-tags origin "${refspecs[@]}"
  if [[ "$(git rev-parse --is-shallow-repository)" == "true" ]] && \
    ! git merge-base "refs/remotes/origin/${1}" "refs/remotes/origin/${2:-$1}" >/dev/null 2>&1; then
    git fetch --no-tags --unshallow origin "${refspecs[@]}"
  fi
  if ! git merge-base "refs/remotes/origin/${1}" "refs/remotes/origin/${2:-$1}" >/dev/null 2>&1; then
    fail "fetch_origin: cannot find a merge base for $*"
  fi
}

# Prints "<fork-only> <upstream-only>" commits for the two mains.
count_delta() {
  git rev-list --left-right --count \
    "origin/${MAIN_BRANCH}...${UPSTREAM_REMOTE}/${MAIN_BRANCH}"
}

latest_upstream_release() {
  gh api "repos/${1}/releases/latest" --jq '.tag_name // empty' 2>/dev/null || true
}

# has_marker PR MARKER: true when the PR already carries a bot comment with the
# given sync-upstream marker, so repeated daily runs stay quiet. A failed query
# must never look like "no marker", otherwise a network blip re-posts the
# comment.
has_marker() {
  local pr="$1" marker="$2"
  [[ "$pr" == "dry-run" || -z "$pr" ]] && return 1
  local out
  if ! out=$(gh pr view "$pr" --json comments --jq '.comments[].body'); then
    fail "has_marker: cannot read comments on PR #${pr}"
  fi
  grep -qF "sync-upstream:${marker}" <<<"$out"
}

comment_once() {
  local pr="$1" marker="$2" body="$3"
  if [[ "$pr" == "dry-run" || -z "$pr" ]]; then
    log "[dry-run] would comment on the PR (${marker})"
    return 0
  fi
  if has_marker "$pr" "$marker"; then
    log "PR #${pr} already reports ${marker}; skipping the comment"
    return 0
  fi
  run gh pr comment "$pr" --body-file "$body"
}

cmd_check() {
  require_repo
  local upstream ahead behind release
  upstream=$(resolve_upstream)
  sync_upstream_remote "$upstream"
  fetch_origin "$MAIN_BRANCH"

  read -r ahead behind < <(count_delta)
  release=$(latest_upstream_release "$upstream")

  set_output upstream_repo "$upstream"
  set_output ahead "$ahead"
  set_output behind "$behind"
  set_output latest_release "$release"
  if [[ "$behind" == "0" ]]; then
    set_output up_to_date true
  else
    set_output up_to_date false
  fi
  if [[ "$ahead" == "0" && "$behind" != "0" ]]; then
    set_output fast_forward true
  else
    set_output fast_forward false
  fi

  log "upstream ${upstream}/${MAIN_BRANCH}: ${behind} commit(s) not in the fork"
  log "fork ${MAIN_BRANCH}: ${ahead} commit(s) not upstream"
  log "upstream latest release: ${release:-unknown}"
}

pr_body_sync_main() {
  local upstream="$1" sha="$2" behind="$3" ahead="$4" state="$5" body
  body=$(work_file "main-pr.md")
  {
    printf 'Sync `%s` `%s` into the fork `%s`.\n\n' "$upstream" "$MAIN_BRANCH" "$MAIN_BRANCH"
    printf '| field | value |\n|---|---|\n'
    printf '| upstream tip | `%s` |\n' "${sha:0:12}"
    printf '| commits from upstream | %s |\n' "$behind"
    printf '| fork-only commits | %s |\n' "$ahead"
    printf '| local merge | %s |\n\n' "$state"
    if [[ "$state" == "conflict" ]]; then
      printf 'The test merge conflicts with fork-only commits, so this PR is left for a\n'
      printf 'human to resolve. Resolve the conflicts on this branch and merge it.\n\n'
    elif [[ "$ahead" == "0" ]]; then
      printf 'Fast-forward: the fork has no commits of its own on `%s`, so this branch is\n' "$MAIN_BRANCH"
      printf 'exactly the upstream tip and the workflow merges it automatically.\n\n'
    else
      printf 'The fork carries its own commits, so this is a merge commit and it is left\n'
      printf 'open for review. Upstream commits are never rewritten.\n\n'
    fi
    printf '%s\n\n' '----'
    printf 'Generated by `.github/workflows/sync-upstream.yml` (`sync-upstream.sh sync-main`).\n'
  } >"$body"
  printf '%s' "$body"
}

cmd_sync_main() {
  require_repo
  local upstream ahead behind sha short branch pr body merged=0

  upstream=$(resolve_upstream)
  sync_upstream_remote "$upstream"
  fetch_origin "$MAIN_BRANCH"
  read -r ahead behind < <(count_delta)

  set_output upstream_repo "$upstream"
  set_output ahead "$ahead"
  set_output behind "$behind"

  if [[ "$behind" == "0" ]]; then
    log "${MAIN_BRANCH} already contains ${upstream}/${MAIN_BRANCH}; nothing to sync"
    set_output main_moved false
    set_output merge_state up-to-date
    set_output pr ""
    return 0
  fi

  sha=$(git rev-parse "${UPSTREAM_REMOTE}/${MAIN_BRANCH}")
  short=${sha:0:7}
  branch="${SYNC_BRANCH_PREFIX}-${short}"
  git switch --force --force-create "$branch" "origin/${MAIN_BRANCH}" >/dev/null

  local state=clean
  if [[ "$ahead" == "0" ]]; then
    git reset --hard "${UPSTREAM_REMOTE}/${MAIN_BRANCH}" >/dev/null
  elif ! git merge --no-ff --no-edit \
    -m "merge: ${upstream} ${MAIN_BRANCH} ${short} into ${MAIN_BRANCH}" \
    "${UPSTREAM_REMOTE}/${MAIN_BRANCH}" >/dev/null 2>&1; then
    state=conflict
    git merge --abort || true
    git reset --hard "${UPSTREAM_REMOTE}/${MAIN_BRANCH}" >/dev/null
    log "test merge conflicts; the PR is left for a human to resolve"
  fi

  if git ls-remote --exit-code --heads origin "refs/heads/${branch}" >/dev/null 2>&1; then
    log "branch ${branch} exists on origin; leaving it untouched"
  else
    run git push origin "refs/heads/${branch}:refs/heads/${branch}"
  fi

  if ! pr=$(gh pr list --state open --base "$MAIN_BRANCH" --head "$branch" \
    --json number --jq '.[0].number // empty'); then
    fail "cannot list open PRs for ${MAIN_BRANCH} <- ${branch}"
  fi
  if [[ -z "$pr" ]]; then
    body=$(pr_body_sync_main "$upstream" "$sha" "$behind" "$ahead" "$state")
    if [[ "$DRY_RUN" == "1" ]]; then
      log "[dry-run] gh pr create --base ${MAIN_BRANCH} --head ${branch}"
      pr=dry-run
    else
      pr=$(gh pr create --base "$MAIN_BRANCH" --head "$branch" \
        --title "chore(sync): upstream ${short} into ${MAIN_BRANCH}" \
        --body-file "$body")
      pr=${pr##*/}
      log "opened PR #${pr} for ${branch}"
    fi
  else
    log "reusing open PR #${pr} for ${branch}"
  fi

  if [[ "$state" == "clean" && "$ahead" == "0" && "$AUTO_MERGE_MAIN" == "true" ]]; then
    if [[ "$DRY_RUN" == "1" ]]; then
      log "[dry-run] gh pr merge ${pr} --merge"
    elif gh pr merge "$pr" --merge >/dev/null 2>&1; then
      merged=1
      log "merged PR #${pr}; ${MAIN_BRANCH} now matches upstream ${short}"
    else
      warn "could not merge PR #${pr}; leaving it open"
    fi
  fi

  if [[ "$state" == "conflict" ]]; then
    local note
    note=$(work_file "main-conflict.md")
    {
      printf 'The test merge of `%s` into `%s` conflicts with commits that only the fork has.\n\n' "$upstream" "$MAIN_BRANCH"
      printf 'Nothing was pushed over your work: resolve the conflicts on this branch, or\n'
      printf 'handle the sync by hand if the fork-only commits no longer belong on `%s`.\n\n' "$MAIN_BRANCH"
      printf '<!-- sync-upstream:main-conflict -->\n'
    } >"$note"
    comment_once "$pr" main-conflict "$note"
  fi

  set_output pr "$pr"
  set_output merge_state "$state"
  if [[ "$merged" == "1" ]]; then
    set_output main_moved true
  else
    set_output main_moved false
  fi
}

pr_body_adapter() {
  local main_sha="$1" adapter_sha="$2" clean="$3" conflicts="$4" body
  body=$(work_file "adapter-pr.md")
  {
    printf 'Merge `%s` into `%s`.\n\n' "$MAIN_BRANCH" "$ADAPTER_BRANCH"
    printf 'Opened by `.github/workflows/sync-upstream.yml` when `%s` moved ahead of\n' "$MAIN_BRANCH"
    printf '`%s`. It is never merged automatically: `%s` carries the iOS adapter work\n' "$ADAPTER_BRANCH" "$ADAPTER_BRANCH"
    printf 'and the previous upstream sync needed 31 conflict resolutions.\n\n'
    printf '| field | value |\n|---|---|\n'
    printf '| `%s` tip | `%s` |\n' "$MAIN_BRANCH" "${main_sha:0:12}"
    printf '| `%s` tip | `%s` |\n' "$ADAPTER_BRANCH" "${adapter_sha:0:12}"
    printf '| test merge | %s |\n' "$(if [[ "$clean" == "true" ]]; then printf 'clean'; else printf 'conflicting'; fi)"
    if [[ "$clean" == "true" ]]; then
      printf '\nVerification runs `./gradlew compileKotlinJvm compileDebugKotlinAndroid jvmTest --continue`\n'
      printf 'on the merged tree. Review the adapter changes before merging this PR.\n'
    else
      printf '\nConflicting files (%s):\n\n```\n%s\n```\n\n' \
        "$(printf '%s\n' "$conflicts" | grep -c . || true)" "$conflicts"
      printf 'The conflicts are listed above. Resolution is filed on the team platform, or\n'
      printf 'flagged on this PR when the platform token is not configured.\n'
    fi
    printf '\n----\n\n'
    printf 'Generated by `.github/workflows/sync-upstream.yml` (`sync-upstream.sh plan-adapter`).\n'
  } >"$body"
  printf '%s' "$body"
}

cmd_plan_adapter() {
  require_repo
  local main_sha adapter_sha clean=true conflicts="" pr body

  fetch_origin "$MAIN_BRANCH" "$ADAPTER_BRANCH"
  main_sha=$(git rev-parse "origin/${MAIN_BRANCH}")
  adapter_sha=$(git rev-parse "origin/${ADAPTER_BRANCH}")

  if git merge-base --is-ancestor "origin/${MAIN_BRANCH}" "origin/${ADAPTER_BRANCH}"; then
    log "${ADAPTER_BRANCH} already contains ${MAIN_BRANCH}; nothing to merge"
    : >"$(work_file 'conflicts.txt')"
    set_output moved false
    set_output clean true
    set_output conflicts ""
    set_output pr ""
    return 0
  fi

  git switch --force --force-create sync-adapter-probe "origin/${ADAPTER_BRANCH}" >/dev/null
  if git merge --no-ff --no-edit "origin/${MAIN_BRANCH}" >/dev/null 2>&1; then
    log "${MAIN_BRANCH} merges cleanly into ${ADAPTER_BRANCH}"
  else
    clean=false
    conflicts=$(git diff --name-only --diff-filter=U | sort)
    git merge --abort || true
    log "test merge of ${MAIN_BRANCH} into ${ADAPTER_BRANCH} conflicts:"
    printf '%s\n' "$conflicts" | sed 's/^/  /'
  fi

  if [[ -n "$conflicts" ]]; then
    printf '%s\n' "$conflicts" >"$(work_file 'conflicts.txt')"
  else
    : >"$(work_file 'conflicts.txt')"
  fi

  if ! pr=$(gh pr list --state open --base "$ADAPTER_BRANCH" --head "$MAIN_BRANCH" \
    --json number --jq '.[0].number // empty'); then
    fail "cannot list open PRs for ${ADAPTER_BRANCH} <- ${MAIN_BRANCH}"
  fi
  if [[ -z "$pr" ]]; then
    body=$(pr_body_adapter "$main_sha" "$adapter_sha" "$clean" "$conflicts")
    if [[ "$DRY_RUN" == "1" ]]; then
      log "[dry-run] gh pr create --base ${ADAPTER_BRANCH} --head ${MAIN_BRANCH}"
      pr=dry-run
    else
      pr=$(gh pr create --base "$ADAPTER_BRANCH" --head "$MAIN_BRANCH" \
        --title "chore(sync): ${MAIN_BRANCH} into ${ADAPTER_BRANCH}" \
        --body-file "$body")
      pr=${pr##*/}
      log "opened PR #${pr}: ${MAIN_BRANCH} into ${ADAPTER_BRANCH}"
    fi
  else
    log "reusing open PR #${pr}: ${MAIN_BRANCH} into ${ADAPTER_BRANCH}"
  fi

  local note
  if [[ "$clean" == "true" ]]; then
    note=$(work_file "adapter-clean.md")
    {
      printf 'The test merge of `%s` into `%s` is clean and verification is running in\n' "$MAIN_BRANCH" "$ADAPTER_BRANCH"
      printf '[run %s](%s/%s/actions/runs/%s). This PR is never merged automatically.\n\n' \
        "${GITHUB_RUN_ID:-unknown}" "${GITHUB_SERVER_URL:-https://github.com}" \
        "${GITHUB_REPOSITORY:-}" "${GITHUB_RUN_ID:-0}"
      printf '<!-- sync-upstream:adapter-clean -->\n'
    } >"$note"
    comment_once "$pr" adapter-clean "$note"
  fi

  set_output moved true
  set_output clean "$clean"
  set_output conflicts "$conflicts"
  set_output pr "$pr"
}

multica_list_ids() { # <subcommand> <json key holding the list> <field> <value>
  local cmd="$1" key="$2" field="$3" value="$4"
  multica "$cmd" list --output json 2>/dev/null |
    python3 -c '
import json, sys
key, field, value = sys.argv[1], sys.argv[2], sys.argv[3]
data = json.load(sys.stdin)
items = data if isinstance(data, list) else data.get(key, [])
for item in items:
    if str(item.get(field) or "") == value:
        print(item["id"])
        break
' "$key" "$field" "$value"
}

handoff_issue_body() { # <pr> <conflicts>
  local pr="$1" conflicts="$2" body="$3"
  {
    printf '上游同步自动化在合并 `main` 到 `ios-adapter` 时检测到冲突，需要人工解冲突并适配 iOS。\n\n'
    printf '## 上下文\n\n'
    printf -- '- 合并 PR：#%s（`%s` → `%s`）%s/%s/pull/%s\n' \
      "$pr" "$MAIN_BRANCH" "$ADAPTER_BRANCH" "${GITHUB_SERVER_URL:-https://github.com}" \
      "${GITHUB_REPOSITORY:-}" "$pr"
    printf -- '- 自动化工作流：%s/%s/actions/runs/%s\n' \
      "${GITHUB_SERVER_URL:-https://github.com}" "${GITHUB_REPOSITORY:-}" "${GITHUB_RUN_ID:-unknown}"
    printf -- '- 上游仓库：%s\n' "${UPSTREAM_REPO:-komi-store/komi-store}"
    printf -- '- 触发方式：%s\n\n' "${GITHUB_EVENT_NAME:-workflow_dispatch}"
    printf '## 冲突文件（%s 个）\n\n```\n%s\n```\n\n' \
      "$(printf '%s\n' "$conflicts" | grep -c . || true)" "$conflicts"
    printf '## 任务链\n\n'
    printf -- '1. Daniel：在 PR #%s 上解冲突并完成 iOS 适配（只 push 到该 PR 的 head 分支，不 push `%s`）。\n' "$pr" "$ADAPTER_BRANCH"
    printf -- '2. Tony：评审解冲突与适配的正确性。\n'
    printf -- '3. Jim：验证三端编译、测试与 iOS 行为无回退。\n\n'
    printf '## 红线\n\n'
    printf -- '- 不自动合并 `%s` → `%s`，合入由人决定。\n' "$MAIN_BRANCH" "$ADAPTER_BRANCH"
    printf -- '- 不改写上游提交、不 rebase。\n'
    printf -- '- 本单由工作流自动创建，请按团队状态纪律推进（进行中 / 审核中，交付时 @Mike）。\n'
  } >"$body"
}

cmd_handoff() {
  local pr="${1:-}" conflicts degrade_reason
  # The plan step already outputs the conflict list; the file copy kept by
  # plan-adapter is only a local dry-run backup.
  conflicts="${CONFLICTS:-$(cat "$(work_file 'conflicts.txt')" 2>/dev/null || true)}"
  if [[ -z "$pr" ]]; then
    if ! pr=$(gh pr list --state open --base "$ADAPTER_BRANCH" --head "$MAIN_BRANCH" \
      --json number --jq '.[0].number // empty'); then
      fail "cannot list open PRs for ${ADAPTER_BRANCH} <- ${MAIN_BRANCH}"
    fi
  fi

  if has_marker "$pr" handoff; then
    log "PR #${pr} already carries a platform handoff; skipping"
    set_output handoff existing
    return 0
  fi

  degrade_reason="the platform handoff is not configured for this repository"
  local can_file=true
  if [[ -z "${MULTICA_TOKEN:-}" ]]; then
    degrade_reason="this repository has no MULTICA_TOKEN secret"
    can_file=false
  elif ! command -v multica >/dev/null 2>&1; then
    degrade_reason="the multica CLI is missing on the runner"
    can_file=false
  fi

  if [[ "$can_file" == "true" ]]; then
    log "filing the conflict work on the team platform"
    local workspace project_id assignee_id title body out ident
    workspace="${MULTICA_WORKSPACE_ID:-}"
    if [[ -z "$workspace" ]]; then
      workspace=$(multica workspace list --output json 2>/dev/null |
        python3 -c 'import json,sys; d=json.load(sys.stdin); ws=d if isinstance(d,list) else d.get("workspaces",[]); print(ws[0]["id"] if ws else "")' || true)
    fi
    if [[ -z "$workspace" ]]; then
      degrade_reason="no Multica workspace was resolved for the token"
      can_file=false
    fi
    if [[ "$can_file" == "true" ]]; then
      export MULTICA_WORKSPACE_ID="$workspace"
      project_id=$(multica_list_ids project projects title "$HANDOFF_PROJECT" || true)
      assignee_id=$(multica_list_ids agent agents name "$HANDOFF_ASSIGNEE" || true)
      if [[ -z "$assignee_id" ]]; then
        degrade_reason="the platform agent ${HANDOFF_ASSIGNEE} was not found"
        can_file=false
      fi
    fi

    if [[ "$can_file" == "true" ]]; then
      title="上游同步冲突：${MAIN_BRANCH} → ${ADAPTER_BRANCH}（PR #${pr}）"
      body="./.sync-upstream-handoff-${RANDOM}.md"
      handoff_issue_body "$pr" "$conflicts" "$body"
      if out=$(multica issue create --title "$title" --description-file "$body" \
        --assignee-id "$assignee_id" --status todo \
        ${project_id:+--project "$project_id"} --output json); then
        rm -f "$body"
        ident=$(printf '%s' "$out" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("identifier",""))' || true)
        log "filed ${ident:-a platform issue} for the conflict resolution"
        local ack
        ack=$(work_file "handoff-ack.md")
        {
          printf 'Filed %s on the team platform for conflict resolution\n' "${ident:-an issue}"
          printf '(Daniel resolves, Tony reviews, Jim verifies). The merge into `%s` stays manual.\n\n' "$ADAPTER_BRANCH"
          printf '<!-- sync-upstream:handoff -->\n'
        } >"$ack"
        comment_once "$pr" handoff "$ack"
        set_output handoff issue
        set_output handoff_issue "$ident"
        return 0
      fi
      rm -f "$body"
      degrade_reason="the platform issue could not be created"
      warn "multica issue create failed; degrading to a PR comment"
      log "$out"
    fi
  fi

  warn "platform handoff skipped: ${degrade_reason}"
  local fallback
  fallback=$(work_file "handoff-fallback.md")
  {
    printf '## Needs manual conflict resolution (需人工解冲突)\n\n'
    printf '`%s` no longer merges into `%s` cleanly. Conflicting files:\n\n' \
      "$MAIN_BRANCH" "$ADAPTER_BRANCH"
    printf '```\n%s\n```\n\n' "$conflicts"
    printf 'The platform handoff was skipped because %s. Resolve the conflicts on this\n' "$degrade_reason"
    printf 'PR, or fix the automation configuration so the next run files the work for\n'
    printf 'the team.\n\n'
    printf '<!-- sync-upstream:handoff-fallback -->\n'
  } >"$fallback"
  comment_once "$pr" handoff-fallback "$fallback"
  set_output handoff pr-comment
  set_output handoff_issue ""
}

cmd_verify_hint() {
  # Kept for local dry runs: print the commands the verification job runs.
  printf 'git switch --force-create adapter-verify origin/%s\n' "$ADAPTER_BRANCH"
  printf 'git merge --no-ff --no-edit origin/%s\n' "$MAIN_BRANCH"
  printf './gradlew compileKotlinJvm compileDebugKotlinAndroid jvmTest --continue\n'
}

main() {
  local cmd="${1:-}"
  shift || true
  case "$cmd" in
  check) cmd_check ;;
  sync-main) cmd_sync_main ;;
  plan-adapter) cmd_plan_adapter ;;
  handoff) cmd_handoff "$@" ;;
  verify-hint) cmd_verify_hint ;;
  *)
    fail "usage: $0 {check|sync-main|plan-adapter|handoff|verify-hint}"
    ;;
  esac
}

main "$@"
