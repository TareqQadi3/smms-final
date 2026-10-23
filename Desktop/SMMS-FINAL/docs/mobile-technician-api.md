# Mobile technician API contract (design; status NOW = exists, GAP = planned)
Source: code at branch claude/security-phase-3. Not implemented as an app; see roadmap.md.

## 1. Conventions
- Base `/api`, JSON, enums lowercase (`in_progress`), timestamps ISO-8601 UTC (`Instant`).
- Error body `{status, message, timestamp}`. Codes: 400 bad input (missing/ill-typed param, bad body), 401 auth, 403 role not allowed for the endpoint, 404 unknown OR out of scope (no id oracle), 409 ticket locked by another user, 422 workflow violation (wrong status, wrong endpoint, role not allowed for that transition), 429 login lockout.
- Pagination `page` (0-based), `size` (max 100). `Authorization: Bearer <access token>` on every call.
- M3 change: calling `/transition` with a target that has a dedicated endpoint (assigned, approval decisions, reopen) is now 422 (was 400).

## 2. Auth and session
| Endpoint | Notes |
|---|---|
| `POST /auth/login {username,password}` | 200 `{token, refreshToken, role, userId, username, fullName, fullNameAr, centerId, centerName, centerNameAr, tenantId, tenantName}`; 401 bad credentials or tenant-less non-admin; 429 after 5 failures |
| `POST /auth/refresh` header `X-Refresh-Token` | 200 same shape; returns the SAME refresh token (no rotation) |
| logout | client-side only (discard tokens); server does not revoke |
- Access 8 h, refresh 7 d (`JWT_EXPIRY_HOURS`, `JWT_REFRESH_EXPIRY_DAYS`); a refresh token is rejected as bearer.
- GAP (LATER unless mobile ships this quarter): refresh rotation + reuse detection, server-side revocation, device sessions (needs a token store; Redis available).

## 3. Assigned orders (role technician)
- `GET /work-orders?status=&priority=&page=&size=&sort=createdAt,desc`: scope is forced to tickets assigned to the caller (`WorkOrderAccessPolicy`); `assignedToId` filter cannot widen it.
- `GET /work-orders/{id}`: 404 if not assigned to the caller.
- `GET /dashboard/technician`: counters.
- Response fields used by mobile: id, ticketNumber, status, currentStage, priority, subType, title, description, locationDetail, slaDeadline, slaBreached, locked, needsMaterials, techReport, warehouseNotes, rejectionReason, requestedBy, center, controlRoom. `currentStage` is `info_requested` only while an information request is pending (status stays `under_review`; technicians never see those tickets).
- LATER: delta sync `updatedSince`.

## 4. Status transitions (technician)
`POST /work-orders/{id}/transition {targetStatus, note?, scheduledDate?}`
| from | to | effect |
|---|---|---|
| assigned | in_progress | accept/start; optional `scheduledDate` stored |
| in_progress | waiting_materials | `note` stored as `techReport`, `needsMaterials=true`, `materialRequiredAt` |
| in_progress | completed | `note` stored as `techReport`, `completedDate=now` |
- Anything else for a technician: 422. Not a technician endpoint: assign, approval, close, reopen (403/422).
- `POST /{id}/lock`, `POST /{id}/unlock` (204): optional soft lock; other users get 409 on transitions while locked; lock/unlock write no history row.
- Each transition writes exactly one `ticket_history` row (from, to, actor, actor role, stage, note).
- Idempotency: repeating a transition returns 422 (status already moved). Client rule: on 422 do `GET /{id}`; if `status` equals the intended target treat as success, else surface message.

## 5. Notes and completion report
- The completion report is the `note` of the `completed` transition (stored in `techReport`, also visible in history). A separate notes endpoint is LATER.

## 6. Photos
- `POST /work-orders/{id}/attachments` multipart, repeat part name `file` (jpg, jpeg, png, pdf, doc, docx; max 50 MB per request). 201 empty body. 404 out of scope, 400 empty file / disallowed type.
- M3: one `ticket_history` row per upload request: from=to=current status, note `Attachments added (N): names`.
- `GET /work-orders/{id}/attachments` -> `[{id, fileName, fileSize, mimeType, createdAt, uploadedById}]` (no server path).
- GAP-NOW (M4): `GET /work-orders/{id}/attachments/{attId}/content` access-checked, `Content-Disposition: attachment`, `X-Content-Type-Options: nosniff`. `/uploads` is deliberately 404 at nginx, so photos cannot be viewed today.
- GAP-NOW (M4): `Idempotency-Key` header for uploads (not idempotent today: a retry creates duplicate attachments).

## 7. Notifications
- `GET /notifications?page=&size=` -> page of `{id,type,title,body,read,createdAt}`; `GET /notifications/unread-count`; `POST /notifications/read-all`. Per-id read LATER.
- Types seen by technicians: `ASSIGNED`, `STATUS_CHANGE`, `REJECTED`.
- WebSocket `/ws/notifications` (through nginx `/ws/`): send `Authorization: Bearer <access token>` on the upgrade (native clients can; browsers cannot). Frame: `{"type","title","workOrderId","createdAt"}` (workOrderId `""` when none). Server closes unauthenticated sessions with 1008.
- M3: frames are now sent after the DB transaction commits (a rolled-back operation produces no frame).
- Observed on a throwaway stack (direct to backend :18081): handshake OK, socket still open after 5 s, `ASSIGNED` frame with the right workOrderId received after assign, a second technician's socket received nothing. Through nginx and tenant-B isolation: NOT TESTED by agent D.
- Push (FCM/APNs): LATER. Clients must poll `unread-count` on app resume.

## 8. History
- `GET /work-orders/{id}/history` -> ordered `[{id, fromStatus, toStatus, stage, note, createdAt, actor{id,fullName,role}}]`. Return-for-information rows have from=to=`under_review` and stage `info_requested` / `under_review`.

## 9. Retry and offline rules
- GET: retry with backoff. POST transitions: do not blind-retry; on timeout re-GET and compare status (section 4). POST uploads: never auto-retry until the idempotency key exists. Queue offline actions and replay in order; on 404 drop the action (access lost); on 409 retry after unlock/refresh.

## 10. Versioning
- `/api` is unversioned; add an `/api/v1` alias at mobile launch (LATER).

## 11. Gap table
| Gap | Decision |
|---|---|
| WS push after commit | DONE in M3 |
| Attachment download endpoint | M4 |
| Upload Idempotency-Key | M4 |
| Refresh rotation / revocation | decide in M4 (needed if mobile ships) |
| Notes endpoint, delta sync, per-id read, push | LATER |
## Acceptance probes per endpoint (for M4): login/refresh 200/401/429; list scope (technician sees only own); transition table above incl. 422 repeat; lock 409; upload 201/400/404 + history row; list attachments; WS open >5 s + frame on assign + no frame to other technician + none after rollback; history order.
