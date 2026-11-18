import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderPage, renderWithProviders, userEvent } from "@eddi/app-kit/test/test-utils";
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
        const id = query.includes("state=") ? "filtered" : query.includes("reason=") ? "reason-only" : "unfiltered";
        return HttpResponse.json({ rows: [row(id)], nextCursor: null });
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

    // "All" removes each key again: state first leaves a reason-only list...
    await userEvent.selectOptions(screen.getByTestId("email-log-filter-state"), "");
    expect(await screen.findByTestId("email-log-row-reason-only")).toBeInTheDocument();
    // ...and clearing the reason too is the unfiltered list.
    await userEvent.selectOptions(screen.getByTestId("email-log-filter-reason"), "");
    expect(await screen.findByTestId("email-log-row-unfiltered")).toBeInTheDocument();
    expect(screen.queryByTestId("email-log-row-reason-only")).not.toBeInTheDocument();
  });

  // ── Edit access withdrawn: one denial for the whole log ─────────────────────
  // A 403 from any mail-log request (or the lookup) drops every cached page of
  // the integration's log, stops fetching, and only Retry lifts it.

  const LOG_KEY = ["channels", "ch-email", "email-log"];
  type Mode = "ok" | "forbidden" | "down";
  function serveLog(state: { mode: Mode; downHits: number }) {
    server.use(
      http.get("*/channelstore/channels/:id/email-log", ({ request }) => {
        if (state.mode === "forbidden") return HttpResponse.json({ error: "forbidden" }, { status: 403 });
        if (state.mode === "down") {
          state.downHits++;
          return HttpResponse.error();
        }
        const filtered = new URL(request.url).searchParams.has("state");
        return HttpResponse.json({ rows: [{ ...MOCK_EMAIL_LOG[0], id: filtered ? "refused" : "all" }], nextCursor: null });
      }),
    );
  }

  it("hides rows already loaded once the log answers 403, and the lookup with them", async () => {
    const state = { mode: "ok" as Mode, downHits: 0 };
    serveLog(state);
    const { queryClient } = renderWithProviders(<EmailMailLog channelId="ch-email" />);
    expect(await screen.findByTestId("email-log-row-all")).toBeInTheDocument();

    state.mode = "forbidden";
    await queryClient.refetchQueries({ queryKey: LOG_KEY });

    await waitFor(() => expect(screen.queryByTestId("email-log-row-all")).not.toBeInTheDocument());
    expect(screen.getByTestId("error-state")).toBeInTheDocument();
    expect(screen.queryByTestId("email-log-lookup-input")).not.toBeInTheDocument();
    // No page of the log is left in the cache (a disabled observer may keep an empty entry).
    await waitFor(() => expect(queryClient.getQueryCache().findAll({ queryKey: LOG_KEY }).every((q) => q.state.data === undefined)).toBe(true));
  });

  it("drops every cached filter on a 403: going back to another filter shows nothing and fetches nothing", async () => {
    const state = { mode: "ok" as Mode, downHits: 0 };
    let requests = 0;
    server.use(
      http.get("*/channelstore/channels/:id/email-log", () => {
        requests++;
        return state.mode === "forbidden"
          ? HttpResponse.json({ error: "forbidden" }, { status: 403 })
          : HttpResponse.json({ rows: [{ ...MOCK_EMAIL_LOG[0], id: "cached" }], nextCursor: null });
      }),
    );
    renderWithProviders(<EmailMailLog channelId="ch-email" />);
    expect(await screen.findByTestId("email-log-row-cached")).toBeInTheDocument();

    state.mode = "forbidden";
    await userEvent.selectOptions(screen.getByTestId("email-log-filter-state"), "REFUSED");
    expect(await screen.findByTestId("error-state")).toBeInTheDocument();
    const afterDenial = requests;

    await userEvent.selectOptions(screen.getByTestId("email-log-filter-state"), "");
    expect(screen.getByTestId("error-state")).toBeInTheDocument();
    expect(screen.queryByTestId("email-log-row-cached")).not.toBeInTheDocument();
    expect(requests).toBe(afterDenial); // nothing fetched while denied
  });

  it("only Retry lifts the denial, with a fresh request", async () => {
    const state = { mode: "forbidden" as Mode, downHits: 0 };
    serveLog(state);
    renderWithProviders(<EmailMailLog channelId="ch-email" />);
    expect(await screen.findByTestId("error-state")).toBeInTheDocument();

    state.mode = "ok";
    await userEvent.click(screen.getByTestId("error-state-retry"));

    expect(await screen.findByTestId("email-log-row-all")).toBeInTheDocument();
    expect(screen.getByTestId("email-log-lookup-input")).toBeInTheDocument();
  });

  it("counts a 403 that arrives after the filter has already changed", async () => {
    let release: (() => void) | null = null;
    const state = { mode: "ok" as Mode, downHits: 0 };
    server.use(
      http.get("*/channelstore/channels/:id/email-log", async ({ request }) => {
        if (new URL(request.url).searchParams.has("state")) {
          await new Promise<void>((resolve) => {
            release = resolve;
          });
          return HttpResponse.json({ error: "forbidden" }, { status: 403 });
        }
        if (state.mode === "down") {
          state.downHits++;
          return HttpResponse.error();
        }
        return HttpResponse.json({ rows: [{ ...MOCK_EMAIL_LOG[0], id: "all" }], nextCursor: null });
      }),
    );
    const { queryClient } = renderWithProviders(<EmailMailLog channelId="ch-email" />);
    expect(await screen.findByTestId("email-log-row-all")).toBeInTheDocument();

    await userEvent.selectOptions(screen.getByTestId("email-log-filter-state"), "REFUSED");
    await waitFor(() => expect(release).not.toBeNull());
    state.mode = "down";
    await userEvent.selectOptions(screen.getByTestId("email-log-filter-state"), "");
    await waitFor(() => expect(state.downHits).toBeGreaterThan(0));
    await waitFor(() => expect(queryClient.isFetching({ queryKey: [...LOG_KEY, {}], exact: true })).toBe(0));
    expect(screen.getByTestId("email-log-row-all")).toBeInTheDocument();

    release!(); // the off-screen request now comes back 403
    await waitFor(() => expect(screen.queryByTestId("email-log-row-all")).not.toBeInTheDocument());
    expect(screen.queryByTestId("email-log-lookup-input")).not.toBeInTheDocument();
  });

  it("does not let a response to a request sent before the denial lift it", async () => {
    let releaseAll: (() => void) | null = null;
    let holdAll = false;
    server.use(
      http.get("*/channelstore/channels/:id/email-log", async ({ request }) => {
        if (new URL(request.url).searchParams.has("state")) return HttpResponse.json({ error: "forbidden" }, { status: 403 });
        if (holdAll) {
          await new Promise<void>((resolve) => {
            releaseAll = resolve;
          });
        }
        return HttpResponse.json({ rows: [{ ...MOCK_EMAIL_LOG[0], id: "late" }], nextCursor: null });
      }),
    );
    const { queryClient } = renderWithProviders(<EmailMailLog channelId="ch-email" />);
    expect(await screen.findByTestId("email-log-row-late")).toBeInTheDocument();

    // A refetch of the list passes the server's check and is still in flight...
    holdAll = true;
    void queryClient.refetchQueries({ queryKey: LOG_KEY });
    await waitFor(() => expect(releaseAll).not.toBeNull());
    // ...when another filter answers 403.
    await userEvent.selectOptions(screen.getByTestId("email-log-filter-state"), "REFUSED");
    expect(await screen.findByTestId("error-state")).toBeInTheDocument();

    releaseAll!(); // the earlier request's success arrives afterwards
    await userEvent.selectOptions(screen.getByTestId("email-log-filter-state"), "");
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(screen.queryByTestId("email-log-row-late")).not.toBeInTheDocument();
    expect(screen.getByTestId("error-state")).toBeInTheDocument();
  });

  it("leaves no cached rows behind for a remount on the same cache", async () => {
    const state = { mode: "ok" as Mode, downHits: 0 };
    serveLog(state);
    const first = renderWithProviders(<EmailMailLog channelId="ch-email" />);
    const { queryClient } = first;
    expect(await screen.findByTestId("email-log-row-all")).toBeInTheDocument();
    state.mode = "forbidden";
    await queryClient.refetchQueries({ queryKey: LOG_KEY });
    await waitFor(() => expect(screen.queryByTestId("email-log-row-all")).not.toBeInTheDocument());
    first.unmount();

    // Back later, same cache: the denial is still recorded, nothing is cached,
    // nothing is fetched until Retry.
    state.mode = "down";
    renderPage("/", <EmailMailLog channelId="ch-email" />, "/", queryClient);
    expect(await screen.findByTestId("error-state-retry")).toBeInTheDocument();
    expect(screen.queryByTestId("email-log-row-all")).not.toBeInTheDocument();
    expect(screen.queryByTestId("email-log-lookup-input")).not.toBeInTheDocument();
    expect(state.downHits).toBe(0);
  });

  it("a 403 from the Message-ID lookup denies the whole log", async () => {
    const state = { mode: "ok" as Mode, downHits: 0 };
    serveLog(state);
    server.use(
      http.post("*/channelstore/channels/:id/email-log/lookup", () => HttpResponse.json({ error: "forbidden" }, { status: 403 })),
    );
    renderWithProviders(<EmailMailLog channelId="ch-email" />);
    expect(await screen.findByTestId("email-log-row-all")).toBeInTheDocument();

    await userEvent.type(screen.getByTestId("email-log-lookup-input"), "<m@x.example>");
    await userEvent.click(screen.getByTestId("email-log-lookup-btn"));

    await waitFor(() => expect(screen.queryByTestId("email-log-row-all")).not.toBeInTheDocument());
    expect(screen.getByTestId("error-state")).toBeInTheDocument();
  });

  it("a Retry that fails on the network keeps rows and lookup hidden, with Retry still offered", async () => {
    const state = { mode: "forbidden" as Mode, downHits: 0 };
    serveLog(state);
    renderWithProviders(<EmailMailLog channelId="ch-email" />);
    expect(await screen.findByTestId("error-state")).toBeInTheDocument();

    state.mode = "down";
    await userEvent.click(screen.getByTestId("error-state-retry"));
    await waitFor(() => expect(state.downHits).toBeGreaterThan(0));
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(screen.queryByTestId("email-log-lookup-input")).not.toBeInTheDocument();
    expect(screen.getByTestId("error-state-retry")).toBeInTheDocument();

    state.mode = "ok";
    await userEvent.click(screen.getByTestId("error-state-retry"));
    expect(await screen.findByTestId("email-log-row-all")).toBeInTheDocument();
    expect(screen.getByTestId("email-log-lookup-input")).toBeInTheDocument();
  });

  it("a list 403 that arrives after the section unmounted still drops the cached rows", async () => {
    let release: (() => void) | null = null;
    let hold = false;
    const state = { mode: "ok" as Mode, downHits: 0 };
    server.use(
      http.get("*/channelstore/channels/:id/email-log", async () => {
        if (hold) {
          await new Promise<void>((resolve) => {
            release = resolve;
          });
          return HttpResponse.json({ error: "forbidden" }, { status: 403 });
        }
        if (state.mode === "down") {
          state.downHits++;
          return HttpResponse.error();
        }
        return HttpResponse.json({ rows: [{ ...MOCK_EMAIL_LOG[0], id: "all" }], nextCursor: null });
      }),
    );
    const first = renderWithProviders(<EmailMailLog channelId="ch-email" />);
    const { queryClient } = first;
    expect(await screen.findByTestId("email-log-row-all")).toBeInTheDocument();

    hold = true;
    void queryClient.refetchQueries({ queryKey: LOG_KEY });
    await waitFor(() => expect(release).not.toBeNull());
    first.unmount();
    release!(); // 403 while nobody is looking
    await waitFor(() => expect(queryClient.getQueryCache().findAll({ queryKey: LOG_KEY })).toHaveLength(0));

    hold = false;
    state.mode = "down";
    renderPage("/", <EmailMailLog channelId="ch-email" />, "/", queryClient);
    expect(await screen.findByTestId("error-state")).toBeInTheDocument();
    expect(screen.queryByTestId("email-log-row-all")).not.toBeInTheDocument();
    expect(screen.queryByTestId("email-log-lookup-input")).not.toBeInTheDocument();
  });

  it("a lookup 403 that arrives after the section unmounted still drops the cached rows", async () => {
    let release: (() => void) | null = null;
    const state = { mode: "ok" as Mode, downHits: 0 };
    serveLog(state);
    server.use(
      http.post("*/channelstore/channels/:id/email-log/lookup", async () => {
        await new Promise<void>((resolve) => {
          release = resolve;
        });
        return HttpResponse.json({ error: "forbidden" }, { status: 403 });
      }),
    );
    const first = renderWithProviders(<EmailMailLog channelId="ch-email" />);
    const { queryClient } = first;
    expect(await screen.findByTestId("email-log-row-all")).toBeInTheDocument();

    await userEvent.type(screen.getByTestId("email-log-lookup-input"), "<m@x.example>");
    await userEvent.click(screen.getByTestId("email-log-lookup-btn"));
    await waitFor(() => expect(release).not.toBeNull());
    first.unmount();
    release!();
    await waitFor(() => expect(queryClient.getQueryCache().findAll({ queryKey: LOG_KEY })).toHaveLength(0));
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
