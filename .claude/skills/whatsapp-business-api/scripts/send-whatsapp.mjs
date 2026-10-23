#!/usr/bin/env node
// Sends a WhatsApp message via Meta's WhatsApp Cloud API directly (no middleman).
// Env: WHATSAPP_ACCESS_TOKEN, WHATSAPP_PHONE_NUMBER_ID, WHATSAPP_API_VERSION (optional)

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
  const token = process.env.WHATSAPP_ACCESS_TOKEN;
  const phoneNumberId = process.env.WHATSAPP_PHONE_NUMBER_ID;
  const apiVersion = process.env.WHATSAPP_API_VERSION || "v21.0";

  if (!token || !phoneNumberId) {
    console.error("Missing WHATSAPP_ACCESS_TOKEN or WHATSAPP_PHONE_NUMBER_ID env vars.");
    process.exit(1);
  }
  if (!args.to) {
    console.error("Usage: send-whatsapp.mjs --to <number> (--text \"...\" | --template <name> --lang <code> [--params \"a,b,c\"])");
    process.exit(1);
  }

  let payload;
  if (args.template) {
    const params = (args.params || "")
      .split(",")
      .filter((p) => p.length > 0)
      .map((p) => ({ type: "text", text: p }));
    payload = {
      messaging_product: "whatsapp",
      to: args.to,
      type: "template",
      template: {
        name: args.template,
        language: { code: args.lang || "en_US" },
        ...(params.length ? { components: [{ type: "body", parameters: params }] } : {}),
      },
    };
  } else if (args.text) {
    payload = {
      messaging_product: "whatsapp",
      to: args.to,
      type: "text",
      text: { body: args.text },
    };
  } else {
    console.error("Provide either --text or --template.");
    process.exit(1);
  }

  const url = `https://graph.facebook.com/${apiVersion}/${phoneNumberId}/messages`;
  const res = await fetch(url, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(payload),
  });

  const body = await res.json();
  if (!res.ok) {
    console.error(`WhatsApp API error (${res.status}):`, JSON.stringify(body, null, 2));
    process.exit(1);
  }
  console.log("Sent:", JSON.stringify(body, null, 2));
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
