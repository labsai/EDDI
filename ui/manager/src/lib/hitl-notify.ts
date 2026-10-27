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
 * A plain `local@domain` — the shape the backend's
 * `HitlConfigValidation.normalizeRecipient` accepts (no display name, no
 * angle brackets, an ASCII local part, a domain of at least two labels with a
 * non-numeric TLD). The domain may be internationalised: the server converts
 * it to ASCII (punycode) before checking it, so it is converted the same way
 * here. The backend stays the authority; this only keeps an obviously broken
 * list out of the draft so a save does not come back as a 400.
 */
const LOCAL_PART = /^[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*$/;
const ASCII_DOMAIN = /^([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z][a-z0-9-]*$/;

/** The ASCII, lower-case form of a mail domain, or null when it has none. */
function asciiDomain(domain: string): string | null {
  // Non-ASCII labels go through the platform's IDNA (UTS 46) conversion, the
  // counterpart of the server's IDN.toASCII.
  if (/[^\x00-\x7F]/.test(domain)) {
    try {
      const host = new URL(`http://${domain}`).hostname;
      return host.includes("@") || host.includes("/") ? null : host;
    } catch {
      return null;
    }
  }
  return domain.toLowerCase();
}

/** The server-side normal form of a valid address (ASCII domain, lower case), or null. */
function normalizeNotifyAddress(value: string): string | null {
  if (value.length > 254) return null;
  const at = value.lastIndexOf("@");
  if (at <= 0) return null;
  const local = value.slice(0, at);
  const domain = asciiDomain(value.slice(at + 1));
  if (domain === null || !LOCAL_PART.test(local) || !ASCII_DOMAIN.test(domain)) return null;
  return `${local}@${domain}`.toLowerCase();
}

export function isValidNotifyAddress(value: string): boolean {
  return normalizeNotifyAddress(value) !== null;
}

/**
 * Splits a draft (commas, semicolons, whitespace or new lines) into
 * addresses and checks them against the save-time rules: at least one, at
 * most {@link MAX_NOTIFY_RECIPIENTS}, each valid, no duplicate ignoring case
 * (and ignoring whether a domain is written in Unicode or punycode).
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
    const key = normalizeNotifyAddress(recipient);
    if (key === null) return { recipients, error: "invalid", index: i };
    if (seen.has(key)) return { recipients, error: "duplicate", index: i };
    seen.add(key);
  }
  return { recipients, error: null, index: null };
}
