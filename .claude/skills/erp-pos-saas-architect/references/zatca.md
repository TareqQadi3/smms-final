# ZATCA E-Invoicing (Fatoora) Reference

Saudi Arabia's ZATCA e-invoicing mandate applies to any resident VAT-registered taxpayer. Non-resident VAT-registered entities are currently exempt. This reference covers the technical shape of the requirements — always confirm current wave deadlines with the user/ZATCA portal since thresholds and dates roll forward regularly.

## Phase 1 — Generation (in force since Dec 4, 2021)

Baseline for every taxpayer, no ZATCA integration required yet:
- Generate invoices electronically (not paper scanned/copied into digital form).
- Include all standard tax invoice fields plus a **QR code** on Simplified (B2C) invoices.
- Invoices must not be deletable/editable without a trail once issued — no "silent edit" functions anywhere in the system.
- No default/factory passwords in the invoicing solution.
- Store invoices securely, tamper-evident, retrievable for audit.

**Design implication:** every invoice-issuing code path in the POS/ERP must go through an immutable, append-only invoice table from day one — this isn't something to bolt on later for Phase 2.

## Phase 2 — Integration (rolling out in revenue-based waves since Jan 1, 2023)

Businesses are notified at least 6 months ahead of their wave deadline; thresholds have been dropping over time (as of mid-2026, waves have reached businesses with taxable revenue over SAR 375,000 — confirm current wave/threshold when it matters for the user's compliance timeline, since ZATCA continues to add waves).

Two invoice flows, different integration pattern for each:

| | Standard Tax Invoice (B2B/B2G) | Simplified Tax Invoice (B2C) |
|---|---|---|
| Flow | **Clearance** — sent to ZATCA, must be approved *before* delivery to buyer | **Reporting** — shared with buyer immediately, reported to ZATCA within 24h |
| Use when | Sales ≥ SAR 1,000, exports, intra-GCC, buyer needs input-VAT credit | Typical POS retail sale < SAR 1,000 |
| QR code | Optional in XML (ZATCA adds/validates it as part of clearance) | Mandatory |
| Sync/async | Synchronous — checkout flow waits on ZATCA's API response or must queue | Asynchronous — invoice signed client-side first |

### Format
- **UBL 2.1 XML**, ZATCA-specific namespaces/extensions — generic UBL without the Saudi-specific elements gets rejected.
- Or **PDF/A-3 with embedded XML** for buyer-facing presentment.
- Each line item needs a sequential numeric ID starting from 1 — a commonly missed validation rule.
- `InvoiceTypeCode` carries a 7-digit bitmask encoding standard/simplified/credit/debit.

### Security chain (implement in this order)
1. **Onboarding**: register with ZATCA, obtain a **CSID** (Cryptographic Stamp Identifier / X.509-based certificate) via the Fatoora portal.
2. **UUID**: every invoice gets a unique 36-char UUID.
3. **Invoice hash**: SHA-256 hash of the XML content.
4. **Previous Invoice Hash (PIH)**: every invoice embeds the hash of the immediately preceding invoice — a hash chain, so tampering with any past invoice breaks the chain going forward. Store `previous_invoice_hash` per branch/device sequence, not globally, if you have multiple concurrent POS terminals — clarify the sequencing unit with the user before implementing.
5. **Cryptographic stamp / digital signature**: XAdES-BES style signature over the invoice, signed with the CSID-issued key.
6. **Submit to ZATCA API**: clearance (sync, B2B) or reporting (async within 24h, B2C).

### QR code (mandatory on B2C, required post-clearance on B2B)
- **TLV (Tag-Length-Value)** encoded, then **Base64**.
- 9 tags for Phase 2, typically including: seller name, VAT registration number, timestamp, invoice total (with VAT), VAT total, invoice hash, cryptographic stamp, public key, and (for B2C) the ECDSA signature.
- Max ~500 characters — don't try to pack extra data in.
- Never generate this with a generic/online QR library that just encodes a URL or plain text — it must be raw TLV bytes, Base64-encoded, matching ZATCA's field order exactly.

### Prohibited functions (build these constraints in, don't just avoid triggering them)
- No anonymous/unauthenticated invoice creation.
- No deleting or altering invoices without an audit trail.
- No multiple/parallel invoice numbering sequences that could allow gaps or duplication.
- No altering timestamps after the fact.
- No offline export of logs without equivalent security controls.

### Credit/debit notes
Same Phase 2 rules apply — they need their own UUID, hash chain entry, and clearance/reporting flow. Don't treat returns/voids as "just an invoice with a negative amount" bypassing the compliance pipeline.

### Practical build guidance for this project
- Put ZATCA XML generation, hashing, signing, and API submission behind a dedicated service/module — don't scatter compliance logic across the POS checkout controller. Every invoice-producing flow (POS sale, ERP sales invoice, credit note, POS return) should call the same service.
- Persist ZATCA submission status per invoice (`pending`, `cleared`, `reported`, `rejected`) and the ZATCA response/rejection reason — the accounting module and support staff will need to see this.
- Retain invoices and their XML for the legally required period (multi-year — confirm current retention requirement, commonly cited around 6 years) — factor this into storage/archival design, not just the live transactional DB.
- When the user is implementing this, ask which wave/deadline applies to their specific client before deciding whether Phase 1-only or full Phase 2 integration is in scope for a given build.
