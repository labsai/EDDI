import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { ClusterDeadLettersPanel } from "@/components/cluster/cluster-dead-letters";
import type { DeadLetterView } from "@/lib/api/cluster";

function entry(id: string): DeadLetterView {
  return {
    id,
    conversationId: `conv-${id}`,
    agentId: null,
    agentVersion: null,
    environment: null,
    error: null,
    timestamp: 0,
    reason: "failed",
    nodeId: null,
    fence: null,
    input: null,
    secretInput: false,
    replayable: false,
    notReplayableReason: "NOT_A_TURN",
    local: true,
  };
}

describe("ClusterDeadLettersPanel paging", () => {
  it("does not offer Next while the next page is still loading", async () => {
    // keepPreviousData leaves the previous page — and its cursor — in `data`
    // while the next one loads; a second click then pushed that cursor twice.
    server.use(
      http.get("*/administration/cluster/dead-letters", ({ request }) =>
        new URL(request.url).searchParams.get("after")
          ? new Promise<Response>(() => {})
          : HttpResponse.json({ entries: [entry("e1")], nextCursor: "c1", scanned: 1 })
      ),
      http.get("*/administration/cluster/dead-letters/summary", () =>
        HttpResponse.json({ total: 1, byReason: {}, oldest: 0, newest: 0 })
      )
    );
    renderWithProviders(<ClusterDeadLettersPanel nodes={[]} />);

    const next = await screen.findByTestId("cluster-dl-next");
    await waitFor(() => expect(next).toBeEnabled());
    await userEvent.click(next);

    // Checked the moment page 2 is showing, before the cluster read's own
    // timeout (8 s) could fail the request and disable the button for a
    // different reason.
    await screen.findByText(/Page 2/);
    expect(screen.getByTestId("cluster-dl-next")).toBeDisabled();
  });
});
