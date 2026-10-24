import { MAX_NOTIFY_RECIPIENTS } from "./api/hitl";

/** Why a recipients draft cannot be saved; the UI turns it into a sentence. */
export type NotifyRecipientsError = "empty" | "tooMany" | "invalid" | "duplicate";

export interface ParsedNotifyRecipients {
  /** The addresses as typed (trimmed), in order. */
  recipients: string[];
  /** The first problem, or null when the list can be saved. */
  error: NotifyRecipientsError | null;
  /** 0-based position of the entry the error is about, when there is one. */
  index: number | null;
}

/**
 * A plain ASCII `local@domain` — the shape the backend's
 * `HitlConfigValidation.normalizeRecipient` accepts (no display name, no
 * angle brackets, a domain of at least two labels with a non-numeric TLD).
 * The backend stays the authority; this only keeps an obviously broken list
 * out of the draft so a save does not come back as a 400.
 */
const ADDRESS = /^[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@([A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z][A-Za-z0-9-]*$/;

export function isValidNotifyAddress(value: string): boolean {
  return value.length <= 254 && ADDRESS.test(value);
}

/**
 * Splits a draft (commas, semicolons, whitespace or new lines) into
 * addresses and checks them against the save-time rules: at least one, at
 * most {@link MAX_NOTIFY_RECIPIENTS}, each valid, no duplicate ignoring case.
 */
export function parseNotifyRecipients(draft: string): ParsedNotifyRecipients {
  const recipients = draft
    .split(/[\s,;]+/)
    .map((entry) => entry.trim())
    .filter((entry) => entry.length > 0);
  if (recipients.length === 0) return { recipients, error: "empty", index: null };
  if (recipients.length > MAX_NOTIFY_RECIPIENTS) return { recipients, error: "tooMany", index: null };
  const seen = new Set<string>();
  for (const [i, recipient] of recipients.entries()) {
    if (!isValidNotifyAddress(recipient)) return { recipients, error: "invalid", index: i };
    const key = recipient.toLowerCase();
    if (seen.has(key)) return { recipients, error: "duplicate", index: i };
    seen.add(key);
  }
  return { recipients, error: null, index: null };
}
