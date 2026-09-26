import { describe, it, expect, vi, beforeEach } from "vitest";
import { useState } from "react";
import { screen, fireEvent } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import {
  McpCallsEditor,
  type McpCallsConfig,
} from "@/components/editors/mcpcalls-editor";
import { discoverMcpTools } from "@/lib/api/mcp-discover";

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
      toolArguments: { query: "{memory.current.input}" },
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

  // ─── McpCall model completeness (McpCall backend model) ──────────────────

  it("renders the call description field and writes to 'description'", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <McpCallsEditor data={populatedConfig} onChange={onChange} />
    );
    const input = screen.getByTestId("mcp-call-description");
    expect(input).toBeInTheDocument();
    await user.type(input, "x");
    const arg = onChange.mock.lastCall![0] as McpCallsConfig;
    expect(arg.mcpCalls![0]!.description).toBe("x");
  });

  it("writes 'continueOnError' when the checkbox is toggled", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <McpCallsEditor data={populatedConfig} onChange={onChange} />
    );
    await user.click(screen.getByTestId("mcp-continue-on-error"));
    const arg = onChange.mock.lastCall![0] as McpCallsConfig;
    expect(arg.mcpCalls![0]!.continueOnError).toBe(true);
  });

  it("shows the continueOnError hint referencing <responseObjectName>Error", () => {
    const cfg: McpCallsConfig = {
      mcpCalls: [
        {
          name: "c",
          toolName: "t",
          responseObjectName: "githubRepos",
          continueOnError: true,
        },
      ],
    };
    renderWithProviders(<McpCallsEditor data={cfg} onChange={onChange} />);
    expect(screen.getByText("githubReposError")).toBeInTheDocument();
  });

  it("renders Pre-Request, Post-Response and Retry & Backoff sections for a call", () => {
    renderWithProviders(
      <McpCallsEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByText("Pre-Request")).toBeInTheDocument();
    expect(screen.getByText("Post-Response")).toBeInTheDocument();
    expect(screen.getByText("Retry & Backoff")).toBeInTheDocument();
  });

  it("seeds RetryConfiguration defaults on add and writes to 'retry'", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <McpCallsEditor data={populatedConfig} onChange={onChange} />
    );
    // Retry & Backoff section is collapsed by default (no retry set) — expand it
    await user.click(screen.getByText("Retry & Backoff"));
    await user.click(screen.getByTestId("add-mcp-retry"));
    const arg = onChange.mock.lastCall![0] as McpCallsConfig;
    expect(arg.mcpCalls![0]!.retry).toEqual({
      maxAttempts: 3,
      backoffDelayMs: 1000,
      backoffMultiplier: 2.0,
      maxBackoffDelayMs: 10000,
    });
  });

  it("edits a RetryConfiguration field (maxAttempts) round-tripping the backend name", () => {
    const cfg: McpCallsConfig = {
      mcpCalls: [
        {
          name: "c",
          toolName: "t",
          retry: {
            maxAttempts: 3,
            backoffDelayMs: 1000,
            backoffMultiplier: 2.0,
            maxBackoffDelayMs: 10000,
          },
        },
      ],
    };
    renderWithProviders(<McpCallsEditor data={cfg} onChange={onChange} />);
    fireEvent.change(screen.getByTestId("mcp-retry-max-attempts"), {
      target: { value: "6" },
    });
    const arg = onChange.mock.lastCall![0] as McpCallsConfig;
    expect(arg.mcpCalls![0]!.retry!.maxAttempts).toBe(6);
  });

  it("writes Pre-Request property instructions under preRequest", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <McpCallsEditor data={populatedConfig} onChange={onChange} />
    );
    await user.click(screen.getByText("Pre-Request"));
    await user.click(screen.getByText("Add Property Instruction"));
    const arg = onChange.mock.lastCall![0] as McpCallsConfig;
    expect(arg.mcpCalls![0]!.preRequest!.propertyInstructions!.length).toBe(1);
  });

  it("populates the tool-name combobox and shows the parameter schema after discovery", async () => {
    const user = userEvent.setup();
    vi.mocked(discoverMcpTools).mockResolvedValue({
      tools: [
        {
          name: "search_documents",
          description: "Search docs",
          parameters: {
            type: "object",
            properties: { query: { type: "string" } },
          },
        },
      ],
      count: 1,
    });
    renderWithProviders(
      <McpCallsEditor data={populatedConfig} onChange={onChange} />
    );
    await user.click(screen.getByTestId("discover-tools-btn"));

    // datalist of discovered tool names now backs the tool-name input
    expect(await screen.findByTestId("tool-name-options")).toBeInTheDocument();

    // matched discovered tool exposes an expandable parameter schema
    const schema = screen.getByTestId("tool-schema");
    expect(schema).toBeInTheDocument();
    await user.click(screen.getByTestId("tool-schema-toggle"));
    expect(screen.getByTestId("tool-schema-content").textContent).toContain(
      "query"
    );
  });
});

describe("McpCallsEditor — connection references in the API key", () => {
  const onChange = vi.fn();

  it("offers the connection picker on the API key — one of the three places it resolves", () => {
    renderWithProviders(<McpCallsEditor data={emptyConfig} onChange={onChange} />);
    expect(screen.getByTestId("mcp-apikey-input-connection-btn")).toBeInTheDocument();
  });

  it("renders a connection reference as a chip rather than a masked secret", () => {
    renderWithProviders(
      <McpCallsEditor
        data={{ ...populatedConfig, apiKey: "${connection:jira}" }}
        onChange={onChange}
      />
    );
    expect(screen.getByTestId("mcp-apikey-input-connection-chip")).toHaveTextContent("jira");
    expect(screen.queryByTestId("mcp-apikey-connection-warning")).not.toBeInTheDocument();
  });

  it("warns when the reference is wrapped in text, which the backend refuses", () => {
    // `Bearer ${connection:x}` used to work by accident against an OAuth
    // connection and send a bare token against a STATIC one. The scheme
    // belongs in the connection's own header value.
    renderWithProviders(
      <McpCallsEditor
        data={{ ...populatedConfig, apiKey: "Bearer ${connection:jira}" }}
        onChange={onChange}
      />
    );
    expect(screen.getByTestId("mcp-apikey-connection-warning")).toHaveTextContent(
      "whole header value"
    );
  });
});

// ─── Tool arguments (editors review) ─────────────────────────────────────────

describe("McpCallsEditor tool arguments", () => {
  const onChange = vi.fn();
  beforeEach(() => vi.clearAllMocks());

  const withArgs = (toolArguments: Record<string, unknown>): McpCallsConfig => ({
    mcpCalls: [{ name: "c", toolName: "t", toolArguments }],
  });
  const lastArgs = () =>
    (onChange.mock.lastCall![0] as McpCallsConfig).mcpCalls![0]!.toolArguments!;

  it("keeps a numeric argument a number when it is edited", () => {
    // The value input was String(v) written back as a string: 5 became "5".
    renderWithProviders(<McpCallsEditor data={withArgs({ limit: 5 })} onChange={onChange} />);
    expect(screen.getByTestId("tool-argument-0-kind")).toHaveValue("json");
    fireEvent.change(screen.getByTestId("tool-argument-0-value"), { target: { value: "10" } });
    expect(lastArgs().limit).toBe(10);
  });

  it("keeps an object argument an object instead of writing [object Object]", () => {
    renderWithProviders(
      <McpCallsEditor data={withArgs({ filter: { lang: "en" } })} onChange={onChange} />,
    );
    const input = screen.getByTestId("tool-argument-0-value");
    expect(input).toHaveValue('{"lang":"en"}');
    fireEvent.change(input, { target: { value: '{"lang":"de","max":3}' } });
    expect(lastArgs().filter).toEqual({ lang: "de", max: 3 });
  });

  it("does not store text that is not valid JSON in a JSON argument", () => {
    renderWithProviders(<McpCallsEditor data={withArgs({ limit: 5 })} onChange={onChange} />);
    const input = screen.getByTestId("tool-argument-0-value");
    fireEvent.change(input, { target: { value: "{oops" } });
    expect(input).toHaveAttribute("aria-invalid", "true");
    expect(onChange).not.toHaveBeenCalled();
  });

  it("converts a text argument to a typed value on switching to JSON", async () => {
    const user = userEvent.setup();
    renderWithProviders(<McpCallsEditor data={withArgs({ limit: "5" })} onChange={onChange} />);
    await user.selectOptions(screen.getByTestId("tool-argument-0-kind"), "json");
    expect(lastArgs().limit).toBe(5);
  });

  it("keeps string arguments as templates", () => {
    renderWithProviders(
      <McpCallsEditor data={withArgs({ query: "{memory.current.input}" })} onChange={onChange} />,
    );
    const input = screen.getByTestId("tool-argument-0-value");
    expect(input).toHaveAttribute("placeholder", "{memory.current.input}");
    fireEvent.change(input, { target: { value: "{properties.topic}" } });
    expect(lastArgs().query).toBe("{properties.topic}");
  });

  it("lets an argument be renamed, committing on blur and keeping its value and position", () => {
    // The name input was read-only, so every added argument was stuck as arg<n>.
    renderWithProviders(
      <McpCallsEditor data={withArgs({ arg1: "x", other: 1 })} onChange={onChange} />,
    );
    const name = screen.getByTestId("tool-argument-0-name");
    fireEvent.change(name, { target: { value: "query" } });
    expect(onChange).not.toHaveBeenCalled();
    fireEvent.blur(name);
    expect(Object.entries(lastArgs())).toEqual([
      ["query", "x"],
      ["other", 1],
    ]);
  });

  it("refuses a rename onto an existing argument instead of overwriting it", () => {
    renderWithProviders(
      <McpCallsEditor data={withArgs({ arg1: "x", other: 1 })} onChange={onChange} />,
    );
    const name = screen.getByTestId("tool-argument-0-name");
    fireEvent.change(name, { target: { value: "other" } });
    expect(name).toHaveAttribute("aria-invalid", "true");
    fireEvent.blur(name);
    expect(onChange).not.toHaveBeenCalled();
    expect(name).toHaveValue("arg1");
  });

  it("adds an argument under a name that is not already taken", async () => {
    const user = userEvent.setup();
    renderWithProviders(<McpCallsEditor data={withArgs({ arg1: "keep" })} onChange={onChange} />);
    await user.click(screen.getByTestId("add-tool-argument"));
    expect(lastArgs()).toEqual({ arg1: "keep", arg2: "" });
  });
});

describe("McpCallsEditor Save Response default", () => {
  it("shows Save Response on when the call does not set it, as the backend defaults it", () => {
    // McpCall.saveResponse defaults to true; the checkbox showed it off.
    renderWithProviders(
      <McpCallsEditor data={{ mcpCalls: [{ name: "c", toolName: "t" }] }} onChange={vi.fn()} />,
    );
    const box = screen.getByText("Save Response").closest("label")!.querySelector("input")!;
    expect(box).toBeChecked();
  });
});

describe("McpCallsEditor argument rows (review follow-ups)", () => {
  function Harness({ initial }: { initial: Record<string, unknown> }) {
    const [config, setConfig] = useState<McpCallsConfig>({
      mcpCalls: [{ name: "c", toolName: "t", toolArguments: initial }],
    });
    latestConfig = config;
    return <McpCallsEditor data={config} onChange={setConfig} />;
  }
  let latestConfig: McpCallsConfig = {};

  it("keeps each row's own kind when a row above it is removed", async () => {
    // Rows keyed by position handed row 0's JSON kind to the row that moved
    // into its place when both held the same value.
    const user = userEvent.setup();
    renderWithProviders(<Harness initial={{ a: "x", b: "x" }} />);
    await user.selectOptions(screen.getByTestId("tool-argument-0-kind"), "json");
    expect(screen.getByTestId("tool-argument-1-kind")).toHaveValue("text");

    await user.click(screen.getAllByRole("button", { name: "Remove argument" })[0]!);
    expect(Object.keys(latestConfig.mcpCalls![0]!.toolArguments!)).toEqual(["b"]);
    expect(screen.getByTestId("tool-argument-0-kind")).toHaveValue("text");
  });

  it("does not rename a stored key with surrounding whitespace on focus and blur", () => {
    const onChange = vi.fn();
    renderWithProviders(
      <McpCallsEditor data={{ mcpCalls: [{ name: "c", toolName: "t", toolArguments: { " limit": 1, limit: 2 } }] }} onChange={onChange} />,
    );
    const name = screen.getByTestId("tool-argument-0-name");
    fireEvent.focus(name);
    fireEvent.blur(name);
    expect(onChange).not.toHaveBeenCalled();
    expect(name).not.toHaveAttribute("aria-invalid");
  });

  it("accepts a name that only exists on Object.prototype", () => {
    const onChange = vi.fn();
    renderWithProviders(
      <McpCallsEditor data={{ mcpCalls: [{ name: "c", toolName: "t", toolArguments: { arg1: "x" } }] }} onChange={onChange} />,
    );
    const name = screen.getByTestId("tool-argument-0-name");
    fireEvent.change(name, { target: { value: "constructor" } });
    fireEvent.blur(name);
    expect(Object.keys((onChange.mock.lastCall![0] as McpCallsConfig).mcpCalls![0]!.toolArguments!)).toEqual(["constructor"]);
  });
});
