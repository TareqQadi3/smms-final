---
name: smms-rtl-ui-review
description: Reviews the SMMS Arabic-first RTL Next.js 14 + Tailwind + shadcn UI for direction, logical CSS, formatting, accessibility, contrast, tap targets, responsiveness (390x844 and 1440x900) and state handling, using Playwright screenshots of real pages. Use when asked to audit or QA the UI, RTL or mobile layout, or after front-end changes.
---

# SMMS RTL / UI review

## When to use
UI audit, RTL regression check, mobile layout check, accessibility pass, or visual evidence for a QA run. Not for backend behavior (use `smms-e2e-qa`).

## Inputs / scope
Base URL of a running app (default `http://localhost`), accounts by username only (passwords read from env at runtime, never written down), list of pages: `login`, `dashboard`, `work-orders`, `new-request`, `my-tasks`, `approvals`, `users`, `regions`, `centers`, `control-rooms`, `reports`, `settings`, `config`. Language toggle values are `ar` (default) and `en` (`smms_lang` in localStorage, set in `authStore.ts`).

## Relevant files (verified)
- `Desktop/SMMS-FINAL/frontend/src/app/layout.tsx` (`<html lang="ar">`, Cairo font with arabic+latin subsets; note: no `dir` on `<html>`)
- `Desktop/SMMS-FINAL/frontend/src/components/layout/DashboardLayout.tsx` (sets `dir` from `lang`), `Sidebar.tsx`, `Topbar.tsx`
- `Desktop/SMMS-FINAL/frontend/src/app/login/page.tsx` (has its own `dir`), `Desktop/SMMS-FINAL/frontend/src/app/globals.css`
- `Desktop/SMMS-FINAL/frontend/src/lib/i18n.ts` (ar/en dictionaries, `useT`), `Desktop/SMMS-FINAL/frontend/src/store/authStore.ts`
- `Desktop/SMMS-FINAL/frontend/src/components/ui/` (shadcn: `button.tsx`, `input.tsx`, `select.tsx`, `dialog.tsx`, `table.tsx`, `tabs.tsx`, `sheet.tsx`, `BilingualInput.tsx`, ...)
- `Desktop/SMMS-FINAL/frontend/tailwind.config.ts` (no RTL plugin, `font-sans` = Cairo), `Desktop/SMMS-FINAL/frontend/components.json`, `Desktop/SMMS-FINAL/frontend/package.json` (Next 14.2.35, Tailwind 3.4)
- Pages under `Desktop/SMMS-FINAL/frontend/src/app/*/page.tsx`
- Role/menu map: section 5 of `Desktop/SMMS-FINAL/docs/qa/test-plan.md`

## Procedure
1. **Static pass (greps).**
```bash
cd /home/user/smms-final/Desktop/SMMS-FINAL/frontend/src
grep -rnE "\b(ml|mr|pl|pr)-[0-9]|text-(left|right)\b|\b(left|right)-[0-9]|rounded-[lr]-|border-[lr]\b" app components   # physical properties: prefer ms/me/ps/pe/start/end, rounded-s/e, border-s/e
grep -rn "dir=" app components                         # where direction is set; every page tree must have a dir
grep -rnE "ChevronLeft|ChevronRight|ArrowLeft|ArrowRight" app components   # directional icons need rtl:rotate-180 or swapping
grep -rnE "toLocale|Intl\.|new Date|\.toFixed" app lib  # number/date formatting (known: date-fns format(..., 'dd/MM/yyyy') is used in approvals, new-request, work-orders/[id] with no locale; expect ar-SA / en-US by lang)
grep -rnE "h-[4-9] |h-8|w-8|size=\"sm\"|text-\[1[0-2]px\]|text-xs" app components | head -40   # small targets / tiny text
```
   Note physical-property counts per file; they are candidates, confirm visually.
2. **Direction.** Confirm `dir="rtl"` is effective on EVERY route (login, new-request, my-tasks are outside `DashboardLayout` in some cases) and flips on language toggle; dialogs/sheets/portals render outside the layout div, so check they carry their own `dir`.
3. **Run the screenshots (real pages only).** Playwright and Chromium are preinstalled; never run `playwright install`, never fabricate or edit a screenshot.
```bash
export PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers
export NODE_PATH=/opt/node22/lib/node_modules
mkdir -p /home/user/smms-final/Desktop/SMMS-FINAL/docs/qa/evidence
node ui-shots.js   # your script kept in the session scratchpad, not in the repo
```
   Script outline: launch chromium headless; contexts `{viewport:{width:390,height:844}, deviceScaleFactor:3, isMobile:true, hasTouch:true}` and `{viewport:{width:1440,height:900}}`; log in through the real form using `process.env.UI_USER` / `process.env.UI_PASS` (never log them); for each page `page.goto`, wait for network idle, `page.screenshot({path, fullPage:true})`; save to `Desktop/SMMS-FINAL/docs/qa/evidence/UI-<page>-<ar|en>-<390|1440>.png`. Capture console errors (`page.on('console')`) and horizontal overflow: `document.documentElement.scrollWidth > innerWidth`.
4. **Checklist per page/viewport:** RTL mirroring of layout, sidebar side and drawer direction; icons mirrored where directional; logical CSS; numbers and dates (ar-SA, Arabic-Indic vs Latin consistency across table, badge and chart); mixed Arabic/English strings (ticket numbers `WO-...`, emails, usernames) wrapped in `dir="ltr"` / `<bdi>` so punctuation does not jump; form labels, required markers, inline errors in Arabic; visible focus rings (Tab through the page); keyboard operability of dialogs, selects, tabs (Esc closes, focus returns); contrast WCAG AA 4.5:1 (check `text-gray-400/500` on white, badges, disabled states; compute with the page's real colors via `getComputedStyle`); tap targets >= 44x44 px at 390 wide; tables scroll within a wrapper without breaking the page; empty / loading (skeleton, spinner) / error states exist and are translated; typography scale and Cairo line-height for Arabic; consistency of shadcn components (button sizes, input heights, radius).
5. **Device honesty.** A 390x844 Chromium context is an emulation, not a physical iPhone: Safari-specific issues (100vh, safe-area, input zoom below 16px font, date pickers) are NOT TESTED unless a real device was used. Say so in the report.
6. **Role views.** Repeat key pages for roles whose menu differs (technician `my-tasks`, resident `new-request`, `control_room`, `system_admin`); screens a role cannot reach are recorded, not skipped.

## Expected output
Findings table plus a screenshot index (path, page, viewport, language, role):

| ID | Severity | File:line | Evidence | Recommendation | Verified? |
|----|----------|-----------|----------|----------------|-----------|
| UI-001 | Medium | frontend/src/app/x/page.tsx:NN | `ml-2` on icon, screenshot `docs/qa/evidence/UI-...png` | use `me-2` | verified by screenshot / code-reading only |

Severity guide: Critical = unusable or data-hiding in RTL; High = key flow broken on mobile; Medium = wrong mirroring/contrast; Low = polish; Info = observation.

## Verification
- Re-run the same script after a fix and compare the new screenshot with the old (store both).
- `cd Desktop/SMMS-FINAL/frontend && npx tsc --noEmit` (note `next.config.mjs` ignores TS build errors, so this is the real check) and `npm run lint` if configured.
- Contrast: report the computed ratio and both colors.

## Safety restrictions
Read-only against the app: no form submissions that create data unless the user approved a labelled test record. Never reset/reseed/drop the DB or delete Docker volumes (`docker compose down -v` forbidden). Never print or commit passwords, tokens or `.env` contents; never write credentials into the script or screenshots (crop or avoid pages that display tokens). Never make ports public. No destructive or DoS testing. Never merge to main. Fixes only when bounded, justified and covered by a regression test or before/after screenshot; stop and ask before architectural changes (e.g. adopting a global i18n router). Distinguish PASS/FAIL/BLOCKED/NOT TESTED; never claim a feature exists without code evidence. Do not run `playwright install` or download browsers.
