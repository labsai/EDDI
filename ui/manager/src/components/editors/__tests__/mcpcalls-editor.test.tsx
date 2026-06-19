import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import {
  McpCallsEditor,
  type McpCallsConfig,
} from "@/components/editors/mcpcalls-editor";

// Mock discoverMcpTools to avoid real network calls
vi.mock("@/lib/api/mcp-discover", () => ({
  discoverMcpTools: vi.fn(),
}));

const emptyConfig: McpCallsConfig = {};

const populatedConfig: McpCallsConfig = {
  name: "My MCP Server",
  mcpServerUrl: "http://localhost:7070/mcp",
  transport: "http",
  apiKey: "${vault:my-mcp-key}",
  timeoutMs: 30000,
  toolsWhitelist: ["search_documents"],
  toolsBlacklist: [],
  mcpCalls: [
    {
      name: "search",
      toolName: "search_documents",
      actions: ["search"],
      toolArguments: { query: "{{memory.input}}" },
      saveResponse: true,
    },
  ],
};

describe("McpCallsEditor", () => {
  const onChange = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders with data-testid mcpcalls-form-editor", () => {
    renderWithProviders(
      <McpCallsEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("mcpcalls-form-editor")).toBeInTheDocument();
  });

  it("shows Server Connection section", () => {
    renderWithProviders(
      <McpCallsEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByText("Server Connection")).toBeInTheDocument();
  });

  it("shows Tool Governance section label", () => {
    renderWithProviders(
      <McpCallsEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByText("Tool Governance")).toBeInTheDocument();
  });

  it("shows Pipeline Calls section label", () => {
    renderWithProviders(
      <McpCallsEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByText("Pipeline Calls")).toBeInTheDocument();
  });

  it("shows display name input", () => {
    renderWithProviders(
      <McpCallsEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("mcp-name-input")).toBeInTheDocument();
  });

  it("shows MCP server URL input", () => {
    renderWithProviders(
      <McpCallsEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("mcp-url-input")).toBeInTheDocument();
  });

  it("shows transport select", () => {
    renderWithProviders(
      <McpCallsEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("mcp-transport-select")).toBeInTheDocument();
  });

  it("shows discover tools button", () => {
    renderWithProviders(
      <McpCallsEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("discover-tools-btn")).toBeInTheDocument();
  });

  it("renders populated config with name", () => {
    renderWithProviders(
      <McpCallsEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("mcp-name-input")).toHaveValue("My MCP Server");
  });

  it("renders populated config with server URL", () => {
    renderWithProviders(
      <McpCallsEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("mcp-url-input")).toHaveValue(
      "http://localhost:7070/mcp"
    );
  });

  it("shows whitelist and blacklist when governance section is expanded (populated config with whitelist)", () => {
    renderWithProviders(
      <McpCallsEditor data={populatedConfig} onChange={onChange} />
    );
    // populatedConfig has toolsWhitelist so the governance section is open
    expect(screen.getByTestId("tools-whitelist")).toBeInTheDocument();
    expect(screen.getByTestId("tools-blacklist")).toBeInTheDocument();
  });

  it("shows whitelisted tool tag for populated config", () => {
    renderWithProviders(
      <McpCallsEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByText("search_documents")).toBeInTheDocument();
  });

  it("expands Tool Governance to see governance hint", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <McpCallsEditor data={emptyConfig} onChange={onChange} />
    );
    // Click to expand Tool Governance section
    await user.click(screen.getByText("Tool Governance"));
    expect(
      screen.getByText(/which tools are exposed/)
    ).toBeInTheDocument();
  });

  it("shows add MCP call button when Pipeline Calls is expanded", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <McpCallsEditor data={emptyConfig} onChange={onChange} />
    );
    // Pipeline Calls section defaults closed when no calls, need to expand
    await user.click(screen.getByText("Pipeline Calls"));
    expect(screen.getByTestId("add-mcp-call")).toBeInTheDocument();
    expect(screen.getByText("Add MCP Call")).toBeInTheDocument();
  });

  it("calls onChange when add MCP call is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <McpCallsEditor data={emptyConfig} onChange={onChange} />
    );
    // Expand Pipeline Calls
    await user.click(screen.getByText("Pipeline Calls"));
    await user.click(screen.getByTestId("add-mcp-call"));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        mcpCalls: [
          expect.objectContaining({
            name: "",
            toolName: "",
            actions: [],
          }),
        ],
      })
    );
  });

  it("hides discover tools button in readOnly mode", () => {
    renderWithProviders(
      <McpCallsEditor data={emptyConfig} onChange={onChange} readOnly />
    );
    expect(screen.queryByTestId("discover-tools-btn")).not.toBeInTheDocument();
  });
});
