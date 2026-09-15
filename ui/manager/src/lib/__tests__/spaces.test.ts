import { describe, expect, it } from "vitest";
import {
  LEGACY_SPACE,
  decodeSubjectPart,
  describeSpace,
  encodeSubjectPart,
  isUserSubject,
  normalizeGroupPath,
  parseSubjectInput,
  teamSubject,
  userSubject,
} from "../spaces";

/**
 * These mirror `Subjects.java`.
 *
 * The space *list* is served by the backend, precisely so this file is not a
 * second implementation of the encoding. What remains here is the other
 * direction — decoding an id the server sent for display, and encoding what a
 * person typed into a share box — and a mismatch there does not throw either:
 * it shares with a subject nobody holds, which looks exactly like success. So
 * the encoding is pinned rather than left to look obviously right.
 */
describe("subject encoding", () => {
  it("escapes the delimiter so a name cannot forge an extra index token", () => {
    expect(userSubject("alice|user:admin")).toBe("user:alice%7Cuser:admin");
  });

  it("escapes percent so encoding round-trips", () => {
    expect(encodeSubjectPart("a%7Cb")).toBe("a%257Cb");
    expect(decodeSubjectPart("a%257Cb")).toBe("a%7Cb");
  });

  it("leaves ordinary principals alone", () => {
    expect(userSubject("alice@example.com")).toBe("user:alice@example.com");
  });

  it("normalises the slashes Keycloak wraps group paths in", () => {
    expect(teamSubject("/engineering/")).toBe("team:engineering");
    expect(normalizeGroupPath("/engineering")).toBe("engineering");
  });

  it("keeps nested group paths distinct", () => {
    // Membership in a child group is NOT membership in its parent — Keycloak
    // lists the groups a user is actually in, and the backend does not invent
    // ancestry either.
    expect(teamSubject("/engineering/backend")).toBe("team:engineering/backend");
    expect(teamSubject("/engineering/backend")).not.toBe(teamSubject("/engineering"));
  });

  it("returns null for input that names nobody", () => {
    expect(userSubject("")).toBeNull();
    expect(userSubject("   ")).toBeNull();
    expect(teamSubject("///")).toBeNull();
  });
});

describe("describeSpace", () => {
  it("renders user and team spaces for humans", () => {
    expect(describeSpace("user:alice@example.com")).toBe("alice@example.com");
    expect(describeSpace("team:engineering")).toBe("engineering");
  });

  it("decodes an escaped name back", () => {
    expect(describeSpace("user:a%7Cb")).toBe("a|b");
  });

  it("passes through legacy and unknown ids rather than guessing", () => {
    // An unrecognised space is something a reader should see and report, not
    // something to paper over with a friendly-looking guess.
    expect(describeSpace(LEGACY_SPACE)).toBe(LEGACY_SPACE);
    expect(describeSpace("something:else")).toBe("something:else");
  });

  it("is null for nothing", () => {
    expect(describeSpace(null)).toBeNull();
    expect(describeSpace(undefined)).toBeNull();
  });
});

describe("parseSubjectInput", () => {
  it("reads a bare name as a person, because that is what people type", () => {
    expect(parseSubjectInput("alice@example.com")).toEqual({ subject: "user:alice@example.com" });
  });

  it("accepts explicit prefixes", () => {
    expect(parseSubjectInput("user:alice")).toEqual({ subject: "user:alice" });
    expect(parseSubjectInput("team:/engineering")).toEqual({ subject: "team:engineering" });
  });

  it("rejects an unknown prefix rather than guessing", () => {
    // A typo that silently became a subject nobody holds looks exactly like a
    // successful share.
    expect(parseSubjectInput("group:engineering")).toEqual({ error: "unknown-prefix" });
  });

  it("rejects empty input", () => {
    expect(parseSubjectInput("   ")).toEqual({ error: "empty" });
  });

  it("trims what was typed", () => {
    expect(parseSubjectInput("  alice  ")).toEqual({ subject: "user:alice" });
  });
});

describe("isUserSubject", () => {
  it("separates people from teams", () => {
    expect(isUserSubject("user:alice")).toBe(true);
    expect(isUserSubject("team:engineering")).toBe(false);
  });
});
