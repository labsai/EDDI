/* ──────────────────────────────────────────────
   ChatWidget — query-param hardening helpers

   Pure helpers that guard the widget against two URL-borne risks:
   cross-origin API redirection (?apiServer=) and untrusted postMessage token
   sources (?tokenOrigin=). Stripping ?token= from the address is covered by
   ChatWidget.review-fixes.test.tsx.
   ────────────────────────────────────────────── */

import { describe, it, expect, vi } from "vitest";
import {
  sanitizeApiServer,
  parseAllowedTokenOrigins,
} from "./ChatWidget";

describe("sanitizeApiServer", () => {
  it("accepts a same-origin relative path", () => {
    expect(sanitizeApiServer("/eddi")).toBe("/eddi");
    expect(sanitizeApiServer("")).toBe(null); // empty → fall through to config
  });

  it("ignores an absolute cross-origin URL", () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    expect(sanitizeApiServer("https://attacker.example")).toBe(null);
    expect(sanitizeApiServer("http://attacker.example/api")).toBe(null);
    expect(warn).toHaveBeenCalled();
    warn.mockRestore();
  });

  it("ignores protocol-relative and backslash tricks", () => {
    vi.spyOn(console, "warn").mockImplementation(() => {});
    expect(sanitizeApiServer("//attacker.example")).toBe(null);
    expect(sanitizeApiServer("/\\attacker.example")).toBe(null);
    expect(sanitizeApiServer("javascript:alert(1)")).toBe(null);
    vi.restoreAllMocks();
  });

  it("ignores control-char / whitespace-prefixed absolute URLs (deny-list bypass)", () => {
    vi.spyOn(console, "warn").mockImplementation(() => {});
    // %09 decodes to a leading TAB the URL parser strips → would resolve to the
    // attacker origin. Also leading space and an embedded newline.
    expect(sanitizeApiServer("\thttps://attacker.example")).toBe(null);
    expect(sanitizeApiServer(" https://attacker.example")).toBe(null);
    expect(sanitizeApiServer("/api\nhttps://attacker.example")).toBe(null);
    expect(sanitizeApiServer("\n//attacker.example")).toBe(null);
    // A value that does not start with a path separator is not a same-origin path.
    expect(sanitizeApiServer("eddi")).toBe(null);
    vi.restoreAllMocks();
  });
});

describe("parseAllowedTokenOrigins", () => {
  const params = (v: string | null) =>
    new URLSearchParams(v === null ? "" : `tokenOrigin=${encodeURIComponent(v)}`);

  it("returns an empty list when unset (no postMessage token accepted by default)", () => {
    expect(parseAllowedTokenOrigins(params(null))).toEqual([]);
  });

  it("accepts well-formed origins and drops malformed entries", () => {
    expect(
      parseAllowedTokenOrigins(
        params("https://portal.example, https://app.example:8443"),
      ),
    ).toEqual(["https://portal.example", "https://app.example:8443"]);
  });

  it("drops entries that carry a path (not a bare origin)", () => {
    expect(
      parseAllowedTokenOrigins(params("https://portal.example/embed")),
    ).toEqual([]);
  });
});
