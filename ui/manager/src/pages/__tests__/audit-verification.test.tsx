import { describe, expect, it } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { AuditPage } from "@/pages/audit";
import { server } from "@/test/mocks/server";
import { auditVerdict, uncoveredCount, type AuditVerificationReport } from "@/lib/api/audit-verify";
import type { AuditEntry } from "@/lib/api/audit";

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

  it("stops claiming VERIFIED once loaded pages reach past the checked window", async () => {
    // The verify endpoint checks the newest N entries (1,000 by default) while
    // the timeline pages in 100 at a time — both newest-first over the same
    // store query. Here the report covered 150 entries: page 1 (100 rows) sits
    // inside that window, page 2 takes the loaded total to 200, and the 50
    // oldest rows on screen were never checked.
    const entry = (i: number): AuditEntry => ({
      id: `e-${i}`,
      conversationId: "conv1",
      agentId: "agent1",
      agentVersion: 1,
      userId: null,
      environment: null,
      stepIndex: 0,
      taskId: `task-${i}`,
      taskType: "behavior",
      taskIndex: i,
      durationMs: 1,
      input: null,
      output: null,
      llmDetail: null,
      toolCalls: null,
      actions: null,
      cost: 0,
      timestamp: "2026-09-26T10:00:00Z",
      hmac: "h",
      agentSignature: null,
    });
    server.use(
      http.get("*/auditstore/:conversationId", ({ request }) => {
        const url = new URL(request.url);
        if (url.pathname.endsWith("/count")) return;
        const skip = Number(url.searchParams.get("skip") ?? "0");
        return HttpResponse.json(Array.from({ length: 100 }, (_, i) => entry(skip + i)));
      }),
    );
    serveVerification(report({ entriesChecked: 150, valid: 150, chainStatus: "NOT_APPLICABLE" }));
    await searchConversation();

    // Every loaded row is inside the checked window: green is earned.
    expect((await verdictShown()).dataset.verdict).toBe("verified");

    await userEvent.setup().click(screen.getByTestId("load-more"));
    await screen.findByTestId("audit-entry-e-199");

    const banner = screen.getByTestId("integrity-banner");
    await waitFor(() => expect(banner.dataset.verdict).toBe("unverified"));
    expect(within(banner).queryByText("VERIFIED")).not.toBeInTheDocument();
    expect(banner).toHaveTextContent(/50 loaded entry\(ies\) older than the checked window/);
    expect(banner).toHaveTextContent(/Checked the 150 most recent entries/);
  });

  it("does not show VERIFIED over trail rows read after the report was requested", async () => {
    // The trail refreshes on its own (auto-refresh tick, window focus). A fresh
    // newest page replaces the old one row for row, so the loaded count — and
    // with it `uncoveredCount` — does not move; only the timing says the report
    // never saw the new rows. Refetching the trail alone reproduces the gap the
    // separate verification timer used to leave open.
    const entry = (id: string): AuditEntry => ({
      id,
      conversationId: "conv1",
      agentId: "agent1",
      agentVersion: 1,
      userId: null,
      environment: null,
      stepIndex: 0,
      taskId: `task-${id}`,
      taskType: "behavior",
      taskIndex: 0,
      durationMs: 1,
      input: null,
      output: null,
      llmDetail: null,
      toolCalls: null,
      actions: null,
      cost: 0,
      timestamp: "2026-09-26T10:00:00Z",
      hmac: "h",
      agentSignature: null,
    });
    let trailReads = 0;
    server.use(
      http.get("*/auditstore/:conversationId", ({ request }) => {
        if (new URL(request.url).pathname.endsWith("/count")) return;
        trailReads += 1;
        // The second read has one newer entry on top; the page stays 100 rows.
        const newest = trailReads > 1 ? [entry("e-new")] : [];
        const rest = Array.from({ length: 100 - newest.length }, (_, i) => entry(`e-${i}`));
        return HttpResponse.json([...newest, ...rest]);
      }),
    );
    const verifyCalls: string[] = [];
    let releaseSecondReport: () => void = () => {};
    const secondReportGate = new Promise<void>((resolve) => {
      releaseSecondReport = resolve;
    });
    server.use(
      http.get("*/auditstore/verify/:conversationId", async ({ request }) => {
        verifyCalls.push(request.url);
        if (verifyCalls.length > 1) await secondReportGate;
        return HttpResponse.json(report({ entriesChecked: 1000, valid: 100 }));
      }),
    );

    const { queryClient } = renderWithProviders(<AuditPage />, { initialRoute: "/manage/audit" });
    const user = userEvent.setup();
    await user.click(screen.getByTestId("mode-conversation"));
    await user.type(screen.getByTestId("conversation-input"), "conv1");
    await user.click(screen.getByTestId("search-button"));
    await screen.findByTestId("audit-timeline");
    expect((await verdictShown()).dataset.verdict).toBe("verified");
    expect(verifyCalls).toHaveLength(1);

    // Only the trail refreshes, as the page's own timer does.
    await queryClient.refetchQueries({ queryKey: ["audit", "trail"] });
    await screen.findByTestId("audit-entry-e-new");

    // The report predates the row on top: no green until one that saw it lands,
    // and that one is requested because the trail moved.
    const banner = screen.getByTestId("integrity-banner");
    expect(banner.dataset.verdict).toBe("loading");
    expect(within(banner).queryByText("VERIFIED")).not.toBeInTheDocument();
    await waitFor(() => expect(verifyCalls).toHaveLength(2));

    releaseSecondReport();
    await waitFor(() => expect(screen.getByTestId("integrity-banner").dataset.verdict).toBe("verified"));
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

  it("downgrades a clean report when more entries are loaded than it checked", () => {
    const clean = report({ entriesChecked: 1000, valid: 1000, chainStatus: "NOT_APPLICABLE" });
    expect(auditVerdict(clean, 1000)).toBe("verified");
    expect(auditVerdict(clean, 1001)).toBe("unverified");
    expect(uncoveredCount(clean, 1100)).toBe(100);
    expect(uncoveredCount(clean, 300)).toBe(0);
    // A disproof over the window stays a disproof, however much is loaded.
    expect(auditVerdict(report({ entriesChecked: 10, invalid: 1 }), 500)).toBe("tampered");
  });

  it("treats an agent sweep's not-applicable chain as clean and a missing key as unchecked", () => {
    expect(auditVerdict(report({ chainStatus: "NOT_APPLICABLE" }))).toBe("verified");
    expect(auditVerdict(report({ signingEnabled: false }))).toBe("signing-disabled");
    expect(auditVerdict(report({ duplicateSequences: [3] }))).toBe("unverified");
    expect(auditVerdict(report({ chainStatus: "INCOMPLETE" }))).toBe("unverified");
  });
});
