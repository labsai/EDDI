import { EMAIL_CONVERSATION_KEYS, initialEmailPlatformConfig } from "./email-channel";

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
  ...EMAIL_CONVERSATION_KEYS,
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

const SENDER_KEYS = ["senderAllowlist", "senderBlocklist"];

function hasSenderEntries(list: string | undefined): boolean {
  return (list ?? "").split(",").some((entry) => entry.trim() !== "");
}

/**
 * Whether saving `next` over the stored `stored` platform config removes the
 * last entry of a non-empty email sender allowlist — which opens the
 * integration to every sender, and which the backend saves only with
 * `confirmOpen`.
 */
export function emptiesSenderAllowlist(
  stored: Record<string, string> | undefined,
  next: Record<string, string> | undefined,
): boolean {
  return hasSenderEntries(stored?.senderAllowlist) && !hasSenderEntries(next?.senderAllowlist);
}

/**
 * The draft after the page adopts a newer stored version (the one a targeted
 * sender-list change wrote). Every field takes its stored value — another
 * editor's change to the host or the routing must not be written back from an
 * old snapshot — except a field the user edited on this page since it was
 * seeded (it differs from `base`). The sender lists always come from the store.
 */
export function adoptStoredVersion<
  T extends { platformConfig: Record<string, string> },
>(draft: T, base: T, stored: T): T {
  const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b);
  const next: Record<string, unknown> = { ...stored };
  for (const key of Object.keys(draft)) {
    if (key === "platformConfig") continue;
    const k = key as keyof T;
    if (!same(draft[k], base[k])) next[key] = draft[k];
  }
  const platformConfig: Record<string, string> = { ...stored.platformConfig };
  for (const key of Object.keys(draft.platformConfig)) {
    if (SENDER_KEYS.includes(key)) continue;
    const mine = draft.platformConfig[key];
    if (mine !== undefined && !same(mine, base.platformConfig[key])) platformConfig[key] = mine;
  }
  next.platformConfig = platformConfig;
  return next as T;
}
