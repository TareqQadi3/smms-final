---
name: transactional-email
description: This skill should be used when the user wants to send transactional emails from their own application - receipts, invoices, password resets, OTPs, notifications - via Resend or SendGrid, called directly with no middleman. Applies whenever a task mentions sending email, email notifications, or transactional email from the backend.
---

# Transactional Email (Resend / SendGrid, direct)

Sends real emails by calling the provider's HTTP API directly. Supports two
providers behind one script - pick whichever the user already has an account
with; no Composio/Rube or other broker involved.

## One-time setup (the user does this, not Claude)

**Resend** (simpler, recommended for new projects):
1. Sign up at resend.com, verify a sending domain (DNS records: SPF + DKIM).
2. Create an API key → set `RESEND_API_KEY`.

**SendGrid** (if the user already has an account):
1. Verify a sender domain or single sender at sendgrid.com.
2. Create a full-access (or Mail Send scoped) API key → set `SENDGRID_API_KEY`.

Set exactly one of `RESEND_API_KEY` or `SENDGRID_API_KEY` - the script auto-detects
which provider to use from whichever is present (Resend takes priority if both are set).

## Sending an email

```bash
node scripts/send-email.mjs \
  --to customer@example.com \
  --from "Qeedha <noreply@yourdomain.com>" \
  --subject "تأكيد الطلب #12345" \
  --html "<p>تم استلام طلبك بنجاح.</p>"
```

- `--from` must use a domain you verified with the provider - unverified sender
  domains get silently spam-filtered or rejected.
- `--html` and/or `--text` (plain-text fallback) - include both for best deliverability.
- `--reply-to` optional.
- `--cc` / `--bcc` optional, comma-separated.

## Common pitfalls

- Sending from an unverified domain: works for a few test emails then starts
  bouncing/landing in spam - verify DNS (SPF, DKIM, and ideally DMARC) before
  any real send volume.
- No unsubscribe link on marketing-style content will get flagged - this skill
  is for transactional mail (receipts, OTPs); use a proper marketing tool for
  campaigns.
- Rate limits: Resend free tier is 100 emails/day, 3,000/month - check current
  plan limits before a bulk operation.

## Reference

Full request/response shapes for both providers: `references/api-reference.md`.
