import { describe, it, expect } from "vitest";
import { toVaultRef, extractVaultKeyName } from "../vault-ref";

/**
 * The stakes here are asymmetric, which is why the braces get their own test:
 * `toVaultRef` producing a bare `vault:KEY` is not a formatting wobble. The
 * backend's `SecretReference.isVaultReference` requires `${vault:...}`, so the
 * bare form falls through to the plaintext branch and the key NAME is stored
 * verbatim as the provider credential.
 *
 * In the other direction, a value that is not a reference must come back as
 * `null` rather than as itself — the config stores key names, and a pasted
 * secret returned from here would be persisted as though it were one.
 */

describe("toVaultRef", () => {
  it("wraps the name in the braces the backend requires", () => {
    expect(toVaultRef("OPENAI_KEY")).toBe("${vault:OPENAI_KEY}");
  });

  it("produces a reference the extractor accepts", () => {
    expect(extractVaultKeyName(toVaultRef("ROUND_TRIP"))).toBe("ROUND_TRIP");
  });
});

describe("extractVaultKeyName", () => {
  it.each([
    ["the canonical form", "${vault:MY_KEY}"],
    ["a bare reference", "vault:MY_KEY"],
    ["the legacy spelling", "eddivault:MY_KEY"],
    ["the legacy spelling, braced", "${eddivault:MY_KEY}"],
    ["braces without the dollar", "{vault:MY_KEY}"],
    ["surrounding whitespace", "  ${vault:MY_KEY}  "],
  ])("reads the key name from %s", (_label, value) => {
    expect(extractVaultKeyName(value)).toBe("MY_KEY");
  });

  it("trims the extracted name, not just the input", () => {
    expect(extractVaultKeyName("${vault: MY_KEY }")).toBe("MY_KEY");
  });

  it.each([
    ["plain text", "sk-live-not-a-reference"],
    ["an empty string", ""],
    ["whitespace only", "   "],
    ["a lookalike prefix", "myvault:MY_KEY"],
    ["the scheme with no name", "${vault:}"],
    // The regex is module-level, so its mutants are static and this file's
    // 100% does not speak for them. Dropping the closing end-of-string anchor
    // makes this case return "MY_KEY" — a pasted secret coming back as a key
    // name, which is the one thing the module exists to prevent.
    ["a reference with trailing content", "${vault:MY_KEY} plus a pasted secret"],
  ])("returns null for %s", (_label, value) => {
    expect(extractVaultKeyName(value)).toBeNull();
  });

  it("does not treat a secret that merely mentions vault as a reference", () => {
    expect(extractVaultKeyName("the vault:key is in the drawer")).toBeNull();
  });
});
