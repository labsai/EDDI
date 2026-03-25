import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Send, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";

interface DiscussionInputProps {
  onSubmit: (question: string) => void;
  isLoading?: boolean;
  disabled?: boolean;
}

export function DiscussionInput({ onSubmit, isLoading, disabled }: DiscussionInputProps) {
  const { t } = useTranslation();
  const [question, setQuestion] = useState("");

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (question.trim() && !isLoading) {
      onSubmit(question.trim());
      setQuestion("");
    }
  }

  return (
    <form onSubmit={handleSubmit} className="flex gap-2 p-3 border-t border-border bg-card/50">
      <textarea
        value={question}
        onChange={(e) => setQuestion(e.target.value)}
        placeholder={t("groups.askQuestion", "Ask a question for the group to discuss…")}
        className="flex-1 resize-none rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring transition-shadow min-h-[40px] max-h-[120px]"
        rows={2}
        disabled={disabled || isLoading}
        onKeyDown={(e) => {
          if (e.key === "Enter" && !e.shiftKey) {
            e.preventDefault();
            handleSubmit(e);
          }
        }}
        data-testid="discussion-input"
      />
      <Button
        type="submit"
        disabled={!question.trim() || isLoading || disabled}
        className="self-end shrink-0"
        data-testid="start-discussion-btn"
      >
        {isLoading ? (
          <Loader2 className="h-4 w-4 animate-spin" />
        ) : (
          <Send className="h-4 w-4" />
        )}
        {t("groups.startDiscussion", "Discuss")}
      </Button>
    </form>
  );
}
