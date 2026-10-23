---
name: smms-security-review
description: Read-only OWASP Top 10 / API Top 10 security review of the SMMS Spring Boot + Next.js codebase (authN, authZ/IDOR, CORS, XSS, uploads, secrets, logging, error leakage, dependencies). Use when asked to audit security, review a PR for security impact, or check a suspected vulnerability.
---

# SMMS security review

## When to use
Security audit of the whole app or of a diff; triage of a suspected vulnerability; before exposing the app beyond localhost. For tenant-boundary depth use `smms-tenant-isolation`; for DB hardening use `smms-database-safety`.

## Inputs / scope
Branch or diff under review, target base URL (default `http://localhost/api`), and which roles have tokens available (tokens come from env at runtime, never typed into files). Default scope: backend, frontend API client, nginx, compose, env templates.

## Relevant files (verified)
- `Desktop/SMMS-FINAL/CLAUDE.md` (RBAC rules: server-side enforcement, technician isolation)
- `Desktop/SMMS-FINAL/backend-source/src/main/java/com/smms/config/SecurityConfig.java` (filter chain, CSRF off, stateless, CORS, `BCryptPasswordEncoder(12)`)
- `Desktop/SMMS-FINAL/backend-source/src/main/java/com/smms/security/JwtService.java`, `JwtAuthFilter.java`, `BruteForceProtectionService.java`, `XssSanitizationFilter.java`, `TenantSecurityService.java`
- `Desktop/SMMS-FINAL/backend-source/src/main/java/com/smms/service/AuthService.java`, `UserService.java`, `AttachmentService.java`
- `Desktop/SMMS-FINAL/backend-source/src/main/java/com/smms/controller/` (every `*Controller.java`)
- `Desktop/SMMS-FINAL/backend-source/src/main/java/com/smms/exception/GlobalExceptionHandler.java`
- `Desktop/SMMS-FINAL/backend-source/src/main/resources/application.yml`, `application-dev.yml`, `application-prod.yml`
- `Desktop/SMMS-FINAL/backend-source/pom.xml`, `Desktop/SMMS-FINAL/frontend/package.json`
- `Desktop/SMMS-FINAL/frontend/src/lib/api.ts`, `Desktop/SMMS-FINAL/nginx/nginx.conf`, `Desktop/SMMS-FINAL/docker-compose.yml`
- `Desktop/SMMS-FINAL/.env.example`, `Desktop/SMMS-FINAL/.env.codespaces` (TEST-only values; only this and the example are tracked)

## Procedure
1. **AuthN / JWT.** Read `JwtService`: HMAC key from Base64 secret, access expiry `app.jwt.expiry-hours` (8), refresh `refresh-expiry-days` (7). Check: no `iss`/`aud`/token-type claim; `generateRefreshToken` uses empty extra claims, and `JwtAuthFilter` accepts any valid token as a Bearer. Lead to verify: a refresh token has no `tenantId` claim, so `extractTenantId` returns null and the filter calls `TenantContext.set(null)` (platform scope). Confirm by reading, then by a test (see Verification) before rating it.
2. **Brute force.** `grep -rn "BruteForceProtectionService" Desktop/SMMS-FINAL/backend-source/src/main`. Lead: the service is only defined, never injected, so login may be unthrottled (and nginx has no `limit_req`). Verify before reporting.
3. **Password hashing.** Confirm BCrypt cost 12 in `SecurityConfig`; check `UserService` password-length rules and `ChangePasswordRequest` validation.
4. **AuthZ coverage.** List every mapping: `grep -rn "Mapping(\|PreAuthorize" Desktop/SMMS-FINAL/backend-source/src/main/java/com/smms/controller`. Methods without a method-level or class-level `@PreAuthorize` (e.g. NotificationController, `GET /api/work-orders/{id}`, `/transition`, lock/unlock, attachments, `/api/users/me`) rely only on `anyRequest().authenticated()` plus service checks; open each service method and confirm a role/ownership check exists.
5. **IDOR.** For `/api/work-orders/{id}`, `/api/admin/users/{id}`, `/api/work-orders/{id}/attachments`, `/history`: confirm the lookup goes through `TenantSecurityService.require*` and that technician ownership (`assertWorkOrderAccess`) is applied on every path, including `getById`, `history`, `lock`.
6. **CORS / CSRF.** `CORS_ALLOWED_ORIGIN_PATTERNS` is wildcard-capable with `allowCredentials(true)`: confirm prod value is an explicit list, no bare `*`. CSRF is disabled; acceptable only because auth is a Bearer header (`api.ts` reads `localStorage`, not cookies). Flag any cookie-based auth introduced later. Note the trade-off: token in `localStorage` is exposed to XSS.
7. **Input validation / XSS.** Read `XssSanitizationFilter` (`@Order(1)`, regex strip of script/JS-proto) and `util/InputSanitizer.java`; note regex sanitizers are bypassable, so output encoding in React is the real control: `grep -rn "dangerouslySetInnerHTML" Desktop/SMMS-FINAL/frontend/src`. Check DTO `@Valid`/`@NotBlank` use.
8. **Uploads.** `AttachmentService`: extension allow-list only (no MIME/magic-byte check), UUID-prefixed name, `sanitize()` strips path chars (traversal handled), size cap from `spring.servlet.multipart` / nginx `client_max_body_size 50M`. Check how `/uploads/` is served (nginx proxies it; confirm backend has a handler) and that `list()` returns the raw `Attachment` entity (leaks `filePath`, `uploadedBy`).
9. **Secrets.** Run the grep block below. Expect: `application-prod.yml` uses `${VARS}`; `application-dev.yml` contains dev-only literals (report as Low/Info, confirm dev profile is not active in prod via `SPRING_PROFILES_ACTIVE: prod` in compose); `.env` is git-ignored.
10. **Sensitive logging.** KNOWN: `resetPassword` takes `@RequestParam String newPassword` (`UserController`), and `api.ts` sends it as a query param (`resetPassword`), so the password lands in nginx access logs, browser history and proxies. Recommend a JSON body DTO (bounded fix + regression test). Also grep `log\.(info|warn|debug).*(password|token)`.
11. **Error leakage.** `GlobalExceptionHandler` returns `e.getMessage()` for several handlers and has a catch-all `Exception` handler; confirm the catch-all returns a generic message, and `application-prod.yml` sets `server.error.include-message: never`.
12. **Dependencies.** Record Spring Boot 3.3.0, jjwt, POI 5.2.5 from `pom.xml`; run `mvn -q dependency:tree` and `npm audit --omit=dev` only if the network permits; otherwise mark NOT TESTED.

```bash
cd /home/user/smms-final/Desktop/SMMS-FINAL
grep -rnEi "(password|secret|api[_-]?key|token)\s*[:=]\s*['\"]?[A-Za-z0-9+/=_-]{8,}" backend-source/src frontend/src scripts *.yml *.sh 2>/dev/null | grep -v "\${" | cut -c1-160   # review hits, never paste values into reports
git ls-files | grep -E "(^|/)\.env"
grep -rnEi "log\.(info|warn|debug|error).*(password|token|secret)" backend-source/src/main
grep -rn "@RequestParam.*[Pp]assword" backend-source/src/main
```
When reporting a secret hit, cite file:line and the kind only, not the value.

## Expected output
Write findings in the chat (or a file under `Desktop/SMMS-FINAL/docs/qa/` if asked):

| ID | Severity | File:line | Evidence | Recommendation | Verified? |
|----|----------|-----------|----------|----------------|-----------|
| SEC-001 | High | path:line | quoted code / probe result | bounded fix | verified by test / code-reading only |

Severities: Critical / High / Medium / Low / Info. Add a PASS/FAIL/BLOCKED/NOT TESTED line per OWASP area.

## Verification
- Unit tests: `cd Desktop/SMMS-FINAL/backend-source && mvn -q test` (existing: `JwtServiceTest`, `BruteForceProtectionServiceTest`, `GlobalExceptionHandlerTest`, `InputSanitizerTest`).
- API probes (token from env, never echoed): `curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $TOKEN_ROLE" http://localhost/api/admin/users`. Expect 401 without token, 403 for a wrong role, 404 (not 200) for another tenant's id.
- Refresh-token lead: log in as a non-admin, send the refresh token as the Bearer to `GET /api/work-orders`, compare the result count with the access token; a larger set confirms the finding.
- A fix is accepted only with a regression test that fails before and passes after.

## Safety restrictions
Read-only by default. Never reset/reseed/drop the DB or delete Docker volumes (`docker compose down -v`, `make clean` are forbidden). Never print or commit passwords, tokens or `.env` contents. Never make ports public. No destructive or DoS testing (no flooding logins to prove brute force; reason from code). Never merge to main. Fixes only when bounded, justified and covered by a regression test; stop and ask before schema migrations or architectural changes. Report PASS / FAIL / BLOCKED / NOT TESTED honestly, and never claim a feature exists without code evidence.
