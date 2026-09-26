import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import {
  applyApprovalPhases,
  getStylePhases,
  NEGOTIATION_ARBITRATION_TEMPLATE,
  repairNegotiationArbitration,
} from "@/lib/hitl-config";
import { normalizeGroupConfig, type AgentGroupConfiguration } from "@/lib/api/groups";

/**
 * NEGOTIATION's Arbitration is the one preset phase with a prompt of its own.
 * Materializing the preset (which enabling any approval point does) used to
 * store it with `inputTemplate: null`, and the backend then ran the generic
 * synthesis prompt in its place: the moderator summarised a deadlock instead of
 * deciding it.
 */

// Resolved from this file, not the working directory the runner starts in
// (`import.meta.url` is an http:// URL under vitest, so `__dirname` it is).
const PRESETS_JAVA = resolve(
  __dirname,
  "../../../../../src/main/java/ai/labs/eddi/configs/groups/model/DiscussionStylePresets.java",
);

/**
 * The value of a Java text block constant, per JLS 3.10.6.
 *
 * - The content starts on the line after the opening `"""`.
 * - The common indentation is computed over the non-blank content lines AND the
 *   closing delimiter's line (when the delimiter sits on a line of its own, its
 *   position counts too), then stripped.
 * - Trailing spaces are stripped, and a `\` at a line end joins the line with
 *   the next.
 *
 * Any other escape sequence (`\"`, `\n`, `\s`, …) is refused rather than
 * interpreted: decoding them here would be a second Java parser to keep right,
 * and the template needs none. If one appears, this test fails loudly and the
 * comparison is updated deliberately.
 */
function javaTextBlock(source: string, name: string): string {
  const start = source.indexOf(`${name} = """`);
  if (start < 0) throw new Error(`${name} not found`);
  const bodyStart = source.indexOf("\n", start) + 1;
  const end = source.indexOf('"""', bodyStart);
  if (end < 0) throw new Error(`${name}: no closing delimiter`);

  const lines = source.slice(bodyStart, end).split("\n");
  // The last element is what precedes the closing """ on its line. Blank means
  // the delimiter stands alone: its indentation counts, and it adds no content.
  const closingLine = lines[lines.length - 1]!;
  const closingAlone = closingLine.trim() === "";
  const contentLines = closingAlone ? lines.slice(0, -1) : lines;

  const indents = contentLines
    .filter((l) => l.trim())
    .map((l) => l.length - l.trimStart().length);
  if (closingAlone) indents.push(closingLine.length);
  const indent = Math.min(...indents);

  let out = "";
  contentLines.forEach((line, i) => {
    const text = line.slice(indent).replace(/[ \t]+$/, "");
    const escapes = text.replace(/\\$/, "").match(/\\./g);
    if (escapes) throw new Error(`${name}: unsupported escape ${escapes[0]} — update this test`);
    const last = i === contentLines.length - 1;
    if (text.endsWith("\\")) out += text.slice(0, -1);
    else out += text + (last && !closingAlone ? "" : "\n");
  });
  return out;
}

describe("javaTextBlock (the drift test's parser)", () => {
  it("counts a dedented closing delimiter and keeps the final newline", () => {
    const src = 'X = """\n        a\n        b\n    """;';
    expect(javaTextBlock(src, "X")).toBe("    a\n    b\n");
  });

  it("joins a line-end continuation and drops a closing delimiter on the last line", () => {
    const src = 'X = """\n    one \\\n    two""";';
    expect(javaTextBlock(src, "X")).toBe("one two");
  });

  it("refuses an escape it does not decode", () => {
    // Java source: `say \"hi\" there` inside the block.
    const src = ['X = """', '    say \\"hi\\" there""";'].join("\n");
    expect(() => javaTextBlock(src, "X")).toThrow(/unsupported escape/);
  });
});

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

describe("NEGOTIATION groups saved before the fix", () => {
  /** What the Manager used to store: the preset with Arbitration's prompt lost. */
  const broken = () =>
    getStylePhases("NEGOTIATION", 3).map((p) => (p.name === "Arbitration" ? { ...p, inputTemplate: null } : p));

  it("get the arbitration prompt back when the group is read", () => {
    const config = { name: "Deal", style: "NEGOTIATION", maxRounds: 3, members: [], phases: broken() } as unknown as AgentGroupConfiguration;
    const read = normalizeGroupConfig(config);
    expect(read.phases!.find((p) => p.name === "Arbitration")!.inputTemplate).toBe(NEGOTIATION_ARBITRATION_TEMPLATE);
  });

  it("never touch a phase the author wrote, or another style", () => {
    const custom = broken().map((p) => (p.name === "Arbitration" ? { ...p, participants: "ALL" } : p));
    expect(repairNegotiationArbitration("NEGOTIATION", custom)).toBe(custom);
    const other = broken();
    expect(repairNegotiationArbitration("CUSTOM", other)).toBe(other);
    const withPrompt = broken().map((p) => (p.name === "Arbitration" ? { ...p, inputTemplate: "Mine" } : p));
    expect(repairNegotiationArbitration("NEGOTIATION", withPrompt)).toBe(withPrompt);
  });
});
