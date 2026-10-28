import { initialEmailPlatformConfig } from "./email-channel";

/**
 * The platformConfig keys each channel type reads, as its settings forms
 * write them (`webhook-settings.tsx`, the Slack fields on the channel page).
 * `email` is absent on purpose: switching to it always starts from
 * `initialEmailPlatformConfig()`, because the backend refuses any key it does
 * not know.
 */
const CHANNEL_PLATFORM_KEYS: Record<string, readonly string[]> = {
  slack: ["channelId", "botToken", "signingSecret", "hitlApprovalChannel", "hitlApproverUserIds"],
  webhook: [
    "signingSecret",
    "signatureScheme",
    "signatureHeader",
    "signatureEncoding",
    "signaturePrefix",
    "conversationStrategy",
    "correlationKeyPath",
    "inputTemplate",
    "eventTypeHeader",
    "eventTypePath",
  ],
};

/** The keys an email integration reads (the backend refuses any other). */
const EMAIL_KEYS = [
  "mode", "transport", "connection", "host", "port", "address", "folder", "processedAction",
  "pollSeconds", "requireAuthentication", "senderAllowlist", "senderBlocklist", "identityMode",
  "correlateThreads", "attachments", "attachmentMimeTypes", "maxMessageBytes", "stripQuotedHistory",
  "inputTemplate", "maxTurnsPerSenderPerDay", "dailyCostBudgetUsd", "ownerNotifyAddresses",
];

function keysOf(type: string): readonly string[] {
  return type === "email" ? EMAIL_KEYS : (CHANNEL_PLATFORM_KEYS[type] ?? []);
}

/**
 * The platformConfig to continue with after the channel type changes from
 * `previousType` to `newType`.
 *
 * `email` always starts from `initialEmailPlatformConfig()`. Any other type
 * keeps only those of `previous`'s keys that it reads AND that the previous
 * type did not read: a key both types share by name (`signingSecret` for Slack
 * and webhooks, `inputTemplate` for webhooks and email) means something
 * different in each, so its old value is stale for the new type. What survives
 * is only a key that already belonged to the new type — a legacy document that
 * carried both. Switching back and forth therefore never resurrects a key:
 * a webhook signing secret cannot reach an email save, nor an email template a
 * webhook one. An unknown type starts empty.
 */
export function platformConfigForType(
  newType: string,
  previousType: string,
  previous: Record<string, string> | null | undefined,
): Record<string, string> {
  if (newType === previousType) return { ...(previous ?? {}) };
  if (newType === "email") return initialEmailPlatformConfig();
  const oldKeys = new Set(keysOf(previousType));
  const next: Record<string, string> = {};
  for (const key of keysOf(newType)) {
    const value = previous?.[key];
    if (value !== undefined && !oldKeys.has(key)) next[key] = value;
  }
  return next;
}
