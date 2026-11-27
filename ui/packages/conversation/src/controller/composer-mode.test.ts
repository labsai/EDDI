import { describe, it, expect } from "vitest";
import {
  inputFieldType,
  isSecretInputField,
  offersQuickReplies,
  resolveComposerMode,
} from "./composer-mode";

/** T2.5 — the composer's three modes and the rules every host keeps around them. */
describe("composer modes", () => {
  it("treats only a password field as a secret — missing subType included, any case", () => {
    expect(isSecretInputField("password")).toBe(true);
    expect(isSecretInputField("Password")).toBe(true);
    expect(isSecretInputField(undefined)).toBe(true);
    expect(isSecretInputField("")).toBe(true);
    expect(isSecretInputField("email")).toBe(false);
    expect(isSecretInputField("text")).toBe(false);
  });

  it("renders a field as password, email or text", () => {
    expect(inputFieldType("password")).toBe("password");
    expect(inputFieldType(undefined)).toBe("password");
    expect(inputFieldType("EMAIL")).toBe("email");
    expect(inputFieldType("text")).toBe("text");
    expect(inputFieldType("number")).toBe("text");
  });

  it("puts the agent's requested field before the user's lock, and the lock before text", () => {
    const field = { subType: "email", label: "Your e-mail" };
    expect(resolveComposerMode({ activeInputField: field, isSecretMode: true })).toEqual({
      kind: "field",
      field,
      secret: false,
      inputType: "email",
    });
    expect(resolveComposerMode({ activeInputField: { subType: "password" }, isSecretMode: false })).toMatchObject({
      kind: "field",
      secret: true,
      inputType: "password",
    });
    expect(resolveComposerMode({ activeInputField: null, isSecretMode: true })).toEqual({ kind: "secret" });
    expect(resolveComposerMode({ activeInputField: undefined, isSecretMode: false })).toEqual({ kind: "text" });
  });

  it("offers quick replies beside text and a requested field, never under the lock", () => {
    expect(offersQuickReplies({ kind: "text" })).toBe(true);
    expect(offersQuickReplies(resolveComposerMode({ activeInputField: { subType: "text" }, isSecretMode: false }))).toBe(true);
    expect(offersQuickReplies({ kind: "secret" })).toBe(false);
  });
});
