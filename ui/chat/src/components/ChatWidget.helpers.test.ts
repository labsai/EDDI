/* ──────────────────────────────────────────────
   ChatWidget — query-param hardening helpers

   Pure helpers that guard the widget against three URL-borne risks:
   token leakage/CSRF (?token=), cross-origin API redirection (?apiServer=),
   and untrusted postMessage token sources (?tokenOrigin=).
   ────────────────────────────────────────────── */

import { describe, it, expect, vi } from "vitest";
import {
  stripTokenFromUrl,
  sanitizeApiServer,
  parseAllowedTokenOrigins,
} from "./ChatWidget";

describe("stripTokenFromUrl", () => {
  it("removes the token param and returns its value", () => {
    const { token, cleanedUrl } = stripTokenFromUrl(
      "https://host.example/chat/prod/agent-1?token=secret-jwt&theme=dark",
    );
    expect(token).toBe("secret-jwt");
    // The token must be gone; other params survive.
    expect(cleanedUrl).toBe("/chat/prod/agent-1?theme=dark");
    expect(cleanedUrl).not.toContain("secret-jwt");
    expect(cleanedUrl).not.toContain("token");
  });

  it("preserves the hash while stripping the token", () => {
    const { cleanedUrl } = stripTokenFromUrl(
      "https://host.example/chat?token=abc#section",
    );
    expect(cleanedUrl).toBe("/chat#section");
  });

  it("returns nulls when there is no token", () => {
    expect(stripTokenFromUrl("https://host.example/chat?theme=dark")).toEqual({
      token: null,
      cleanedUrl: null,
    });
  });

  it("returns nulls for an unparseable URL rather than throwing", () => {
    expect(stripTokenFromUrl("::::not a url::::")).toEqual({
      token: null,
      cleanedUrl: null,
    });
  });
});

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
