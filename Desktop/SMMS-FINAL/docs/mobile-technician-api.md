# Mobile technician API contract (verified against the backend code of branch claude/fp1-fc, FP1)
There is NO native app. The technician client is the mobile-first web area `/tech` of the Next.js frontend (see section 12). Sections 2-11 are the original design notes; where they disagree with section 12, section 12 (checked in code) wins.

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


## Addendum (security phase 4, now implemented in the backend)
- **Refresh:** every `POST /auth/refresh` returns a NEW refresh token and kills the old one. Reusing an old one revokes the whole session (no grace window), so the client must serialize (single-flight) refresh calls and persist the new token before using it. `POST /auth/logout` always returns 204 and revokes the session.
- **Errors:** missing/invalid/expired/revoked/deactivated token = 401 (never 403).
- **WebSocket:** `POST /api/ws/ticket` (Bearer) returns a single-use 30 s ticket; open `/ws/notifications` with sub-protocols `['smms.v1', 'smms.ticket.<ticket>']`. Nothing in the URL.
- **Photo upload retries:** send an `Idempotency-Key` header (UUID, one per logical upload). A replay returns 200 with `Idempotent-Replayed: true`; the same key with different content is 422; a malformed key is 400. Files must match their extension by magic bytes (max 10 files).
- **Download:** attachment download endpoint with the same visibility rules as the work order (out of scope = 404).
- **Login/refresh body** includes `passwordChangeRequired`.


## 12. Actual behaviour verified in code (FP1, supersedes the notes above)

Client: `/tech` (mobile-first web, Arabic RTL first), `lib/api.ts` (session), `lib/offline/*` (queue), `lib/ws.ts` (notifications). No native app, no service worker, no push; the offline queue lives only while the tab is open (persisted per user, replayed on the next visit).

### Session
- `POST /auth/login` -> `{token, refreshToken, ..., passwordChangeRequired}`. `POST /auth/refresh` (header `X-Refresh-Token`) ROTATES: new access AND new refresh token; the old refresh token dies; presenting a rotated one revokes the whole session (401, no grace). Any invalid/expired/revoked/reused/deactivated case is 401.
- Client rule implemented: one shared refresh promise (single-flight), concurrent 401s wait for it and retry once; the new pair is persisted before use; a Web Lock serialises tabs; refresh 401/403 or missing refresh token = full logout + redirect to `/login?expired=1&next=<path>`; network error or 5xx during refresh keeps the session.
- `POST /auth/logout` (Bearer and optional `X-Refresh-Token`) always 204 and revokes the session; the client calls it and clears storage regardless of the result.

### WebSocket
- `POST /api/ws/ticket` (Bearer) -> `{ticket, expiresInSeconds: 30}`, single use, 429 when too many pending. Open `/ws/notifications` with sub-protocols `['smms.v1', 'smms.ticket.<ticket>']`; nothing in the URL. A new ticket per connection attempt. Frame: `{type, title, workOrderId ("" if none), createdAt}`. Client reconnects with exponential backoff (1 s doubling to 60 s, jitter; reset after a connection stayed up 10 s) and polls `GET /notifications/unread-count` every 60 s while not connected.

### Idempotency-Key
- Format: 8-64 characters. Work-order `transition`, `notes`, `confirm`: optional key, `[A-Za-z0-9-]` via the generic service; replay = 200 + `Idempotent-Replayed: true` (notes replay returns the stored history row with 200 instead of 201). Attachment upload: optional key `[A-Za-z0-9_-]{8,64}`; replay 200 + header; same key with other files/phase = 422. Material request `consume`/`issue`/`return`/`reserve`: key `[A-Za-z0-9-]{8,64}` (no underscore), malformed = 400. The client uses a UUID v4, minted once per logical action and reused by every retry.

### Transitions (technician)
- `POST /work-orders/{id}/transition {targetStatus, note?, scheduledDate?}`: technician may do assigned -> in_progress, in_progress -> waiting_materials, in_progress -> completed. `note` becomes `techReport` for waiting_materials and completed. waiting_materials -> in_progress is the WAREHOUSE's. Anything else 422; `assigned` target 422; ticket locked by another user 409; out of scope 404. With an Idempotency-Key a repeat returns the current state (200, replayed). Without it a repeat is 422: the client then GETs the ticket and counts the action as done if the status equals the target.

### Notes
- `POST /work-orders/{id}/notes {text 1..2000}` -> 201 history row (stage `note`, status unchanged). Roles technician, control_room, maintenance_manager, hospital_supervisor, system_admin. A technician must be the assigned one (else 403) and the status must be assigned, in_progress or waiting_materials (else 422).

### Attachments / photos
- `POST /work-orders/{id}/attachments?phase=before|after` multipart part `file` (repeatable, max 10 files): 201, or 200 + `Idempotent-Replayed` on a replay. Extensions jpg, jpeg, png, pdf, doc, docx; magic bytes must match the extension (so the client always sends real JPEG named `.jpg`, compressed to <= 2 MB, long side <= 2048 px). Bad `phase` = 400. `GET .../attachments` rows carry `photoPhase` ("before" | "after" | null). `GET .../attachments/{attId}/content` is an authenticated download (blob with Bearer), 404 when out of scope.

### Materials
- `GET /work-orders/{id}/material-requests` (summaries) and `GET /material-requests/{id}` (lines with qtyRequested/Approved/Reserved/Issued/Consumed/Returned, `version`). `POST /material-requests/{id}/consume {lines:[{lineId, quantity}], note?, version?}` is technician-only, assigned technician only (else 403), request status `issued` (else 422), quantity <= issued - consumed - returned (422). `version` optional (stale = 409). Technicians cannot list warehouses, so the app does not create material requests.

### Requester endpoints
- `GET /work-orders/{id}/info-requests`; `POST /provide-info {note}` (requester only, only while stage `info_requested`; 403/422 otherwise). `POST /confirm {accepted, note?}` (requester, status completed, once; note required when `accepted=false` = else 400; 403 non-requester; 422 wrong status or already confirmed); optional Idempotency-Key. `POST /rating {score 1..5, comment?}` 201; 409 already rated; 422 not eligible or 30-day window over; `GET /rating` 200 or 404. `POST /status-link` (requester) 201 `{token, path: "/status/<token>", expiresAt}` shown once, revokes the previous link, 422 when the feature is disabled. `GET /api/public/request-status/{token}` (no auth, no credentials sent by the client): `{ticketNumber, status, statusGroup, createdAt, lastUpdatedAt, completed}`; every failure including a disabled feature is the same 404; 429 + `Retry-After: 60` beyond 30 requests/minute per `X-Real-IP`.

### Errors the client maps
- 401 session (refresh, else login); 403 role/ownership; 404 gone or out of scope (queued action is dropped); 409 lock or concurrent change; 422 workflow rule (server message shown as detail); 400 bad input; 408/425/429/5xx and network errors are retried with backoff (5xx/429 give up after 8 attempts, network errors never).
