import { describe, it, expect } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderPage } from "@/test/test-utils";
import { ResourceDetailPage } from "@/pages/resource-detail";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

function renderMcpPage(id = "res1") {
  return renderPage(
    `/manage/resources/mcpcalls/${id}`,
    <ResourceDetailPage />,
    "/manage/resources/:type/:id"
  );
}

describe("MCP Calls Editor", () => {
  // ─── Basic Rendering ─────────────────────────────────────────

  it("renders mcpcalls form editor", async () => {
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getByTestId("mcpcalls-form-editor")).toBeInTheDocument();
    });
  });

  it("renders form tab as default when editor exists", async () => {
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getByTestId("form-view")).toBeInTheDocument();
    });
  });

  it("renders server connection section with name input", async () => {
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getByTestId("mcp-name-input")).toBeInTheDocument();
    });
  });

  it("renders MCP server URL input", async () => {
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getByTestId("mcp-url-input")).toBeInTheDocument();
    });
  });

  it("renders transport select dropdown", async () => {
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getByTestId("mcp-transport-select")).toBeInTheDocument();
    });
  });

  it("renders API key input", async () => {
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getByTestId("mcp-apikey-input")).toBeInTheDocument();
    });
  });

  it("renders tool governance whitelist", async () => {
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getByTestId("tools-whitelist")).toBeInTheDocument();
    });
  });

  it("renders tool governance blacklist", async () => {
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getByTestId("tools-blacklist")).toBeInTheDocument();
    });
  });

  it("renders MCP call editor card from mock data", async () => {
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getAllByTestId("mcp-call-editor").length).toBeGreaterThan(0);
    });
  });

  it("renders tool name input inside call editor", async () => {
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getAllByTestId("tool-name-input").length).toBeGreaterThan(0);
    });
  });

  it("renders trigger actions for call", async () => {
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getAllByTestId("call-actions").length).toBeGreaterThan(0);
    });
  });

  it("renders add MCP call button", async () => {
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getByTestId("add-mcp-call")).toBeInTheDocument();
    });
  });

  // ─── Data Population Tests ────────────────────────────────

  it("populates server URL from mock data", async () => {
    renderMcpPage();
    await waitFor(() => {
      const input = screen.getByTestId("mcp-url-input") as HTMLInputElement;
      expect(input.value).toBe("https://mcp.internal.example.com/v1");
    });
  });

  it("populates display name from mock data", async () => {
    renderMcpPage();
    await waitFor(() => {
      const input = screen.getByTestId("mcp-name-input") as HTMLInputElement;
      expect(input.value).toBe("Enterprise Document Tools Server");
    });
  });

  it("populates transport from mock data", async () => {
    renderMcpPage();
    await waitFor(() => {
      const select = screen.getByTestId(
        "mcp-transport-select"
      ) as HTMLSelectElement;
      expect(select.value).toBe("http");
    });
  });

  it("populates API key from mock data", async () => {
    renderMcpPage();
    await waitFor(() => {
      // SecretKeyPicker v2 auto-detects ${vault:...} and shows an amber chip
      const picker = screen.getByTestId("mcp-apikey-input");
      expect(picker).toBeInTheDocument();
      // The chip displays the vault key name as text
      expect(picker).toHaveTextContent("mcp-doc-key");
    });
  });

  it("populates tool name in call editor from mock data", async () => {
    renderMcpPage();
    await waitFor(() => {
      const inputs = screen.getAllByTestId("tool-name-input") as HTMLInputElement[];
      expect(inputs[0]!.value).toBe("search_documents");
    });
  });

  it("renders whitelist tags from mock data", async () => {
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getByText("search_documents")).toBeInTheDocument();
      expect(screen.getByText("index_document")).toBeInTheDocument();
    });
  });

  // ─── Tool Discovery Tests ────────────────────────────────────────────────

  it("renders discover tools button", async () => {
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getByTestId("discover-tools-btn")).toBeInTheDocument();
    });
  });

  it("shows discovered tools panel after clicking discover", async () => {
    const user = userEvent.setup();
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getByTestId("discover-tools-btn")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("discover-tools-btn"));

    await waitFor(() => {
      expect(screen.getByTestId("discovered-tools-panel")).toBeInTheDocument();
    });
  });

  it("shows discovered tool items with names", async () => {
    const user = userEvent.setup();
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getByTestId("discover-tools-btn")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("discover-tools-btn"));

    await waitFor(() => {
      const items = screen.getAllByTestId("discovered-tool-item");
      expect(items.length).toBe(3);
    });
  });

  it("shows tool descriptions in discovered panel", async () => {
    const user = userEvent.setup();
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getByTestId("discover-tools-btn")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("discover-tools-btn"));

    await waitFor(() => {
      expect(
        screen.getByText("Search indexed documents by query")
      ).toBeInTheDocument();
      expect(
        screen.getByText("Delete a document by its unique ID")
      ).toBeInTheDocument();
    });
  });

  it("shows tool count in discovered panel header", async () => {
    const user = userEvent.setup();
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getByTestId("discover-tools-btn")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("discover-tools-btn"));

    await waitFor(() => {
      expect(screen.getByText(/Available Tools.*\(3\)/)).toBeInTheDocument();
    });
  });

  // ─── Interaction Tests ────────────────────────────────────────────────

  it("clicking add MCP call button adds a new call editor", async () => {
    const user = userEvent.setup();
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getByTestId("add-mcp-call")).toBeInTheDocument();
    });

    const initialCalls = screen.getAllByTestId("mcp-call-editor").length;
    await user.click(screen.getByTestId("add-mcp-call"));

    await waitFor(() => {
      expect(screen.getAllByTestId("mcp-call-editor").length).toBe(
        initialCalls + 1
      );
    });
  });

  it("changes transport dropdown to sse", async () => {
    const user = userEvent.setup();
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getByTestId("mcp-transport-select")).toBeInTheDocument();
    });

    const select = screen.getByTestId("mcp-transport-select") as HTMLSelectElement;
    await user.selectOptions(select, "sse");

    await waitFor(() => {
      expect(select.value).toBe("sse");
    });
  });

  it("changes transport dropdown back to http after switching to sse", async () => {
    const user = userEvent.setup();
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getByTestId("mcp-transport-select")).toBeInTheDocument();
    });

    const select = screen.getByTestId("mcp-transport-select") as HTMLSelectElement;
    // Switch to sse first
    await user.selectOptions(select, "sse");
    await waitFor(() => {
      expect(select.value).toBe("sse");
    });

    // Switch back to http
    await user.selectOptions(select, "http");
    await waitFor(() => {
      expect(select.value).toBe("http");
    });
  });

  it("switches to JSON tab and shows JSON view", async () => {
    const user = userEvent.setup();
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getByTestId("tab-json")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("tab-json"));

    await waitFor(() => {
      expect(screen.getByTestId("json-view")).toBeInTheDocument();
    });
  });

  it("marks dirty indicator when server name is changed", async () => {
    const user = userEvent.setup();
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getByTestId("mcp-name-input")).toBeInTheDocument();
    });

    const input = screen.getByTestId("mcp-name-input");
    await user.clear(input);
    await user.type(input, "New Server Name");

    await waitFor(() => {
      expect(screen.getByTestId("dirty-indicator")).toBeInTheDocument();
    });
  });

  it("edits tool name in call editor", async () => {
    const user = userEvent.setup();
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getAllByTestId("tool-name-input").length).toBeGreaterThan(0);
    });

    const toolInput = screen.getAllByTestId("tool-name-input")[0] as HTMLInputElement;
    await user.clear(toolInput);
    await user.type(toolInput, "new_tool_name");

    await waitFor(() => {
      expect(toolInput.value).toBe("new_tool_name");
    });
  });

  it("edits MCP server URL", async () => {
    const user = userEvent.setup();
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getByTestId("mcp-url-input")).toBeInTheDocument();
    });

    const urlInput = screen.getByTestId("mcp-url-input") as HTMLInputElement;
    await user.clear(urlInput);
    await user.type(urlInput, "https://new-server.example.com");

    await waitFor(() => {
      expect(urlInput.value).toBe("https://new-server.example.com");
    });
  });

  it("renders call editor with correct tool description scoped within call card", async () => {
    renderMcpPage();
    await waitFor(() => {
      const cards = screen.getAllByTestId("mcp-call-editor");
      expect(cards.length).toBeGreaterThan(0);

      // Use within() to scope the query
      const firstCard = cards[0];
      const toolInput = within(firstCard).getByTestId("tool-name-input") as HTMLInputElement;
      expect(toolInput.value).toBe("search_documents");
    });
  });

  it("renders multiple call editors from mock data", async () => {
    renderMcpPage();
    await waitFor(() => {
      const cards = screen.getAllByTestId("mcp-call-editor");
      expect(cards.length).toBe(3); // mock data has 3 calls
    });
  });

  it("renders action tags for each call editor", async () => {
    renderMcpPage();
    await waitFor(() => {
      // Mock data: first call has actions ["search", "find_info"]
      expect(screen.getByText("search")).toBeInTheDocument();
      expect(screen.getByText("find_info")).toBeInTheDocument();
    });
  });

  // ─── Discovery Error & Empty States ────────────────────────────────────

  it("shows discovery error message when server returns error", async () => {
    server.use(
      http.get("*/mcpcallsstore/mcpcalls/discover-tools", () => {
        return HttpResponse.json(
          { error: "Connection refused" },
          { status: 500 }
        );
      })
    );

    const user = userEvent.setup();
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getByTestId("discover-tools-btn")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("discover-tools-btn"));

    await waitFor(() => {
      expect(screen.getByTestId("discovery-error")).toBeInTheDocument();
    });
  });

  it("shows discovery empty state when no tools found", async () => {
    server.use(
      http.get("*/mcpcallsstore/mcpcalls/discover-tools", () => {
        return HttpResponse.json({ tools: [], count: 0 });
      })
    );

    const user = userEvent.setup();
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getByTestId("discover-tools-btn")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("discover-tools-btn"));

    await waitFor(() => {
      expect(screen.getByTestId("discovery-empty")).toBeInTheDocument();
      expect(screen.getByText("No tools found on this server")).toBeInTheDocument();
    });
  });

  it("shows retry button in error state and retries on click", async () => {
    let callCount = 0;
    server.use(
      http.get("*/mcpcallsstore/mcpcalls/discover-tools", () => {
        callCount++;
        if (callCount === 1) {
          return HttpResponse.json(
            { error: "Connection refused" },
            { status: 500 }
          );
        }
        return HttpResponse.json({
          tools: [{ name: "tool1", description: "A tool" }],
          count: 1,
        });
      })
    );

    const user = userEvent.setup();
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getByTestId("discover-tools-btn")).toBeInTheDocument();
    });

    // First click — triggers error
    await user.click(screen.getByTestId("discover-tools-btn"));
    await waitFor(() => {
      expect(screen.getByTestId("discovery-error")).toBeInTheDocument();
    });

    // Click retry button
    const retryBtn = screen.getByText("Retry");
    await user.click(retryBtn);

    // Should now show discovered tools
    await waitFor(() => {
      expect(screen.getByTestId("discovered-tools-panel")).toBeInTheDocument();
    });
  });

  // ─── Discovered Tools: Whitelist / Blacklist Interactions ──────────────

  it("shows Whitelisted badge for tools already in whitelist", async () => {
    const user = userEvent.setup();
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getByTestId("discover-tools-btn")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("discover-tools-btn"));

    await waitFor(() => {
      expect(screen.getByTestId("discovered-tools-panel")).toBeInTheDocument();
    });

    // search_documents and index_document are both in whitelist from mock data
    const whitelistedBadges = screen.getAllByText("Whitelisted");
    expect(whitelistedBadges.length).toBe(2);
  });

  it("shows Blacklisted badge for tools already in blacklist", async () => {
    const user = userEvent.setup();
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getByTestId("discover-tools-btn")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("discover-tools-btn"));

    await waitFor(() => {
      expect(screen.getByTestId("discovered-tools-panel")).toBeInTheDocument();
    });

    // delete_document is in blacklist from mock data
    expect(screen.getByText("Blacklisted")).toBeInTheDocument();
  });

  it("adds a tool to blacklist from discovered tools panel", async () => {
    const user = userEvent.setup();
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getByTestId("discover-tools-btn")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("discover-tools-btn"));

    await waitFor(() => {
      expect(screen.getByTestId("discovered-tools-panel")).toBeInTheDocument();
    });

    // "index_document" is in whitelist but not blacklist.
    // Find its discovered tool item and click the Blacklist button
    const items = screen.getAllByTestId("discovered-tool-item");
    const indexDocItem = items.find((item) =>
      item.textContent?.includes("index_document")
    );
    expect(indexDocItem).toBeDefined();

    const blacklistBtn = within(indexDocItem!).getByText("Blacklist");
    await user.click(blacklistBtn);

    // After clicking, the blacklist section should contain the tool
    await waitFor(() => {
      const blacklistSection = screen.getByTestId("tools-blacklist");
      expect(blacklistSection).toHaveTextContent("index_document");
    });
  });

  // ─── MCP Call: Remove, Collapse, Save Response ─────────────────────────

  it("removes an MCP call when delete button is clicked", async () => {
    const user = userEvent.setup();
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getAllByTestId("mcp-call-editor").length).toBe(3);
    });

    // Click remove on the first call
    const firstCall = screen.getAllByTestId("mcp-call-editor")[0];
    const removeBtn = within(firstCall).getByRole("button", { name: /remove call/i });
    await user.click(removeBtn);

    await waitFor(() => {
      expect(screen.getAllByTestId("mcp-call-editor").length).toBe(2);
    });
  });

  it("collapses and expands a call editor", async () => {
    const user = userEvent.setup();
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getAllByTestId("mcp-call-editor").length).toBeGreaterThan(0);
    });

    const firstCall = screen.getAllByTestId("mcp-call-editor")[0];
    // Initially expanded — tool-name-input is visible
    expect(within(firstCall).getByTestId("tool-name-input")).toBeInTheDocument();

    // Click the chevron toggle — first button in the call header
    const buttons = within(firstCall).getAllByRole("button");
    // The first button is the expand/collapse toggle
    await user.click(buttons[0]);

    // After collapsing, tool-name-input should be hidden
    await waitFor(() => {
      expect(within(firstCall).queryByTestId("tool-name-input")).not.toBeInTheDocument();
    });

    // Expand again
    await user.click(buttons[0]);
    await waitFor(() => {
      expect(within(firstCall).getByTestId("tool-name-input")).toBeInTheDocument();
    });
  });

  it("toggles save response checkbox on a call", async () => {
    const user = userEvent.setup();
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getAllByTestId("mcp-call-editor").length).toBeGreaterThan(0);
    });

    // The first call has saveResponse: true from mock data
    const firstCall = screen.getAllByTestId("mcp-call-editor")[0];
    const checkbox = within(firstCall).getByRole("checkbox", { name: /save response/i });
    expect((checkbox as HTMLInputElement).checked).toBe(true);

    // Uncheck
    await user.click(checkbox);
    await waitFor(() => {
      expect((checkbox as HTMLInputElement).checked).toBe(false);
    });

    // The response object name input should disappear when unchecked
    expect(within(firstCall).queryByPlaceholderText("Response object name")).not.toBeInTheDocument();
  });

  it("edits response object name when save response is checked", async () => {
    const user = userEvent.setup();
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getAllByTestId("mcp-call-editor").length).toBeGreaterThan(0);
    });

    const firstCall = screen.getAllByTestId("mcp-call-editor")[0];
    // Mock data has responseObjectName: "searchResults"
    const responseInput = within(firstCall).getByDisplayValue("searchResults") as HTMLInputElement;
    await user.clear(responseInput);
    await user.type(responseInput, "newResponseName");

    await waitFor(() => {
      expect(responseInput.value).toBe("newResponseName");
    });
  });

  // ─── MCP Call: Tool Arguments ──────────────────────────────────────────

  it("shows tool argument key-value pairs from mock data", async () => {
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getAllByTestId("mcp-call-editor").length).toBeGreaterThan(0);
    });

    // First call has toolArguments: { query: "...", maxResults: "5", minScore: "0.7" }
    const firstCall = screen.getAllByTestId("mcp-call-editor")[0];
    expect(within(firstCall).getByDisplayValue("query")).toBeInTheDocument();
    expect(within(firstCall).getByDisplayValue("maxResults")).toBeInTheDocument();
  });

  it("adds a new tool argument", async () => {
    const user = userEvent.setup();
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getAllByTestId("mcp-call-editor").length).toBeGreaterThan(0);
    });

    const firstCall = screen.getAllByTestId("mcp-call-editor")[0];
    const addArgBtn = within(firstCall).getByText("Add Argument");
    await user.click(addArgBtn);

    // A new argument row with key "arg3" should appear (index 3 since we have 3 existing)
    await waitFor(() => {
      expect(within(firstCall).getByDisplayValue("arg3")).toBeInTheDocument();
    });
  });

  // ─── Whitelist / Blacklist Tag Removal ─────────────────────────────────

  it("removes a whitelist tag", async () => {
    const user = userEvent.setup();
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getByTestId("tools-whitelist")).toBeInTheDocument();
    });

    const whitelist = screen.getByTestId("tools-whitelist");
    // Mock data has 3 whitelist items: search_documents, index_document, get_document_metadata
    const removeBtn = within(whitelist).getByRole("button", { name: /remove get_document_metadata/i });
    await user.click(removeBtn);

    await waitFor(() => {
      expect(within(whitelist).queryByText("get_document_metadata")).not.toBeInTheDocument();
    });
  });

  it("removes a blacklist tag", async () => {
    const user = userEvent.setup();
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getByTestId("tools-blacklist")).toBeInTheDocument();
    });

    const blacklist = screen.getByTestId("tools-blacklist");
    // Mock data has 1 blacklist item: delete_document
    const removeBtn = within(blacklist).getByRole("button", { name: /remove delete_document/i });
    await user.click(removeBtn);

    await waitFor(() => {
      expect(within(blacklist).queryByText("delete_document")).not.toBeInTheDocument();
    });
  });

  it("adds a new whitelist tag via input", async () => {
    const user = userEvent.setup();
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getByTestId("tools-whitelist")).toBeInTheDocument();
    });

    const whitelist = screen.getByTestId("tools-whitelist");
    const input = within(whitelist).getByPlaceholderText("tool_name");
    await user.type(input, "new_custom_tool{enter}");

    await waitFor(() => {
      expect(within(whitelist).getByText("new_custom_tool")).toBeInTheDocument();
    });
  });

  it("adds a new blacklist tag via input", async () => {
    const user = userEvent.setup();
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getByTestId("tools-blacklist")).toBeInTheDocument();
    });

    const blacklist = screen.getByTestId("tools-blacklist");
    const input = within(blacklist).getByPlaceholderText("tool_name");
    await user.type(input, "blocked_tool{enter}");

    await waitFor(() => {
      expect(within(blacklist).getByText("blocked_tool")).toBeInTheDocument();
    });
  });

  // ─── Call Name Editing ─────────────────────────────────────────────────

  it("edits call name in the call header", async () => {
    const user = userEvent.setup();
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getAllByTestId("mcp-call-editor").length).toBeGreaterThan(0);
    });

    // First call has name "searchDocs"
    const nameInput = screen.getByDisplayValue("searchDocs") as HTMLInputElement;
    await user.clear(nameInput);
    await user.type(nameInput, "renamedCall");

    await waitFor(() => {
      expect(nameInput.value).toBe("renamedCall");
    });
  });

  // ─── Action Tag Management in Call Editor ──────────────────────────────

  it("removes an action tag from a call", async () => {
    const user = userEvent.setup();
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getAllByTestId("call-actions").length).toBeGreaterThan(0);
    });

    const firstActions = screen.getAllByTestId("call-actions")[0];
    const removeBtn = within(firstActions).getByRole("button", { name: /remove search/i });
    await user.click(removeBtn);

    await waitFor(() => {
      expect(within(firstActions).queryByText("search")).not.toBeInTheDocument();
    });
  });

  // ─── Save API Verification ─────────────────────────────────────────────

  it("verifies save API is called when save button is clicked", async () => {
    let saveCalled = false;
    server.use(
      http.put("*/mcpcallsstore/mcpcalls/*", () => {
        saveCalled = true;
        return HttpResponse.json({}, { status: 200 });
      })
    );

    const user = userEvent.setup();
    renderMcpPage();
    await waitFor(() => {
      expect(screen.getByTestId("mcp-name-input")).toBeInTheDocument();
    });

    // Make a change to enable save
    const input = screen.getByTestId("mcp-name-input");
    await user.clear(input);
    await user.type(input, "Changed Name");

    await waitFor(() => {
      expect(screen.getByTestId("dirty-indicator")).toBeInTheDocument();
    });

    // Click save
    const saveBtn = screen.getByTestId("save-btn");
    await user.click(saveBtn);

    await waitFor(() => {
      expect(saveCalled).toBe(true);
    });
  });
});
