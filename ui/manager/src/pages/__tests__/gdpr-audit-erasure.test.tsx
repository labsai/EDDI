import { describe, it, expect, afterEach } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { GdprPage } from "@/pages/gdpr";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

/**
 * What an erasure did to the audit ledger (EDDI 6.6 `auditEntriesRedacted`,
 * the honest `complete`, the `auditRedaction` step) — and that an older
 * backend, which sends none of it, is described as what it is.
 */

const BASE_RESULT = {
  userId: "user-123",
  memoriesDeleted: 1,
  conversationsDeleted: 2,
  conversationMappingsDeleted: 0,
  logsPseudonymized: 3,
  auditEntriesPseudonymized: 6,
  attachmentsDeleted: 0,
  journalEntriesDeleted: 0,
  checkpointsDeleted: 0,
  groupConversationsDeleted: 0,
  sharedArtifactsDeleted: 0,
  schedulesDeleted: 0,
  failedSteps: [] as string[],
  complete: true,
  completedAt: "2026-10-04T10:00:00Z",
};

function answer(body: Record<string, unknown>, status = 200) {
  server.use(http.delete("*/admin/gdpr/:userId", () => HttpResponse.json(body, { status })));
}

async function runErasure() {
  renderWithProviders(<GdprPage />, { initialRoute: "/manage/gdpr" });
  const user = userEvent.setup();
  await user.type(screen.getByTestId("gdpr-user-id"), "user-123");
  await user.click(screen.getByTestId("gdpr-delete-btn"));
  await user.click(await screen.findByText(/Yes, Delete All Data/i));
  return screen.findByTestId("gdpr-results");
}

describe("GDPR erasure — audit ledger", () => {
  afterEach(() => server.resetHandlers());

  it("shows how many audit entries were redacted and explains the redaction", async () => {
    answer({ ...BASE_RESULT, auditEntriesRedacted: 6 });
    await runErasure();

    expect(within(screen.getByTestId("gdpr-audit-redacted")).getByText("6")).toBeInTheDocument();
    expect(screen.getByTestId("gdpr-audit-summary")).toHaveTextContent(/redaction marker/);
    expect(screen.getByTestId("gdpr-complete-explanation")).toHaveTextContent(/final check/);
  });

  it("says the content was kept when the server runs in pseudonymize mode", async () => {
    answer({ ...BASE_RESULT, auditEntriesRedacted: 0, auditEntriesPseudonymized: 6 });
    await runErasure();

    expect(screen.getByTestId("gdpr-audit-summary")).toHaveTextContent(/erasure-mode=pseudonymize/);
    expect(within(screen.getByTestId("gdpr-audit-redacted")).getByText("0")).toBeInTheDocument();
  });

  it("on an older EDDI shows no redaction tile and says the content is kept", async () => {
    answer({ ...BASE_RESULT });
    await runErasure();

    expect(screen.queryByTestId("gdpr-audit-redacted")).not.toBeInTheDocument();
    expect(screen.getByTestId("gdpr-audit-summary")).toHaveTextContent(/only replaces the user id/);
    // `complete` from an older server is not dressed up with the 6.6 meaning.
    expect(screen.queryByTestId("gdpr-complete-explanation")).not.toBeInTheDocument();
  });

  it("explains a failed auditRedaction step in plain language and does not call the erasure complete", async () => {
    answer({ ...BASE_RESULT, auditEntriesRedacted: 4, failedSteps: ["auditRedaction"], complete: false }, 207);
    await runErasure();

    const step = screen.getByTestId("gdpr-failed-step-auditRedaction");
    expect(step).toHaveTextContent("auditRedaction");
    expect(step).toHaveTextContent(/still hold this user's prompts or responses/);
    expect(screen.queryByTestId("gdpr-complete-explanation")).not.toBeInTheDocument();
  });

  it("does not blame the pseudonymize mode when the redaction itself failed", async () => {
    // Redaction failed outright: nothing redacted, rows pseudonymised afterwards.
    answer({ ...BASE_RESULT, auditEntriesRedacted: 0, auditEntriesPseudonymized: 6, failedSteps: ["auditRedaction"], complete: false }, 207);
    await runErasure();

    const summary = screen.getByTestId("gdpr-audit-summary");
    expect(summary).not.toHaveTextContent(/erasure-mode=pseudonymize/);
    expect(summary).toHaveTextContent(/redaction step failed/);
  });

  it("explains an unknown failed step with the generic guidance", async () => {
    answer({ ...BASE_RESULT, auditEntriesRedacted: 6, failedSteps: ["schedules"], complete: false }, 207);
    await runErasure();

    await waitFor(() =>
      expect(screen.getByTestId("gdpr-failed-step-schedules")).toHaveTextContent(/safe to repeat/),
    );
  });
});
