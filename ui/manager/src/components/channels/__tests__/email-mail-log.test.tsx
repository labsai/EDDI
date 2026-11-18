import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders, userEvent } from "@eddi/app-kit/test/test-utils";
import { server } from "@eddi/app-kit/test/mocks/server";
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
    server.use(
      http.get("*/channelstore/channels/:id/email-log", ({ request }) => {
        seen.push(new URL(request.url).search);
        return HttpResponse.json({ rows: [], nextCursor: null });
      }),
    );
    renderWithProviders(<EmailMailLog channelId="ch-email" />);
    await screen.findByTestId("empty-state");

    await userEvent.selectOptions(screen.getByTestId("email-log-filter-state"), "REFUSED");
    await userEvent.selectOptions(screen.getByTestId("email-log-filter-reason"), "blocked");

    await waitFor(() => expect(seen.some((q) => q.includes("state=REFUSED") && q.includes("reason=blocked"))).toBe(true));
    expect(seen[0]).not.toContain("state=");
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
    expect(result).toHaveTextContent("SENT");
    expect(result).toHaveTextContent("DONE");

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
