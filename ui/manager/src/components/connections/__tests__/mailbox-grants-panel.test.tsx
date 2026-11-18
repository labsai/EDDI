import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@eddi/app-kit/test/mocks/server";
import { renderWithProviders, userEvent } from "@eddi/app-kit/test/test-utils";
import { navigateAway } from "@/lib/navigate-away";
import { MailboxGrantsPanel } from "@/components/connections/mailbox-grants-panel";

vi.mock("@/lib/navigate-away", () => ({ navigateAway: vi.fn() }));
const assign = vi.mocked(navigateAway);

/**
 * The admin panel of a MAILBOX connection: the linked mailboxes, linking one
 * (a body naming the address, then a top-level navigation) and unlinking one.
 */

const LINKED = [
  {
    address: "support@company.example",
    status: "ACTIVE",
    usable: true,
    expiresAt: null,
    lastRefreshAt: null,
    scopes: ["https://mail.google.com/"],
    connectedAt: "2026-11-18T10:00:00Z",
  },
];

beforeEach(() => {
  assign.mockClear();
});

describe("MailboxGrantsPanel", () => {
  it("lists the linked mailboxes with their status", async () => {
    server.use(http.get("*/connections/gws/mailbox-grants", () => HttpResponse.json(LINKED)));
    renderWithProviders(<MailboxGrantsPanel connectionName="gws" />);

    expect(await screen.findByTestId("mailbox-grant-row")).toHaveTextContent("support@company.example");
    expect(screen.getByTestId("grant-status-ACTIVE")).toBeInTheDocument();
  });

  it("says when nothing is linked yet", async () => {
    server.use(http.get("*/connections/gws/mailbox-grants", () => HttpResponse.json([])));
    renderWithProviders(<MailboxGrantsPanel connectionName="gws" />);

    expect(await screen.findByTestId("mailbox-grants-empty")).toBeInTheDocument();
  });

  it("starts a link with the address in the body, then navigates the whole page", async () => {
    let body: unknown = null;
    server.use(
      http.get("*/connections/gws/mailbox-grants", () => HttpResponse.json([])),
      http.post("*/connections/gws/mailbox-grants/authorize", async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ authorizationUrl: "https://accounts.google.com/o/oauth2/v2/auth?state=x" });
      }),
    );
    renderWithProviders(<MailboxGrantsPanel connectionName="gws" />);

    await userEvent.type(await screen.findByTestId("mailbox-grant-address"), "support@company.example");
    await userEvent.click(screen.getByTestId("mailbox-grant-link"));

    await waitFor(() => expect(assign).toHaveBeenCalledWith("https://accounts.google.com/o/oauth2/v2/auth?state=x"));
    expect(body).toEqual({ address: "support@company.example" });
  });

  it("does not leave the page while the editor holds unsaved changes", async () => {
    server.use(http.get("*/connections/gws/mailbox-grants", () => HttpResponse.json([])));
    renderWithProviders(<MailboxGrantsPanel connectionName="gws" unsavedChanges />);

    await userEvent.type(await screen.findByTestId("mailbox-grant-address"), "support@company.example");
    expect(screen.getByTestId("mailbox-grant-link")).toBeDisabled();
    expect(screen.getByTestId("mailbox-grant-unsaved")).toBeInTheDocument();
  });

  it("unlinks a mailbox after confirmation", async () => {
    let unlinked: unknown = null;
    server.use(
      http.get("*/connections/gws/mailbox-grants", () => HttpResponse.json(LINKED)),
      http.post("*/connections/gws/mailbox-grants/unlink", async ({ request }) => {
        unlinked = await request.json();
        return new HttpResponse(null, { status: 204 });
      }),
    );
    renderWithProviders(<MailboxGrantsPanel connectionName="gws" />);

    await userEvent.click(await screen.findByTestId("mailbox-grant-unlink"));
    await userEvent.click(await screen.findByRole("button", { name: /unlink/i, hidden: false }));

    await waitFor(() => expect(unlinked).toEqual({ address: "support@company.example" }));
  });
});
