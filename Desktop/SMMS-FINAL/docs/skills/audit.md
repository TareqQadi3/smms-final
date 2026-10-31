# Skill source audit

Evaluation of third-party skill candidates for SMMS. Research only: nothing in this document was installed by the evaluator. Installs (if any) are recorded in [README.md](README.md); how skills were applied is in [application-report.md](application-report.md).

- Evaluated: 2026-10-23, by the Opus planning agent, from shallow clones in the remote sandbox scratchpad.
- All third-party content was treated as untrusted data. Security checks: grep for `curl|sh`, `rm -rf`, env/secret reads, network endpoints, hooks/settings files, prompt-injection phrasing, plus manual reading of every SKILL.md frontmatter and scripts directory.
- **Scope honesty:** any install performed from this audit lands in the ephemeral Claude Code Remote sandbox (`/root/.claude/skills`). It is **not** the user's local Claude Code and **not** the GitHub Codespace. Local install commands are given at the end.

## SMMS context used for relevance

- Backend: Spring Boot 3.3 / Java 21. Frontend: Next.js 14, Arabic RTL, Tailwind + shadcn/ui.
- Database: PostgreSQL 16. Tenant isolation is **application-level** (`tenant_id` + `TenantSecurityService`). **No PostgreSQL RLS exists**: `grep -niE "ROW LEVEL SECURITY|CREATE POLICY" scripts/*.sql` returns nothing.

## 1. jakubkrehel/skills

| Field | Value |
|---|---|
| Commit | `4c66b1fa86e9ab70187b43d7da9b6fdb29f2ef63` (2026-10-12) |
| License | MIT (`LICENSE`); plugin manifest `interfaces` v1.13.2 |
| Format | Claude Code compatible: `skills/<name>/SKILL.md` with `name` + `description` frontmatter; also packaged as a plugin (`.claude-plugin/`) |
| Scripts / hooks | No hooks and no settings files. `agents/openai.yaml` files are inert metadata for other agents. `technical-drawing/scripts` exists but is not selected |
| Network | No URLs inside the selected skills |
| Security | Clean: no secret reads, no shell pipelines, no injection phrasing |
| Maintenance | Active (commit 11 days before evaluation) |

| Skill | Description (abridged) | Relevance |
|---|---|---|
| better-ui | Surfaces, icons, motion polish | Medium |
| better-typography | Type scale, spacing, wrapping, punctuation; covers RTL | High (Arabic text) |
| better-colors | Palettes, semantic tokens, contrast | Medium |
| better-accessibility | WCAG 2.2: focus, ARIA, forms, screen readers; covers RTL | High |
| better-layout | Grouping, reading order, responsive, mirrored/translated layouts | High (RTL + iPhone) |
| better-writing | Interface copy, errors, empty states | Medium (Arabic copy) |
| better-interface | Runs all six `better-*` reviews as one | High; **depends on the six** |
| interface-review | Reviews a branch/PR for interface regressions; references the `better-*` skills | High |
| state-machine, variant, break, build-design, explain-interface, technical-drawing | Prototyping, Figma build, effect explanation, CAD drawings | Low for SMMS |

All eight previously named UI skills (better-ui, better-typography, better-colors, better-accessibility, better-layout, better-interface, interface-review, better-writing) are present **only in this repo**. None was present in this environment (`/root/.claude/skills` had arena, session-start-hook and synced).

## 2. secureIO-GmbH/security-skills

| Field | Value |
|---|---|
| Commit | `49f63fb87a4ac9d8045e85add9e12ab4c8376b80` (2026-03-27) |
| License | MIT (`LICENSE`) |
| Format | Compatible: `skills/<name>/SKILL.md` with name + description; plugin manifest present |
| Skills | `security-audit` (orchestrates the other three); `threat-model` (STRIDE + OWASP API Top 10 references); `security-scan` (SAST semgrep/opengrep, secrets trufflehog, SCA/IaC trivy/grype); `security-arch-review` (OWASP Proactive Controls) |
| Scripts / hooks | None bundled. `security-scan` shells out to external scanners if installed, and skips each with an install message if missing. `brew install trivy/grype` appears only as a suggestion |
| Writes | Reports go to `security-audit/*.md` in the target repo (e.g. `security-audit/threat-model.md`) |
| Network | Only through the external scanners, if present. No bundled endpoints |
| Security | Clean. `os.environ` and webhook mentions are OWASP teaching examples in the reference docs |
| Environment fit | semgrep, trivy, trufflehog, gitleaks and grype are **all missing** in this sandbox, so `security-scan` would degrade to "skipped" for all four tools. `threat-model` and `security-arch-review` are code-reading skills and work without tools |
| Maintenance | Last commit 7 months before evaluation (stable, low activity) |

## 3. ekhorkov/rls-audit

| Field | Value |
|---|---|
| Commit | `73c87c9df8ffb4ee76a372c26f9328717b12d119` (2026-04-20) |
| License | **None**: no LICENSE file, so default copyright applies and there is no redistribution right |
| Format | Compatible (`skills/rls-audit/SKILL.md`) |
| Skill | `rls-audit`: audits PostgreSQL/Supabase RLS policies; discovers schema via `supabase/migrations` |
| Security | Clean (70-line markdown, no scripts) |
| Relevance | **Low**: SMMS has no RLS policies and no Supabase; isolation is app-level. Its tenant-leak checklist overlaps with the project skill `smms-tenant-isolation`, which targets SMMS's real mechanism |

## 4. nicohodt/claude-code-ui-ux-skill

| Field | Value |
|---|---|
| Commit | `29dc4c56aa862226a6ff053ff4255bbed062af81` (2026-08-02) |
| License | MIT (`LICENSE`; `ui-styling/LICENSE.txt`) |
| Format | Compatible: `.claude/skills/<name>/SKILL.md`. Also ships a CLI, a `stack/.claude/settings.json`, and Python scripts with data files |
| Skills | `ui-ux-pro-max` (searchable local style/palette/font DB via Python scripts); `ui-styling` (shadcn/Tailwind guidance); `design-system`; `design` (logo, CIP, banners via **Gemini API**); `brand`; `slides`; `banner-design` |
| Scripts | `design/scripts/{logo,cip,icon}/generate.py` auto-load `.env` files (skill dir, `~/.claude/skills/.env`, `~/.claude/.env`) into the environment and call Google Gemini with an API key. They reference Pexels images and Google Fonts |
| Security | No malicious code found, but: (1) silent `.env` loading from shared locations; (2) prompts and assets sent to a third-party AI API; (3) a large unreviewed surface (CLI, data, settings.json). Not appropriate near a hospital project's secrets |
| Overlap | `ui-styling`/`design-system` overlap with jakubkrehel `better-*` and the project skill `smms-rtl-ui-review`. Design/brand/banner/slides are marketing tools; brag/HyperFrames are being evaluated for the video work |

## Decision

| Skill | Decision | Reason |
|---|---|---|
| better-accessibility | **Install** | WCAG 2.2 + RTL guidance directly needed for the Arabic UI |
| better-layout | **Install** | Mirrored/RTL and responsive (iPhone) layout review |
| better-typography | **Install** | Arabic text rendering, wrapping, punctuation |
| better-colors | **Install** | Contrast checks; required by better-interface |
| better-writing | **Install** | Arabic/English UI copy and error messages; required by better-interface |
| better-ui | **Install** | Required by better-interface; low risk |
| better-interface | **Install** | One-shot combined UI review (needs the six above) |
| interface-review | **Install** | Branch/diff UI regression review, useful before merges |
| threat-model | **Install** | STRIDE threat model of SMMS; tool-free |
| security-arch-review | **Install** | OWASP Proactive Controls code review; tool-free |
| security-scan | **Defer** | Useful only once semgrep/trufflehog/trivy are available; all are missing here |
| security-audit | **Defer** | Orchestrator that depends on security-scan; install it together with security-scan later |
| state-machine, variant, break, build-design, explain-interface, technical-drawing | **Reject** | Not relevant to SMMS review work |
| rls-audit | **Reject** | No license (no redistribution right); RLS/Supabase-specific; SMMS uses app-level isolation (covered by `smms-tenant-isolation`) |
| ui-ux-pro-max, ui-styling, design-system, design, brand, slides, banner-design | **Reject** | Overlap with jakubkrehel + project skills; `design` scripts auto-load `.env` files and send data to Gemini; large unreviewed surface |

## Install commands (for the Sonnet execution agent, sandbox only)

Copy only the selected skill directories and keep attribution. Check for collisions first.

```bash
SRC=/tmp/claude-0/-home-user-smms-final/cb542d5f-a4dc-51fb-a70b-71e2e80e9c99/scratchpad/skill-candidates
DST=/root/.claude/skills
for s in better-accessibility better-layout better-typography better-colors better-writing better-ui better-interface interface-review threat-model security-arch-review; do
  [ -e "$DST/$s" ] && { echo "COLLISION: $s already exists - stop"; exit 1; }
done
for s in better-accessibility better-layout better-typography better-colors better-writing better-ui better-interface interface-review; do
  cp -r "$SRC/jakubkrehel_skills/skills/$s" "$DST/$s"
  cp "$SRC/jakubkrehel_skills/LICENSE" "$DST/$s/LICENSE"
  printf 'Source: https://github.com/jakubkrehel/skills @ 4c66b1fa86e9ab70187b43d7da9b6fdb29f2ef63 (MIT)\n' > "$DST/$s/SOURCE.txt"
done
for s in threat-model security-arch-review; do
  cp -r "$SRC/secureIO-GmbH_security-skills/skills/$s" "$DST/$s"
  cp "$SRC/secureIO-GmbH_security-skills/LICENSE" "$DST/$s/LICENSE"
  printf 'Source: https://github.com/secureIO-GmbH/security-skills @ 49f63fb87a4ac9d8045e85add9e12ab4c8376b80 (MIT)\n' > "$DST/$s/SOURCE.txt"
done
```

### Post-install verification

```bash
for d in /root/.claude/skills/*/; do
  n=$(basename "$d"); f="$d/SKILL.md"
  fm=$(awk '/^---/{c++; next} c==1 && /^name:/{print $2; exit}' "$f" | tr -d '"')
  [ "$fm" = "$n" ] && echo "OK   $n" || echo "CHECK $n (frontmatter name: $fm)"
done
# Expect no duplicate names across user and project skills:
ls /root/.claude/skills /home/user/smms-final/.claude/skills 2>/dev/null | sort | uniq -d
```

A skill counts as discoverable only when it appears in the session's skill list. Verify that and record PASS/FAIL in [README.md](README.md).

## Installing on the user's own machine (not done here)

Option A, plugins (whole repos, simplest):

```text
/plugin marketplace add jakubkrehel/skills
/plugin install interfaces@interfaces
/plugin marketplace add secureIO-GmbH/security-skills
```

Then install the security plugin by the name its marketplace lists.

Option B, only the selected skills: clone both repos and copy the directories listed above into `~/.claude/skills/` (macOS/Linux) or `%USERPROFILE%\.claude\skills\` (Windows), keeping LICENSE files.
