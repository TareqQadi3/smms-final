# Implementation ledger (feature program 1, branch `claude/feature-program-1`, base `727a37a`)

Architecture: `fp1-plan` by claude-opus-5-5 (self-reported). Status values: planned / in progress / merged / deferred / blocked. Updated by the coordinator after each merge.

| WS | Owner | Files (main) | Migrations | Depends on | Tests | Status |
|---|---|---|---|---|---|---|
| Contracts | W0 | WorkOrder/User entities, WorkOrderResponse/Mapper, WorkOrderHistoryWriter, createGenerated, idempotency/*, StockLedger, CsvWriter, GlobalExceptionHandler, AbstractDbIT, pom surefire | V13, V14 | base 727a37a | mapper, idempotency, CSV, handler, createGenerated | planned |
| Frontend tooling | F0 | package.json, vitest/playwright config, module-i18n, components/common/* | – | – | component tests | planned |
| WS1 workflow | W1 | WorkOrderService, WorkflowEngine, NotificationService, SlaScheduler, WorkOrderController, AttachmentService, workflow/**, department/** | V20-V25 | W0 | matrix, SLA pause, escalation, confirm/rating, public status, emergency, dept scope | planned |
| WS2 inventory core | W2a | inventory/** (StockLedgerService), InventoryItem/Movement | V30-V34 | W0 | concurrency/idempotency DB ITs, triggers, CHECKs | planned |
| WS2 procurement/materials | W2b | procurement/**, materials/**, reports/inventory/** | V35-V39 | W0, W2a (merge) | state machines, SoD, over-receipt, MR arithmetic | planned |
| WS3 assets/PM | W3 | Asset, assets/**, pm/**, reports/assets/** | V40-V44 | W0 | generation duplicates, tenant isolation, overdue, downtime | planned |
| WS4 reports/notifications | W4 | reports/**, notify/**, Dashboard*, NotificationController/Repository | V50-V51 | W0, W1 (merge) | ranges, scope, known-answer SQL, CSV, providers, dispatcher | planned |
| WS5 mobile + requester | FC | lib/api.ts (refresh), authStore, lib/offline/*, lib/ws.ts, app/tech/**, app/my-requests/**, app/status/[token] | – | Phase A, F0 | vitest, Playwright (refresh single-flight, offline replay) | planned |
| WS6 inventory UI | FA | app/inventory/**, app/procurement/**, app/material-requests/**, components/materials/** | – | W2a, W2b, F0 | vitest, Playwright | planned |
| WS6 assets/PM/reports UI | FB | app/assets/**, app/maintenance-plans/**, app/reports/page.tsx, app/departments/** | – | W1, W3, W4, F0 | vitest, Playwright | planned |
| WS6 workflow staff UI | FD | app/work-orders/[id], app/new-request, app/approvals, notification settings/admin | – | W1, W4, FA panel, FB picker | vitest, Playwright | planned |
| Integration | Coordinator | Sidebar nav, application.yml keys, .env.example, V60 diagnostics, CI test step, docs | V60 | all | full suite | planned |
| WS7 verification | V | throwaway Postgres (local pg16 or unique compose project), probes, e2e | – | all | fresh + upgrade migrations, 2 tenants, all roles, concurrency, security regression | planned |
