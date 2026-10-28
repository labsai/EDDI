import { describe, expect, it } from "vitest";
import { platformConfigForType } from "@/lib/channel-platform-config";

const WEBHOOK = {
  signingSecret: "${vault:hook-secret}",
  signatureScheme: "generic-hmac",
  signatureHeader: "X-Sig",
  conversationStrategy: "correlate",
  correlationKeyPath: "/id",
  inputTemplate: "New {event.type}",
};
const EMAIL = {
  mode: "trigger",
  transport: "imap",
  connection: "exchange-mailbox",
  host: "outlook.office365.com",
  address: "invoices@example.com",
  inputTemplate: "Email from {mail.from}",
};
const SLACK = {
  channelId: "C0123ABCDEF",
  botToken: "${vault:slack-bot}",
  signingSecret: "${vault:slack-signing}",
  hitlApprovalChannel: "C0999",
};
const FIXED = { mode: "trigger", transport: "imap" };

describe("platformConfigForType", () => {
  it("webhook → email keeps nothing but the fixed mode and transport", () => {
    expect(platformConfigForType("email", "webhook", WEBHOOK)).toEqual(FIXED);
  });

  it("slack → email drops the Slack credentials too", () => {
    expect(platformConfigForType("email", "slack", SLACK)).toEqual(FIXED);
  });

  it("email → webhook drops every email key, the shared-name inputTemplate included", () => {
    expect(platformConfigForType("webhook", "email", EMAIL)).toEqual({});
  });

  it("email → slack starts empty", () => {
    expect(platformConfigForType("slack", "email", EMAIL)).toEqual({});
  });

  it("slack ↔ webhook does not carry the signing secret, whose meaning differs", () => {
    expect(platformConfigForType("slack", "webhook", WEBHOOK)).toEqual({});
    expect(platformConfigForType("webhook", "slack", SLACK)).toEqual({});
  });

  it("keeps a new-type key a legacy document already carried", () => {
    expect(platformConfigForType("slack", "webhook", { ...WEBHOOK, channelId: "C42" })).toEqual({ channelId: "C42" });
  });

  it("does not resurrect stale keys across a round trip", () => {
    const toEmail = platformConfigForType("email", "webhook", WEBHOOK);
    expect(platformConfigForType("webhook", "email", toEmail)).toEqual({});
    const toWebhook = platformConfigForType("webhook", "email", EMAIL);
    expect(platformConfigForType("email", "webhook", toWebhook)).toEqual(FIXED);
  });

  it("leaves the config alone when the type does not change, and starts an unknown type empty", () => {
    expect(platformConfigForType("email", "email", EMAIL)).toEqual(EMAIL);
    expect(platformConfigForType("teams", "slack", SLACK)).toEqual({});
    expect(platformConfigForType("webhook", "slack", null)).toEqual({});
  });
});
