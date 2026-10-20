import { useId } from "react";
import { useTranslation } from "react-i18next";
import { Input } from "@/components/ui/input";
import { SecretKeyPicker } from "@/components/shared/secret-key-picker";
import { WEBHOOK_SIGNATURE_SCHEMES, type ChannelIntegrationConfiguration } from "@/lib/api/channels";

const selectClass =
  "flex h-9 w-full rounded-lg border border-border bg-background px-3 text-sm text-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-primary";

/**
 * Platform settings of a `webhook` channel integration. Only the signing secret
 * is required (and only as a vault reference — the backend refuses anything
 * else); every other field has a backend default when left empty.
 */
/** One-line explanation of the chosen signature scheme, shown under the picker. */
export function SchemeHint({ scheme, className, testId }: { scheme: string; className: string; testId: string }) {
  const { t } = useTranslation();
  const text =
    scheme === "github"
      ? t("webhook.schemeHintGithub", "GitHub's X-Hub-Signature-256 header (sha256=<hex>). Use it to receive GitHub webhooks as they are.")
      : scheme === "generic-hmac"
        ? t("webhook.schemeHintGenericHmac", "A plain HMAC-SHA256 of the body, sent in X-Signature unless you set another header on the channel page — for senders that sign their own way.")
        : t("webhook.schemeHintEddi", "EDDI's timestamped signature (X-Eddi-Signature). Stale requests are refused, which protects against replays.");
  return <p className={className} data-testid={testId}>{text}</p>;
}

export function WebhookPlatformFields({
  draft,
  onChange,
}: {
  draft: ChannelIntegrationConfiguration;
  onChange: (platformConfig: Record<string, string>) => void;
}) {
  const { t } = useTranslation();
  const uid = useId();
  const pc = draft.platformConfig;
  const set = (key: string, value: string) => {
    const next = { ...pc };
    if (value === "") delete next[key];
    else next[key] = value;
    onChange(next);
  };
  const scheme = pc.signatureScheme || "eddi-v1";
  const strategy = pc.conversationStrategy || "new";

  return (
    <div className="space-y-3" data-testid="webhook-platform-fields">
      <div className="space-y-1">
        <label htmlFor={`${uid}-signing`} className="text-xs font-medium">{t("channelDetail.signingSecret", "Signing Secret")}</label>
        <SecretKeyPicker id={`${uid}-signing`} referenceOnly testId="webhook-signing-secret" value={pc.signingSecret ?? ""} onChange={(v) => set("signingSecret", v)} placeholder="${vault:webhook-signing-secret}" />
        <p className="text-[10px] text-muted-foreground">{t("webhook.signingSecretHint", "Required. The shared HMAC secret, stored in the secrets vault and referenced as ${vault:…}.")}</p>
      </div>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <div className="space-y-1">
          <label htmlFor={`${uid}-scheme`} className="text-xs font-medium">{t("webhook.signatureScheme", "Signature scheme")}</label>
          <select id={`${uid}-scheme`} data-testid="webhook-scheme-select" className={selectClass} value={scheme} onChange={(e) => set("signatureScheme", e.target.value)}>
            {WEBHOOK_SIGNATURE_SCHEMES.map((s) => (<option key={s} value={s}>{s}</option>))}
          </select>
          <SchemeHint scheme={scheme} className="text-[10px] text-muted-foreground" testId="webhook-scheme-hint" />
        </div>
        <div className="space-y-1">
          <label htmlFor={`${uid}-strategy`} className="text-xs font-medium">{t("webhook.conversationStrategy", "Conversation strategy")}</label>
          <select id={`${uid}-strategy`} data-testid="webhook-strategy-select" className={selectClass} value={strategy} onChange={(e) => set("conversationStrategy", e.target.value)}>
            <option value="new">new</option>
            <option value="correlate">correlate</option>
          </select>
          <p className="text-[10px] text-muted-foreground">
            {strategy === "correlate"
              ? t("webhook.strategyHintCorrelate", "Events with the same correlation key continue one conversation.")
              : t("webhook.strategyHintNew", "Every event starts a new conversation.")}
          </p>
        </div>
      </div>
      {scheme === "generic-hmac" && (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
          <div className="space-y-1">
            <label htmlFor={`${uid}-sig-header`} className="text-xs font-medium">{t("webhook.signatureHeaderLabel", "Signature header")}</label>
            <Input id={`${uid}-sig-header`} value={pc.signatureHeader ?? ""} onChange={(e) => set("signatureHeader", e.target.value)} placeholder="X-Signature" />
          </div>
          <div className="space-y-1">
            <label htmlFor={`${uid}-sig-encoding`} className="text-xs font-medium">{t("webhook.signatureEncodingLabel", "Signature encoding")}</label>
            <select id={`${uid}-sig-encoding`} className={selectClass} value={pc.signatureEncoding || "hex"} onChange={(e) => set("signatureEncoding", e.target.value)}>
              <option value="hex">hex</option>
              <option value="base64">base64</option>
            </select>
          </div>
          <div className="space-y-1">
            <label htmlFor={`${uid}-sig-prefix`} className="text-xs font-medium">{t("webhook.signaturePrefixLabel", "Signature prefix")}</label>
            <Input id={`${uid}-sig-prefix`} value={pc.signaturePrefix ?? ""} onChange={(e) => set("signaturePrefix", e.target.value)} placeholder="sha256=" />
          </div>
        </div>
      )}
      {strategy === "correlate" && (
        <div className="space-y-1">
          <label htmlFor={`${uid}-corr`} className="text-xs font-medium">{t("webhook.correlationKeyPath", "Correlation key (JSON pointer)")}</label>
          <Input id={`${uid}-corr`} data-testid="webhook-correlation-path" value={pc.correlationKeyPath ?? ""} onChange={(e) => set("correlationKeyPath", e.target.value)} placeholder="/data/pull_request/number" />
        </div>
      )}
      <div className="space-y-1">
        <label htmlFor={`${uid}-template`} className="text-xs font-medium">{t("webhook.inputTemplate", "Input template")}</label>
        <Input id={`${uid}-template`} data-testid="webhook-input-template" value={pc.inputTemplate ?? ""} onChange={(e) => set("inputTemplate", e.target.value)} placeholder="New {event.type} from {event.source}" />
        <p className="text-[10px] text-muted-foreground">{t("webhook.inputTemplateHint", "The turn's input text. Defaults to event:<type>. The full event is available to the agent as {context.event}.")}</p>
      </div>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <div className="space-y-1">
          <label htmlFor={`${uid}-evt-header`} className="text-xs font-medium">{t("webhook.eventTypeHeaderLabel", "Event type header")}</label>
          <Input id={`${uid}-evt-header`} value={pc.eventTypeHeader ?? ""} onChange={(e) => set("eventTypeHeader", e.target.value)} placeholder={t("webhook.eventTypeHeader", "Event type header (e.g. X-GitHub-Event)")} />
        </div>
        <div className="space-y-1">
          <label htmlFor={`${uid}-evt-path`} className="text-xs font-medium">{t("webhook.eventTypePathLabel", "Event type JSON pointer")}</label>
          <Input id={`${uid}-evt-path`} value={pc.eventTypePath ?? ""} onChange={(e) => set("eventTypePath", e.target.value)} placeholder={t("webhook.eventTypePath", "Event type JSON pointer (e.g. /action)")} />
        </div>
      </div>
    </div>
  );
}

/** Endpoint URL and how to sign a request for the integration's scheme. */
export function WebhookSigningInstructions({ scheme, platformConfig = {} }: { scheme: string; platformConfig?: Record<string, string> }) {
  const { t } = useTranslation();
  // Mirror WebhookChannelSettings: a blank header falls back to X-Signature,
  // a blank encoding to hex, and the prefix is sent as configured (none by default).
  const genericHeader = platformConfig.signatureHeader?.trim() || "X-Signature";
  const genericEncoding = platformConfig.signatureEncoding?.trim().toLowerCase() || "hex";
  const genericPrefix = platformConfig.signaturePrefix ?? "";
  const header =
    scheme === "github"
      ? "X-Hub-Signature-256: sha256=<hex HMAC-SHA256(secret, body)>"
      : scheme === "generic-hmac"
        ? `${genericHeader}: ${genericPrefix}<${genericEncoding} HMAC-SHA256(secret, body)>`
        : "X-Eddi-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + \".\" + body)>";
  return (
    <div className="text-xs text-muted-foreground space-y-1" data-testid="webhook-signing-instructions">
      <p>{t("webhook.signingInstructions", "Sign the raw request body with the signing secret and send the signature in this header. Unsigned or tampered requests are refused with 401.")}</p>
      {/* Only eddi-v1 signs a timestamp; promising stale-request refusal for the
          other schemes would read as replay protection they do not have. */}
      <p data-testid="webhook-replay-note">
        {scheme === "github" || scheme === "generic-hmac"
          ? t("webhook.signingInstructionsNoTimestamp", "This scheme signs no timestamp: a replayed request is caught only by deduplication, for 10 minutes on each node.")
          : t("webhook.signingInstructionsStale", "Requests whose timestamp is more than 5 minutes off are refused with 401 too, which protects against replays.")}
      </p>
      <code className="block rounded-lg bg-background border border-border px-3 py-2 font-mono break-all">{header}</code>
      <p>{t("webhook.formatsHint", "Accepts CloudEvents 1.0 (structured or binary mode) and plain JSON up to 256 KB. Answers 202 with the conversationId; a repeated event id answers 200.")}</p>
    </div>
  );
}
