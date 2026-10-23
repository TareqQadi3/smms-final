# Module Reference

Read the relevant section before implementing that module. Each section lists core entities, key workflows, and edge cases a product manager should push back on if missing.

## POS Sales

**Entities**: `sales`, `sale_items`, `payments` (supports split tender), `sale_returns`
**Workflows**: cart build → discount/tax calc → payment (cash/card/wallet, split allowed) → invoice issuance (see ZATCA) → receipt print/QR
**Edge cases to design for**: partial refunds/exchanges tied to the original sale item (not a generic refund), voided sales require manager approval + audit log, offline mode (POS terminal loses internet — queue sales locally, sync with idempotency keys, resolve stock conflicts on reconnect), price overrides need a permission + audit reason, multi-currency if the store serves tourists, held/parked carts (customer steps away).

## Purchasing

**Entities**: `purchase_orders`, `po_items`, `goods_receipts`, `supplier_invoices`
**Workflows**: PO created → sent to supplier → goods received (may be partial/split across multiple receipts) → supplier invoice matched against PO + receipt (three-way match) → payment
**Edge cases**: over/under receipt tolerance, price discrepancies between PO and supplier invoice, drop-ship (goods go direct to customer, never hit warehouse stock), backorders.

## Inventory

**Entities**: `products`, `product_variants` (color/storage/etc.), `stock_levels` (per branch/warehouse), `stock_movements`, `stock_transfers`, `stocktakes`
**Workflows**: stock in (purchase/transfer/adjustment) and out (sale/transfer/adjustment/write-off) always create a `stock_movements` row — never mutate `stock_levels` directly without one.
**Edge cases**: negative stock policy (allow with warning vs. hard block — ask the user which per product type), serialized items (phones — see Device Tracking) vs. bulk/fungible items (accessories) need different tracking granularity, reserved stock for pending sales/repairs, barcode collisions across suppliers (use internal SKU as source of truth, barcode as a lookup alias, support multiple barcodes per product).

## Repair Management

**Entities**: `repair_tickets`, `repair_status_history`, `repair_parts_used`, `repair_diagnostics`
**Workflows**: intake (device details, reported issue, condition checklist/photos) → diagnosis → customer quote approval → repair (parts consumed from inventory) → QA → ready-for-pickup notification → close (often generates a sale/invoice for parts + labor)
**Edge cases**: customer declines quote (device return workflow, possible diagnostic fee), warranty repair vs. paid repair (different invoicing/costing), parts on backorder mid-repair, unclaimed devices after N days (needs a policy field, not hardcoded), linking repair to the original sale for warranty validation.

## Device Tracking & Warranty

**Entities**: `devices` (IMEI/serial), `device_sale_link`, `warranties`, `warranty_claims`
**Workflows**: device serial captured at sale → warranty term starts at sale date (or activation date if different) → claims validated against warranty term + proof of purchase
**Edge cases**: resold/transferred devices (warranty may or may not transfer — policy-dependent), grey-market/parallel-import devices (may have no manufacturer warranty — store-only warranty terms differ), duplicate IMEI detection (fraud signal, don't silently allow), warranty void conditions (physical damage, unauthorized repair) need to be recorded, not just assumed.

## Accounting

**Entities**: `chart_of_accounts`, `journal_entries`, `journal_lines`, `fiscal_periods`
**Workflows**: operational events (sale, purchase, stock write-off, repair completion) auto-generate journal entries — don't make accounting a manual re-entry step. Sale → debit cash/AR, credit revenue, credit VAT payable, debit/credit COGS + inventory.
**Edge cases**: period locking (no backdated entries into a closed fiscal period without explicit reopening + audit trail), multi-currency revaluation if applicable, manual journal entries still need the same audit trail as auto-generated ones, reversing entries for corrections (never edit a posted entry).

## Cash Management

**Entities**: `cash_sessions` (shift open/close), `cash_movements` (paid in/out), `till_reconciliation`
**Workflows**: cashier opens shift with a starting float → sales/refunds move cash → shift close counts actual cash vs. expected (from sales + movements) → variance recorded
**Edge cases**: multiple cashiers/tills per branch open concurrently, mid-shift manager cash pickups, variance beyond threshold should require a note/approval, cash session must be closed before next day's session opens (or explicitly allow overlap if multi-till).

## Customers & Suppliers

**Entities**: shared `parties` base (or separate `customers`/`suppliers` — pick one pattern per project and stay consistent) with `credit_terms`, `statements`
**Workflows**: credit limit checks at sale time for account customers, statement generation (open invoices, aging)
**Edge cases**: walk-in customer (no record) vs. registered customer — don't force account creation for POS walk-ins, blacklist/credit-hold flag, one entity acting as both customer and supplier (common for trade-ins).

## Financial Reports

**Core reports**: P&L, balance sheet, inventory valuation (FIFO/weighted-avg — confirm which method per project), sales by branch/product/category, tax report (VAT collected/paid, feeds ZATCA obligations), cash flow.
**Design note**: reports should query from posted journal entries / materialized aggregates for accounting reports, not recompute from raw transactional tables on every request at scale — consider a reporting read-model once data volume justifies it, but don't over-engineer this on day one.
