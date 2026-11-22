import { describe, expect, it } from "vitest";
import { platformConfigForType, adoptStoredVersion, emptiesSenderAllowlist } from "@/lib/channel-platform-config";

describe("emptiesSenderAllowlist", () => {
  it("is true only when a non-empty stored allowlist would be saved empty", () => {
    expect(emptiesSenderAllowlist({ senderAllowlist: "a@x.com" }, {})).toBe(true);
    expect(emptiesSenderAllowlist({ senderAllowlist: "a@x.com" }, { senderAllowlist: " , " })).toBe(true);
    expect(emptiesSenderAllowlist({ senderAllowlist: "a@x.com" }, { senderAllowlist: "b@x.com" })).toBe(false);
    expect(emptiesSenderAllowlist({}, {})).toBe(false);
    expect(emptiesSenderAllowlist(undefined, {})).toBe(false);
  });
});

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
const FIXED = { mode: "trigger", actWhen: "addressed", transport: "imap" };

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

describe("adoptStoredVersion", () => {
  const cfg = (host: string, lists: string, name = "n") => ({
    name,
    platformConfig: { host, senderAllowlist: lists } as Record<string, string>,
  });

  it("takes every stored field, so another editor's change is not written back from an old snapshot", () => {
    const merged = adoptStoredVersion(cfg("old", "a@x.com"), cfg("old", "a@x.com"), cfg("theirs", "b@x.com", "renamed"));
    expect(merged).toEqual(cfg("theirs", "b@x.com", "renamed"));
  });

  it("keeps a field the user edited on this page, and still takes the stored lists", () => {
    const merged = adoptStoredVersion(cfg("old", "a@x.com", "mine"), cfg("old", "a@x.com", "n"), cfg("old", "b@x.com", "n"));
    expect(merged).toEqual(cfg("old", "b@x.com", "mine"));
  });

  it("keeps an edited platform key but never a stale sender list", () => {
    const merged = adoptStoredVersion(cfg("mine", "a@x.com"), cfg("old", "a@x.com"), cfg("old", "b@x.com"));
    expect(merged.platformConfig).toEqual({ host: "mine", senderAllowlist: "b@x.com" });
  });
});

describe("adoptStoredVersion routing", () => {
  type Routed = { targets: { name: string }[]; defaultTargetName: string; platformConfig: Record<string, string> };
  const routed = (names: string[], def: string): Routed => ({ targets: names.map((name) => ({ name })), defaultTargetName: def, platformConfig: {} });

  it("keeps the user's targets and default together, so the default is always among the targets", () => {
    // The user made B the default; the stored version removed B.
    const merged = adoptStoredVersion(routed(["A", "B"], "B"), routed(["A", "B"], "A"), routed(["A"], "A"));
    expect(merged.defaultTargetName).toBe("B");
    expect(merged.targets.map((t) => t.name)).toContain("B");
  });

  it("takes the stored targets and default together when the user did not touch the routing", () => {
    const merged = adoptStoredVersion(routed(["A", "B"], "A"), routed(["A", "B"], "A"), routed(["A"], "A"));
    expect(merged.targets.map((t) => t.name)).toEqual(["A"]);
    expect(merged.defaultTargetName).toBe("A");
  });
});
