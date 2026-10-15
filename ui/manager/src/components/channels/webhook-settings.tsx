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
      <div className="grid grid-cols-2 gap-3">
        <div className="space-y-1">
          <label htmlFor={`${uid}-scheme`} className="text-xs font-medium">{t("webhook.signatureScheme", "Signature scheme")}</label>
          <select id={`${uid}-scheme`} data-testid="webhook-scheme-select" className={selectClass} value={scheme} onChange={(e) => set("signatureScheme", e.target.value)}>
            {WEBHOOK_SIGNATURE_SCHEMES.map((s) => (<option key={s} value={s}>{s}</option>))}
          </select>
        </div>
        <div className="space-y-1">
          <label htmlFor={`${uid}-strategy`} className="text-xs font-medium">{t("webhook.conversationStrategy", "Conversation strategy")}</label>
          <select id={`${uid}-strategy`} data-testid="webhook-strategy-select" className={selectClass} value={strategy} onChange={(e) => set("conversationStrategy", e.target.value)}>
            <option value="new">new</option>
            <option value="correlate">correlate</option>
          </select>
        </div>
      </div>
      {scheme === "generic-hmac" && (
        <div className="grid grid-cols-3 gap-3">
          <Input aria-label="signatureHeader" value={pc.signatureHeader ?? ""} onChange={(e) => set("signatureHeader", e.target.value)} placeholder="X-Signature" />
          <select aria-label="signatureEncoding" className={selectClass} value={pc.signatureEncoding || "hex"} onChange={(e) => set("signatureEncoding", e.target.value)}>
            <option value="hex">hex</option>
            <option value="base64">base64</option>
          </select>
          <Input aria-label="signaturePrefix" value={pc.signaturePrefix ?? ""} onChange={(e) => set("signaturePrefix", e.target.value)} placeholder="sha256=" />
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
      <div className="grid grid-cols-2 gap-3">
        <Input aria-label="eventTypeHeader" value={pc.eventTypeHeader ?? ""} onChange={(e) => set("eventTypeHeader", e.target.value)} placeholder={t("webhook.eventTypeHeader", "Event type header (e.g. X-GitHub-Event)")} />
        <Input aria-label="eventTypePath" value={pc.eventTypePath ?? ""} onChange={(e) => set("eventTypePath", e.target.value)} placeholder={t("webhook.eventTypePath", "Event type JSON pointer (e.g. /action)")} />
      </div>
    </div>
  );
}

/** Endpoint URL and how to sign a request for the integration's scheme. */
export function WebhookSigningInstructions({ scheme }: { scheme: string }) {
  const { t } = useTranslation();
  const header =
    scheme === "github"
      ? "X-Hub-Signature-256: sha256=<hex HMAC-SHA256(secret, body)>"
      : scheme === "generic-hmac"
        ? "<signatureHeader>: <signaturePrefix><hex|base64 HMAC-SHA256(secret, body)>"
        : "X-Eddi-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + \".\" + body)>";
  return (
    <div className="text-xs text-muted-foreground space-y-1" data-testid="webhook-signing-instructions">
      <p>{t("webhook.signingInstructions", "Sign the raw request body with the signing secret and send the signature in this header. Unsigned, stale or tampered requests are refused with 401.")}</p>
      <code className="block rounded-lg bg-background border border-border px-3 py-2 font-mono break-all">{header}</code>
      <p>{t("webhook.formatsHint", "Accepts CloudEvents 1.0 (structured or binary mode) and plain JSON up to 256 KB. Answers 202 with the conversationId; a repeated event id answers 200.")}</p>
    </div>
  );
}
