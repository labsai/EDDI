import { useState } from "react";
import { useTranslation } from "react-i18next";
import { X } from "lucide-react";
import { useCreateBot } from "@/hooks/use-bots";

interface CreateBotDialogProps {
  open: boolean;
  onClose: () => void;
}

export function CreateBotDialog({ open, onClose }: CreateBotDialogProps) {
  const { t } = useTranslation();
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const createBot = useCreateBot();

  if (!open) return null;

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    try {
      await createBot.mutateAsync({ packages: [], channels: [] });
      setName("");
      setDescription("");
      onClose();
    } catch {
      // Error handled by TanStack Query
    }
  }

  return (
    <>
      {/* Backdrop */}
      <div
        className="fixed inset-0 z-50 bg-black/50 backdrop-blur-sm"
        onClick={onClose}
      />

      {/* Dialog */}
      <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
        <div
          className="w-full max-w-md rounded-xl border bg-card p-6 shadow-2xl"
          onClick={(e) => e.stopPropagation()}
        >
          {/* Header */}
          <div className="flex items-center justify-between">
            <h2 className="text-lg font-semibold text-foreground">
              {t("bots.createTitle", "Create New Bot")}
            </h2>
            <button
              onClick={onClose}
              className="rounded-md p-1 text-muted-foreground hover:bg-secondary hover:text-foreground"
            >
              <X className="h-5 w-5" />
            </button>
          </div>

          {/* Form */}
          <form onSubmit={handleSubmit} className="mt-6 space-y-4">
            <div>
              <label
                htmlFor="bot-name"
                className="mb-1.5 block text-sm font-medium text-foreground"
              >
                {t("bots.name", "Name")}
              </label>
              <input
                id="bot-name"
                type="text"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder={t("bots.namePlaceholder", "My Bot")}
                className="w-full rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                autoFocus
              />
            </div>

            <div>
              <label
                htmlFor="bot-description"
                className="mb-1.5 block text-sm font-medium text-foreground"
              >
                {t("bots.description", "Description")}
              </label>
              <textarea
                id="bot-description"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder={t(
                  "bots.descriptionPlaceholder",
                  "Describe what this bot does..."
                )}
                rows={3}
                className="w-full rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring resize-none"
              />
            </div>

            {/* Error */}
            {createBot.isError && (
              <p className="text-sm text-destructive">
                {t("common.error")}
              </p>
            )}

            {/* Actions */}
            <div className="flex justify-end gap-2 pt-2">
              <button
                type="button"
                onClick={onClose}
                className="rounded-lg px-4 py-2 text-sm font-medium text-muted-foreground hover:bg-secondary transition-colors"
              >
                {t("common.cancel")}
              </button>
              <button
                type="submit"
                disabled={createBot.isPending}
                className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-50"
              >
                {createBot.isPending
                  ? t("common.loading")
                  : t("common.create")}
              </button>
            </div>
          </form>
        </div>
      </div>
    </>
  );
}
