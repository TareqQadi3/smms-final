#!/usr/bin/env node
// Sends an SMS via Twilio's REST API directly (no middleman).
// Env: TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN, TWILIO_FROM_NUMBER

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i += 2) {
    const key = argv[i].replace(/^--/, "");
    args[key] = argv[i + 1];
  }
  return args;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const sid = process.env.TWILIO_ACCOUNT_SID;
  const token = process.env.TWILIO_AUTH_TOKEN;
  const from = process.env.TWILIO_FROM_NUMBER;

  if (!sid || !token || !from) {
    console.error("Missing TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN, or TWILIO_FROM_NUMBER env vars.");
    process.exit(1);
  }
  if (!args.to || !args.text) {
    console.error('Usage: send-sms.mjs --to "+9665XXXXXXXX" --text "message"');
    process.exit(1);
  }

  const url = `https://api.twilio.com/2010-04-01/Accounts/${sid}/Messages.json`;
  const body = new URLSearchParams({
    To: args.to,
    From: from,
    Body: args.text,
  });

  const res = await fetch(url, {
    method: "POST",
    headers: {
      Authorization: "Basic " + Buffer.from(`${sid}:${token}`).toString("base64"),
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body,
  });

  const data = await res.json();
  if (!res.ok) {
    console.error(`Twilio error (${res.status}):`, JSON.stringify(data, null, 2));
    process.exit(1);
  }
  console.log("Sent:", JSON.stringify({ sid: data.sid, status: data.status }, null, 2));
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
