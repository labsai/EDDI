import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { CoordinatorPage } from "@/pages/coordinator";
import { server } from "@/test/mocks/server";
import { degradedOverview, resetClusterFixture, SINGLE_NODE_OVERVIEW } from "@/test/mocks/cluster-handlers";
import { AuthContext, GUEST_CONTEXT, type AuthContextValue } from "@/components/auth/auth-context";

/**
 * The SSE source is replaced by a handle the test can push events through —
 * jsdom has no streaming fetch body, and a source that never opens would leave
 * the "live" behaviour untested.
 */
type Listener = (event: MessageEvent) => void;
const sources: { listeners: Record<string, Listener[]>; onopen: (() => void) | null; onerror: (() => void) | null; close: () => void }[] = [];
vi.mock("@/lib/bearer-event-source", () => ({
  BearerEventSource: vi.fn().mockImplementation(function () {
    const source = {
      listeners: {} as Record<string, Listener[]>,
      onopen: null as (() => void) | null,
      onerror: null as (() => void) | null,
      onexhausted: null as (() => void) | null,
      addEventListener(type: string, l: Listener) {
        (this.listeners[type] ??= []).push(l);
      },
      close: vi.fn(),
    };
    sources.push(source);
    return source;
  }),
}));

function emit(type: string, data: unknown) {
  const source = sources[sources.length - 1]!;
  act(() => {
    for (const l of source.listeners[type] ?? []) l(new MessageEvent(type, { data: JSON.stringify(data) }));
  });
}

const ADMIN: AuthContextValue = { ...GUEST_CONTEXT, method: "keycloak", roles: ["eddi-admin"] };
const VIEWER: AuthContextValue = { ...GUEST_CONTEXT, method: "keycloak", roles: ["eddi-viewer"] };
const EDITOR: AuthContextValue = { ...GUEST_CONTEXT, method: "keycloak", roles: ["eddi-editor"] };

function renderConsole(route = "/manage/coordinator", auth: AuthContextValue = ADMIN) {
  return renderWithProviders(
    <AuthContext.Provider value={auth}>
      <CoordinatorPage />
    </AuthContext.Provider>,
    { initialRoute: route },
  );
}

beforeEach(() => {
  resetClusterFixture();
  sources.length = 0;
});

describe("Cluster console — overview", () => {
  it("states the verdict in words with its reasons and the next step", async () => {
    renderConsole();
    const verdict = await screen.findByTestId("cluster-verdict");
    expect(verdict).toHaveAttribute("data-verdict", "DEGRADED");
    expect(screen.getByTestId("cluster-reason-NODE_LOST")).toBeInTheDocument();
    expect(screen.getByTestId("cluster-next-step")).toHaveTextContent(/lost node/i);
    expect(screen.getByTestId("cluster-member-count")).toHaveTextContent("2 of 3");
  });

  it("marks a lost node and explains what happens to its leases; the leader carries its badge", async () => {
    renderConsole();
    const lost = await screen.findByTestId("cluster-node-eddi-3");
    expect(lost).toHaveAttribute("data-state", "LOST");
    // Its last heartbeat is older than the lease TTL: its leases have expired already.
    expect(screen.getByTestId("cluster-node-gone-eddi-3")).toHaveTextContent(/Its leases have expired/);
    expect(within(screen.getByTestId("cluster-node-eddi-1")).getByText("HITL leader")).toBeInTheDocument();
    // A gone node cannot be drained.
    expect(screen.queryByTestId("cluster-drain-eddi-3")).not.toBeInTheDocument();
  });

  it("shows NATS streams and buckets with their replicas, and the lease epoch", async () => {
    renderConsole();
    expect(await screen.findByTestId("cluster-stream-EDDI_DEAD_LETTERS")).toBeInTheDocument();
    expect(screen.getByTestId("cluster-bucket-LEASES")).toHaveTextContent("20 s");
    expect(screen.getByTestId("cluster-lease-epoch")).toHaveTextContent("1791045643000000");
  });

  it("explains degraded mode with the local policy: unfenced, may overlap", async () => {
    server.use(http.get("*/administration/cluster/overview", () => HttpResponse.json(degradedOverview("local"))));
    renderConsole();
    const banner = await screen.findByTestId("cluster-degraded-banner");
    expect(banner).toHaveAttribute("data-policy", "local");
    expect(screen.getByTestId("cluster-degraded-local")).toHaveTextContent(/both may process it/);
    expect(screen.queryByTestId("cluster-degraded-reject")).not.toBeInTheDocument();
    // The other node is not "late": this node simply cannot see it. No action is offered on it.
    expect(screen.getByTestId("cluster-node-eddi-2")).toHaveAttribute("data-state", "UNKNOWN");
    expect(screen.getByTestId("cluster-node-unknown-eddi-2")).toBeInTheDocument();
    expect(screen.queryByTestId("cluster-drain-eddi-2")).not.toBeInTheDocument();
  });

  it("the lease list says why it is empty while NATS is unreachable", async () => {
    server.use(
      http.get("*/administration/cluster/leases", () =>
        HttpResponse.json({ code: "NATS_UNREACHABLE", message: "NATS is unreachable from this node" }, { status: 409 }),
      ),
    );
    renderConsole("/manage/coordinator?tab=leases");
    expect(await screen.findByTestId("error-state")).toHaveTextContent(/Leases live in NATS/);
  });

  it("explains degraded mode with the reject policy: 409 with Retry-After", async () => {
    server.use(http.get("*/administration/cluster/overview", () => HttpResponse.json(degradedOverview("reject"))));
    renderConsole();
    expect(await screen.findByTestId("cluster-degraded-reject")).toHaveTextContent(/409/);
  });

  it("single node: says so, explains cluster mode, offers no lease tab and no NATS panel", async () => {
    server.use(http.get("*/administration/cluster/overview", () => HttpResponse.json(SINGLE_NODE_OVERVIEW)));
    renderConsole();
    expect(await screen.findByTestId("cluster-verdict")).toHaveAttribute("data-verdict", "SINGLE_NODE");
    expect(screen.getByTestId("cluster-single-node")).toBeInTheDocument();
    expect(screen.getByTestId("cluster-docs-link")).toHaveAttribute("href", expect.stringContaining("clustering"));
    expect(screen.queryByTestId("cluster-tab-leases")).not.toBeInTheDocument();
    expect(screen.queryByTestId("cluster-nats")).not.toBeInTheDocument();
    expect(screen.queryByTestId("cluster-drain-local")).not.toBeInTheDocument();
  });

  it("a failed first load is an error, not an empty cluster", async () => {
    server.use(http.get("*/administration/cluster/overview", () => new HttpResponse(null, { status: 500 })));
    renderConsole();
    expect(await screen.findByTestId("error-state")).toBeInTheDocument();
    expect(screen.queryByTestId("cluster-verdict")).not.toBeInTheDocument();
  });
});

describe("Cluster console — roles", () => {
  it("eddi-viewer sees insights but no action and no dead-letter content", async () => {
    renderConsole("/manage/coordinator", VIEWER);
    expect(await screen.findByTestId("cluster-read-only")).toBeInTheDocument();
    await screen.findByTestId("cluster-node-eddi-2");
    expect(screen.queryByTestId("cluster-drain-eddi-2")).not.toBeInTheDocument();
    expect(screen.queryByTestId("cluster-recovery")).not.toBeInTheDocument();
    expect(screen.queryByTestId("cluster-audit")).not.toBeInTheDocument();
    await userEvent.click(screen.getByTestId("cluster-tab-deadLetters"));
    expect(await screen.findByTestId("cluster-dl-admin-only")).toBeInTheDocument();
    expect(screen.queryByTestId("dead-letters-table")).not.toBeInTheDocument();
  });

  it("a role without access sees why, and nothing is requested", async () => {
    let requested = false;
    server.use(
      http.get("*/administration/cluster/overview", () => {
        requested = true;
        return HttpResponse.json({});
      }),
    );
    renderConsole("/manage/coordinator", EDITOR);
    expect(await screen.findByText(/eddi-admin or eddi-viewer/)).toBeInTheDocument();
    expect(requested).toBe(false);
    expect(sources).toHaveLength(0);
  });
});

describe("Cluster console — leases", () => {
  it("lists the suspicious lease first with its flags and a conversation link", async () => {
    renderConsole("/manage/coordinator?tab=leases");
    const table = await screen.findByTestId("cluster-leases-table");
    const rows = within(table).getAllByRole("row").slice(1);
    expect(rows[0]).toHaveAttribute("data-testid", "cluster-lease-c.68b1f0c2d4e5a60012ab34cd");
    expect(within(rows[0]!).getByTestId("cluster-lease-flag-HOLDER_GONE")).toBeInTheDocument();
    expect(within(rows[0]!).getByRole("link")).toHaveAttribute("href", "/manage/conversationview/68b1f0c2d4e5a60012ab34cd");
  });

  it("force-release sends the revision the admin saw, and a renewed lease asks again before releasing", async () => {
    const bodies: unknown[] = [];
    let calls = 0;
    server.use(
      http.post("*/administration/cluster/leases/:id/release", async ({ request }) => {
        bodies.push(await request.json());
        calls++;
        return calls === 1
          ? HttpResponse.json({ action: "lease.release", outcome: "RENEWED", message: "renewed", details: { currentRevision: 99 } })
          : HttpResponse.json({ action: "lease.release", outcome: "RELEASED", message: "released", details: {} });
      }),
    );
    renderConsole("/manage/coordinator?tab=leases");
    await userEvent.click(await screen.findByTestId("cluster-release-68b1f0c2d4e5a60012ab9911"));
    await userEvent.click(screen.getByTestId("alert-dialog-confirm"));
    // The second confirmation names the danger: the holder is alive.
    expect(await screen.findByText("The holder is alive — release anyway?")).toBeInTheDocument();
    await userEvent.click(screen.getByTestId("alert-dialog-confirm"));
    await waitFor(() => expect(calls).toBe(2));
    expect(bodies[0]).toEqual({ expectedRevision: 1_791_045_643_049_537 });
    expect(bodies[1]).toEqual({ expectedRevision: 99 });
  });

  it("filters to suspicious leases only", async () => {
    renderConsole("/manage/coordinator?tab=leases");
    await screen.findByTestId("cluster-leases-table");
    await userEvent.click(screen.getByTestId("cluster-leases-flagged"));
    await waitFor(() => expect(within(screen.getByTestId("cluster-leases-table")).getAllByRole("row")).toHaveLength(2));
  });
});

describe("Cluster console — dead letters", () => {
  it("filters by reason on the server", async () => {
    const reasons: (string | null)[] = [];
    server.use(
      http.get("*/administration/cluster/dead-letters", ({ request }) => {
        reasons.push(new URL(request.url).searchParams.get("reason"));
        return HttpResponse.json({ entries: [], nextCursor: null, scanned: 0 });
      }),
    );
    renderConsole("/manage/coordinator?tab=deadLetters");
    await screen.findByTestId("dead-letters-empty");
    await userEvent.selectOptions(screen.getByTestId("cluster-dl-filter-reason"), "fenced");
    await waitFor(() => expect(reasons).toContain("fenced"));
  });

  it("the drawer explains a fenced write with both tokens and shows the input", async () => {
    renderConsole("/manage/coordinator?tab=deadLetters");
    await userEvent.click(await screen.findByTestId("cluster-dl-open-14"));
    const drawer = await screen.findByTestId("cluster-dl-drawer");
    expect(within(drawer).getByTestId("cluster-dl-fence")).toHaveTextContent("1791045643049531");
    expect(within(drawer).getByTestId("cluster-dl-why")).toHaveTextContent(/lost the conversation's lease/);
    expect(within(drawer).getByTestId("cluster-dl-input")).toHaveTextContent("Friday");
    expect(within(drawer).getByTestId("cluster-dl-conversation-link")).toHaveAttribute("href", "/manage/conversationview/68b1f0c2d4e5a60012ab34cd");
    await userEvent.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByTestId("cluster-dl-drawer")).not.toBeInTheDocument());
    // Focus returns to the row that opened it.
    expect(screen.getByTestId("cluster-dl-open-14")).toHaveFocus();
  });

  it("a secret entry is masked and says why it cannot be replayed, instead of failing with 409", async () => {
    renderConsole("/manage/coordinator?tab=deadLetters");
    await userEvent.click(await screen.findByTestId("cluster-dl-open-17"));
    const drawer = await screen.findByTestId("cluster-dl-drawer");
    expect(within(drawer).getByTestId("cluster-dl-input-secret")).toBeInTheDocument();
    expect(within(drawer).queryByTestId("cluster-dl-input")).not.toBeInTheDocument();
    expect(within(drawer).getByTestId("cluster-dl-not-replayable")).toHaveTextContent(/marked the input secret/);
    expect(within(drawer).getByTestId("cluster-dl-replay")).toBeDisabled();
  });

  it("bulk replay sends only the replayable entries and reports each outcome", async () => {
    let sent: string[] = [];
    server.use(
      http.post("*/administration/cluster/dead-letters/replay", async ({ request }) => {
        sent = ((await request.json()) as { ids: string[] }).ids;
        return HttpResponse.json({ results: [{ id: "14", outcome: "REPLAYED", message: null }], succeeded: 1, failed: 0 });
      }),
    );
    renderConsole("/manage/coordinator?tab=deadLetters");
    await userEvent.click(await screen.findByTestId("cluster-dl-select-14"));
    await userEvent.click(screen.getByTestId("cluster-dl-select-17"));
    expect(screen.getByTestId("cluster-dl-bulk-replay")).toHaveTextContent("1");
    await userEvent.click(screen.getByTestId("cluster-dl-bulk-replay"));
    await userEvent.click(screen.getByTestId("alert-dialog-confirm"));
    expect(await screen.findByTestId("cluster-dl-outcome-14")).toHaveAttribute("data-outcome", "REPLAYED");
    expect(sent).toEqual(["14"]);
  });

  it("bulk discard reports a per-item outcome, including one already gone", async () => {
    server.use(
      http.post("*/administration/cluster/dead-letters/discard", () =>
        HttpResponse.json({
          results: [
            { id: "14", outcome: "DISCARDED", message: null },
            { id: "15", outcome: "NOT_FOUND", message: "already replayed" },
          ],
          succeeded: 1,
          failed: 1,
        }),
      ),
    );
    renderConsole("/manage/coordinator?tab=deadLetters");
    await userEvent.click(await screen.findByTestId("cluster-dl-select-all"));
    await userEvent.click(screen.getByTestId("cluster-dl-bulk-discard"));
    await userEvent.click(screen.getByTestId("alert-dialog-confirm"));
    expect(await screen.findByTestId("cluster-dl-outcome-15")).toHaveAttribute("data-outcome", "NOT_FOUND");
    expect(screen.getByTestId("cluster-dl-outcome-14")).toHaveAttribute("data-outcome", "DISCARDED");
  });
});

describe("Cluster console — actions", () => {
  it("drain asks first, then calls the node's drain endpoint", async () => {
    let called = "";
    server.use(
      http.post("*/administration/cluster/nodes/:nodeId/drain", ({ params }) => {
        called = `${String(params.nodeId)}/drain`;
        return HttpResponse.json({ action: "node.drain", outcome: "DRAINED", message: "drained", details: {} });
      }),
    );
    renderConsole();
    await userEvent.click(await screen.findByTestId("cluster-drain-eddi-2"));
    expect(called).toBe("");
    expect(screen.getByRole("dialog")).toHaveTextContent(/409 with Retry-After/);
    await userEvent.click(screen.getByTestId("alert-dialog-confirm"));
    await waitFor(() => expect(called).toBe("eddi-2/drain"));
  });

  it("a cache resync is confirmed with its consequence and reports the result", async () => {
    renderConsole();
    await userEvent.click(await screen.findByTestId("cluster-action-resync"));
    expect(screen.getByRole("dialog")).toHaveTextContent(/No data changes/);
    await userEvent.click(screen.getByTestId("alert-dialog-confirm"));
    expect(await screen.findByTestId("cluster-action-result")).toHaveTextContent("DONE");
  });
});

describe("Cluster console — activity", () => {
  it("shows the cluster timeline and adds live entries from the stream", async () => {
    renderConsole("/manage/coordinator?tab=activity");
    expect(await screen.findByTestId("cluster-activity-node.lost")).toHaveTextContent("eddi-3");
    emit("activity", { id: "live-1", type: "lease.takeover", severity: "warning", node: "eddi-2", ts: Date.now(), payload: { conversationId: "c-9", previousNode: "eddi-3" } });
    expect(await screen.findByTestId("cluster-activity-lease.takeover")).toHaveTextContent("c-9");
  });

  it("pausing keeps what is shown and loses nothing: entries that arrive meanwhile appear on resume", async () => {
    renderConsole("/manage/coordinator?tab=activity");
    await screen.findByTestId("cluster-activity-node.lost");
    await userEvent.click(screen.getByTestId("cluster-activity-pause"));
    emit("activity", { id: "live-2", type: "degraded.on", severity: "error", node: "eddi-2", ts: Date.now(), payload: { nodeId: "eddi-2", turnsPolicy: "local" } });
    expect(screen.queryByTestId("cluster-activity-degraded.on")).not.toBeInTheDocument();
    expect(screen.getByTestId("cluster-activity-pending")).toHaveTextContent("1");
    await userEvent.click(screen.getByTestId("cluster-activity-pause"));
    expect(await screen.findByTestId("cluster-activity-degraded.on")).toBeInTheDocument();
  });

  it("after the stream reconnects, the history is read again so nothing missed meanwhile is lost", async () => {
    let reads = 0;
    server.use(
      http.get("*/administration/cluster/activity", () => {
        reads++;
        return HttpResponse.json(
          reads === 1
            ? []
            : [{ id: "missed", type: "node.lost", severity: "error", node: "eddi-1", ts: Date.now(), payload: { nodeId: "eddi-2" } }],
        );
      }),
    );
    renderConsole("/manage/coordinator?tab=activity");
    await waitFor(() => expect(reads).toBe(1));
    const source = sources[sources.length - 1]!;
    act(() => source.onerror?.());
    act(() => source.onopen?.());
    expect(await screen.findByTestId("cluster-activity-node.lost")).toHaveTextContent("eddi-2");
    expect(reads).toBe(2);
  });

  it("filters by kind", async () => {
    renderConsole("/manage/coordinator?tab=activity");
    await screen.findByTestId("cluster-activity-node.lost");
    await userEvent.click(screen.getByTestId("cluster-activity-group-nodes"));
    expect(screen.queryByTestId("cluster-activity-node.lost")).not.toBeInTheDocument();
    expect(screen.getByTestId("cluster-activity-fence.rejected")).toBeInTheDocument();
  });
});

describe("Cluster console — stuck conversation", () => {
  it("finds an orphaned lease, suggests releasing it, and links the dead letters", async () => {
    renderConsole("/manage/coordinator?tab=diagnose");
    await userEvent.type(await screen.findByTestId("cluster-diagnose-input"), "68b1f0c2d4e5a60012ab34cd");
    await userEvent.click(screen.getByTestId("cluster-diagnose-submit"));
    expect(await screen.findByTestId("cluster-diagnosis")).toHaveAttribute("data-verdict", "STUCK");
    expect(screen.getByTestId("cluster-finding-LEASE_ORPHANED")).toHaveTextContent("eddi-3");
    expect(screen.getByTestId("cluster-diagnosis-release")).toBeInTheDocument();
    await userEvent.click(screen.getByTestId("cluster-diagnosis-replay"));
    // Switches to the dead letters, filtered to this conversation.
    expect(await screen.findByTestId("cluster-dl-filter-conversation")).toHaveValue("68b1f0c2d4e5a60012ab34cd");
  });

  it("an unknown id is reported as not found", async () => {
    renderConsole("/manage/coordinator?tab=diagnose&conversation=nope");
    expect(await screen.findByTestId("cluster-diagnosis")).toHaveAttribute("data-verdict", "NOT_FOUND");
  });
});

describe("Cluster console — keyboard", () => {
  it("tabs move with the arrow keys", async () => {
    renderConsole();
    const overview = await screen.findByTestId("cluster-tab-overview");
    overview.focus();
    await userEvent.keyboard("{ArrowRight}");
    expect(screen.getByTestId("cluster-tab-leases")).toHaveFocus();
    expect(screen.getByTestId("cluster-tab-leases")).toHaveAttribute("aria-selected", "true");
    await userEvent.keyboard("{End}");
    expect(screen.getByTestId("cluster-tab-diagnose")).toHaveFocus();
  });
});
