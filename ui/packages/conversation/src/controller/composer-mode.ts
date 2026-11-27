/* ──────────────────────────────────────────────
   Composer modes (frontend unification plan, T2.5)

   The composer of every agent surface is in exactly one of three modes:

   - "field"  — the agent asked for one typed value: an `inputField` output
                item on the last reply (`activeInputField`). Only a
                `password` field is a secret; `text` and `email` are ordinary
                fields of that type.
   - "secret" — the user switched secret mode on (the lock). One masked,
                single-line value, sent with `secretInput`.
   - "text"   — the ordinary composer.

   An agent's requested field wins over the user's lock; the lock wins over
   plain text. Quick replies are offered in "text" and "field" mode (an agent
   may ask for a value AND offer "Skip"), never while the lock is on: a pill
   would send a plain turn while a masked value sits in the field.

   The rules every host keeps around the modes:
   - A secret value lives only in the field that holds it. It is never moved
     into the text draft when the mode changes, never written to storage, and
     dropped when its field goes away (sent, cancelled, or the lock switched
     off). The text draft typed before the lock was switched on survives it.
   - A secret is sent as typed, never trimmed: whitespace is valid in a
     password or a token.
   ────────────────────────────────────────────── */

import type { InputField } from "@eddi/core";

export type ComposerMode =
  | {
      kind: "field";
      field: InputField;
      /** True for a `password` field only: masked, and sent with `secretInput`. */
      secret: boolean;
      /** The `<input type>` the field renders while it is not revealed. */
      inputType: "password" | "email" | "text";
    }
  | { kind: "secret" }
  | { kind: "text" };

/**
 * Whether a requested field is a secret. A missing or empty subType is a
 * password, as the backend's `InputFieldOutputItem` defaults it; the match
 * ignores case so `"Password"` from a hand-written config is still masked.
 */
export function isSecretInputField(subType: string | null | undefined): boolean {
  return (subType || "password").toLowerCase() === "password";
}

/** The input type a requested field renders while it is not revealed. */
export function inputFieldType(subType: string | null | undefined): "password" | "email" | "text" {
  if (isSecretInputField(subType)) return "password";
  return (subType ?? "").toLowerCase() === "email" ? "email" : "text";
}

/** The composer's mode for this state. The requested field wins, then the lock. */
export function resolveComposerMode(state: {
  activeInputField: InputField | null | undefined;
  isSecretMode: boolean;
}): ComposerMode {
  const field = state.activeInputField;
  if (field) {
    return {
      kind: "field",
      field,
      secret: isSecretInputField(field.subType),
      inputType: inputFieldType(field.subType),
    };
  }
  return state.isSecretMode ? { kind: "secret" } : { kind: "text" };
}

/** Whether quick replies may be offered beside the composer in this mode. */
export function offersQuickReplies(mode: ComposerMode): boolean {
  return mode.kind !== "secret";
}
