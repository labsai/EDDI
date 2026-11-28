/**
 * The mailbox a link was started for, kept in this tab's sessionStorage across the
 * round trip to the provider. The callback names only the connection
 * (`?mailboxLinked=<connection>`): an address in the URL would land in browser
 * history and access logs. The link-result handler reads it back to check that
 * mailbox's grant, not just any usable one.
 */

const keyOf = (connection: string) => `eddi.mailboxLink.${connection}`;

/**
 * Lower case, with the domain in its ASCII (punycode) form, as the backend's
 * AddressNormalizer files it: `info@Bücher.de` is listed as `info@xn--bcher-kva.de`.
 */
export function normalizeMailboxAddress(address: string): string {
  const trimmed = address.trim().toLowerCase();
  const at = trimmed.lastIndexOf("@");
  if (at < 0) return trimmed;
  try {
    return `${trimmed.slice(0, at)}@${new URL(`http://${trimmed.slice(at + 1)}`).hostname}`;
  } catch {
    return trimmed;
  }
}

export function rememberPendingMailboxLink(connection: string, address: string): void {
  try {
    sessionStorage.setItem(keyOf(connection), normalizeMailboxAddress(address));
  } catch {
    // Storage blocked: the handler then makes no success claim; the list shows the state.
  }
}

/** Reads and forgets the remembered address; `null` when none was kept. */
export function takePendingMailboxLink(connection: string): string | null {
  try {
    const key = keyOf(connection);
    const address = sessionStorage.getItem(key);
    sessionStorage.removeItem(key);
    return address;
  } catch {
    return null;
  }
}
