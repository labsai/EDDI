import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders, userEvent } from "@eddi/app-kit/test/test-utils";
import { server } from "@eddi/app-kit/test/mocks/server";
import { MOCK_EMAIL_LOG } from "@eddi/app-kit/test/mocks/handlers";
import { EmailMailLog } from "@/components/channels/email-mail-log";
import { EmailPlatformFields } from "@/components/channels/email-settings";

describe("EmailMailLog", () => {
  it("lists the integration's rows with their state and reason", async () => {
    renderWithProviders(<EmailMailLog channelId="ch-email" />);

    expect(await screen.findByTestId("email-log-row-log-1")).toBeInTheDocument();
    expect(screen.getByTestId("email-log-row-log-2")).toBeInTheDocument();
    expect(screen.getByTestId("email-log-reason-log-1")).toHaveTextContent("blocked:not_allowlisted");
    expect(screen.getByTestId("email-log-state-log-1")).toHaveTextContent("REFUSED");
    expect(screen.queryByTestId("email-log-load-more")).not.toBeInTheDocument();
  });

  it("sends the filters to the server and starts a new list", async () => {
    const seen: string[] = [];
    const row = (id: string) => ({ ...MOCK_EMAIL_LOG[0], id });
    server.use(
      http.get("*/channelstore/channels/:id/email-log", ({ request }) => {
        const query = new URL(request.url).search;
        seen.push(query);
        return HttpResponse.json({ rows: [row(query.includes("state=") ? "filtered" : "unfiltered")], nextCursor: null });
      }),
    );
    renderWithProviders(<EmailMailLog channelId="ch-email" />);
    expect(await screen.findByTestId("email-log-row-unfiltered")).toBeInTheDocument();

    await userEvent.selectOptions(screen.getByTestId("email-log-filter-state"), "REFUSED");
    await userEvent.selectOptions(screen.getByTestId("email-log-filter-reason"), "blocked");

    expect(await screen.findByTestId("email-log-row-filtered")).toBeInTheDocument();
    expect(screen.queryByTestId("email-log-row-unfiltered")).not.toBeInTheDocument();
    await waitFor(() => expect(seen.some((q) => q.includes("state=REFUSED") && q.includes("reason=blocked"))).toBe(true));
    expect(seen[0]).not.toContain("state=");

    // "All" removes the key again: the unfiltered list, from the same query key.
    await userEvent.selectOptions(screen.getByTestId("email-log-filter-state"), "");
    await userEvent.selectOptions(screen.getByTestId("email-log-filter-reason"), "");
    expect(await screen.findByTestId("email-log-row-unfiltered")).toBeInTheDocument();
  });

  it("hides rows already loaded once the log answers 403 (edit access withdrawn)", async () => {
    let forbidden = false;
    server.use(
      http.get("*/channelstore/channels/:id/email-log", () =>
        forbidden
          ? HttpResponse.json({ error: "forbidden" }, { status: 403 })
          : HttpResponse.json({ rows: [{ ...MOCK_EMAIL_LOG[0], id: "kept" }], nextCursor: null }),
      ),
    );
    const { queryClient } = renderWithProviders(<EmailMailLog channelId="ch-email" />);
    expect(await screen.findByTestId("email-log-row-kept")).toBeInTheDocument();

    forbidden = true;
    await queryClient.refetchQueries({ queryKey: ["channels", "ch-email", "email-log"] });

    await waitFor(() => expect(screen.queryByTestId("email-log-row-kept")).not.toBeInTheDocument());
  });

  it("loads the next page with the cursor the server returned", async () => {
    const afters: (string | null)[] = [];
    server.use(
      http.get("*/channelstore/channels/:id/email-log", ({ request }) => {
        const after = new URL(request.url).searchParams.get("after");
        afters.push(after);
        const row = (id: string) => ({
          id, kind: "TRIGGER", direction: "IN", messageId: `<${id}@x.example>`, state: "DONE", reason: null, reasonCategory: null,
          conversationId: null, counterpart: "a@x.example", attempts: 0, createdAt: "2026-11-17T10:00:00Z", updatedAt: null,
        });
        return HttpResponse.json(after ? { rows: [row("p2")], nextCursor: null } : { rows: [row("p1")], nextCursor: "cursor-1" });
      }),
    );
    renderWithProviders(<EmailMailLog channelId="ch-email" />);

    await userEvent.click(await screen.findByTestId("email-log-load-more"));

    expect(await screen.findByTestId("email-log-row-p2")).toBeInTheDocument();
    expect(screen.getByTestId("email-log-row-p1")).toBeInTheDocument();
    expect(afters).toEqual([null, "cursor-1"]);
    expect(screen.queryByTestId("email-log-load-more")).not.toBeInTheDocument();
  });

  it("looks a Message-ID up, and says so when the log has none", async () => {
    renderWithProviders(<EmailMailLog channelId="ch-email" />);
    const input = await screen.findByTestId("email-log-lookup-input");

    await userEvent.type(input, "<CAF0002@mail.supplier-a.example>");
    await userEvent.click(screen.getByTestId("email-log-lookup-btn"));
    const result = await screen.findByTestId("email-log-lookup-result");
    expect(result).toHaveTextContent("DONE");
    expect(result).toHaveTextContent("6a1b2c3d4e5f60718293a4b5");
    expect(result).not.toHaveTextContent("SENT");

    await userEvent.clear(input);
    await userEvent.type(input, "<gone@x.example>");
    await userEvent.click(screen.getByTestId("email-log-lookup-btn"));
    expect(await screen.findByTestId("email-log-lookup-not-found")).toBeInTheDocument();
  });

  it("shows an error state when the log cannot be read", async () => {
    server.use(http.get("*/channelstore/channels/:id/email-log", () => HttpResponse.json({ error: "forbidden" }, { status: 403 })));
    renderWithProviders(<EmailMailLog channelId="ch-email" />);

    expect(await screen.findByTestId("error-state")).toBeInTheDocument();
  });
});

describe("ownerDigest field", () => {
  it("writes daily, and removes the key for off", async () => {
    const changes: Record<string, string>[] = [];
    const { rerender } = renderWithProviders(
      <EmailPlatformFields advancedOpen platformConfig={{ mode: "trigger" }} onChange={(pc) => changes.push(pc)} />,
    );
    const select = screen.getByTestId("email-owner-digest");
    expect(select).toHaveValue("off");

    await userEvent.selectOptions(select, "daily");
    expect(changes[changes.length - 1]).toEqual({ mode: "trigger", ownerDigest: "daily" });

    rerender(<EmailPlatformFields advancedOpen platformConfig={{ mode: "trigger", ownerDigest: "daily" }} onChange={(pc) => changes.push(pc)} />);
    await userEvent.selectOptions(screen.getByTestId("email-owner-digest"), "off");
    expect(changes[changes.length - 1]).toEqual({ mode: "trigger" });
  });
});
