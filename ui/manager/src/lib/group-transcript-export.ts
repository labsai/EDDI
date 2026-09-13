import type { GroupConversation } from "@/lib/api/groups";
import { hasDisplayableDecision } from "@/lib/group-config";
import { parseTranscriptContent } from "@/components/groups/group-utils";

/**
 * The caller's translator, narrowed to what this module needs.
 *
 * Passed in rather than imported: this is a plain module with no React context,
 * and both call sites are components that already hold `t`. The signature
 * matches `CronDescribeT` in `lib/api/schedules.ts`, which solves the same
 * problem the same way.
 */
export type ExportT = (key: string, fallback: string) => string;

/** Used when a caller has no translator — the English the export always had. */
const IDENTITY_T: ExportT = (_key, fallback) => fallback;

/**
 * The one Markdown rendering of a group discussion.
 *
 * There used to be two: this one, behind the board's export menu, and a second
 * hand-rolled copy inside the history viewer's own toolbar. The code comment on
 * the copy already acknowledged the duplication ("a second, parallel markdown
 * export"), and the two had drifted — the copy titled every file
 * "# Task Force Discussion" whatever the discussion style, and omitted the
 * group name, the structured decision, the minority report and the unparsed
 * judgment. Both toolbars now call this.
 */

/** An entry's body as a reader should see it, never as the wire shape. */
function readable(content: string | null | undefined): string {
  return content ? parseTranscriptContent(content) : "";
}

export function generateMarkdown(
  conversation: GroupConversation,
  groupName?: string,
  t: ExportT = IDENTITY_T,
): string {
  const lines: string[] = [];
  lines.push(`# ${groupName ?? t("groups.export.discussion", "Discussion")}`);
  lines.push(``);
  lines.push(`**${t("groups.export.date", "Date")}:** ${new Date(conversation.created).toLocaleString()}`);
  // The state is a label, not the wire enum: an export headed
  // "AWAITING_HUMAN_INPUT" reads as a leak, and every other surface has shown a
  // localized state for a long time.
  lines.push(
    `**${t("groups.export.status", "Status")}:** ${t(`groups.state.${conversation.state}`, conversation.state)}`,
  );
  if (conversation.originalQuestion) {
    lines.push(``);
    lines.push(`> **${t("groups.export.question", "Question")}:** ${conversation.originalQuestion}`);
  }
  lines.push(``);
  lines.push(`---`);
  lines.push(``);

  // Phase separators, which the history viewer's own renderer emitted and this
  // one did not: without them a multi-phase discussion exports as an
  // undifferentiated run of speaker headings.
  let lastPhaseIndex = -1;
  for (const entry of conversation.transcript ?? []) {
    if (
      entry.phaseIndex >= 0 &&
      entry.phaseIndex !== lastPhaseIndex &&
      entry.type !== "QUESTION"
    ) {
      lastPhaseIndex = entry.phaseIndex;
      lines.push(`## ${t("groups.export.phase", "Phase")} ${entry.phaseIndex + 1}: ${entry.phaseName ?? entry.type}`);
      lines.push(``);
    }
    // The same reading every transcript surface does — a judge answers in JSON,
    // so an unparsed SYNTHESIS body exports as a raw blob under a "Synthesis"
    // heading. Markdown is the human-readable export; the JSON one below is
    // where the verbatim document belongs.
    const body = readable(entry.content);
    if (entry.type === "QUESTION") {
      lines.push(`> **${t("groups.export.question", "Question")}:** ${body}`);
      lines.push(``);
    } else if (entry.type === "SYNTHESIS") {
      lines.push(`## ${t("groups.export.synthesis", "Synthesis")}`);
      lines.push(``);
      lines.push(body);
      lines.push(``);
    } else if (entry.type === "ERROR") {
      lines.push(`### ⚠️ ${entry.speakerDisplayName} (${t("groups.export.error", "Error")})`);
      if (entry.errorReason) lines.push(`> ${entry.errorReason}`);
      if (body) lines.push(body);
      lines.push(``);
    } else if (entry.type !== "SKIPPED") {
      lines.push(`### ${entry.speakerDisplayName} (${entry.type})`);
      lines.push(``);
      lines.push(body);
      lines.push(``);
    }
  }

  // Structured decision (F3) — without it the export keeps "who won" only as
  // prose, which is the gap the decision record exists to close.
  if (hasDisplayableDecision(conversation.decision)) {
    const d = conversation.decision;
    lines.push(`---`);
    lines.push(``);
    // The decision's own name is the heading, as it is on the card this export
    // mirrors. "Decision (No structured decision)" was the alternative.
    lines.push(`## ${t(`groups.decisionType.${d.type}`, d.type)}`);
    lines.push(``);
    if (d.winner) lines.push(`**${t("groups.export.winner", "Winner")}:** ${d.winner}`);
    if (d.outcome) lines.push(`**${t("groups.export.outcome", "Outcome")}:** ${d.outcome}`);
    // A NONE decision that carries `raw` means a judgment WAS produced but
    // could not be parsed — the card shows it verbatim, so the export must too
    // or the section is an empty heading.
    if (d.type === "NONE" && d.raw?.trim()) {
      lines.push(``);
      lines.push(`### ${t("groups.export.unparsedJudgment", "Unparsed judgment")}`);
      lines.push(``);
      lines.push(d.raw);
    }
    if (d.tally && Object.keys(d.tally).length > 0) {
      lines.push(``);
      for (const [key, value] of Object.entries(d.tally)) {
        lines.push(`- ${key}: ${typeof value === "object" ? JSON.stringify(value) : String(value)}`);
      }
    }
    const dissents = d.dissents ?? [];
    if (dissents.length > 0) {
      lines.push(``);
      lines.push(`**${t("groups.export.minorityReport", "Minority report")}:**`);
      for (const dis of dissents) {
        lines.push(`- ${dis.displayName || dis.agentId}: ${dis.position}`);
      }
    }
    lines.push(``);
  }

  // Read the same way as the entries above. A verdict reaches this field
  // rather than a SYNTHESIS entry whenever the discussion carries no synthesis
  // element, so exporting it raw put the blob back under a different heading —
  // and a verdict that was only a tally leaves nothing to print at all.
  // Skipped when a SYNTHESIS entry already carried it: the viewer's renderer
  // guarded this and the board's did not, so consolidating without the guard
  // wrote the same text twice, under two headings.
  const finalAnswer = readable(conversation.synthesizedAnswer);
  // Suppressed only when the SAME text is already in the file. Presence of a
  // SYNTHESIS entry is not enough on either side of it: an entry with no body
  // writes nothing, and an entry whose text differs from the final answer is a
  // second piece of content, not a duplicate of this one. Both were dropping
  // `synthesizedAnswer` out of the export entirely.
  const synthesisAlreadyWritten = (conversation.transcript ?? []).some(
    (entry) => entry.type === "SYNTHESIS" && readable(entry.content).trim() === finalAnswer.trim(),
  );
  if (finalAnswer.trim() && !synthesisAlreadyWritten) {
    lines.push(`---`);
    lines.push(``);
    lines.push(`## ${t("groups.export.finalAnswer", "Final Answer")}`);
    lines.push(``);
    lines.push(finalAnswer);
  }

  return lines.join("\n");
}

export function downloadFile(content: string, filename: string, mimeType: string) {
  const blob = new Blob([content], { type: mimeType });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}
