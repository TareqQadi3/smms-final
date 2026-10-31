# Resend / SendGrid - Quick Reference

## Resend
```
POST https://api.resend.com/emails
Authorization: Bearer {RESEND_API_KEY}
Content-Type: application/json

{
  "from": "Qeedha <noreply@yourdomain.com>",
  "to": ["customer@example.com"],
  "subject": "...",
  "html": "<p>...</p>",
  "text": "...",
  "cc": [], "bcc": [],
  "reply_to": "support@yourdomain.com"
}
```
Response: `{ "id": "..." }` on success (HTTP 200).

## SendGrid
```
POST https://api.sendgrid.com/v3/mail/send
Authorization: Bearer {SENDGRID_API_KEY}
Content-Type: application/json

{
  "personalizations": [{ "to": [{ "email": "customer@example.com" }] }],
  "from": { "email": "noreply@yourdomain.com" },
  "subject": "...",
  "content": [{ "type": "text/html", "value": "<p>...</p>" }]
}
```
Response: HTTP 202 with empty body on success.

## Domain verification (required before real sending)
Both providers require you to add DNS records to the sending domain:
- **SPF** (TXT record) - authorizes the provider to send on your behalf
- **DKIM** (CNAME/TXT records) - signs outgoing mail
- **DMARC** (TXT record, recommended) - tells receivers what to do with mail
  that fails SPF/DKIM

Without these, mail is delivered inconsistently or lands in spam. Both
dashboards show exact records to add and verify domain status.

## Rate limits (check current plan - these change)
- Resend free: 100/day, 3,000/month
- SendGrid free: 100/day
