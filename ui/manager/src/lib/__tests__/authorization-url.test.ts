import { describe, it, expect } from "vitest";
import { isSafeAuthorizationUrl } from "../authorization-url";

describe("isSafeAuthorizationUrl", () => {
  it.each([
    "https://accounts.example.com/o/oauth2/auth?client_id=x&state=y",
    "https://provider.example:8443/authorize",
    "http://localhost:8080/authorize",
    "http://127.0.0.1:9000/authorize",
    "http://[::1]:9000/authorize",
  ])("allows %s", (url) => {
    expect(isSafeAuthorizationUrl(url)).toBe(true);
  });

  it.each([
    ["javascript:alert(1)"],
    ["JaVaScRiPt:alert(1)"],
    ["data:text/html,hi"],
    ["file:///etc/passwd"],
    ["blob:https://x/1"],
    ["ftp://provider.example/a"],
    ["http://provider.example/authorize"],
    ["http://localhost.attacker.example/authorize"],
    ["https://user:pw@provider.example/a"],
    ["//provider.example/a"],
    ["/relative/path"],
    ["provider.example/a"],
    [""],
    ["   "],
  ])("refuses %j", (url) => {
    expect(isSafeAuthorizationUrl(url)).toBe(false);
  });

  it("refuses non-strings", () => {
    expect(isSafeAuthorizationUrl(undefined)).toBe(false);
    expect(isSafeAuthorizationUrl(null)).toBe(false);
    expect(isSafeAuthorizationUrl({ href: "https://x" })).toBe(false);
  });
});
