---
name: whatsapp-business-api
description: This skill should be used when the user wants to send WhatsApp messages (text, template/notification, or media) from their own application - order confirmations, OTPs, payment reminders, support replies. Calls Meta's WhatsApp Cloud API directly, no third-party middleman. Applies whenever a task mentions WhatsApp messaging, WhatsApp Business API, or sending customer notifications via WhatsApp.
---

# WhatsApp Business API (Meta Cloud API, direct)

Sends real WhatsApp messages by calling Meta's WhatsApp Cloud API directly over HTTPS.
No Composio/Rube or any other middleman - this only needs a Meta developer app and the
two credentials below.

## One-time setup (the user does this, not Claude)

1. Create a Meta App at developers.facebook.com → add the "WhatsApp" product.
2. In WhatsApp → API Setup, note:
   - **Phone number ID** (test number works for development)
   - **Temporary access token** (24h) - for production, generate a permanent token via
     a System User in Meta Business Settings (Business Settings → System Users → add
     WhatsApp asset → generate token with `whatsapp_business_messaging` permission).
3. Set environment variables before running any script here:
   ```
   WHATSAPP_ACCESS_TOKEN=...
   WHATSAPP_PHONE_NUMBER_ID=...
   ```
4. To receive replies/status updates, configure a webhook (Meta App → WhatsApp →
   Configuration → Webhook) pointing at an endpoint in the user's own backend -
   that endpoint is application-specific, not part of this skill.

## Sending a message

```bash
node scripts/send-whatsapp.mjs --to 9665XXXXXXXX --text "طلبك جاهز للاستلام"
node scripts/send-whatsapp.mjs --to 9665XXXXXXXX --template order_confirmation --lang ar --params "12345,150 SAR"
```

- `--to` is the recipient's full number in international format, digits only (no `+`).
- Free-form `--text` only works within a 24h customer-service window (the customer
  messaged the business first, or replied recently). Outside that window, WhatsApp
  requires a **pre-approved template** (`--template`) - approve templates in Meta
  Business Manager → WhatsApp Manager → Message Templates before using them here.
- `--params` are comma-separated values filling the template's `{{1}}`, `{{2}}`, ...
  placeholders in order.

## Common pitfalls

- Sending free-form text outside the 24h window silently fails with error code 131047 -
  use a template instead.
- The recipient number must have WhatsApp active and, for test numbers, must be added
  to the app's allowed recipient list first (Meta App → WhatsApp → API Setup →
  "To" list) until the app passes Business Verification.
- Templates take minutes to hours to get approved the first time - don't block a launch
  on same-day approval.
- Rate limits scale with the phone number's messaging tier (starts at 250 unique
  recipients/24h) - check current tier in WhatsApp Manager before a bulk send.

## Reference

- API version pinned in the script via `WHATSAPP_API_VERSION` (default `v21.0`) -
  bump periodically per Meta's changelog.
- Full parameter reference: `references/api-reference.md`.
