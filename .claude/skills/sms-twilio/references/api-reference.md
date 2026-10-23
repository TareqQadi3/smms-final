# Twilio SMS - Quick Reference

## Endpoint
```
POST https://api.twilio.com/2010-04-01/Accounts/{AccountSid}/Messages.json
Authorization: Basic base64(AccountSid:AuthToken)
Content-Type: application/x-www-form-urlencoded

To=%2B9665XXXXXXXX&From=%2B1XXXXXXXXXX&Body=your+message
```

## Success response (HTTP 201)
```json
{
  "sid": "SMxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
  "status": "queued",
  "to": "+9665XXXXXXXX",
  "from": "+1XXXXXXXXXX",
  "body": "your message"
}
```
`status` progresses: queued → sent → delivered (or `failed`/`undelivered`) -
poll `GET .../Messages/{Sid}.json` or configure a status callback URL to
track delivery.

## Common error codes
| Code | Meaning |
|---|---|
| 21211 | Invalid `To` phone number format |
| 21608 | Trial account: `To` number not verified |
| 21610 | Recipient has opted out (STOP reply) |
| 30003/30005 | Unreachable / unknown destination handset |

## Segment/encoding notes
- GSM-7 (Latin text only): 160 chars/segment
- UCS-2 (Arabic, emoji, most non-Latin scripts): 70 chars/segment
- Twilio auto-splits and bills per segment - keep messages short.
