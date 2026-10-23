# WhatsApp Cloud API - Quick Reference

## Endpoint
```
POST https://graph.facebook.com/{version}/{phone-number-id}/messages
Authorization: Bearer {access-token}
Content-Type: application/json
```

## Text message body
```json
{
  "messaging_product": "whatsapp",
  "to": "9665XXXXXXXX",
  "type": "text",
  "text": { "body": "your message" }
}
```

## Template message body
```json
{
  "messaging_product": "whatsapp",
  "to": "9665XXXXXXXX",
  "type": "template",
  "template": {
    "name": "order_confirmation",
    "language": { "code": "ar" },
    "components": [
      { "type": "body", "parameters": [{ "type": "text", "text": "12345" }] }
    ]
  }
}
```

## Media message body (image example)
```json
{
  "messaging_product": "whatsapp",
  "to": "9665XXXXXXXX",
  "type": "image",
  "image": { "link": "https://example.com/receipt.png" }
}
```

## Common error codes
| Code | Meaning | Fix |
|---|---|---|
| 131047 | Re-engagement message outside 24h window | Use a template instead of free text |
| 131026 | Message undeliverable | Number not on WhatsApp or blocked the business |
| 100 | Invalid parameter | Check phone number format (digits only, country code, no `+`) |
| 190 | Access token expired | Regenerate token (use a permanent System User token for production) |

## Webhook payload (incoming message) - shape only
```json
{
  "entry": [{
    "changes": [{
      "value": {
        "messages": [{ "from": "9665XXXXXXXX", "type": "text", "text": { "body": "..." } }],
        "statuses": [{ "id": "wamid...", "status": "delivered" }]
      }
    }]
  }]
}
```
Verify webhook signatures using the App Secret (`X-Hub-Signature-256` header, HMAC-SHA256)
before trusting any incoming payload.
