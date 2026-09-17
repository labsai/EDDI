import { describe, expect, it } from "vitest";
import type { AuthUser } from "@/components/auth/auth-context";
import { userDisplayName, userInitials } from "@/lib/user-display";

const EMPTY: AuthUser = { username: "", firstName: "", lastName: "", email: "", fullName: "" };
const user = (patch: Partial<AuthUser>): AuthUser => ({ ...EMPTY, ...patch });

describe("userInitials", () => {
  it("prefers given and family name", () => {
    expect(userInitials(user({ firstName: "jane", lastName: "doe", fullName: "Someone Else" }))).toBe("JD");
  });

  it("uses a single given name alone", () => {
    expect(userInitials(user({ firstName: "Jane" }))).toBe("J");
  });

  it("takes the first and last word of the display name", () => {
    expect(userInitials(user({ fullName: "  Maria  de la Cruz " }))).toBe("MC");
    expect(userInitials(user({ fullName: "Cher" }))).toBe("C");
  });

  it("falls back to the first letter or digit of the username", () => {
    expect(userInitials(user({ username: "_svc-bot" }))).toBe("S");
    expect(userInitials(user({ username: "42ops" }))).toBe("4");
  });

  it("falls back to the email local part", () => {
    expect(userInitials(user({ email: "ops@example.com" }))).toBe("O");
  });

  it("keeps non-Latin and astral characters whole", () => {
    expect(userInitials(user({ fullName: "محمد علي" }))).toBe("مع");
    expect(userInitials(user({ username: "𝒜lice" }))).toBe("𝒜");
  });

  it("returns an empty string, never a placeholder, when nothing usable is present", () => {
    expect(userInitials(EMPTY)).toBe("");
    expect(userInitials(user({ username: "___", email: "@example.com" }))).toBe("");
  });
});

describe("userDisplayName", () => {
  it("prefers the display name, then given + family, then username, then email", () => {
    expect(userDisplayName(user({ fullName: "Jane Doe", username: "jd" }))).toBe("Jane Doe");
    expect(userDisplayName(user({ firstName: "Jane", lastName: "Doe", username: "jd" }))).toBe("Jane Doe");
    expect(userDisplayName(user({ username: "jd", email: "jd@example.com" }))).toBe("jd");
    expect(userDisplayName(user({ email: "jd@example.com" }))).toBe("jd@example.com");
  });

  it("returns an empty string when the token carries no identity claims", () => {
    expect(userDisplayName(EMPTY)).toBe("");
  });
});
