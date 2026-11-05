import type { TFunction } from "i18next";
import type { EditableAnswer } from "@/lib/api/hitl";

/** Length in code points — the backend counts `maxEditChars` the same way. */
export function codePointLength(text: string): number {
  return Array.from(text).length;
}

/**
 * Checks an edited answer the way the backend will (blank, `maxChars`, a JSON
 * object for a JSON answer) so the approver hears it before the request; the
 * backend checks again before the pause is consumed. Returns the message, or
 * null when the edit is acceptable.
 */
export function validateEditedAnswer(t: TFunction, text: string, editable: EditableAnswer): string | null {
  if (!text.trim()) {
    return t("hitlReview.editEmpty", "The answer must not be empty.");
  }
  if (codePointLength(text) > editable.maxChars) {
    return t("hitlReview.editTooLong", "The answer is longer than {{max}} characters.", { max: editable.maxChars });
  }
  if (editable.format === "json") {
    try {
      const parsed = JSON.parse(text);
      if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
        return t("hitlReview.editNotJson", "This agent answers in JSON — the answer must be a JSON object.");
      }
    } catch {
      return t("hitlReview.editNotJson", "This agent answers in JSON — the answer must be a JSON object.");
    }
  }
  return null;
}
