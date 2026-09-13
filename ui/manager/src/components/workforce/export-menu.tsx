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
import { downloadFile, generateMarkdown } from "@/lib/group-transcript-export";

// ─── Component ───────────────────────────────────────────────────

interface ExportMenuProps {
  conversation: GroupConversation | null;
  groupName?: string;
  className?: string;
}

function ExportMenu({ conversation, groupName, className }: ExportMenuProps) {
  const { t } = useTranslation();
  // `generateMarkdown` is a plain module with no React context, so it takes the
  // translator rather than reaching for one — same shape as `CronDescribeT`.
  const exportT = useCallback(
    (key: string, fallback: string) => t(key, { defaultValue: fallback }),
    [t],
  );

  const handleMarkdown = useCallback(() => {
    if (!conversation) return;
    const md = generateMarkdown(conversation, groupName, exportT);
    downloadFile(md, `discussion-${conversation.id.slice(0, 8)}.md`, "text/markdown");
    toast.success(t("Workforce.export.downloadedMd", "Downloaded as Markdown"));
  }, [conversation, groupName, exportT, t]);

  const handleJson = useCallback(() => {
    if (!conversation) return;
    const json = JSON.stringify(conversation, null, 2);
    downloadFile(json, `discussion-${conversation.id.slice(0, 8)}.json`, "application/json");
    toast.success(t("Workforce.export.downloadedJson", "Downloaded as JSON"));
  }, [conversation, t]);

  const handleCopy = useCallback(async () => {
    if (!conversation) return;
    const md = generateMarkdown(conversation, groupName, exportT);
    try {
      await navigator.clipboard.writeText(md);
      toast.success(t("Workforce.export.copied", "Copied to clipboard"));
    } catch {
      toast.error(t("Workforce.export.copyFailed", "Failed to copy to clipboard"));
    }
  }, [conversation, groupName, exportT, t]);

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
