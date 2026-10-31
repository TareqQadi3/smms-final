# Skills Import Report — spruvex-r → SMMS

Date: 2026-10-23 · Source: https://github.com/TareqQadi3/spruvex-r @ `86b8eac19c68dc33215810792c46da82d1bcdc4c` (`.claude/skills/`) · Destination: repo-level `.claude/skills/` of this repository (branch `claude/smms-browser-test-setup`).

Related: [README.md](README.md) (registry) · [audit.md](audit.md) (third-party source review).

## Method
- Read-only inventory and security review first (Opus planning), then additive copy (Sonnet execution) with `cp -rn` (no-clobber), one directory at a time, only when the destination directory did not exist.
- No imported script was executed. Nothing was deleted, renamed or modified; an attribution file `SOURCE.txt` was added to imported directories that had no LICENSE (86 of 105).

## Counts
| Item | Count |
|---|---|
| Top-level dirs in source `.claude/skills/` | 106 |
| Valid skills (dir with SKILL.md + frontmatter) | 105 |
| Imported | 105 |
| Excluded | 1 — `humanizer` (empty directory, no SKILL.md) |
| Name conflicts with existing repo skills | 0 |
| Same-name overlaps with user-level (sandbox) skills | 5 (versions differ; both kept) |
| Repo-level skills after import | 111 (6 `smms-*` + 105 imported) |

Counting method: a skill = a directory directly under `.claude/skills/` containing `SKILL.md` whose YAML frontmatter parses with `name` and `description`. Folders without `SKILL.md` and support files are not counted.

## Preservation proof
- sha256 of all 452 pre-existing skill files (6 repo `smms-*` skills + every file under `/root/.claude/skills`) recorded before and after: identical.
- Every imported directory is byte-identical to the source except the added `SOURCE.txt` (`diff -rq` per directory).
- Secret sweep (`.env*`, `*.pem`, `*.key`, `id_rsa*`) inside `.claude/skills`: none. No `settings*.json` or hooks imported.

## Overlaps and naming notes
- Same name as user-level sandbox skills (from jakubkrehel/skills, MIT): `better-accessibility`, `better-colors`, `better-layout`, `better-typography`, `better-ui`. The spruvex-r copies differ from them. Repo-level copies were imported; user-level copies were not touched. Both exist at different levels; prefer invoking by the intended source when it matters.
- Frontmatter `name` differs from directory name (imported unchanged; discoverable under the frontmatter name): `facebook-automation`, `quickbooks-automation`, `taste-skill`, `xero-automation`, `zoho-books-automation`.

## Skills with bundled scripts (27)
These include helper scripts (mostly security-testing helpers). They were reviewed (no malicious code, no secrets) and are stored only. **Never run them without explicit authorization and only against systems you own or are permitted to test.**

`anydesign`, `detecting-broken-object-property-level-authorization`, `exploiting-broken-function-level-authorization`, `exploiting-jwt-algorithm-confusion-attack`, `exploiting-mass-assignment-in-rest-apis`, `exploiting-sql-injection-vulnerabilities`, `full-page-screenshot`, `hardening-docker-containers-for-production`, `implementing-api-rate-limiting-and-throttling`, `implementing-jwt-signing-and-verification`, `implementing-secret-scanning-with-gitleaks`, `mcp-builder`, `performing-ssrf-vulnerability-exploitation`, `performing-web-application-penetration-test`, `postgres`, `scanning-docker-images-with-trivy`, `skill-creator`, `sms-twilio`, `testing-api-for-broken-object-level-authorization`, `testing-cors-misconfiguration`, `testing-for-host-header-injection`, `testing-for-xss-vulnerabilities`, `testing-for-xxe-injection-vulnerabilities`, `testing-jwt-token-security`, `transactional-email`, `webapp-testing`, `whatsapp-business-api`

## Imported skills (105)
| Directory | Frontmatter name |
|---|---|
| `Facebook Automation` | facebook-automation |
| `QuickBooks Automation` | quickbooks-automation |
| `Xero Automation` | xero-automation |
| `Zoho Books Automation` | zoho-books-automation |
| `ab-testing` | ab-testing |
| `ad-creative` | ad-creative |
| `ads` | ads |
| `ai-seo` | ai-seo |
| `analytics` | analytics |
| `anydesign` | anydesign |
| `aso` | aso |
| `attribution` | attribution |
| `better-accessibility` | better-accessibility |
| `better-colors` | better-colors |
| `better-layout` | better-layout |
| `better-typography` | better-typography |
| `better-ui` | better-ui |
| `canvas-design` | canvas-design |
| `churn-prevention` | churn-prevention |
| `co-marketing` | co-marketing |
| `cold-email` | cold-email |
| `community-marketing` | community-marketing |
| `competitor-profiling` | competitor-profiling |
| `competitors` | competitors |
| `content-strategy` | content-strategy |
| `copy-editing` | copy-editing |
| `copywriting` | copywriting |
| `cro` | cro |
| `customer-research` | customer-research |
| `design-taste-frontend` | taste-skill |
| `detecting-broken-object-property-level-authorization` | detecting-broken-object-property-level-authorization |
| `directory-submissions` | directory-submissions |
| `emails` | emails |
| `erp-pos-saas-architect` | erp-pos-saas-architect |
| `events` | events |
| `exploiting-broken-function-level-authorization` | exploiting-broken-function-level-authorization |
| `exploiting-jwt-algorithm-confusion-attack` | exploiting-jwt-algorithm-confusion-attack |
| `exploiting-mass-assignment-in-rest-apis` | exploiting-mass-assignment-in-rest-apis |
| `exploiting-sql-injection-vulnerabilities` | exploiting-sql-injection-vulnerabilities |
| `ffuf-web-fuzzing` | ffuf-web-fuzzing |
| `finishing-a-development-branch` | finishing-a-development-branch |
| `free-tools` | free-tools |
| `full-page-screenshot` | full-page-screenshot |
| `github-automation` | github-automation |
| `hardening-docker-containers-for-production` | hardening-docker-containers-for-production |
| `image` | image |
| `implementing-api-rate-limiting-and-throttling` | implementing-api-rate-limiting-and-throttling |
| `implementing-jwt-signing-and-verification` | implementing-jwt-signing-and-verification |
| `implementing-secret-scanning-with-gitleaks` | implementing-secret-scanning-with-gitleaks |
| `influencer-marketing` | influencer-marketing |
| `launch` | launch |
| `lead-magnets` | lead-magnets |
| `marketing-council` | marketing-council |
| `marketing-ideas` | marketing-ideas |
| `marketing-loops` | marketing-loops |
| `marketing-plan` | marketing-plan |
| `marketing-psychology` | marketing-psychology |
| `mcp-builder` | mcp-builder |
| `offers` | offers |
| `onboarding` | onboarding |
| `openweb` | openweb |
| `paywalls` | paywalls |
| `performing-ssrf-vulnerability-exploitation` | performing-ssrf-vulnerability-exploitation |
| `performing-web-application-penetration-test` | performing-web-application-penetration-test |
| `playwright-skill` | playwright-skill |
| `popups` | popups |
| `postgres` | postgres |
| `pricing` | pricing |
| `product-marketing` | product-marketing |
| `programmatic-seo` | programmatic-seo |
| `prompt-engineering` | prompt-engineering |
| `prospecting` | prospecting |
| `public-relations` | public-relations |
| `referrals` | referrals |
| `revops` | revops |
| `sales-enablement` | sales-enablement |
| `salesforce-marketing-cloud-automation` | salesforce-marketing-cloud-automation |
| `salesforce-service-cloud-automation` | salesforce-service-cloud-automation |
| `scanning-docker-images-with-trivy` | scanning-docker-images-with-trivy |
| `schema` | schema |
| `seo-audit` | seo-audit |
| `signup` | signup |
| `site-architecture` | site-architecture |
| `skill-creator` | skill-creator |
| `sms` | sms |
| `sms-twilio` | sms-twilio |
| `social` | social |
| `subagent-driven-development` | subagent-driven-development |
| `systematic-debugging` | systematic-debugging |
| `test-driven-development` | test-driven-development |
| `testing-api-for-broken-object-level-authorization` | testing-api-for-broken-object-level-authorization |
| `testing-cors-misconfiguration` | testing-cors-misconfiguration |
| `testing-for-host-header-injection` | testing-for-host-header-injection |
| `testing-for-xss-vulnerabilities` | testing-for-xss-vulnerabilities |
| `testing-for-xxe-injection-vulnerabilities` | testing-for-xxe-injection-vulnerabilities |
| `testing-jwt-token-security` | testing-jwt-token-security |
| `theme-factory` | theme-factory |
| `transactional-email` | transactional-email |
| `using-git-worktrees` | using-git-worktrees |
| `video` | video |
| `webapp-testing` | webapp-testing |
| `whatsapp-business-api` | whatsapp-business-api |
| `zoho-inventory-automation` | zoho-inventory-automation |
| `zoho-invoice-automation` | zoho-invoice-automation |
| `zoho-mail-automation` | zoho-mail-automation |

## How to use
Open this repository in Claude Code (local, Codespace, or cloud session); repo-level skills in `.claude/skills/` are discovered automatically and appear in the skill list. Invoke by name (e.g. `/smms-security-review`) or let Claude select a skill from its description.

