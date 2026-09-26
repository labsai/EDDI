import { describe, it, expect } from "vitest";
import i18n from "@/i18n/config";
import { CascadeReferenceError } from "@/lib/api/cascade-save";
import { describeSaveError } from "@/lib/save-error";

/**
 * The cascade's own refusals reach toasts. They used to be English in every
 * locale; they now go through the locale files like every other message.
 */
describe("describeSaveError", () => {
  const err = new CascadeReferenceError(
    "agentChanged",
    { agentId: "a1", version: 3, current: 5 },
    "English fallback",
  );

  it("renders a cascade refusal in the active language", async () => {
    await i18n.changeLanguage("de");
    try {
      expect(describeSaveError(err, i18n.t)).toBe(
        "Agent a1 wurde anderswo geändert (jetzt Version 5, diese Seite hat Version 3). Laden Sie die Seite neu. Es wurde nichts gespeichert.",
      );
    } finally {
      await i18n.changeLanguage("en");
    }
  });

  it("renders every code with its parameters", () => {
    const codes = [
      ["workflowChanged", { workflowId: "w", version: 1, current: 2 }],
      ["agentChanged", { agentId: "a", version: 1, current: 2 }],
      ["workflowMissingResource", { workflowId: "w", version: 1, resource: "rulesets/r" }],
      ["agentWorkflowMismatch", { agentId: "a", version: 1, workflowId: "w", found: "3", expected: 1 }],
      ["agentMissingWorkflow", { agentId: "a", version: 1, workflowId: "w" }],
    ] as const;
    for (const [code, params] of codes) {
      const text = describeSaveError(new CascadeReferenceError(code, params, "x"), i18n.t);
      expect(text).not.toMatch(/\{\{|cascadeSave\./);
      expect(text).toContain("Nothing was saved");
    }
  });

  it("leaves any other error to getErrorMessage", () => {
    expect(describeSaveError(new Error("boom"), i18n.t)).toBe("boom");
  });
});
