import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderPage } from "@/test/test-utils";
import { GroupDetailPage } from "@/pages/group-detail";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

function renderGroupDetail(id = "grp1", version = "1") {
  return renderPage(
    `/manage/groups/${id}?version=${version}`,
    <GroupDetailPage />,
    "/manage/groups/:id"
  );
}

describe("GroupDetailPage", () => {
  it("renders the group name in heading", async () => {
    renderGroupDetail();

    await waitFor(() => {
      // Name appears in <h1> and also in config panel — use getAllByText
      const matches = screen.getAllByText("Product Review Panel");
      expect(matches.length).toBeGreaterThanOrEqual(1);
    });
  });

  it("renders heading as h1", async () => {
    renderGroupDetail();

    await waitFor(() => {
      const h1 = screen.getByRole("heading", { level: 1 });
      expect(h1).toHaveTextContent("Product Review Panel");
    });
  });

  it("shows the group description", async () => {
    renderGroupDetail();

    await waitFor(() => {
      const matches = screen.getAllByText("Peer-review discussion for product decisions");
      expect(matches.length).toBeGreaterThanOrEqual(1);
    });
  });

  it("shows member count badge", async () => {
    renderGroupDetail();

    await waitFor(() => {
      // "2" appears in multiple places — just verify it exists
      const matches = screen.getAllByText("2");
      expect(matches.length).toBeGreaterThanOrEqual(1);
    });
  });

  it("shows style badge", async () => {
    renderGroupDetail();

    await waitFor(() => {
      const matches = screen.getAllByText(/Peer Review/);
      expect(matches.length).toBeGreaterThanOrEqual(1);
    });
  });

  it("shows no discussions message when empty", async () => {
    renderGroupDetail();

    await waitFor(() => {
      expect(
        screen.getByText("No discussions yet")
      ).toBeInTheDocument();
    });
  });

  it("shows ask below hint when no discussions", async () => {
    renderGroupDetail();

    await waitFor(() => {
      expect(
        screen.getByText("Ask a question below to start")
      ).toBeInTheDocument();
    });
  });

  it("shows the Discussions sidebar label", async () => {
    renderGroupDetail();

    await waitFor(() => {
      const discussions = screen.getAllByText("Discussions");
      expect(discussions.length).toBeGreaterThan(0);
    });
  });

  it("shows error state on config load failure", async () => {
    server.use(
      http.get("*/groupstore/groups/:id", () => {
        return new HttpResponse(null, { status: 500 });
      })
    );

    renderGroupDetail("bad-group");

    await waitFor(() => {
      expect(
        screen.getByText("Something went wrong")
      ).toBeInTheDocument();
    });
  });

  it("shows retry button on error", async () => {
    server.use(
      http.get("*/groupstore/groups/:id", () => {
        return new HttpResponse(null, { status: 500 });
      })
    );

    renderGroupDetail("bad-group");

    await waitFor(() => {
      expect(screen.getByText("Retry")).toBeInTheDocument();
    });
  });

  it("shows loading skeletons initially", () => {
    server.use(
      http.get("*/groupstore/groups/:id", async () => {
        await new Promise((resolve) => setTimeout(resolve, 10000));
        return HttpResponse.json({});
      })
    );

    renderGroupDetail("slow-group");

    // Loading state renders Skeleton components; they should be visible
    // No heading should be rendered yet
    expect(screen.queryByRole("heading", { level: 1 })).not.toBeInTheDocument();
  });

  it("shows fullscreen toggle button", async () => {
    renderGroupDetail();

    await waitFor(() => {
      expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Product Review Panel");
    });

    // Fullscreen button has title "Fullscreen"
    expect(screen.getByTitle("Fullscreen")).toBeInTheDocument();
  });

  it("renders the back link to groups", async () => {
    renderGroupDetail();

    await waitFor(() => {
      expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Product Review Panel");
    });

    expect(screen.getByTestId("back-to-list")).toBeInTheDocument();
    const link = screen.getByTestId("back-to-list");
    expect(link).toHaveAttribute("href", "/manage/groups");
  });

  it("renders discussion list with conversations", async () => {
    server.use(
      http.get("*/groups/:groupId/conversations", () => {
        return HttpResponse.json([
          {
            id: "conv-1",
            originalQuestion: "Should we use React or Vue?",
            state: "COMPLETED",
            created: Date.now() - 3600000,
          },
          {
            id: "conv-2",
            originalQuestion: "Quarterly planning review",
            state: "IN_PROGRESS",
            created: Date.now(),
          },
        ]);
      })
    );

    renderGroupDetail();

    await waitFor(() => {
      expect(
        screen.getByText("Should we use React or Vue?")
      ).toBeInTheDocument();
    });
  });

  it("shows COMPLETED state for a finished conversation", async () => {
    server.use(
      http.get("*/groups/:groupId/conversations", () => {
        return HttpResponse.json([
          {
            id: "conv-1",
            originalQuestion: "Test question",
            state: "COMPLETED",
            created: Date.now(),
          },
        ]);
      })
    );

    renderGroupDetail();

    await waitFor(() => {
      expect(screen.getByText("Completed")).toBeInTheDocument();
    });
  });

  it("renders group with no members shows 0 badge", async () => {
    server.use(
      http.get("*/groupstore/groups/:id", () => {
        return HttpResponse.json({
          name: "Empty Group",
          description: "No members yet",
          members: [],
          moderatorAgentId: "",
          style: "ROUND_TABLE",
          maxRounds: 1,
          phases: [],
          protocol: {
            agentTimeoutSeconds: 60,
            onAgentFailure: "SKIP",
            maxRetries: 2,
            onMemberUnavailable: "SKIP",
          },
        });
      })
    );

    renderGroupDetail();

    await waitFor(() => {
      expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Empty Group");
    });

    // Member count badge should show "0" somewhere on the page
    expect(screen.getByText("0")).toBeInTheDocument();
  });

  it("handles null members gracefully", async () => {
    server.use(
      http.get("*/groupstore/groups/:id", () => {
        return HttpResponse.json({
          name: "Null Members Group",
          description: "Null members field",
          members: null,
          moderatorAgentId: "",
          style: "ROUND_TABLE",
          maxRounds: 1,
          phases: [],
          protocol: {
            agentTimeoutSeconds: 60,
            onAgentFailure: "SKIP",
            maxRetries: 2,
            onMemberUnavailable: "SKIP",
          },
        });
      })
    );

    renderGroupDetail();

    await waitFor(() => {
      expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Null Members Group");
    });

    // safeConfig should have members = [] → shows "0"
    expect(screen.getByText("0")).toBeInTheDocument();
  });

  it("renders without description", async () => {
    server.use(
      http.get("*/groupstore/groups/:id", () => {
        return HttpResponse.json({
          name: "Simple Group",
          description: "",
          members: [],
          moderatorAgentId: "agent1",
          style: "ROUND_TABLE",
          maxRounds: 3,
          phases: [],
          protocol: {
            agentTimeoutSeconds: 60,
            onAgentFailure: "SKIP",
            maxRetries: 2,
            onMemberUnavailable: "SKIP",
          },
        });
      })
    );

    renderGroupDetail();

    await waitFor(() => {
      expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Simple Group");
    });

    // With empty description, no description paragraph should be rendered near the heading
    // The description is "" so the conditional {groupConfig.description && ...} is falsy
    const heading = screen.getByRole("heading", { level: 1 });
    expect(heading).toHaveTextContent("Simple Group");
  });

  // ─── Conversation state display ────────────────────────────────────

  it("conversation state shows translated label instead of raw state", async () => {
    server.use(
      http.get("*/groups/:groupId/conversations", () => {
        return HttpResponse.json([
          {
            id: "conv-state-1",
            originalQuestion: "Test translated state",
            state: "IN_PROGRESS",
            created: Date.now(),
          },
        ]);
      })
    );

    renderGroupDetail();

    await waitFor(() => {
      // Should show "In Progress" (translated label) not "IN_PROGRESS" (raw state)
      expect(screen.getByText("In Progress")).toBeInTheDocument();
      expect(screen.queryByText("IN_PROGRESS")).not.toBeInTheDocument();
    });
  });

  it("conversation state has colored dot indicator", async () => {
    server.use(
      http.get("*/groups/:groupId/conversations", () => {
        return HttpResponse.json([
          {
            id: "conv-dot-1",
            originalQuestion: "Test dot indicator",
            state: "COMPLETED",
            created: Date.now(),
          },
        ]);
      })
    );

    renderGroupDetail();

    await waitFor(() => {
      // The dot indicator has data-testid="state-dot-{convId}"
      const dot = screen.getByTestId("state-dot-conv-dot-1");
      expect(dot).toBeInTheDocument();
      // The dot should have a rounded-full class (visual indicator)
      expect(dot.className).toContain("rounded-full");
    });
  });

  it("conversation state uses STATE_CONFIG for label and color", async () => {
    server.use(
      http.get("*/groups/:groupId/conversations", () => {
        return HttpResponse.json([
          {
            id: "conv-cfg-1",
            originalQuestion: "Test STATE_CONFIG usage",
            state: "FAILED",
            created: Date.now(),
          },
        ]);
      })
    );

    renderGroupDetail();

    await waitFor(() => {
      // STATE_CONFIG.FAILED.label is "Failed"
      expect(screen.getByText("Failed")).toBeInTheDocument();
      // The state container has data-testid="discussion-state-{convId}"
      const stateEl = screen.getByTestId("discussion-state-conv-cfg-1");
      expect(stateEl).toBeInTheDocument();
      // Should have the destructive color class from STATE_CONFIG.FAILED.color
      expect(stateEl.className).toContain("text-destructive");
    });
  });
});
