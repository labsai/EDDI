import { useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { Paperclip, Download, Trash2, FileText, Image as ImageIcon } from "lucide-react";
import { getErrorMessage } from "@/lib/api-client";
import { downloadAttachment, formatBytes, isImageMime } from "@/lib/api/attachments";
import {
  useConversationAttachments,
  useDeleteAttachment,
  useDeleteAllAttachments,
  type ConversationAttachment,
} from "@/hooks/use-attachments";
import { AlertDialog } from "@/components/ui/alert-dialog";

/**
 * Operator surface for the attachments a conversation owns — browse, download and
 * delete files that users/agents uploaded (moderation / support / debugging).
 * Backs onto GET/DELETE /conversations/{id}/attachments (client already shipped).
 */
export function AttachmentsSection({ conversationId }: { conversationId: string }) {
  const { t } = useTranslation();
  const { data: attachments, isLoading, isError } =
    useConversationAttachments(conversationId);
  const deleteOne = useDeleteAttachment(conversationId);
  const deleteAll = useDeleteAllAttachments(conversationId);

  // { kind: "one", ref } | { kind: "all" } | null
  const [confirm, setConfirm] = useState<
    { kind: "one"; att: ConversationAttachment } | { kind: "all" } | null
  >(null);

  async function handleDownload(att: ConversationAttachment) {
    try {
      const blob = await downloadAttachment(conversationId, att.storageRef);
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = att.fileName;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    } catch (err) {
      toast.error(getErrorMessage(err));
    }
  }

  function runConfirm() {
    if (!confirm) return;
    if (confirm.kind === "one") {
      const ref = confirm.att.storageRef;
      deleteOne.mutate(ref, {
        onSuccess: () =>
          toast.success(t("attachments.deleted", "Attachment deleted")),
        onError: (err) => toast.error(getErrorMessage(err)),
      });
    } else {
      deleteAll.mutate(undefined, {
        onSuccess: (count) =>
          toast.success(
            t("attachments.deletedAll", {
              count,
              defaultValue: "Deleted {{count}} attachments",
            })
          ),
        onError: (err) => toast.error(getErrorMessage(err)),
      });
    }
    setConfirm(null);
  }

  const count = attachments?.length ?? 0;

  return (
    <section data-testid="attachments-section" className="space-y-3">
      <div className="flex items-center justify-between">
        <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
          <Paperclip className="h-4 w-4 text-primary" />
          {t("attachments.title", "Attachments")}
          {count > 0 && (
            <span className="text-xs font-normal text-muted-foreground">
              ({count})
            </span>
          )}
        </h3>
        {count > 0 && (
          <button
            type="button"
            onClick={() => setConfirm({ kind: "all" })}
            className="inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1 text-xs font-medium text-destructive transition-colors hover:bg-destructive/10"
            data-testid="attachments-delete-all"
          >
            <Trash2 className="h-3.5 w-3.5" />
            {t("attachments.deleteAll", "Delete all")}
          </button>
        )}
      </div>

      {isLoading ? (
        <div className="rounded-lg border border-dashed p-4 text-center">
          <div className="mx-auto h-4 w-4 animate-spin rounded-full border-2 border-primary border-t-transparent" />
        </div>
      ) : isError ? (
        <p
          className="rounded-lg border border-destructive/30 bg-destructive/5 p-3 text-xs text-destructive"
          data-testid="attachments-error"
        >
          {t("attachments.loadError", "Could not load attachments.")}
        </p>
      ) : count === 0 ? (
        <p className="rounded-lg border border-dashed p-4 text-center text-xs italic text-muted-foreground">
          {t("attachments.empty", "This conversation has no attachments.")}
        </p>
      ) : (
        <ul className="divide-y divide-border/60 rounded-lg border border-border/60">
          {attachments!.map((att) => (
            <li
              key={att.storageRef}
              className="flex items-center gap-3 px-3 py-2"
              data-testid="attachment-row"
            >
              <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-muted text-muted-foreground">
                {isImageMime(att.mimeType) ? (
                  <ImageIcon className="h-4 w-4" />
                ) : (
                  <FileText className="h-4 w-4" />
                )}
              </div>
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm text-foreground" title={att.fileName}>
                  {att.fileName}
                </p>
                <p className="truncate font-mono text-[11px] text-muted-foreground">
                  {att.mimeType ?? "—"}
                  {att.sizeBytes != null && ` · ${formatBytes(att.sizeBytes)}`}
                </p>
              </div>
              <button
                type="button"
                onClick={() => handleDownload(att)}
                className="rounded-lg p-1.5 text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground"
                aria-label={t("attachments.download", "Download")}
                title={t("attachments.download", "Download")}
              >
                <Download className="h-4 w-4" />
              </button>
              <button
                type="button"
                onClick={() => setConfirm({ kind: "one", att })}
                className="rounded-lg p-1.5 text-muted-foreground transition-colors hover:text-destructive"
                aria-label={t("attachments.delete", "Delete attachment")}
                title={t("attachments.delete", "Delete attachment")}
              >
                <Trash2 className="h-4 w-4" />
              </button>
            </li>
          ))}
        </ul>
      )}

      <AlertDialog
        open={confirm !== null}
        onOpenChange={(open) => !open && setConfirm(null)}
        title={
          confirm?.kind === "all"
            ? t("attachments.confirmDeleteAllTitle", "Delete all attachments?")
            : t("attachments.confirmDeleteTitle", "Delete attachment?")
        }
        description={
          confirm?.kind === "all"
            ? t(
                "attachments.confirmDeleteAllDesc",
                "Permanently deletes every file this conversation owns. This cannot be undone."
              )
            : t(
                "attachments.confirmDeleteDesc",
                "Permanently deletes this file. This cannot be undone."
              )
        }
        confirmLabel={t("common.delete", "Delete")}
        cancelLabel={t("common.cancel", "Cancel")}
        onConfirm={runConfirm}
        isPending={deleteOne.isPending || deleteAll.isPending}
      />
    </section>
  );
}
