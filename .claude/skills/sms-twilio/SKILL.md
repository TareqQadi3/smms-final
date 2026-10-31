---
name: sms-twilio
description: This skill should be used when the user wants to send SMS messages from their own application - OTPs, delivery/payment alerts, appointment reminders - via Twilio's REST API called directly with no middleman. Applies whenever a task mentions sending SMS or text message notifications from the backend.
---

# SMS via Twilio (direct)

Sends real SMS messages by calling Twilio's REST API directly over HTTPS Basic
Auth. No Composio/Rube or other broker - only needs a Twilio account.

## One-time setup (the user does this, not Claude)

1. Sign up at twilio.com, verify a phone number for testing (trial accounts can
   only send to verified numbers).
2. From the Console dashboard, note **Account SID** and **Auth Token**.
3. Buy/reserve a sending number (or use an approved Sender ID / WhatsApp-enabled
   number in supported countries) → this becomes `TWILIO_FROM_NUMBER`.
4. Set environment variables:
   ```
   TWILIO_ACCOUNT_SID=AC...
   TWILIO_AUTH_TOKEN=...
   TWILIO_FROM_NUMBER=+1XXXXXXXXXX
   ```
5. For Saudi/GCC numbers specifically: check Twilio's country-specific
   regulatory requirements (some countries require a registered Sender ID or
   local number - see Twilio's regulatory guidelines for the target country
   before going live).

## Sending an SMS

```bash
node scripts/send-sms.mjs --to +9665XXXXXXXX --text "رمز التحقق الخاص بك: 482913"
```

## Common pitfalls

- Trial accounts can only send to phone numbers you've manually verified in
  the Twilio Console - upgrade to a paid account to send to any number.
- `--to` must be in E.164 format (`+` then country code then number, no spaces).
- Some countries require pre-registered Sender IDs for promotional SMS - OTP/
  transactional SMS is usually less restricted but still varies by country.
- Twilio charges per segment (160 chars for GSM-7, less for messages with
  Arabic/emoji since those use UCS-2 encoding at 70 chars/segment) - keep OTP/
  alert messages short to avoid multi-segment charges.

## Reference

Full request/response shape: `references/api-reference.md`.
