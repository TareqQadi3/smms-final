# QA Evidence

Related: [../test-plan.md](../test-plan.md) · [../test-results.md](../test-results.md) · [../../project-memory.md](../../project-memory.md) · [../../skills/README.md](../../skills/README.md)

## Rules

1. **Real application only.** Every screenshot must come from the running SMMS app in a browser (Playwright or the user's device). Never mock, edit or composite screenshots and present them as evidence.
2. **Record the environment** in the test-results row: Codespace name or "Claude local copy", commit hash, date, executing model.
3. **No secrets or personal data.** Never capture passwords, JWTs, `.env` contents, cookies, or real patient/employee data. Use QA test accounts and records whose title starts with `QA-`. Redact (solid box) any token, password field value, phone number, or email that appears. If a screenshot cannot be redacted safely, do not store it.
4. Do not commit screenshots that show sensitive data. Review each image before `git add`.

## Naming

`QA-<id>-<step>[-<viewport>].png`, lowercase step, hyphenated.

Examples: `QA-001-login.png`, `QA-101-submitted.png`, `QA-106-closed.png`, `QA-972-dashboard-iphone.png`, `QA-901-api-403.txt` (API evidence as redacted request/response text).

QA ID ranges (defined in the test plan): 001–006 auth · 010–021 navigation · 050–053 entry gate · 1xx–8xx scenarios A–H · 9xx API authorization (901–908), isolation (951–953), UI/viewport (971–973), security/data rules (981–986). `GAP-*` rows have no evidence; `DEF-###` are defects.

## Viewports

| Name | Size | Notes |
|---|---|---|
| desktop | 1440×900 | default |
| iphone | 390×844, deviceScaleFactor 3, touch, mobile UA | **emulation**, label as such |
| physical | user's iPhone Safari | captured by the user; mark "physical device" |

## Storage

Save files to `docs/qa/evidence/`. Link each file from the Evidence column in `test-results.md`. Large sets can go in a sub-folder per scenario (`evidence/A/`).

## Reproducing a test

1. Check out the commit recorded in the results row and start the stack (`Desktop/SMMS-FINAL/setup.sh`).
2. Log in with the listed test account (credentials are kept outside the repo).
3. Follow the steps in the referenced test-plan section. Use the recorded request reference (ticket number) to find the same record.
4. Compare against "Expected"; capture new evidence with the same name plus `-retest`.
