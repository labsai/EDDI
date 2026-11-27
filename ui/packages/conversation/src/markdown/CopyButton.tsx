/* The copy button of code blocks, diagrams and messages: its own module, so
   the renderer and the lazy diagram both import it without a cycle. */

import { useCallback, useEffect, useRef, useState } from "react";
import { Check, Copy, X } from "lucide-react";
import { useConversationText } from "../react/hooks";

/** Write text to the clipboard; false when the browser refuses (insecure frame, no permission). */
async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}

/** A copy button that confirms for a moment, visually and to screen readers. */
export function CopyButton({
  getText,
  label,
  className,
}: {
  getText: () => string;
  label: string;
  className: string;
}) {
  const t = useConversationText();
  // Last outcome, shown for a moment: a refused copy must not look like a
  // button that did nothing.
  const [status, setStatus] = useState<"idle" | "done" | "failed">("idle");
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current);
    },
    [],
  );
  const onClick = useCallback(async () => {
    const ok = await copyText(getText());
    setStatus(ok ? "done" : "failed");
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => setStatus("idle"), 1500);
  }, [getText]);
  const shown =
    status === "done" ? t("conversation.copy.done") : status === "failed" ? t("conversation.copy.failed") : label;
  return (
    <button
      type="button"
      className={className}
      onClick={onClick}
      aria-label={shown}
      title={shown}
      data-copy-status={status}
      data-testid="copy-button"
    >
      {status === "done" ? <Check size="1em" /> : status === "failed" ? <X size="1em" /> : <Copy size="1em" />}
    </button>
  );
}
