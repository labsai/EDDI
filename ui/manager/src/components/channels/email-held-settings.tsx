import { useEffect, useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { Input } from "@eddi/app-kit/components/ui/input";
import type { EmailControl, EmailRefusalAction } from "@/lib/api/channels";

const selectClass =
  "flex h-9 w-full rounded-lg border border-border bg-background px-3 text-sm text-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-primary";
const hintClass = "text-[10px] text-muted-foreground";
const labelClass = "text-xs font-medium";

/** Drops empty sections, so an integration that never held mail keeps no `emailControl` noise. */
function withHeldMailSettings(
  control: EmailControl | null | undefined,
  onRefused: EmailControl["onRefused"],
  review: EmailControl["review"],
): EmailControl | null {
  const next: EmailControl = { ...(control ?? {}) };
  const cleanOnRefused = Object.fromEntries(Object.entries(onRefused ?? {}).filter(([, v]) => v !== undefined && v !== "drop"));
  const cleanReview = Object.fromEntries(
    Object.entries(review ?? {}).filter(([, v]) => v !== undefined && v !== "" && !(Array.isArray(v) && v.length === 0)),
  );
  if (Object.keys(cleanOnRefused).length > 0) next.onRefused = cleanOnRefused as EmailControl["onRefused"];
  else delete next.onRefused;
  if (Object.keys(cleanReview).length > 0) next.review = cleanReview as EmailControl["review"];
  else delete next.review;
  return Object.keys(next).length > 0 ? next : null;
}

/**
 * Held mail (email channel v3 §7.1): mail EDDI would refuse because the sender
 * is not on the allowlist, or did not authenticate, can be held for a reviewer
 * instead of dropped. Reviewers decide on the Approvals page.
 */
export function EmailHeldMailFields({
  emailControl,
  onChange,
}: {
  emailControl: EmailControl | null | undefined;
  onChange: (emailControl: EmailControl | null) => void;
}) {
  const { t } = useTranslation();
  const uid = useId();
  const onRefused = emailControl?.onRefused ?? {};
  const review = emailControl?.review ?? {};
  const setAction = (key: "notAllowlisted" | "unauthenticated", value: EmailRefusalAction) =>
    onChange(withHeldMailSettings(emailControl, { ...onRefused, [key]: value }, review));
  const setReview = (patch: NonNullable<EmailControl["review"]>) => onChange(withHeldMailSettings(emailControl, onRefused, { ...review, ...patch }));
  const numberOrUndefined = (value: string) => (value.trim() === "" ? undefined : Number(value));
  // The reviewers field is edited as text and committed as a list on blur, so a
  // comma typed mid-entry is not swallowed; it follows a reload of the document.
  const reviewersText = (review.reviewers ?? []).join(", ");
  const [reviewersDraft, setReviewersDraft] = useState(reviewersText);
  useEffect(() => setReviewersDraft(reviewersText), [reviewersText]);

  return (
    <div className="space-y-3 border-t border-border/50 pt-3" data-testid="email-held-settings">
      <p className="text-xs font-semibold text-muted-foreground">{t("emailHeld.title", "Held mail: review instead of drop")}</p>
      <p className={hintClass}>
        {t(
          "emailHeld.hint",
          "Mail that would be refused can be held for a person instead, who discards it, runs it once, or runs it and allows the sender. Loops, bounces, the blocklist and limits are never reviewable.",
        )}
      </p>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <div className="space-y-1">
          <label htmlFor={`${uid}-na`} className={labelClass}>{t("emailHeld.notAllowlisted", "Sender not on the allowlist")}</label>
          <select id={`${uid}-na`} className={selectClass} value={onRefused.notAllowlisted ?? "drop"} data-testid="email-held-not-allowlisted"
            onChange={(e) => setAction("notAllowlisted", e.target.value as EmailRefusalAction)}>
            <option value="drop">{t("emailHeld.drop", "Drop")}</option>
            <option value="review">{t("emailHeld.review", "Hold for review")}</option>
          </select>
        </div>
        <div className="space-y-1">
          <label htmlFor={`${uid}-un`} className={labelClass}>{t("emailHeld.unauthenticated", "Sender not authenticated")}</label>
          <select id={`${uid}-un`} className={selectClass} value={onRefused.unauthenticated ?? "drop"} data-testid="email-held-unauthenticated"
            onChange={(e) => setAction("unauthenticated", e.target.value as EmailRefusalAction)}>
            <option value="drop">{t("emailHeld.drop", "Drop")}</option>
            <option value="review">{t("emailHeld.review", "Hold for review")}</option>
          </select>
          <p className={hintClass}>
            {t("emailHeld.unauthenticatedHint", "A released unauthenticated mail runs anonymously, with read-only tools only, and never causes outgoing mail.")}
          </p>
        </div>
        <div className="space-y-1">
          <label htmlFor={`${uid}-exp`} className={labelClass}>{t("emailHeld.expiry", "Drop undecided mail after")}</label>
          <Input id={`${uid}-exp`} value={review.expiry ?? ""} placeholder="P7D" data-testid="email-held-expiry"
            onChange={(e) => setReview({ expiry: e.target.value.trim() || undefined })} />
          <p className={hintClass}>{t("emailHeld.expiryHint", "An ISO-8601 duration from PT1H to P90D, at most the ledger retention minus a day.")}</p>
        </div>
        <div className="space-y-1">
          <label htmlFor={`${uid}-max`} className={labelClass}>{t("emailHeld.maxPerDay", "Held mail per day at most")}</label>
          <Input id={`${uid}-max`} type="number" min={1} max={1000} value={review.maxHeldPerDay ?? ""} placeholder="50" data-testid="email-held-max"
            onChange={(e) => setReview({ maxHeldPerDay: numberOrUndefined(e.target.value) })} />
        </div>
        <div className="space-y-1">
          <label htmlFor={`${uid}-dom`} className={labelClass}>{t("emailHeld.maxPerDomain", "Per sender domain and day at most")}</label>
          <Input id={`${uid}-dom`} type="number" min={1} max={100} value={review.maxHeldPerSenderDomainPerDay ?? ""} placeholder="5"
            data-testid="email-held-max-domain" onChange={(e) => setReview({ maxHeldPerSenderDomainPerDay: numberOrUndefined(e.target.value) })} />
        </div>
        <div className="space-y-1">
          <label htmlFor={`${uid}-rev`} className={labelClass}>{t("emailHeld.reviewers", "Reviewers")}</label>
          <Input id={`${uid}-rev`} value={reviewersDraft}
            onChange={(e) => setReviewersDraft(e.target.value)} placeholder="role:eddi-approver, alice" data-testid="email-held-reviewers"
            onBlur={(e) => setReview({ reviewers: e.target.value.split(",").map((s) => s.trim()).filter(Boolean) })} />
          <p className={hintClass}>
            {t("emailHeld.reviewersHint", "Besides this integration's editors: role:eddi-approver, role:eddi-editor or role:eddi-admin, or user ids of people who hold one of those roles. Empty: eddi-approver. Run and allow always needs edit access.")}
          </p>
        </div>
      </div>
    </div>
  );
}
