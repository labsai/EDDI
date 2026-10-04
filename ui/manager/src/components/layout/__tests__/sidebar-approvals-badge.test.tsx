import { describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { Sidebar } from "@/components/layout/sidebar";

const row = (conversationId: string, extra: Record<string, unknown> = {}) => ({
  conversationId,
  pausedAt: "2026-06-01T10:00:00Z",
  pauseType: "RULE",
  ...extra,
});

describe("Sidebar approvals badge", () => {
  it("counts group phase approvals and human-member turns, deduped, like the approvals page", async () => {
    server.use(
      http.get("*/agents/pending-approvals", () => HttpResponse.json([row("c1"), row("shared")])),
      http.get("*/groups/pending-approvals", () =>
        HttpResponse.json([
          row("g1", { groupId: "grp" }),
          row("g2", { groupId: "grp", pauseType: "HUMAN_TURN", pendingMemberId: "ana" }),
          row("shared", { groupId: "grp" }),
        ]),
      ),
    );
    renderWithProviders(<Sidebar collapsed={false} onToggle={() => {}} />);
    const badge = await screen.findByTestId("nav-approvals-badge");
    // c1 + shared + g1 + g2 — not just the two 1:1 rows.
    await waitFor(() => expect(badge.textContent).toBe("4"));
  });
});
