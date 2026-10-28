import { useState, useCallback } from "react";
import { toast } from "sonner";
import { getErrorMessage } from "@/lib/api-client";
import { useTranslation } from "react-i18next";
import { Cable, ChevronRight, ChevronLeft, AlertCircle } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { SchemeHint } from "./webhook-settings";
import { EmailPlatformFields } from "./email-settings";
import { emailConfigProblems, initialEmailPlatformConfig, withEmailFixedKeys } from "@/lib/email-channel";
import { toastChannelWarnings } from "@/lib/email-channel-messages";
import { SecretKeyPicker } from "@/components/shared/secret-key-picker";
import { isEmptyOrReference } from "@/lib/channel-secrets";
import { StepDots } from "@/components/shared/step-dots";
import { useCreateChannel } from "@/hooks/use-channels";
import {
  CHANNEL_TYPES,
  WEBHOOK_SIGNATURE_SCHEMES,
  createDefaultTarget,
  type ChannelIntegrationConfiguration,
} from "@/lib/api/channels";

interface CreateChannelDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onCreated?: (id: string) => void;
  defaultAgentId?: string;
}

type Step = "type" | "credentials" | "target";
const STEPS: Step[] = ["type", "credentials", "target"];

export function CreateChannelDialog({
  open,
  onOpenChange,
  onCreated,
  defaultAgentId,
}: CreateChannelDialogProps) {
  const { t } = useTranslation();
  const createMutation = useCreateChannel();

  const [step, setStep] = useState<Step>("type");
  const [name, setName] = useState("");
  const [channelType, setChannelType] = useState("slack");
  const [channelId, setChannelId] = useState("");
  const [botToken, setBotToken] = useState("");
  const [signingSecret, setSigningSecret] = useState("");
  const [signatureScheme, setSignatureScheme] = useState("eddi-v1");
  const [emailConfig, setEmailConfig] = useState<Record<string, string>>(initialEmailPlatformConfig);
  const [targetAgentId, setTargetAgentId] = useState(defaultAgentId ?? "");

  const reset = useCallback(() => {
    setStep("type");
    setName("");
    setChannelType("slack");
    setChannelId("");
    setBotToken("");
    setSigningSecret("");
    setSignatureScheme("eddi-v1");
    setEmailConfig(initialEmailPlatformConfig());
    setTargetAgentId(defaultAgentId ?? "");
  }, [defaultAgentId]);

  const isWebhook = channelType === "webhook";
  const isEmail = channelType === "email";
  const stepIndex = STEPS.indexOf(step);
  const isFirst = stepIndex === 0;
  const isLast = stepIndex === STEPS.length - 1;

  const canProceed = () => {
    switch (step) {
      case "type": return name.trim().length > 0;
      // Both credentials are optional here, but if given they must be vault
      // references: see `plaintextSecretFields`.
      case "credentials":
        // A webhook is authenticated by its signature alone, so its secret is
        // required (the backend refuses to save one without it).
        if (isWebhook) return signingSecret.trim().length > 0 && isEmptyOrReference(signingSecret);
        // The backend re-checks every rule; this only spares a round trip.
        if (isEmail) return emailConfigProblems(emailConfig).length === 0;
        return channelId.trim().length > 0 && isEmptyOrReference(botToken) && isEmptyOrReference(signingSecret);
      case "target": return targetAgentId.trim().length > 0;
    }
  };

  const handleNext = () => { if (!isLast) setStep(STEPS[stepIndex + 1]!); };
  const handleBack = () => { if (!isFirst) setStep(STEPS[stepIndex - 1]!); };

  const handleCreate = async () => {
    const config: ChannelIntegrationConfiguration = {
      name: name.trim(),
      channelType,
      platformConfig: isEmail
        ? withEmailFixedKeys(emailConfig)
        : isWebhook
        ? { signingSecret: signingSecret.trim(), signatureScheme }
        : {
            channelId: channelId.trim(),
            botToken: botToken.trim(),
            signingSecret: signingSecret.trim(),
          },
      targets: [createDefaultTarget(targetAgentId.trim())],
      defaultTargetName: "default",
    };

    try {
      const result = await createMutation.mutateAsync(config);
      const location = result?.location ?? "";
      toastChannelWarnings(result?.warnings ?? [], t);
      const match = location.match(/channels\/([^?]+)/);
      const newId = match?.[1] ?? "";
      onCreated?.(newId);
      onOpenChange(false);
      reset();
    } catch (err) {
      toast.error(getErrorMessage(err));
    }
  };

  /** Close and clear — but not mid-create, or the request's result lands on a reset form. */
  const dismiss = () => {
    if (createMutation.isPending) return;
    reset();
    onOpenChange(false);
  };

  if (!open) return null;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4"
      onKeyDown={(e) => {
        // A child that handled Escape (the secret picker's list or its
        // portalled "Add Secret" modal) marks it with preventDefault.
        if (e.key === "Escape" && !e.defaultPrevented) dismiss();
      }}
    >
      <div className="fixed inset-0 bg-black/50" onClick={dismiss} />
      <div className="relative z-50 w-full max-w-lg rounded-2xl border border-border bg-card p-6 shadow-2xl max-h-[calc(100dvh-2rem)] overflow-y-auto" role="dialog" aria-modal="true" aria-labelledby="create-channel-title">
        {/* Header */}
        <div className="flex items-center gap-2 mb-4">
          <Cable className="h-5 w-5 text-primary" />
          <h2 id="create-channel-title" className="text-lg font-semibold">
            {t("channels.createTitle", "Create Channel Integration")}
          </h2>
        </div>

        <StepDots total={STEPS.length} current={stepIndex} />

        <div className="space-y-4 min-h-[200px]">
          {/* Step 1: Type & Name */}
          {step === "type" && (
            <>
              <div className="space-y-1.5">
                <label className="text-sm font-medium text-foreground" htmlFor="create-channel-name">
                  {t("channels.name", "Name")}
                </label>
                <Input
                  id="create-channel-name"
                  data-testid="create-channel-name"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder={t("channels.namePlaceholder", "e.g. Engineering AI Hub")}
                  autoFocus
                />
              </div>
              <div className="space-y-1.5">
                <label className="text-sm font-medium text-foreground" htmlFor="create-channel-type">{t("channels.channelType", "Channel Type")}</label>
                <select
                  id="create-channel-type"
                  data-testid="create-channel-type"
                  className="flex h-9 w-full rounded-lg border border-border bg-background px-3 py-1 text-sm text-foreground transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-primary"
                  value={channelType}
                  onChange={(e) => setChannelType(e.target.value)}
                >
                  {CHANNEL_TYPES.map((ct) => (
                    <option key={ct} value={ct}>{ct.charAt(0).toUpperCase() + ct.slice(1)}</option>
                  ))}
                </select>
              </div>
            </>
          )}

          {/* Step 2: Credentials */}
          {step === "credentials" && isWebhook && (
            <>
              <div className="space-y-1.5">
                <label className="text-sm font-medium text-foreground" htmlFor="create-webhook-signing-field">{t("channels.signingSecret", "Signing Secret")}</label>
                <SecretKeyPicker id="create-webhook-signing-field" referenceOnly testId="create-webhook-signing-secret" value={signingSecret} onChange={setSigningSecret} placeholder="${vault:webhook-signing-secret}" />
                <p className="text-xs text-muted-foreground">{t("webhook.signingSecretHint", "Required. The shared HMAC secret, stored in the secrets vault and referenced as ${vault:…}.")}</p>
              </div>
              <div className="space-y-1.5">
                <label className="text-sm font-medium text-foreground" htmlFor="create-webhook-scheme">{t("webhook.signatureScheme", "Signature scheme")}</label>
                <select
                  id="create-webhook-scheme"
                  data-testid="create-webhook-scheme"
                  className="flex h-9 w-full rounded-lg border border-border bg-background px-3 py-1 text-sm text-foreground"
                  value={signatureScheme}
                  onChange={(e) => setSignatureScheme(e.target.value)}
                >
                  {WEBHOOK_SIGNATURE_SCHEMES.map((s) => (<option key={s} value={s}>{s}</option>))}
                </select>
                <SchemeHint scheme={signatureScheme} className="text-xs text-muted-foreground" testId="create-webhook-scheme-hint" />
              </div>
            </>
          )}
          {step === "credentials" && isEmail && (
            <EmailPlatformFields platformConfig={emailConfig} onChange={setEmailConfig} />
          )}
          {step === "credentials" && !isWebhook && !isEmail && (
            <>
              <div className="space-y-1.5">
                <label className="text-sm font-medium text-foreground" htmlFor="create-channel-id">
                  {t("channels.channelId", "Slack Channel ID")}
                </label>
                <Input id="create-channel-id" data-testid="create-channel-id" value={channelId} onChange={(e) => setChannelId(e.target.value)} placeholder="C0123ABCDEF" autoFocus />
                <p className="text-xs text-muted-foreground">{t("channels.channelIdHint", "Right-click a Slack channel → View channel details → copy the Channel ID")}</p>
              </div>
              <div className="space-y-1.5">
                <label className="text-sm font-medium text-foreground">{t("channels.botToken", "Bot Token")}</label>
                <SecretKeyPicker referenceOnly testId="create-channel-bot-token" value={botToken} onChange={setBotToken} placeholder="${vault:slack-bot-token}" />
                <p className="text-xs text-muted-foreground">{t("channels.botTokenHint", "Bot User OAuth Token. Use a vault reference for security.")}</p>
              </div>
              <div className="space-y-1.5">
                <label className="text-sm font-medium text-foreground">{t("channels.signingSecret", "Signing Secret")}</label>
                <SecretKeyPicker referenceOnly testId="create-channel-signing-secret" value={signingSecret} onChange={setSigningSecret} placeholder="${vault:slack-signing-secret}" />
                <p className="text-xs text-muted-foreground">{t("channels.signingSecretHint", "From your Slack App's Basic Information page.")}</p>
              </div>
            </>
          )}

          {/* Step 3: Default Target */}
          {step === "target" && (
            <>
              <div className="rounded-lg border border-amber-200 bg-amber-50 p-3 dark:border-amber-800 dark:bg-amber-950/30">
                <div className="flex items-start gap-2">
                  <AlertCircle className="h-4 w-4 text-amber-600 dark:text-amber-400 mt-0.5 shrink-0" />
                  <p className="text-xs text-amber-700 dark:text-amber-300">
                    {isEmail
                      ? t("emailChannel.defaultTargetHint", "The default target handles every mail whose plus-tag (invoices+urgent@…) or subject matches no other target's trigger. You can add more targets after creation.")
                      : isWebhook
                      ? t("webhook.defaultTargetHint", "The default target handles every event that no other target's event type matches. You can add more targets after creation.")
                      : t("channels.defaultTargetHint", "The default target handles all messages that don't match a trigger keyword. You can add more targets after creation.")}
                  </p>
                </div>
              </div>
              <div className="space-y-1.5">
                <label className="text-sm font-medium text-foreground" htmlFor="create-target-agent">
                  {t("channels.defaultAgentId", "Default Agent ID")}
                </label>
                <Input id="create-target-agent" data-testid="create-target-agent" value={targetAgentId} onChange={(e) => setTargetAgentId(e.target.value)} placeholder={t("channels.agentIdPlaceholder", "Agent ID to handle messages")} autoFocus />
              </div>
            </>
          )}
        </div>

        {/* Footer */}
        <div className="flex justify-between mt-6">
          <Button variant="outline" onClick={handleBack} disabled={isFirst} className={isFirst ? "invisible" : ""}>
            <ChevronLeft className="h-4 w-4 me-1 rtl:rotate-180" /> {t("common.back", "Back")}
          </Button>
          {isLast ? (
            <Button onClick={handleCreate} disabled={!canProceed() || createMutation.isPending} data-testid="create-channel-submit">
              {createMutation.isPending ? t("common.saving", "Creating...") : t("channels.create", "Create Channel")}
            </Button>
          ) : (
            <Button onClick={handleNext} disabled={!canProceed()} data-testid="create-channel-next">
              {t("common.next", "Next")} <ChevronRight className="h-4 w-4 ms-1 rtl:rotate-180" />
            </Button>
          )}
        </div>
      </div>
    </div>
  );
}
