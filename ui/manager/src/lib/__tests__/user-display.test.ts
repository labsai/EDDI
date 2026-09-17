import { describe, expect, it } from "vitest";
import type { AuthUser } from "@/components/auth/auth-context";
import { userDisplayName, userInitials, userSecondaryEmail } from "@/lib/user-display";

const EMPTY: AuthUser = { username: "", firstName: "", lastName: "", email: "", fullName: "" };
const user = (patch: Partial<AuthUser>): AuthUser => ({ ...EMPTY, ...patch });

describe("userInitials", () => {
  it("prefers given and family name over the free-text display name", () => {
    expect(userInitials(user({ firstName: "jane", lastName: "doe", fullName: "Doe, Jane (Contractor)" }))).toBe("JD");
  });

  it("uses whichever name part is present", () => {
    expect(userInitials(user({ firstName: "Jane" }))).toBe("J");
    expect(userInitials(user({ lastName: "Doe" }))).toBe("D");
    expect(userInitials(user({ firstName: "   ", lastName: "Doe" }))).toBe("D");
  });

  it("takes the first letter of the display name's first and last word", () => {
    expect(userInitials(user({ fullName: "  Maria  de la Cruz " }))).toBe("MC");
    expect(userInitials(user({ fullName: "Cher" }))).toBe("C");
  });

  it("skips punctuation and emoji rather than turning them into an initial", () => {
    expect(userInitials(user({ fullName: "Doe, Jane (Contractor)" }))).toBe("DC");
    expect(userInitials(user({ fullName: "👩🏽‍💻 Jane" }))).toBe("J");
    expect(userInitials(user({ fullName: "Jane –" }))).toBe("J");
  });

  it("falls back to the first letter or digit of the username", () => {
    expect(userInitials(user({ username: "_svc-bot" }))).toBe("S");
    expect(userInitials(user({ username: "42ops" }))).toBe("4");
  });

  it("falls back to the email when the username has no letter or digit", () => {
    expect(userInitials(user({ email: "ops@example.com" }))).toBe("O");
    expect(userInitials(user({ username: "___", email: "ops@example.com" }))).toBe("O");
  });

  it("upper-cases independently of the browser locale", () => {
    // A Turkish system locale would make toLocaleUpperCase() produce "İ".
    expect(userInitials(user({ username: "isabel" }))).toBe("I");
  });

  it("keeps accents, including decomposed ones", () => {
    expect(userInitials(user({ firstName: "émile" }))).toBe("É");
  });

  it("keeps Arabic initials from joining into a word", () => {
    // Without the zero-width non-joiner, م and ع render as the word "مع".
    expect(userInitials(user({ fullName: "محمد علي" }))).toBe("م‌ع");
  });

  it("skips Thai vowels written before their consonant", () => {
    expect(userInitials(user({ fullName: "ไพโรจน์ ศรีสุข" }))).toBe("พศ");
  });

  it("takes a Devanagari letter without its vowel sign", () => {
    expect(userInitials(user({ firstName: "प्रिया", lastName: "शर्मा" }))).toBe("पश");
  });

  it("keeps characters outside the Basic Multilingual Plane whole", () => {
    expect(userInitials(user({ username: "𝒜lice" }))).toBe("𝒜");
  });

  it("returns an empty string, never a placeholder, when nothing usable is present", () => {
    expect(userInitials(EMPTY)).toBe("");
    expect(userInitials(user({ username: "___", email: "@example.com", fullName: "— 🙂" }))).toBe("");
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

describe("userSecondaryEmail", () => {
  it("returns the email when the name is something else", () => {
    expect(userSecondaryEmail(user({ fullName: "Jane Doe", email: "jane@example.com" }))).toBe("jane@example.com");
  });

  it("does not repeat the email when it is already the name, whatever its case", () => {
    expect(userSecondaryEmail(user({ email: "jane@example.com" }))).toBe("");
    expect(userSecondaryEmail(user({ username: "Jane@Example.com", email: "jane@example.com" }))).toBe("");
  });

  it("returns an empty string when there is no email", () => {
    expect(userSecondaryEmail(user({ fullName: "Jane Doe" }))).toBe("");
  });
});
