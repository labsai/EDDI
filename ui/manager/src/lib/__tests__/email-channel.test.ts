import { describe, expect, it } from "vitest";
import {
  EMAIL_FIXED_KEYS,
  emailConfigProblems,
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

  it("always writes mode and transport, overriding anything loaded", () => {
    expect(initialEmailPlatformConfig()).toEqual({ mode: "trigger", transport: "imap" });
    expect(withEmailFixedKeys({ ...VALID, mode: "conversation" })).toEqual({ ...VALID, ...EMAIL_FIXED_KEYS });
  });
});
