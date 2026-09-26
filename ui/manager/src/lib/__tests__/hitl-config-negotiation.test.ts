import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import {
  applyApprovalPhases,
  getStylePhases,
  NEGOTIATION_ARBITRATION_TEMPLATE,
} from "@/lib/hitl-config";

/**
 * NEGOTIATION's Arbitration is the one preset phase with a prompt of its own.
 * Materializing the preset (which enabling any approval point does) used to
 * store it with `inputTemplate: null`, and the backend then ran the generic
 * synthesis prompt in its place: the moderator summarised a deadlock instead of
 * deciding it.
 */

const PRESETS_JAVA = resolve(
  process.cwd(),
  "../../src/main/java/ai/labs/eddi/configs/groups/model/DiscussionStylePresets.java",
);

/**
 * The value of a Java text block constant, per JLS 3.10.6: common indentation
 * stripped (the closing delimiter's line counts), trailing spaces stripped, and
 * a `\` at a line end joining it to the next line.
 */
function javaTextBlock(source: string, name: string): string {
  const start = source.indexOf(`${name} = """`);
  if (start < 0) throw new Error(`${name} not found`);
  const bodyStart = source.indexOf("\n", start) + 1;
  const end = source.indexOf('"""', bodyStart);
  const raw = source.slice(bodyStart, end);
  const lines = raw.split("\n");
  // The closing `"""` shares the last content line here, so every line is content.
  const indent = Math.min(
    ...lines.filter((l) => l.trim()).map((l) => l.length - l.trimStart().length),
  );
  let out = "";
  lines.forEach((line, i) => {
    const text = line.slice(indent).replace(/\s+$/, "");
    if (text.endsWith("\\")) {
      out += text.slice(0, -1);
    } else {
      out += text + (i < lines.length - 1 ? "\n" : "");
    }
  });
  return out;
}

describe("NEGOTIATION preset — Arbitration", () => {
  it("carries the arbitration prompt, verbatim from the backend preset", () => {
    const java = readFileSync(PRESETS_JAVA, "utf-8");
    expect(NEGOTIATION_ARBITRATION_TEMPLATE).toBe(javaTextBlock(java, "TEMPLATE_ARBITRATION"));
  });

  it("stores the prompt on the materialized phase, and survives an approval point", () => {
    const phases = applyApprovalPhases(getStylePhases("NEGOTIATION", 3), ["Bargaining"]);
    const arbitration = phases.find((p) => p.name === "Arbitration")!;
    expect(arbitration.inputTemplate).toBe(NEGOTIATION_ARBITRATION_TEMPLATE);
    expect(arbitration.skipIf).toBe("AGREEMENT_REACHED");
    // Every other phase still resolves its prompt from the phase type.
    expect(phases.filter((p) => p.name !== "Arbitration").every((p) => p.inputTemplate === null)).toBe(true);
  });
});
