import { describe, expect, it } from "vitest";
import {
  EMAIL_FIXED_KEYS,
  aiDisclosureEnabled,
  emailConfigProblems,
  emailMode,
  initialEmailPlatformConfig,
  isOpenTrigger,
  isSenderPattern,
  withEmailFixedKeys,
} from "@/lib/email-channel";

const VALID = {
  connection: "exchange-mailbox",
  host: "outlook.office365.com",
  address: "invoices@example.com",
};

describe("email channel rules", () => {
  it("accepts the three required keys and nothing else", () => {
    expect(emailConfigProblems(VALID)).toEqual([]);
  });

  it("names every missing required key", () => {
    expect(emailConfigProblems({})).toEqual(["connectionRequired", "hostRequired", "addressRequired"]);
  });

  it("refuses a ${…} reference as the connection", () => {
    expect(emailConfigProblems({ ...VALID, connection: "${connection:mail}" })).toEqual(["connectionReference"]);
  });

  it("refuses IP literals as the host, v4 and v6", () => {
    expect(emailConfigProblems({ ...VALID, host: "10.0.0.5" })).toEqual(["hostIpLiteral"]);
    expect(emailConfigProblems({ ...VALID, host: "[::1]" })).toEqual(["hostIpLiteral"]);
    expect(emailConfigProblems({ ...VALID, host: "fe80::1" })).toEqual(["hostIpLiteral"]);
  });

  it("allows only ports 993 and 143", () => {
    expect(emailConfigProblems({ ...VALID, port: "143" })).toEqual([]);
    expect(emailConfigProblems({ ...VALID, port: "587" })).toEqual(["port"]);
  });

  it("requires MIME types when attachments are stored, and only then", () => {
    expect(emailConfigProblems({ ...VALID, attachments: "store" })).toEqual(["mimeTypesRequired"]);
    expect(emailConfigProblems({ ...VALID, attachments: "store", attachmentMimeTypes: "application/pdf, image/*" })).toEqual([]);
    expect(emailConfigProblems({ ...VALID, attachments: "metadata" })).toEqual([]);
    expect(emailConfigProblems({ ...VALID, attachmentMimeTypes: "pdf" })).toEqual(["mimeTypesInvalid"]);
  });

  it("checks the numeric ranges at their bounds", () => {
    expect(emailConfigProblems({ ...VALID, pollSeconds: "60", maxMessageBytes: "1024", maxTurnsPerSenderPerDay: "0" })).toEqual([]);
    expect(emailConfigProblems({ ...VALID, pollSeconds: "86400", maxMessageBytes: "52428800", maxTurnsPerSenderPerDay: "100000" })).toEqual([]);
    expect(emailConfigProblems({ ...VALID, pollSeconds: "59" })).toEqual(["pollSeconds"]);
    expect(emailConfigProblems({ ...VALID, pollSeconds: "60.5" })).toEqual(["pollSeconds"]);
    expect(emailConfigProblems({ ...VALID, maxMessageBytes: "52428801" })).toEqual(["maxMessageBytes"]);
    expect(emailConfigProblems({ ...VALID, maxTurnsPerSenderPerDay: "100001" })).toEqual(["maxTurnsPerSenderPerDay"]);
  });

  it("wants a budget above zero, or none", () => {
    expect(emailConfigProblems({ ...VALID, dailyCostBudgetUsd: "2.50" })).toEqual([]);
    expect(emailConfigProblems({ ...VALID, dailyCostBudgetUsd: "0" })).toEqual(["dailyCostBudgetUsd"]);
    expect(emailConfigProblems({ ...VALID, dailyCostBudgetUsd: "-1" })).toEqual(["dailyCostBudgetUsd"]);
  });

  it("caps the owner addresses at five", () => {
    const five = "a@x.io,b@x.io,c@x.io,d@x.io,e@x.io";
    expect(emailConfigProblems({ ...VALID, ownerNotifyAddresses: five })).toEqual([]);
    expect(emailConfigProblems({ ...VALID, ownerNotifyAddresses: `${five},f@x.io` })).toEqual(["ownerNotifyTooMany"]);
  });

  it("needs a folder for move:", () => {
    expect(emailConfigProblems({ ...VALID, processedAction: "move:" })).toEqual(["moveFolderRequired"]);
    expect(emailConfigProblems({ ...VALID, processedAction: "move:Processed" })).toEqual([]);
  });

  it("accepts the three sender pattern shapes", () => {
    expect(isSenderPattern("ap@partner.com")).toBe(true);
    expect(isSenderPattern("*@partner.com")).toBe(true);
    expect(isSenderPattern("*@*.partner.com")).toBe(true);
    expect(isSenderPattern("partner.com")).toBe(false);
    expect(isSenderPattern("a*@partner.com")).toBe(false);
    expect(emailConfigProblems({ ...VALID, senderAllowlist: "*@partner.com, nope" })).toEqual(["senderAllowlist"]);
  });

  it("treats an empty allowlist without authentication as an open trigger", () => {
    expect(isOpenTrigger({})).toBe(false);
    expect(isOpenTrigger({ requireAuthentication: "false" })).toBe(true);
    expect(isOpenTrigger({ requireAuthentication: "false", senderAllowlist: " , " })).toBe(true);
    expect(isOpenTrigger({ requireAuthentication: "false", senderAllowlist: "*@example.com" })).toBe(false);
  });

  it("always writes the transport and a mode, trigger unless conversation is chosen", () => {
    expect(initialEmailPlatformConfig()).toEqual({ mode: "trigger", transport: "imap" });
    expect(withEmailFixedKeys({ ...VALID, transport: "graph" })).toEqual({ ...VALID, mode: "trigger", ...EMAIL_FIXED_KEYS });
    expect(withEmailFixedKeys({ ...VALID, mode: "whatever" }).mode).toBe("trigger");
    expect(withEmailFixedKeys({ ...VALID, mode: "conversation", smtpHost: "smtp.example.com" }).mode).toBe("conversation");
  });

  it("drops the other mode's keys, so the backend never refuses a hidden value", () => {
    const trigger = withEmailFixedKeys({ ...VALID, smtpHost: "smtp.example.com", aiDisclosure: "", correlateThreads: "true" });
    expect(trigger).toEqual({ ...VALID, mode: "trigger", transport: "imap", correlateThreads: "true" });
    const conversation = withEmailFixedKeys({
      ...VALID,
      mode: "conversation",
      smtpHost: "smtp.example.com",
      aiDisclosure: "",
      correlateThreads: "true",
      requireAuthentication: "false",
    });
    expect(conversation).toEqual({ ...VALID, mode: "conversation", transport: "imap", smtpHost: "smtp.example.com", aiDisclosure: "" });
  });
});

describe("email conversation mode rules", () => {
  const CONVERSATION = { ...VALID, mode: "conversation", smtpHost: "smtp.office365.com" };

  it("needs an SMTP host, a DNS name, and port 587 or 465", () => {
    expect(emailConfigProblems(CONVERSATION)).toEqual([]);
    expect(emailConfigProblems({ ...CONVERSATION, smtpHost: "" })).toEqual(["smtpHostRequired"]);
    expect(emailConfigProblems({ ...CONVERSATION, smtpHost: "10.0.0.5" })).toEqual(["smtpHostIpLiteral"]);
    expect(emailConfigProblems({ ...CONVERSATION, smtpHost: "bad host" })).toEqual(["smtpHostInvalid"]);
    expect(emailConfigProblems({ ...CONVERSATION, smtpPort: "465" })).toEqual([]);
    expect(emailConfigProblems({ ...CONVERSATION, smtpPort: "25" })).toEqual(["smtpPort"]);
  });

  it("checks the sender name, the template and disclosure lengths and the reply limit", () => {
    expect(emailConfigProblems({ ...CONVERSATION, fromName: "Support\nBcc: x@evil.example" })).toEqual(["fromNameInvalid"]);
    expect(emailConfigProblems({ ...CONVERSATION, fromName: "x".repeat(101) })).toEqual(["fromNameInvalid"]);
    expect(emailConfigProblems({ ...CONVERSATION, replyTemplate: "x".repeat(4001) })).toEqual(["replyTemplateTooLong"]);
    expect(emailConfigProblems({ ...CONVERSATION, aiDisclosure: "x".repeat(501) })).toEqual(["aiDisclosureTooLong"]);
    expect(emailConfigProblems({ ...CONVERSATION, maxRepliesPerThreadPerHour: "0" })).toEqual(["maxRepliesPerThreadPerHour"]);
    expect(emailConfigProblems({ ...CONVERSATION, maxRepliesPerThreadPerHour: "1000" })).toEqual([]);
  });

  it("ignores conversation fields in trigger mode, and is never an open trigger", () => {
    expect(emailConfigProblems({ ...VALID, smtpHost: "" })).toEqual([]);
    expect(isOpenTrigger({ ...CONVERSATION, requireAuthentication: "false" })).toBe(false);
  });

  it("reads the disclosure as on unless it is present and empty", () => {
    expect(aiDisclosureEnabled({})).toBe(true);
    expect(aiDisclosureEnabled({ aiDisclosure: "Written by an AI." })).toBe(true);
    expect(aiDisclosureEnabled({ aiDisclosure: " " })).toBe(false);
    expect(emailMode({ mode: "conversation" })).toBe("conversation");
    expect(emailMode({})).toBe("trigger");
  });
});
