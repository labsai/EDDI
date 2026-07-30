/**
 * Types for the pure helpers in audit-prod.mjs, so the gate's logic can be
 * unit-tested from the TypeScript suite. The script itself stays plain JS
 * because CI runs it with bare `node`, before any build step exists.
 */

export interface Advisory {
  id: string;
  package: string;
  severity: string;
  title: string;
}

export interface AllowlistEntry {
  package: string;
  reason: string;
}

/** The subset of `npm audit --json` these helpers read. */
export interface AuditReport {
  vulnerabilities?: Record<
    string,
    { severity?: string; via?: Array<string | { url?: string; severity?: string; title?: string }> }
  >;
  metadata?: unknown;
}

/** One entry per (advisory, package) occurrence. */
export function advisories(report: AuditReport): Advisory[];

/** True only when the allowlist accepts this advisory *for this package*. */
export function isAccepted(
  advisory: Advisory,
  allowlist?: Record<string, AllowlistEntry>,
): boolean;

/** Advisories in the full audit that are absent from the production-only one. */
export function devOnlyAdvisories(all: Advisory[], prod: Advisory[]): Advisory[];
