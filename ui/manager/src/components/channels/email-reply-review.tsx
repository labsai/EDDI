import { useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { MailCheck, X } from "lucide-react";
import { Badge, Button } from "@eddi/ui";
import { Input } from "@eddi/app-kit/components/ui/input";
import { isImeComposing } from "@eddi/app-kit/lib/ime";
import {
  REPLY_REVIEW_MODES,
  isInternalDomainPattern,
  type EmailControl,
  type ReplyReviewMode,
} from "@/lib/api/channels";

/**
 * Reply review of an email integration (`emailControl.replyReview`,
 * `emailControl.internalDomains`): which replies wait for a person to approve,
 * edit or reject them before they are sent. The reviews themselves are decided
 * on the Approvals page. Conversation mode only — trigger mode never replies,
 * and the backend refuses a review there.
 */
export function EmailReplyReviewSection({
  emailControl,
  conversation,
  onChange,
}: {
  emailControl: EmailControl | undefined;
  conversation: boolean;
  onChange: (next: EmailControl | undefined) => void;
}) {
  const { t } = useTranslation();
  const uid = useId();
  const [domainInput, setDomainInput] = useState("");
  const [domainError, setDomainError] = useState(false);
  const mode: ReplyReviewMode = emailControl?.replyReview ?? "never";
  const domains = emailControl?.internalDomains ?? [];

  const update = (patch: Partial<EmailControl>) => {
    const next: EmailControl = { ...(emailControl ?? {}), ...patch };
    if (next.replyReview === undefined) delete next.replyReview;
    if (next.internalDomains === undefined || next.internalDomains.length === 0) delete next.internalDomains;
    onChange(Object.keys(next).length > 0 ? next : undefined);
  };

  const addDomain = () => {
    const value = domainInput.trim().toLowerCase();
    if (!value) return;
    if (!isInternalDomainPattern(value)) {
      setDomainError(true);
      return;
    }
    setDomainError(false);
    if (!domains.includes(value)) update({ internalDomains: [...domains, value] });
    setDomainInput("");
  };

  return (
    <section className="rounded-xl border border-border/50 p-5 space-y-4" data-testid="email-reply-review-section">
      <h2 className="text-sm font-semibold flex items-center gap-2">
        <MailCheck className="h-4 w-4 text-primary" />
        {t("emailChannel.replyReview.title", "Reply review")}
      </h2>
      {!conversation ? (
        <p className="text-xs text-muted-foreground" data-testid="reply-review-trigger-note">
          {t("emailChannel.replyReview.triggerNote", "Trigger mode never replies, so there is no reply to review.")}
        </p>
      ) : (
        <div className="space-y-1">
          <label htmlFor={`${uid}-mode`} className="text-xs font-medium">
            {t("emailChannel.replyReview.modeLabel", "Review replies before they are sent")}
          </label>
          <select
            id={`${uid}-mode`}
            data-testid="reply-review-mode"
            className="flex h-8 w-full rounded-lg border border-border bg-background px-2 text-sm"
            value={mode}
            onChange={(e) => {
              const next = e.target.value as ReplyReviewMode;
              update({ replyReview: next === "never" ? undefined : next });
            }}
          >
            {REPLY_REVIEW_MODES.map((value) => (
              <option key={value} value={value}>{t(`emailChannel.replyReview.mode.${value}`, value)}</option>
            ))}
          </select>
          <p className="text-xs text-muted-foreground" data-testid="reply-review-hint">
            {t(`emailChannel.replyReview.hint.${mode}`, "")}
          </p>
        </div>
      )}

      <div className="space-y-1">
        <label htmlFor={`${uid}-domains`} className="text-xs font-medium">
          {t("emailChannel.replyReview.internalDomainsLabel", "Internal domains")}
        </label>
        <p className="text-xs text-muted-foreground">
          {t("emailChannel.replyReview.internalDomainsHint", "Domains whose addresses count as internal, such as contoso.com or *.contoso.com (subdomains only). With none, every recipient is external.")}
        </p>
        <div className="flex gap-1.5 flex-wrap" data-testid="internal-domains">
          {domains.map((domain) => (
            <Badge key={domain} variant="secondary" className="text-xs gap-1 font-mono">
              {domain}
              <button
                aria-label={t("common.removeItem", { item: domain, defaultValue: "Remove {{item}}" })}
                onClick={() => update({ internalDomains: domains.filter((d) => d !== domain) })}
                className="hover:text-destructive"
              >
                <X className="h-3 w-3" />
              </button>
            </Badge>
          ))}
        </div>
        <div className="flex gap-1">
          <Input
            id={`${uid}-domains`}
            data-testid="internal-domain-input"
            className="h-7 text-xs font-mono"
            value={domainInput}
            aria-invalid={domainError || undefined}
            onChange={(e) => {
              setDomainInput(e.target.value);
              setDomainError(false);
            }}
            placeholder="contoso.com"
            onKeyDown={(e) => {
              if (isImeComposing(e)) return;
              if (e.key === "Enter") {
                e.preventDefault();
                addDomain();
              }
            }}
          />
          <Button size="sm" variant="outline" className="h-7 text-xs" onClick={addDomain}>{t("common.add", "Add")}</Button>
        </div>
        {domainError && (
          <p className="text-xs text-destructive" role="alert" data-testid="internal-domain-error">
            {t("emailChannel.replyReview.internalDomainInvalid", "Enter a domain such as contoso.com, or *.contoso.com for its subdomains.")}
          </p>
        )}
      </div>
    </section>
  );
}
