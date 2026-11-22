import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { Toaster } from "sonner";
import { server } from "@eddi/app-kit/test/mocks/server";
import { renderWithProviders, userEvent } from "@eddi/app-kit/test/test-utils";
import { HeldEmailDecisionDialog, HeldEmailSection } from "@/components/hitl/held-email-section";
import type { HeldEmailItem } from "@/lib/api/channels";

const item = (overrides: Partial<HeldEmailItem>): HeldEmailItem => ({
  rowId: "row-1",
  integrationId: "mail1",
  sender: "stranger@partner.com",
  authVerdict: "aligned-pass",
  heldFor: ["not_allowlisted"],
  heldAt: "2026-11-22T10:00:00Z",
  expiresAt: "2026-11-29T10:00:00Z",
  runAndAllowOffered: true,
  runsAnonymously: false,
  ...overrides,
});

function preview(forItem: HeldEmailItem) {
  server.use(
    http.get("*/channelstore/channels/:id/email-held/:rowId", () =>
      HttpResponse.json({ item: forItem, fromName: "Stranger", subject: "Quote please", text: "Hello, a quote?", textTruncated: false, attachments: ["a.pdf"] }),
    ),
  );
}

describe("HeldEmailDecisionDialog", () => {
  it("shows the live preview and offers run-and-allow for an aligned pass", async () => {
    const aligned = item({});
    preview(aligned);
    renderWithProviders(<HeldEmailDecisionDialog selected={{ integrationId: "mail1", integrationName: "Mail", item: aligned }} onClose={() => {}} />);
    await waitFor(() => expect(screen.getByTestId("held-email-preview")).toBeInTheDocument());
    expect(screen.getByText("Quote please")).toBeInTheDocument();
    expect(screen.getByTestId("held-email-run-and-allow")).toBeInTheDocument();
    expect(screen.queryByTestId("held-email-anonymous-warning")).not.toBeInTheDocument();
  });

  it("for a forged sender: warns before the decision and never offers run-and-allow", async () => {
    const forged = item({ authVerdict: "fail", heldFor: ["not_allowlisted", "unauthenticated"], runAndAllowOffered: false, runsAnonymously: true });
    preview(forged);
    renderWithProviders(<HeldEmailDecisionDialog selected={{ integrationId: "mail1", integrationName: "Mail", item: forged }} onClose={() => {}} />);
    expect(screen.getByTestId("held-email-anonymous-warning")).toBeInTheDocument();
    expect(screen.queryByTestId("held-email-run-and-allow")).not.toBeInTheDocument();
    expect(screen.getByTestId("held-email-run-once")).toBeInTheDocument();
  });

  it("sends the decision with the note", async () => {
    const aligned = item({});
    preview(aligned);
    let sent: unknown = null;
    server.use(
      http.post("*/channelstore/channels/:id/email-held/:rowId/decision", async ({ request }) => {
        sent = await request.json();
        return HttpResponse.json({ rowId: "row-1", decision: "runOnce", state: "RECEIVED", allowlistVersion: null, warnings: [] });
      }),
    );
    let closed = false;
    renderWithProviders(
      <>
        <Toaster />
        <HeldEmailDecisionDialog selected={{ integrationId: "mail1", integrationName: "Mail", item: aligned }} onClose={() => (closed = true)} />
      </>,
    );
    const user = userEvent.setup();
    await user.type(screen.getByTestId("held-email-note"), "known partner");
    await user.click(screen.getByTestId("held-email-run-once"));
    await waitFor(() => expect(closed).toBe(true));
    expect(sent).toEqual({ decision: "runOnce", note: "known partner" });
  });
});

describe("HeldEmailSection", () => {
  it("renders nothing when no integration holds mail", async () => {
    renderWithProviders(<HeldEmailSection />);
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(screen.queryByTestId("held-email-section")).not.toBeInTheDocument();
  });
});
