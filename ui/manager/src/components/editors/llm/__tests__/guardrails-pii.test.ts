import { describe, it, expect } from "vitest";
import {
  PII_COUNTRIES,
  PII_KINDS,
  PII_STANDARD_KINDS,
  effectivePiiKinds,
  enabledPiiCountries,
  enabledPiiKinds,
  withPiiCountries,
  withPiiKinds,
} from "../guardrails";
import type { GuardrailConfig } from "../types";

const pii = (parameters?: Record<string, string>): GuardrailConfig =>
  parameters === undefined ? { type: "pii-redaction" } : { type: "pii-redaction", parameters };

describe("pii-redaction kinds", () => {
  it("treats absent or blank patterns as the standard set", () => {
    expect(enabledPiiKinds(pii()).sort()).toEqual([...PII_STANDARD_KINDS].sort());
    expect(enabledPiiKinds(pii({ patterns: "   " })).sort()).toEqual([...PII_STANDARD_KINDS].sort());
  });

  it("reads kind names case-insensitively", () => {
    expect(enabledPiiKinds(pii({ patterns: "IBAN, email, FR_NIR" }))).toEqual(["email", "iban", "fr_nir"]);
  });

  it("maps the backend's entity-name aliases onto their kinds", () => {
    expect(enabledPiiKinds(pii({ patterns: "CREDIT_CARD,US_SSN,DE_TAX_ID" }))).toEqual(["creditcard", "ssn", "de_tax_id"]);
  });

  it("stores exactly the standard set as absent, anything else as a list", () => {
    expect(withPiiKinds(pii({ patterns: "email" }), [...PII_STANDARD_KINDS]).parameters).toBeUndefined();
    expect(withPiiKinds(pii(), ["email", "pl_pesel"]).parameters).toEqual({ patterns: "email,pl_pesel" });
  });

  it("lists every kind once", () => {
    expect(new Set(PII_KINDS).size).toBe(PII_KINDS.length);
    expect(PII_KINDS).toHaveLength(40);
  });
});

describe("pii-redaction countries", () => {
  it("reads country codes in any case and drops unknown ones", () => {
    expect(enabledPiiCountries(pii({ countries: "fr, de ,XX" }))).toEqual(["FR", "DE"]);
    expect(enabledPiiCountries(pii())).toEqual([]);
  });

  it("writes countries in a fixed order, and none as absent", () => {
    expect(withPiiCountries(pii(), ["DE", "US"]).parameters).toEqual({ countries: "US,DE" });
    expect(withPiiCountries(pii({ countries: "DE" }), []).parameters).toBeUndefined();
  });

  it("adds all of a country's identifiers to the selected kinds", () => {
    const g = pii({ patterns: "email", countries: "DE" });
    expect(effectivePiiKinds(g)).toEqual(["email", "de_tax_id", "de_social_security", "de_id_card"]);
  });

  it("names every identifier after its country", () => {
    for (const country of PII_COUNTRIES) {
      expect(country.kinds.length).toBeGreaterThan(0);
      for (const { kind } of country.kinds) {
        expect(kind === "ssn" || kind.startsWith(country.code.toLowerCase() + "_")).toBe(true);
      }
    }
  });
});
