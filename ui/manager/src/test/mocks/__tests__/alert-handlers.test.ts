import { describe, expect, it } from "vitest";
import { createAlertRule, listAlertRules, testAlertRule, updateAlertRule } from "@/lib/api/alerts";
import type { AlertRule } from "@/lib/api/alerts";

/** The mock mirrors the server's targetId rules, so page tests exercise the real contract. */
describe("alert handlers — webhook URLs and targetId", () => {
  async function storedHookRule(): Promise<AlertRule> {
    const page = await listAlertRules();
    return page.rules.find((r) => r.id === "rule-errors")!;
  }

  it("reads mask the URL and carry the targetId", async () => {
    const rule = await storedHookRule();
    expect(rule.delivery[0]).toMatchObject({ url: "https://hooks.example.com/***", urlConfigured: true, targetId: "t-hook-1" });
  });

  it("an update with the masked URL and its targetId keeps the stored URL", async () => {
    const rule = await storedHookRule();
    const saved = await updateAlertRule("rule-errors", { ...rule, name: "renamed" });
    expect(saved.delivery[0]).toMatchObject({ url: "https://hooks.example.com/***", targetId: "t-hook-1" });
  });

  it("a masked URL with an unknown or missing targetId is refused with INVALID_RULE", async () => {
    const rule = await storedHookRule();
    const hook = rule.delivery[0]!;
    const unknown: AlertRule = { ...rule, delivery: [{ ...hook, targetId: "t-forged" }] };
    const missing: AlertRule = { ...rule, delivery: [{ ...hook, targetId: undefined }] };
    await expect(updateAlertRule("rule-errors", unknown)).rejects.toMatchObject({ status: 400 });
    await expect(updateAlertRule("rule-errors", missing)).rejects.toMatchObject({ status: 400 });
    await expect(testAlertRule(missing)).rejects.toMatchObject({ status: 400 });
  });

  it("a create assigns ids, replacing any the client sent", async () => {
    const rule = await storedHookRule();
    const created = await createAlertRule({
      ...rule,
      id: undefined,
      delivery: [{ type: "WEBHOOK", url: "https://hooks.example.com/new", signingSecret: "${vault:x}", targetId: "t-hook-1" }],
    });
    expect(created.delivery[0]?.targetId).toMatch(/^t-new-/);
  });
});
