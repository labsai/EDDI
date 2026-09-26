import { describe, expect, it } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { AuditPage } from "@/pages/audit";
import { server } from "@/test/mocks/server";
import { auditVerdict, type AuditVerificationReport } from "@/lib/api/audit-verify";

/**
 * UI review High 9: the audit screen showed a green "SIGNED" shield whenever
 * every entry had an `hmac` field, and never called the verify endpoints. A
 * forged entry carries an hmac too, and a deleted one leaves nothing behind to
 * fail — so a tampered trail looked exactly like an intact one.
 */

function report(overrides: Partial<AuditVerificationReport> = {}): AuditVerificationReport {
  return {
    scope: "conversation",
    scopeId: "conv1",
    signingEnabled: true,
    entriesChecked: 4,
    valid: 4,
    recovered: 0,
    recoverySkipped: 0,
    invalid: 0,
    unsigned: 0,
    chainStatus: "INTACT",
    missingSequences: [],
    undeliveredSequences: [],
    duplicateSequences: [],
    problems: [],
    verifiedAt: "2026-09-26T10:00:00Z",
    ...overrides,
  };
}

function serveVerification(body: AuditVerificationReport | null, status = 200) {
  const calls: string[] = [];
  server.use(
    http.get("*/auditstore/verify/:conversationId", ({ request }) => {
      calls.push(request.url);
      return body ? HttpResponse.json(body, { status }) : new HttpResponse(null, { status });
    }),
  );
  return calls;
}

async function searchConversation() {
  renderWithProviders(<AuditPage />, { initialRoute: "/manage/audit" });
  const user = userEvent.setup();
  await user.click(screen.getByTestId("mode-conversation"));
  await user.type(screen.getByTestId("conversation-input"), "conv1");
  await user.click(screen.getByTestId("search-button"));
  await screen.findByTestId("audit-timeline");
}

async function verdictShown() {
  await waitFor(() =>
    expect(screen.getByTestId("integrity-banner").dataset.verdict).not.toBe("loading"),
  );
  return screen.getByTestId("integrity-banner");
}

describe("audit integrity banner", () => {
  it("is green only when the backend verified the trail", async () => {
    const calls = serveVerification(report());
    await searchConversation();
    const banner = await verdictShown();
    expect(banner.dataset.verdict).toBe("verified");
    expect(within(banner).getByText("VERIFIED")).toBeInTheDocument();
    expect(new URL(calls[0]!).pathname).toBe("/auditstore/verify/conv1");
  });

  it("reports tampering even though every entry carries an hmac", async () => {
    serveVerification(
      report({
        valid: 3,
        invalid: 1,
        problems: [
          {
            entryId: "audit-2",
            conversationId: "conv1",
            sequence: 1,
            timestamp: "2026-09-26T10:00:00Z",
            status: "INVALID",
            hmacVersion: "v4",
          },
        ],
      }),
    );
    await searchConversation();
    const banner = await verdictShown();
    expect(banner.dataset.verdict).toBe("tampered");
    expect(banner).toHaveTextContent(/do not match/);
    expect(screen.queryByText("VERIFIED")).not.toBeInTheDocument();
    // The failing entry is marked where it is shown.
    expect(within(screen.getByTestId("audit-entry-audit-2")).getByTestId("entry-verify-invalid")).toBeInTheDocument();
    expect(screen.getAllByTestId("entry-verify-invalid")).toHaveLength(1);
  });

  it("reports a broken chain — a deleted entry leaves no signature to fail", async () => {
    serveVerification(report({ chainStatus: "BROKEN", missingSequences: [2] }));
    await searchConversation();
    const banner = await verdictShown();
    expect(banner.dataset.verdict).toBe("tampered");
    expect(banner).toHaveTextContent(/missing from the sequence \(2\)/);
  });

  it("never shows green when verification fails", async () => {
    serveVerification(null, 500);
    await searchConversation();
    const banner = await verdictShown();
    expect(banner.dataset.verdict).toBe("error");
    expect(screen.queryByText("VERIFIED")).not.toBeInTheDocument();
    expect(within(banner).getByTestId("integrity-retry")).toBeInTheDocument();
  });

  it("says what could not be checked when nothing is disproven", async () => {
    serveVerification(report({ valid: 3, unsigned: 1 }));
    await searchConversation();
    const banner = await verdictShown();
    expect(banner.dataset.verdict).toBe("unverified");
    expect(banner).toHaveTextContent(/1 unsigned/);
  });
});

describe("auditVerdict", () => {
  it("treats a key the deployment no longer holds as unverified, not tampered", () => {
    expect(
      auditVerdict(
        report({
          invalid: 1,
          problems: [
            {
              entryId: "e",
              conversationId: "c",
              sequence: 0,
              timestamp: "t",
              status: "UNKNOWN_KEY",
            },
          ],
        }),
      ),
    ).toBe("unverified");
  });

  it("does not count legacy entries the recovery budget skipped as disproven", () => {
    expect(auditVerdict(report({ invalid: 2, recoverySkipped: 2 }))).toBe("unverified");
    expect(auditVerdict(report({ invalid: 3, recoverySkipped: 2 }))).toBe("tampered");
  });

  it("treats an agent sweep's not-applicable chain as clean and a missing key as unchecked", () => {
    expect(auditVerdict(report({ chainStatus: "NOT_APPLICABLE" }))).toBe("verified");
    expect(auditVerdict(report({ signingEnabled: false }))).toBe("signing-disabled");
    expect(auditVerdict(report({ duplicateSequences: [3] }))).toBe("unverified");
    expect(auditVerdict(report({ chainStatus: "INCOMPLETE" }))).toBe("unverified");
  });
});
