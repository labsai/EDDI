import { describe, it, expect, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { Toaster } from "sonner";
import { server } from "@eddi/app-kit/test/mocks/server";
import { renderWithProviders, userEvent } from "@eddi/app-kit/test/test-utils";
import { EmailSenderListsEditor } from "@/components/channels/email-sender-lists";

function render(onChanged = vi.fn(), disabled = false) {
  renderWithProviders(
    <>
      <Toaster />
      <EmailSenderListsEditor integrationId="mail1" disabled={disabled} onChanged={onChanged} />
    </>,
  );
  return onChanged;
}

describe("EmailSenderListsEditor", () => {
  it("shows the current lists as chips", async () => {
    render();
    await waitFor(() => expect(screen.getAllByTestId("email-sender-chip-allow")).toHaveLength(1));
    expect(screen.getByText("bob@partner.com")).toBeInTheDocument();
    expect(screen.getByTestId("email-senders-block").textContent).toContain("None.");
  });

  it("adds an entry, sends it with the version it read and the reason, and reports the new version", async () => {
    let sent: Record<string, unknown> | undefined;
    server.use(
      http.post("*/channelstore/channels/:id/email-senders", async ({ request }) => {
        sent = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({
          previousVersion: 1, version: 2, changed: true, allow: ["bob@partner.com", "alice@partner.com"], block: [],
          added: ["allow:alice@partner.com"], removed: [], unchanged: [], warnings: [],
        });
      }),
    );
    const onChanged = render();
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByTestId("email-senders-allow-input")).toBeInTheDocument());

    await user.type(screen.getByTestId("email-senders-allow-input"), "Alice@Partner.com{Enter}");
    await user.type(screen.getByTestId("email-senders-reason"), "new supplier");
    await user.click(screen.getByTestId("email-senders-apply"));

    await waitFor(() => expect(onChanged).toHaveBeenCalledWith(2));
    expect(sent).toMatchObject({
      expectedVersion: 1,
      add: { allow: ["alice@partner.com"], block: [] },
      remove: { allow: [], block: [] },
      reason: "new supplier",
    });
  });

  it("removing the last allowlist entry needs the explicit confirmation", async () => {
    let sent: Record<string, unknown> | undefined;
    server.use(
      http.post("*/channelstore/channels/:id/email-senders", async ({ request }) => {
        sent = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({
          previousVersion: 1, version: 2, changed: true, allow: [], block: [], added: [], removed: ["allow:bob@partner.com"],
          unchanged: [], warnings: ["email.allowlist-emptied"],
        });
      }),
    );
    render();
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByText("bob@partner.com")).toBeInTheDocument());

    await user.click(screen.getByRole("button", { name: "Remove bob@partner.com" }));
    expect(screen.getByTestId("email-senders-apply")).toBeDisabled();
    await user.click(screen.getByTestId("email-senders-confirm-open").querySelector("input")!);
    await user.click(screen.getByTestId("email-senders-apply"));

    await waitFor(() => expect(sent?.confirmOpen).toBe(true));
    await waitFor(() => expect(screen.getByText(/the allowlist is now empty/)).toBeInTheDocument());
  });

  it("a 409 reloads the lists instead of overwriting someone else's change", async () => {
    let reads = 0;
    server.use(
      http.get("*/channelstore/channels/:id/email-senders", () => {
        reads += 1;
        return HttpResponse.json({ version: reads, allow: ["bob@partner.com"], block: [] });
      }),
      http.post("*/channelstore/channels/:id/email-senders", () =>
        HttpResponse.json({ version: 2, allow: ["bob@partner.com"], block: [] }, { status: 409 }),
      ),
    );
    const onChanged = render();
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByTestId("email-senders-block-input")).toBeInTheDocument());

    await user.type(screen.getByTestId("email-senders-block-input"), "*@*.spam.example{Enter}");
    await user.click(screen.getByTestId("email-senders-apply"));

    await waitFor(() => expect(screen.getByText(/changed this integration meanwhile/)).toBeInTheDocument());
    expect(reads).toBeGreaterThanOrEqual(2);
    expect(onChanged).not.toHaveBeenCalled();
  });

  it("refuses an invalid entry locally, without a request", async () => {
    let posts = 0;
    server.use(
      http.post("*/channelstore/channels/:id/email-senders", () => {
        posts += 1;
        return HttpResponse.json({});
      }),
    );
    render();
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByTestId("email-senders-allow-input")).toBeInTheDocument());

    await user.type(screen.getByTestId("email-senders-allow-input"), "*@*{Enter}");

    await waitFor(() => expect(screen.getByText(/Use user@domain/)).toBeInTheDocument());
    expect(screen.queryByTestId("email-senders-apply")).toBeNull();
    expect(posts).toBe(0);
  });

  it("offers nothing while the page has unsaved changes", async () => {
    render(vi.fn(), true);
    await waitFor(() => expect(screen.getByTestId("email-senders-disabled")).toBeInTheDocument());
    expect(screen.getByTestId("email-senders-allow-input")).toBeDisabled();
  });
});
