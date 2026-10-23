---
name: github-automation
description: This skill should be used when the user wants to automate GitHub work - creating or triaging issues, opening and reviewing pull requests, managing branches, checking CI/Actions status, or searching code/commits across repositories. Applies whenever a task mentions GitHub, a repo owner/name, a PR or issue number/link, or "check CI" / "open a PR" / "merge this" style requests.
---

# GitHub Automation

Automates the common GitHub workflows a developer repeats by hand: issue triage, PR creation
and review, branch/CI management, and code search - across one or many repositories.

## Picking an interface

Two interfaces exist for GitHub work; use whichever is actually available in the current
session, in this order of preference:

1. **GitHub MCP server tools** (names like `mcp__github__*` or similar) - if present, prefer
   these. They return structured JSON, support pagination, and don't require separate auth
   setup.
2. **`gh` CLI** - the portable fallback available in almost every terminal/Claude Code
   environment. Check it's authenticated first: `gh auth status`. If not, run `gh auth login`
   (interactive) or point to a token via `GH_TOKEN`/`GITHUB_TOKEN`.

Never shell out to raw GitHub REST calls with `curl` when either of the above is available -
they handle auth, pagination, and rate limits correctly.

Before doing anything else in a new repo, identify the current user/context:
`gh api user --jq .login` (CLI) or the MCP server's "get authenticated user" tool.

## Core workflows

### Issues

- List/search: `gh issue list --repo OWNER/REPO --state open --label bug`
- Search across repos by text/filters: `gh search issues "OWNER/REPO is:open label:bug"`
- Before filing a new issue, search first to avoid duplicates.
- Create: `gh issue create --repo OWNER/REPO --title "..." --body "..."`
- Close with a reason: `gh issue close 123 --repo OWNER/REPO --reason "not planned"`
- Comment: `gh issue comment 123 --repo OWNER/REPO --body "..."`

### Pull requests

- Check for an existing PR template first: `.github/pull_request_template.md`,
  `.github/PULL_REQUEST_TEMPLATE.md`, or root `PULL_REQUEST_TEMPLATE.md`. If one exists, mirror
  its section headings in the PR body instead of writing free-form.
- Create: `gh pr create --repo OWNER/REPO --title "..." --body "..." --base main --head branch`
- List / filter: `gh pr list --repo OWNER/REPO --state open --author @me`
- Read a PR fully before acting: `gh pr view 42 --repo OWNER/REPO --json title,body,files,reviews,statusCheckRollup`
- Diff: `gh pr diff 42 --repo OWNER/REPO`
- Review: `gh pr review 42 --repo OWNER/REPO --approve|--request-changes|--comment --body "..."`
- Merge only once CI is green and required approvals are in: `gh pr merge 42 --repo OWNER/REPO --squash` (or `--merge`/`--rebase` per the repo's convention - check past merged PRs if unsure)
- Never force-push to a shared/default branch, never skip hooks (`--no-verify`), never rewrite history on a branch you did not create alone.

### Branches & commits

- Create a branch off the right base: `git fetch origin BASE && git checkout -B NEW_BRANCH origin/BASE`
- Keep commits scoped and descriptive; do not bundle unrelated changes.
- Push: `git push -u origin BRANCH_NAME`
- Resolve merge conflicts with a merge commit unless the repo's contributing guide says otherwise; never discard the other side's changes without checking what they were for.

### CI / GitHub Actions

- List recent runs: `gh run list --repo OWNER/REPO --branch BRANCH --limit 10`
- Inspect a failing run: `gh run view RUN_ID --repo OWNER/REPO --log-failed`
- Re-run only failed jobs after a genuine fix: `gh run rerun RUN_ID --failed`
- Distinguish infra flakes (checkout/install failures, runner loss) from real failures before
  re-running - re-running a run that fails deterministically wastes CI minutes and hides the
  real bug.
- A check failing identically on the base/default branch is not this branch's fault - say so
  rather than trying to "fix" unrelated code.

### Code & commit search

- Code: `gh search code "SYMBOL_OR_STRING" --repo OWNER/REPO` (or `--owner ORG` across an org)
- Commits: `gh search commits "MESSAGE_FRAGMENT" --repo OWNER/REPO`
- Prefer scoping search to the specific repo/org over a global search - faster and more relevant.

### Releases & tags

- `gh release list --repo OWNER/REPO`
- `gh release create TAG --repo OWNER/REPO --title "..." --notes "..."`

## Guardrails (always apply)

- Read before you write: fetch the current state of an issue/PR/branch before commenting on
  or modifying it - stale assumptions produce wrong actions.
- Confirm before anything hard to reverse or externally visible: force-push, deleting a
  branch, closing someone else's issue/PR, merging to a protected branch, or posting a comment
  visible to a wider team - unless the user has already authorized that specific action.
- Never commit secrets (tokens, `.env` contents, credentials) even if a file with a
  plausible-looking name is staged - check contents, not just the filename.
- Attribute automated comments/PRs clearly if the workflow posts on the user's behalf, so
  human collaborators know an agent made the change.
- When multiple repos are in scope, always pass `--repo OWNER/REPO` explicitly rather than
  relying on the current directory's remote - avoids acting on the wrong repository.

## Quick reference

| Task | Command |
|---|---|
| Auth check | `gh auth status` |
| List open PRs authored by me | `gh pr list --author @me --state open` |
| View PR with checks | `gh pr view N --json statusCheckRollup` |
| Approve a PR | `gh pr review N --approve` |
| Failed CI logs | `gh run view RUN_ID --log-failed` |
| Search code in a repo | `gh search code "term" --repo OWNER/REPO` |
| Create issue | `gh issue create --title "..." --body "..."` |
| Merge (squash) | `gh pr merge N --squash` |
