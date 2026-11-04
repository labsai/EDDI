import { describe, it, expect } from "vitest";
import { PII_KINDS, enabledPiiKinds, withPiiKinds } from "../guardrails";
import type { GuardrailConfig } from "../types";

const pii = (patterns?: string): GuardrailConfig =>
  patterns === undefined ? { type: "pii-redaction" } : { type: "pii-redaction", parameters: { patterns } };

describe("pii-redaction kinds", () => {
  it("treats absent patterns as every kind", () => {
    expect(enabledPiiKinds(pii())).toEqual([...PII_KINDS]);
    expect(enabledPiiKinds(pii("   "))).toEqual([...PII_KINDS]);
  });

  it("reads kind names case-insensitively, in the backend's order", () => {
    expect(enabledPiiKinds(pii("IBAN, email"))).toEqual(["email", "iban"]);
  });

  it("maps the backend's entity-name aliases onto their kinds", () => {
    expect(enabledPiiKinds(pii("CREDIT_CARD,US_SSN,DE_TAX_ID"))).toEqual(["ssn", "creditcard", "de_tax_id"]);
  });

  it("stores every kind as absent", () => {
    expect(withPiiKinds(pii("email"), [...PII_KINDS]).parameters?.patterns ?? "").toBe("");
  });
});
