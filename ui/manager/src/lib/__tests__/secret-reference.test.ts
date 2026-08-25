import { describe, it, expect } from "vitest";
import {
  canonicalizeReference,
  hasReferencePrefix,
  interpolatedSegments,
  isSecretReference,
  isVaultScheme,
  parseSecretReference,
  referenceLabel,
  splitTemplate,
  toReference,
  toVaultReference,
  REFERENCE_SCHEMES,
} from "@/lib/secret-reference";

/**
 * The grammar three components used to spell out for themselves.
 *
 * The cases below are the ones that were wrong in at least one of the copies:
 * `vars` recognised by validation but not by the picker, whitespace trimmed on
 * one path and not another, and a canonicalisation that rewrote the scheme.
 */

describe("isSecretReference — the backend's rule", () => {
  it("accepts every scheme the backend accepts", () => {
    for (const scheme of REFERENCE_SCHEMES) {
      expect(isSecretReference(`\${${scheme}:key}`)).toBe(true);
    }
  });

  it("trims, because a pasted reference carries whitespace", () => {
    expect(isSecretReference("  ${vault:key}\n")).toBe(true);
  });

  it("rejects a literal that merely CONTAINS a reference", () => {
    expect(isSecretReference("sk-live-x${vault:unused}")).toBe(false);
  });

  it("rejects the unbraced spellings the backend refuses", () => {
    expect(isSecretReference("vault:key")).toBe(false);
    expect(isSecretReference("eddivault:key")).toBe(false);
    expect(isSecretReference("vars:key")).toBe(false);
  });

  it("rejects an empty body, an unknown scheme and a non-string", () => {
    expect(isSecretReference("${vault:}")).toBe(false);
    expect(isSecretReference("${env:KEY}")).toBe(false);
    expect(isSecretReference(null)).toBe(false);
    expect(isSecretReference(undefined)).toBe(false);
  });
});

describe("hasReferencePrefix — what somebody typing looks like", () => {
  it("recognises a half-typed reference, which the strict test cannot", () => {
    expect(hasReferencePrefix("${vault:")).toBe(true);
    expect(isSecretReference("${vault:")).toBe(false);
  });

  it("recognises the unbraced spellings and every scheme", () => {
    expect(hasReferencePrefix("vault:key")).toBe(true);
    expect(hasReferencePrefix("eddivault:key")).toBe(true);
    // The gap that made the picker refuse a value the backend accepts.
    expect(hasReferencePrefix("${vars:tenant-key}")).toBe(true);
    expect(hasReferencePrefix("vars:tenant-key")).toBe(true);
  });

  it("does not mistake a plain secret for one", () => {
    expect(hasReferencePrefix("sk-live-abcdef")).toBe(false);
    expect(hasReferencePrefix("")).toBe(false);
  });
});

describe("canonicalizeReference", () => {
  it("braces an unbraced reference", () => {
    expect(canonicalizeReference("vault:jira-token")).toBe("${vault:jira-token}");
  });

  it("preserves the scheme rather than normalising it to vault", () => {
    // eddivault and vars resolve differently; rewriting one into another would
    // change which secret the connection reads.
    expect(canonicalizeReference("vars:tenant-key")).toBe("${vars:tenant-key}");
    expect(canonicalizeReference("eddivault:old")).toBe("${eddivault:old}");
  });

  it("leaves a half-typed braced value completely alone", () => {
    // The bug this pins: rewriting `${vault:` mid-typing yields `${vault:}` and
    // strands the rest of the word after the closing brace.
    expect(canonicalizeReference("${vault:")).toBeNull();
    expect(canonicalizeReference("${vault:jir")).toBeNull();
  });

  it("has nothing to do for a value that is already canonical", () => {
    expect(canonicalizeReference("${vault:key}")).toBeNull();
  });

  it("never produces a value its own parser would reject", () => {
    // `vault:key}` used to canonicalise to `${vault:key}}` — a correction that
    // left the value exactly as unusable as it found it.
    for (const input of ["vault:key}", "vars:a}b", "eddivault:}"]) {
      const result = canonicalizeReference(input);
      if (result !== null) expect(isSecretReference(result)).toBe(true);
    }
    expect(canonicalizeReference("vault:key}")).toBeNull();
  });

  it("has nothing to do for a literal or an empty value", () => {
    expect(canonicalizeReference("sk-live-abc")).toBeNull();
    expect(canonicalizeReference("   ")).toBeNull();
  });

  it("trims around the value and inside the body", () => {
    expect(canonicalizeReference("  vault:key  ")).toBe("${vault:key}");
  });
});

describe("referenceLabel", () => {
  it("shows a vault key bare", () => {
    expect(referenceLabel("${vault:jira-token}")).toBe("jira-token");
  });

  it("keeps the vars scheme, which is part of the value's meaning", () => {
    expect(referenceLabel("${vars:tenant-key}")).toBe("vars:tenant-key");
  });

  it("keeps a tenant-qualified key intact", () => {
    expect(referenceLabel("${vault:acme/key}")).toBe("acme/key");
  });

  it("reads an unbraced value too, so a chip stays legible mid-correction", () => {
    expect(referenceLabel("vault:key")).toBe("key");
  });
});

describe("isVaultScheme", () => {
  it("separates vault-backed references from variable ones", () => {
    // A `${vars:…}` reference resolves where the picker cannot look, so
    // checking it against the vault key list would flag every one as missing.
    expect(isVaultScheme("${vault:key}")).toBe(true);
    expect(isVaultScheme("${eddivault:key}")).toBe(true);
    expect(isVaultScheme("${vars:key}")).toBe(false);
    expect(isVaultScheme("vars:key")).toBe(false);
  });
});

describe("splitTemplate", () => {
  it("splits a prefix and one trailing reference", () => {
    expect(splitTemplate("Bearer ${vault:jira-token}")).toEqual({
      prefix: "Bearer ",
      reference: "${vault:jira-token}",
    });
  });

  it("handles a template that is nothing but a reference", () => {
    expect(splitTemplate("${vault:key}")).toEqual({
      prefix: "",
      reference: "${vault:key}",
    });
  });

  it("refuses shapes two fields cannot express", () => {
    expect(splitTemplate("${vault:a} ${vault:b}")).toBeNull();
    expect(splitTemplate("${vault:a} trailing")).toBeNull();
    expect(splitTemplate("no references here")).toBeNull();
    expect(splitTemplate("")).toBeNull();
  });

  it("refuses a prefix carrying a dollar, which would not round-trip", () => {
    expect(splitTemplate("$x ${vault:a}")).toBeNull();
  });
});

describe("interpolatedSegments", () => {
  it("returns every segment in order", () => {
    expect(interpolatedSegments("a ${vault:x} b ${vars:y}")).toEqual([
      "${vault:x}",
      "${vars:y}",
    ]);
  });

  it("returns nothing for a literal", () => {
    expect(interpolatedSegments("Bearer sk-live")).toEqual([]);
  });

  it("is not stateful between calls", () => {
    // A shared `/g` regex carries lastIndex; a function cannot, and this is the
    // assertion that would fail if one were reintroduced.
    const template = "${vault:x}";
    expect(interpolatedSegments(template)).toEqual(["${vault:x}"]);
    expect(interpolatedSegments(template)).toEqual(["${vault:x}"]);
  });
});

describe("builders", () => {
  it("build the braced form", () => {
    expect(toVaultReference("key")).toBe("${vault:key}");
    expect(toReference("vars", "key")).toBe("${vars:key}");
  });

  it("produce values their own parser accepts", () => {
    for (const scheme of REFERENCE_SCHEMES) {
      const built = toReference(scheme, "round-trip");
      expect(isSecretReference(built)).toBe(true);
      expect(parseSecretReference(built)).toEqual({ scheme, body: "round-trip" });
    }
  });
});
