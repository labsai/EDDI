import { useCallback } from "react";
import { useTranslation } from "react-i18next";
import { Download, FileText, Braces, Copy } from "lucide-react";
import { toast } from "sonner";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import type { GroupConversation } from "@/lib/api/groups";
import { hasDisplayableDecision } from "@/lib/group-config";
import { parseTranscriptContent } from "@/components/groups/group-utils";

// ─── Helpers (not exported) ──────────────────────────────────────

/** An entry's body as a reader should see it, never as the wire shape. */
function readable(content: string | null | undefined): string {
  return content ? parseTranscriptContent(content) : "";
}

function generateMarkdown(
  conversation: GroupConversation,
  groupName?: string,
): string {
  const lines: string[] = [];
  lines.push(`# ${groupName ?? "Discussion"}`);
  lines.push(``);
  lines.push(`**Date:** ${new Date(conversation.created).toLocaleString()}`);
  lines.push(`**Status:** ${conversation.state}`);
  if (conversation.originalQuestion) {
    lines.push(``);
    lines.push(`> **Question:** ${conversation.originalQuestion}`);
  }
  lines.push(``);
  lines.push(`---`);
  lines.push(``);

  for (const entry of conversation.transcript ?? []) {
    // The same reading every transcript surface does — a judge answers in JSON,
    // so an unparsed SYNTHESIS body exports as a raw blob under a "Synthesis"
    // heading. Markdown is the human-readable export; the JSON one below is
    // where the verbatim document belongs.
    const body = readable(entry.content);
    if (entry.type === "QUESTION") {
      lines.push(`> **Question:** ${body}`);
      lines.push(``);
    } else if (entry.type === "SYNTHESIS") {
      lines.push(`## Synthesis`);
      lines.push(``);
      lines.push(body);
      lines.push(``);
    } else if (entry.type === "ERROR") {
      lines.push(`### ⚠️ ${entry.speakerDisplayName} (Error)`);
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
    lines.push(`## Decision (${d.type})`);
    lines.push(``);
    if (d.winner) lines.push(`**Winner:** ${d.winner}`);
    if (d.outcome) lines.push(`**Outcome:** ${d.outcome}`);
    // A NONE decision that carries `raw` means a judgment WAS produced but
    // could not be parsed — the card shows it verbatim, so the export must too
    // or the section is an empty heading.
    if (d.type === "NONE" && d.raw?.trim()) {
      lines.push(``);
      lines.push(`### Unparsed judgment`);
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
      lines.push(`**Minority report:**`);
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
  const finalAnswer = readable(conversation.synthesizedAnswer);
  if (finalAnswer.trim()) {
    lines.push(`---`);
    lines.push(``);
    lines.push(`## Final Answer`);
    lines.push(``);
    lines.push(finalAnswer);
  }

  return lines.join("\n");
}

function downloadFile(content: string, filename: string, mimeType: string) {
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

// ─── Component ───────────────────────────────────────────────────

interface ExportMenuProps {
  conversation: GroupConversation | null;
  groupName?: string;
  className?: string;
}

function ExportMenu({ conversation, groupName, className }: ExportMenuProps) {
  const { t } = useTranslation();

  const handleMarkdown = useCallback(() => {
    if (!conversation) return;
    const md = generateMarkdown(conversation, groupName);
    downloadFile(md, `discussion-${conversation.id.slice(0, 8)}.md`, "text/markdown");
    toast.success(t("Workforce.export.downloadedMd", "Downloaded as Markdown"));
  }, [conversation, groupName, t]);

  const handleJson = useCallback(() => {
    if (!conversation) return;
    const json = JSON.stringify(conversation, null, 2);
    downloadFile(json, `discussion-${conversation.id.slice(0, 8)}.json`, "application/json");
    toast.success(t("Workforce.export.downloadedJson", "Downloaded as JSON"));
  }, [conversation, t]);

  const handleCopy = useCallback(async () => {
    if (!conversation) return;
    const md = generateMarkdown(conversation, groupName);
    try {
      await navigator.clipboard.writeText(md);
      toast.success(t("Workforce.export.copied", "Copied to clipboard"));
    } catch {
      toast.error(t("Workforce.export.copyFailed", "Failed to copy to clipboard"));
    }
  }, [conversation, groupName, t]);

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          variant="ghost"
          size="icon"
          disabled={!conversation}
          className={cn("h-8 w-8", className)}
          aria-label={t("Workforce.export.title", "Export discussion")}
        >
          <Download className="h-4 w-4" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuItem onClick={handleMarkdown}>
          <FileText className="h-4 w-4 me-2" />
          {t("Workforce.export.markdown", "Export as Markdown")}
        </DropdownMenuItem>
        <DropdownMenuItem onClick={handleJson}>
          <Braces className="h-4 w-4 me-2" />
          {t("Workforce.export.json", "Export as JSON")}
        </DropdownMenuItem>
        <DropdownMenuItem onClick={handleCopy}>
          <Copy className="h-4 w-4 me-2" />
          {t("Workforce.export.clipboard", "Copy to Clipboard")}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

export { ExportMenu };
