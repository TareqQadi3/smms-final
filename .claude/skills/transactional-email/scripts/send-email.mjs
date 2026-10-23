#!/usr/bin/env node
// Sends a transactional email via Resend or SendGrid directly (no middleman).
// Env: RESEND_API_KEY or SENDGRID_API_KEY (Resend takes priority if both set).

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i += 2) {
    const key = argv[i].replace(/^--/, "");
    args[key] = argv[i + 1];
  }
  return args;
}

function splitList(v) {
  return (v || "")
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
}

async function sendViaResend(args) {
  const res = await fetch("https://api.resend.com/emails", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${process.env.RESEND_API_KEY}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      from: args.from,
      to: splitList(args.to),
      subject: args.subject,
      html: args.html,
      text: args.text,
      cc: splitList(args.cc),
      bcc: splitList(args.bcc),
      reply_to: args["reply-to"],
    }),
  });
  const body = await res.json();
  if (!res.ok) throw new Error(`Resend error (${res.status}): ${JSON.stringify(body)}`);
  return body;
}

async function sendViaSendGrid(args) {
  const personalization = {
    to: splitList(args.to).map((email) => ({ email })),
  };
  if (args.cc) personalization.cc = splitList(args.cc).map((email) => ({ email }));
  if (args.bcc) personalization.bcc = splitList(args.bcc).map((email) => ({ email }));

  const content = [];
  if (args.text) content.push({ type: "text/plain", value: args.text });
  if (args.html) content.push({ type: "text/html", value: args.html });

  const res = await fetch("https://api.sendgrid.com/v3/mail/send", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${process.env.SENDGRID_API_KEY}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      personalizations: [personalization],
      from: { email: args.from },
      subject: args.subject,
      content,
      ...(args["reply-to"] ? { reply_to: { email: args["reply-to"] } } : {}),
    }),
  });
  if (!res.ok) {
    const body = await res.text();
    throw new Error(`SendGrid error (${res.status}): ${body}`);
  }
  return { status: "queued" };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (!args.to || !args.from || !args.subject || (!args.html && !args.text)) {
    console.error(
      'Usage: send-email.mjs --to <email[,email]> --from "Name <addr>" --subject "..." --html "<p>...</p>" [--text "..."] [--cc ...] [--bcc ...] [--reply-to ...]'
    );
    process.exit(1);
  }

  let result;
  if (process.env.RESEND_API_KEY) {
    result = await sendViaResend(args);
  } else if (process.env.SENDGRID_API_KEY) {
    result = await sendViaSendGrid(args);
  } else {
    console.error("Set RESEND_API_KEY or SENDGRID_API_KEY.");
    process.exit(1);
  }
  console.log("Sent:", JSON.stringify(result, null, 2));
}

main().catch((err) => {
  console.error(err.message || err);
  process.exit(1);
});
