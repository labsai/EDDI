import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderPage, userEvent } from "@/test/test-utils";
import { ConversationDetailPage } from "@/pages/conversation-detail";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

function renderConvDetail(id = "conv1") {
  return renderPage(
    `/manage/conversationview/${id}`,
    <ConversationDetailPage />,
    "/manage/conversationview/:id"
  );
}

// Save and restore globals that export test mutates
let originalCreateObjectURL: typeof URL.createObjectURL;
let originalRevokeObjectURL: typeof URL.revokeObjectURL;

beforeEach(() => {
  originalCreateObjectURL = globalThis.URL.createObjectURL;
  originalRevokeObjectURL = globalThis.URL.revokeObjectURL;
});

afterEach(() => {
  globalThis.URL.createObjectURL = originalCreateObjectURL;
  globalThis.URL.revokeObjectURL = originalRevokeObjectURL;
});

describe("ConversationDetailPage", () => {
  it("renders the conversation title heading", async () => {
    renderConvDetail();

    await waitFor(() => {
      expect(screen.getByText("Conversation")).toBeInTheDocument();
    });
  });

  it("shows the conversation ID", async () => {
    renderConvDetail();

    await waitFor(() => {
      expect(screen.getByText("ID: conv1")).toBeInTheDocument();
    });
  });

  it("renders the chat log section", async () => {
    renderConvDetail();

    await waitFor(() => {
      expect(screen.getByTestId("conversation-chat")).toBeInTheDocument();
    });
  });

  it("shows user input messages", async () => {
    renderConvDetail();

    await waitFor(() => {
      expect(
        screen.getByText("Hi, I need help with my order")
      ).toBeInTheDocument();
    });
  });

  it("shows agent responses", async () => {
    renderConvDetail();

    await waitFor(() => {
      expect(
        screen.getByText(/Hello! I'd be happy to help/)
      ).toBeInTheDocument();
    });
  });

  it("shows the step count badge", async () => {
    renderConvDetail();

    await waitFor(() => {
      // "5 steps" text
      expect(screen.getByText("5 steps")).toBeInTheDocument();
    });
  });

  it("shows state badge as Active for READY state", async () => {
    renderConvDetail();

    await waitFor(() => {
      expect(screen.getByText("Active")).toBeInTheDocument();
    });
  });

  it("shows agent info badge", async () => {
    renderConvDetail();

    await waitFor(() => {
      expect(screen.getByText("agent1 v3")).toBeInTheDocument();
    });
  });

  it("renders export markdown button", async () => {
    renderConvDetail();

    await waitFor(() => {
      expect(screen.getByTestId("export-md")).toBeInTheDocument();
    });
  });

  it("renders continue in chat button for READY state", async () => {
    renderConvDetail();

    await waitFor(() => {
      expect(screen.getByTestId("continue-in-chat")).toBeInTheDocument();
    });
  });

  it("renders transcript search input", async () => {
    renderConvDetail();

    await waitFor(() => {
      expect(screen.getByTestId("transcript-search")).toBeInTheDocument();
    });
  });

  it("filters transcript by search query", async () => {
    renderConvDetail();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("transcript-search")).toBeInTheDocument();
    });

    // Verify all messages shown before search
    await waitFor(() => {
      expect(
        screen.getByText("Hi, I need help with my order")
      ).toBeInTheDocument();
    });

    await user.type(
      screen.getByTestId("transcript-search"),
      "delivery"
    );

    await waitFor(() => {
      // Step with "delivery" should still be visible via highlight marks
      const chatSection = screen.getByTestId("conversation-chat");
      expect(chatSection.textContent).toContain("delivery");
      // "Hi, I need help with my order" does NOT contain "delivery" so should be filtered out
      expect(
        screen.queryByText("Hi, I need help with my order")
      ).not.toBeInTheDocument();
    });
  });

  it("toggles raw JSON data for a step", async () => {
    renderConvDetail();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByText("Step 1")).toBeInTheDocument();
    });

    // Click step 1 to expand raw data
    await user.click(screen.getByText("Step 1"));

    // Raw JSON should appear inside a <pre> with data-testid
    await waitFor(() => {
      const rawBlock = screen.getByTestId("step-raw-1");
      expect(rawBlock.textContent).toContain("input:initial");
    });

    // Click again to collapse
    await user.click(screen.getByText("Step 1"));

    await waitFor(() => {
      expect(screen.queryByTestId("step-raw-1")).not.toBeInTheDocument();
    });
  });

  it("HTML-escapes conversation content in the raw step view (no stored XSS)", async () => {
    // A payload stored in conversation memory must render as inert text — never
    // as live markup — when an operator expands a step's raw-data view.
    server.use(
      http.get("*/conversationstore/conversations/simple/:id", () =>
        HttpResponse.json({
          agentId: "agent1",
          agentVersion: 1,
          conversationId: "conv1",
          conversationState: "READY",
          conversationSteps: [
            {
              conversationStep: [
                {
                  key: "input:initial",
                  value: "<img src=x onerror=alert(1)>",
                  timestamp: new Date().toISOString(),
                  originWorkflowId: null,
                },
              ],
              timestamp: new Date().toISOString(),
            },
          ],
          conversationOutputs: [],
        })
      )
    );

    renderConvDetail();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByText("Step 1")).toBeInTheDocument();
    });
    await user.click(screen.getByText("Step 1"));

    const rawBlock = await screen.findByTestId("step-raw-1");
    // Escaped, not executed: no live <img> node, angle brackets are entities.
    expect(rawBlock.querySelector("img")).toBeNull();
    expect(rawBlock.innerHTML).toContain("&lt;img");
    expect(rawBlock.innerHTML).not.toContain("<img");
  });

  it("renders conversation properties section when available", async () => {
    renderConvDetail();

    await waitFor(() => {
      expect(
        screen.getByText("Conversation Properties")
      ).toBeInTheDocument();
    });
  });

  it("expands conversation properties on click", async () => {
    renderConvDetail();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(
        screen.getByText("Conversation Properties")
      ).toBeInTheDocument();
    });

    await user.click(screen.getByText("Conversation Properties"));

    await waitFor(() => {
      const propertiesJson = screen.getByTestId("properties-json");
      expect(propertiesJson.textContent).toContain("agentName");
    });
  });

  it("shows actions between messages", async () => {
    renderConvDetail();

    await waitFor(() => {
      // Actions from step 1: greet, order_inquiry
      expect(screen.getByText("greet")).toBeInTheDocument();
      expect(screen.getByText("order_inquiry")).toBeInTheDocument();
    });
  });

  it("shows processing time for steps", async () => {
    renderConvDetail();

    await waitFor(() => {
      // Step 1 has timestamps separated by ~1s, should show processing time
      // The processing time appears as text like "1.0s" within the chat section
      const chatSection = screen.getByTestId("conversation-chat");
      expect(chatSection.textContent).toMatch(/\d+(\.\d+)?s/);
    });
  });

  it("shows loading spinner initially", () => {
    server.use(
      http.get("*/conversationstore/conversations/simple/:id", async () => {
        await new Promise((resolve) => setTimeout(resolve, 5000));
        return HttpResponse.json({});
      })
    );

    renderConvDetail("slow-conv");

    expect(screen.getByTestId("conversation-loading")).toBeInTheDocument();
  });

  it("shows error state on API failure", async () => {
    server.use(
      http.get("*/conversationstore/conversations/simple/:id", () => {
        return new HttpResponse(null, { status: 500 });
      })
    );

    renderConvDetail("error-conv");

    await waitFor(() => {
      expect(screen.getByText("Something went wrong")).toBeInTheDocument();
    });
  });

  it("shows retry button on error", async () => {
    server.use(
      http.get("*/conversationstore/conversations/simple/:id", () => {
        return new HttpResponse(null, { status: 500 });
      })
    );

    renderConvDetail("error-conv");

    await waitFor(() => {
      expect(screen.getByText("Retry")).toBeInTheDocument();
    });
  });

  it("shows back to conversations link", async () => {
    renderConvDetail();

    await waitFor(() => {
      expect(
        screen.getByText("Back to Conversations")
      ).toBeInTheDocument();
    });
  });

  it("shows Chat Log section heading", async () => {
    renderConvDetail();

    await waitFor(() => {
      expect(screen.getByText("Chat Log")).toBeInTheDocument();
    });
  });

  it("shows no steps message for empty conversation", async () => {
    server.use(
      http.get("*/conversationstore/conversations/simple/:id", () => {
        return HttpResponse.json({
          agentId: "agent1",
          agentVersion: 1,
          conversationId: "empty-conv",
          conversationState: "READY",
          environment: "production",
          conversationSteps: [],
          conversationOutputs: [],
        });
      })
    );

    renderConvDetail("empty-conv");

    await waitFor(() => {
      expect(
        screen.getByText("No conversation steps recorded")
      ).toBeInTheDocument();
    });
  });

  it("does not show continue button for ENDED state", async () => {
    server.use(
      http.get("*/conversationstore/conversations/simple/:id", () => {
        return HttpResponse.json({
          agentId: "agent1",
          agentVersion: 1,
          conversationId: "ended-conv",
          conversationState: "ENDED",
          environment: "production",
          conversationSteps: [],
          conversationOutputs: [],
        });
      })
    );

    renderConvDetail("ended-conv");

    await waitFor(() => {
      expect(screen.getByText("Ended")).toBeInTheDocument();
    });

    expect(screen.queryByTestId("continue-in-chat")).not.toBeInTheDocument();
  });

  it("shows delete button via data-testid", async () => {
    renderConvDetail();

    await waitFor(() => {
      expect(screen.getByText("Conversation")).toBeInTheDocument();
    });

    // Use the data-testid we added to the delete button
    expect(screen.getByTestId("delete-conversation-btn")).toBeInTheDocument();
  });

  // Regression: the detail-page delete dialog defaults to a SOFT delete; only
  // checking the permanent option escalates to deletePermanently=true.
  it("soft-deletes by default (deletePermanently=false)", async () => {
    let deletedUrl = "";
    server.use(
      http.delete("*/conversationstore/conversations/:id", ({ request }) => {
        deletedUrl = request.url;
        return new HttpResponse(null, { status: 204 });
      })
    );
    renderConvDetail();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("delete-conversation-btn")).toBeInTheDocument();
    });
    await user.click(screen.getByTestId("delete-conversation-btn"));

    // The permanent checkbox starts unchecked
    expect(await screen.findByTestId("delete-permanent-checkbox")).not.toBeChecked();
    await user.click(screen.getByRole("button", { name: "Delete" }));

    await waitFor(() => {
      expect(deletedUrl).toContain("deletePermanently=false");
    });
  });

  it("passes deletePermanently=true when the permanent checkbox is checked", async () => {
    let deletedUrl = "";
    server.use(
      http.delete("*/conversationstore/conversations/:id", ({ request }) => {
        deletedUrl = request.url;
        return new HttpResponse(null, { status: 204 });
      })
    );
    renderConvDetail();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("delete-conversation-btn")).toBeInTheDocument();
    });
    await user.click(screen.getByTestId("delete-conversation-btn"));

    await user.click(await screen.findByTestId("delete-permanent-checkbox"));
    // Confirm label escalates to "Delete permanently" once the box is checked
    await user.click(screen.getByRole("button", { name: "Delete permanently" }));

    await waitFor(() => {
      expect(deletedUrl).toContain("deletePermanently=true");
    });
  });

  it("export markdown creates a download", async () => {
    renderConvDetail();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("export-md")).toBeInTheDocument();
    });

    // Mock URL.createObjectURL and revokeObjectURL
    const createObjectURL = vi.fn(() => "blob:test");
    const revokeObjectURL = vi.fn();
    globalThis.URL.createObjectURL = createObjectURL;
    globalThis.URL.revokeObjectURL = revokeObjectURL;

    // Mock createElement to intercept the download link
    const clickSpy = vi.fn();
    const originalCreateElement = document.createElement.bind(document);
    vi.spyOn(document, "createElement").mockImplementation((tag: string) => {
      const el = originalCreateElement(tag);
      if (tag === "a") {
        vi.spyOn(el, "click").mockImplementation(clickSpy);
      }
      return el;
    });

    await user.click(screen.getByTestId("export-md"));

    expect(createObjectURL).toHaveBeenCalled();
    expect(clickSpy).toHaveBeenCalled();
    expect(revokeObjectURL).toHaveBeenCalled();

    vi.restoreAllMocks();
  });
});
