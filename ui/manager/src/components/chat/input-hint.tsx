import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";

/**
 * One-line keyboard hint under a multi-line chat input.
 *
 * Every chat surface accepts Shift+Enter for a new line, and none of them said
 * so — a plain single-line-looking box whose Enter sends gives no clue that
 * multi-line input exists at all. Shared component so the wording cannot drift
 * between surfaces, with `sendKey` for the one composer whose send is
 * Ctrl+Enter rather than Enter.
 */
export function InputHint({ sendKey = "enter", className }: { sendKey?: "enter" | "ctrl-enter"; className?: string }) {
  const { t } = useTranslation();
  return (
    <p className={cn("mt-1 px-1 text-[10px] text-muted-foreground/70 select-none", className)} data-testid="input-hint">
      {sendKey === "ctrl-enter"
        ? t("chat.multilineHintCtrlEnter", "Ctrl+Enter to send · Enter for a new line")
        : t("chat.multilineHint", "Enter to send · Shift+Enter for a new line")}
    </p>
  );
}
