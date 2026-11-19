import { useEffect, useId, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Bell, BellOff, Hand } from "lucide-react";
import { Button } from "@eddi/ui";
import { AppLink } from "@eddi/app-kit/components/shared/app-link";
import { cn } from "@eddi/app-kit/lib/utils";
import { useWorkforceApprovals } from "@/hooks/use-workforce-approvals";
import { useBrowserNotices } from "@/hooks/use-browser-notices";
import { MANAGER_APPROVALS_HREF, type ApprovalItem } from "@/lib/workforce-approvals";

/**
 * The topbar's "waiting on a human" control (T2.3), on every viewport: a count
 * of the person's paused conversations and team sessions — and, for an
 * approver or administrator, of the pauses they may decide — with a panel
 * that lists them and jumps to each. It also holds the opt-in to browser
 * notifications for Workforce's notices (T2.8), the one place that may ask
 * the browser for permission, from a click.
 *
 * The count updates from the user event stream (`approval.*`, `team.*`), not a
 * poll, while the stream is open.
 */
export function ApprovalsIndicator() {
  const { t, i18n } = useTranslation();
  const { items, mine, toReview } = useWorkforceApprovals();
  const [open, setOpen] = useState(false);
  const panelId = useId();
  const buttonRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const count = items.length;

  // Escape and a click elsewhere close the panel; Escape returns focus to the button.
  useEffect(() => {
    if (!open) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      setOpen(false);
      buttonRef.current?.focus();
    };
    const onPointerDown = (e: PointerEvent) => {
      const target = e.target as Node;
      if (panelRef.current?.contains(target) || buttonRef.current?.contains(target)) return;
      setOpen(false);
    };
    window.addEventListener("keydown", onKeyDown);
    window.addEventListener("pointerdown", onPointerDown);
    return () => {
      window.removeEventListener("keydown", onKeyDown);
      window.removeEventListener("pointerdown", onPointerDown);
    };
  }, [open]);

  const label =
    count > 0
      ? t("Workforce.approvals.buttonCount", "Waiting on a human: {{count}}", { count })
      : t("Workforce.approvals.button", "Approvals and notices");

  return (
    <div className="relative">
      <Button
        ref={buttonRef}
        variant="ghost"
        size="icon"
        className="relative h-9 w-9"
        aria-label={label}
        title={label}
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => setOpen((value) => !value)}
        data-testid="workforce-approvals-button"
      >
        <Hand className="h-4 w-4" aria-hidden="true" />
        {count > 0 && (
          <span
            className="absolute -top-0.5 -end-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-warn-500 ps-1 pe-1 text-[10px] font-semibold leading-none text-white"
            data-testid="workforce-approvals-count"
            aria-hidden="true"
          >
            {count > 99 ? "99+" : count}
          </span>
        )}
      </Button>

      {open && (
        <div
          ref={panelRef}
          id={panelId}
          role="region"
          aria-label={t("Workforce.approvals.panel", "Waiting on a human")}
          className="absolute end-0 top-full z-40 mt-2 w-80 max-w-[calc(100vw-2rem)] rounded-xl border border-border bg-card p-2 shadow-lg"
          data-testid="workforce-approvals-panel"
        >
          {count === 0 && (
            <p className="ps-2 pe-2 py-3 text-sm text-muted-foreground" data-testid="workforce-approvals-empty">
              {t("Workforce.approvals.empty", "Nothing is waiting for approval.")}
            </p>
          )}
          {mine.length > 0 && (
            <ApprovalSection
              title={t("Workforce.approvals.mine", "Your paused conversations")}
              items={mine}
              locale={i18n.resolvedLanguage ?? i18n.language}
              onNavigate={() => setOpen(false)}
              testId="workforce-approvals-mine"
            />
          )}
          {toReview.length > 0 && (
            <ApprovalSection
              title={t("Workforce.approvals.toReview", "To review")}
              items={toReview}
              locale={i18n.resolvedLanguage ?? i18n.language}
              onNavigate={() => setOpen(false)}
              testId="workforce-approvals-review"
            />
          )}
          {toReview.length > 0 && (
            <AppLink
              to={MANAGER_APPROVALS_HREF}
              className="mt-1 block rounded-lg ps-2 pe-2 py-1.5 text-xs font-medium text-primary hover:bg-muted"
              data-testid="workforce-approvals-inbox"
            >
              {t("Workforce.approvals.openInbox", "Open the approvals inbox")}
            </AppLink>
          )}
          <BrowserNoticesToggle />
        </div>
      )}
    </div>
  );
}

function ApprovalSection({
  title,
  items,
  locale,
  onNavigate,
  testId,
}: {
  title: string;
  items: ApprovalItem[];
  locale: string;
  onNavigate: () => void;
  testId: string;
}) {
  const { t } = useTranslation();
  return (
    <section className="pb-1" data-testid={testId}>
      <h2 className="ps-2 pe-2 pb-1 pt-1 text-xs font-medium uppercase tracking-wider text-muted-foreground">{title}</h2>
      <ul className="flex flex-col gap-0.5">
        {items.map((item) => (
          <li key={item.conversationId}>
            <AppLink
              to={item.href}
              onClick={onNavigate}
              className={cn(
                "flex flex-col gap-0.5 rounded-lg ps-2 pe-2 py-1.5 text-start text-sm transition-colors",
                "hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
              )}
              data-testid={`workforce-approval-${item.conversationId}`}
            >
              <span className="text-foreground">{itemTitle(item, t)}</span>
              <span className="text-xs text-muted-foreground">{deadlineText(item, locale, t)}</span>
            </AppLink>
          </li>
        ))}
      </ul>
    </section>
  );
}

type Translate = ReturnType<typeof useTranslation>["t"];

function itemTitle(item: ApprovalItem, t: Translate): string {
  if (item.humanTurn) return t("Workforce.approvals.humanTurn", "Team session waiting for a human member");
  return item.surface === "group"
    ? t("Workforce.approvals.team", "Team session waiting for approval")
    : t("Workforce.approvals.chat", "Chat waiting for approval");
}

/** By when: what the timeout policy does and when, or that it waits for a person. */
function deadlineText(item: ApprovalItem, locale: string, t: Translate): string {
  if (item.deadline === null) return t("Workforce.approvals.waitsIndefinitely", "Waits until someone decides");
  const time = formatWhen(item.deadline, locale);
  switch (item.timeoutPolicy) {
    case "AUTO_APPROVE":
      return t("Workforce.approvals.autoApproveAt", "Approved automatically at {{time}}", { time });
    case "AUTO_REJECT":
      return t("Workforce.approvals.autoRejectAt", "Rejected automatically at {{time}}", { time });
    default:
      return t("Workforce.approvals.abortAt", "Stopped automatically at {{time}}", { time });
  }
}

function formatWhen(epochMs: number, locale: string): string {
  const when = new Date(epochMs);
  const sameDay = when.toDateString() === new Date().toDateString();
  try {
    return new Intl.DateTimeFormat(locale, sameDay ? { timeStyle: "short" } : { dateStyle: "medium", timeStyle: "short" }).format(when);
  } catch {
    return when.toLocaleString();
  }
}

function BrowserNoticesToggle() {
  const { t } = useTranslation();
  const { state, enable, disable } = useBrowserNotices();
  if (state === "unsupported") return null;
  return (
    <div className="mt-1 flex items-center gap-2 border-t border-border ps-2 pe-2 pt-2 text-xs text-muted-foreground" data-testid="workforce-browser-notices">
      {state === "on" ? <Bell className="h-3.5 w-3.5 shrink-0" aria-hidden="true" /> : <BellOff className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />}
      <span className="flex-1">
        {state === "on"
          ? t("Workforce.notices.browserOn", "Browser notifications are on for finished jobs and teams.")
          : state === "blocked"
            ? t("Workforce.notices.browserBlocked", "Your browser blocks notifications from this site.")
            : t("Workforce.notices.browserOff", "Get a browser notification when a job or team finishes.")}
      </span>
      {state === "on" && (
        <Button variant="ghost" size="sm" onClick={disable} data-testid="workforce-browser-notices-off">
          {t("Workforce.notices.browserTurnOff", "Turn off")}
        </Button>
      )}
      {state === "off" && (
        <Button variant="ghost" size="sm" onClick={() => void enable()} data-testid="workforce-browser-notices-on">
          {t("Workforce.notices.browserTurnOn", "Turn on")}
        </Button>
      )}
    </div>
  );
}
