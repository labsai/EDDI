import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, within } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { LlmEditor, type LlmConfig } from "@/components/editors/llm-editor";

// Mock monaco
vi.mock("@monaco-editor/react", () => ({
  default: vi.fn(({ value }: { value: string }) => (
    <textarea data-testid="mock-monaco" defaultValue={value} />
  )),
}));

const emptyConfig: LlmConfig = {};

const populatedConfig: LlmConfig = {
  tasks: [
    {
      type: "openai",
      actions: ["complete"],
      id: "task-1",
      description: "Main chat task",
      parameters: {
        systemMessage: "You are a helpful assistant",
        model: "gpt-4o",
        apiKey: "${vault:openai-key}",
      },
    },
  ],
};

const agentConfig: LlmConfig = {
  tasks: [
    {
      type: "openai",
      actions: ["agent-action"],
      enableBuiltInTools: true,
      tools: ["eddi://ai.labs.apicalls/apicallstore/apicalls/abc123"],
      a2aAgents: [
        {
          url: "https://remote.example.com/a2a/agents/agent1",
          name: "Remote Agent",
          timeoutMs: 30000,
        },
      ],
      parameters: {
        systemMessage: "You are an agent",
        model: "gpt-4o",
      },
      enableHttpCallTools: true,
      enableMcpCallTools: true,
      conversationHistoryLimit: 20,
    },
  ],
};

const safetyConfig: LlmConfig = {
  tasks: [
    {
      type: "openai",
      actions: [],
      parameters: { systemMessage: "" },
      counterweight: {
        enabled: true,
        level: "cautious",
        placement: "suffix",
      },
      identityMasking: {
        enabled: true,
        rules: ["Never reveal you are an AI"],
      },
    },
  ],
};

const executionConfig: LlmConfig = {
  tasks: [
    {
      type: "openai",
      actions: [],
      parameters: { systemMessage: "" },
      enableParallelExecution: true,
      parallelExecutionTimeoutMs: 60000,
      enableRateLimiting: true,
      defaultRateLimit: 100,
      toolRateLimits: { my_tool: 50 },
      toolResponseLimits: {
        defaultMaxChars: 50000,
        truncationStrategy: "truncate",
        perToolLimits: { heavy_tool: 10000 },
      },
      maxBudgetPerConversation: 5.0,
      enableCostTracking: true,
      enableToolCaching: true,
    },
  ],
};

/** Helper to open a collapsed EditorSection by its label text */
async function openSection(user: ReturnType<typeof userEvent.setup>, label: string) {
  const btn = screen.getByRole("button", { name: new RegExp(label, "i") });
  await user.click(btn);
}

describe("LlmEditor", () => {
  const onChange = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
  });

  // ── Basic rendering ────────────────────────────────────────────────────────

  it("renders with data-testid llm-editor", () => {
    renderWithProviders(
      <LlmEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("llm-editor")).toBeInTheDocument();
  });

  it("shows LLM Tasks heading", () => {
    renderWithProviders(
      <LlmEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByText("LLM Tasks")).toBeInTheDocument();
  });

  it("shows no tasks message when empty", () => {
    renderWithProviders(
      <LlmEditor data={emptyConfig} onChange={onChange} />
    );
    expect(
      screen.getByText("No LLM Tasks configured")
    ).toBeInTheDocument();
  });

  it("shows add task button", () => {
    renderWithProviders(
      <LlmEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("add-task-btn")).toBeInTheDocument();
    expect(screen.getByText("Add Task")).toBeInTheDocument();
  });

  it("hides add task button in readOnly mode", () => {
    renderWithProviders(
      <LlmEditor data={emptyConfig} onChange={onChange} readOnly />
    );
    expect(screen.queryByTestId("add-task-btn")).not.toBeInTheDocument();
  });

  it("calls onChange when add task is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <LlmEditor data={emptyConfig} onChange={onChange} />
    );
    await user.click(screen.getByTestId("add-task-btn"));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        tasks: [
          expect.objectContaining({
            type: "openai",
            actions: [],
          }),
        ],
      })
    );
  });

  it("renders populated config with task", () => {
    renderWithProviders(
      <LlmEditor data={populatedConfig} onChange={onChange} />
    );
    expect(
      screen.queryByText("No LLM Tasks configured")
    ).not.toBeInTheDocument();
  });

  // ── Task editor details ─────────────────────────────────────────────────────

  it("shows model type dropdown with current value", () => {
    renderWithProviders(
      <LlmEditor data={populatedConfig} onChange={onChange} />
    );
    const select = screen.getByTestId("model-type-select");
    expect(select).toHaveValue("openai");
  });

  it("changes model type when dropdown is changed", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <LlmEditor data={populatedConfig} onChange={onChange} />
    );
    await user.selectOptions(screen.getByTestId("model-type-select"), "anthropic");
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        tasks: [expect.objectContaining({ type: "anthropic" })],
      })
    );
  });

  it("shows Chat mode badge when no tools configured", () => {
    renderWithProviders(
      <LlmEditor data={populatedConfig} onChange={onChange} />
    );
    const badge = screen.getByTestId("mode-badge");
    expect(badge).toHaveTextContent("Chat");
  });

  it("shows Agent mode badge when tools are configured", () => {
    renderWithProviders(
      <LlmEditor data={agentConfig} onChange={onChange} />
    );
    const badge = screen.getByTestId("mode-badge");
    expect(badge).toHaveTextContent("Agent");
  });

  it("shows task description input", () => {
    renderWithProviders(
      <LlmEditor data={populatedConfig} onChange={onChange} />
    );
    const desc = screen.getByDisplayValue("Main chat task");
    expect(desc).toBeInTheDocument();
  });

  it("removes task when remove button is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <LlmEditor data={populatedConfig} onChange={onChange} />
    );
    const removeBtn = screen.getByRole("button", { name: "Remove Task" });
    await user.click(removeBtn);
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ tasks: [] })
    );
  });

  it("collapses and expands a task editor", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <LlmEditor data={populatedConfig} onChange={onChange} />
    );
    // The description is visible initially (expanded)
    expect(screen.getByDisplayValue("Main chat task")).toBeInTheDocument();

    // Find the task editor and click the chevron (first button in the task header)
    const taskEditor = screen.getByTestId("llm-task-editor");
    const collapseBtn = within(taskEditor).getAllByRole("button")[0];
    await user.click(collapseBtn);

    // Description should be hidden after collapse
    expect(screen.queryByDisplayValue("Main chat task")).not.toBeInTheDocument();
  });

  it("shows action tags for a task", () => {
    renderWithProviders(
      <LlmEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByText("complete")).toBeInTheDocument();
  });

  it("shows model parameter key-value pairs after opening Model Parameters", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <LlmEditor data={populatedConfig} onChange={onChange} />
    );
    // Model Parameters is defaultOpen={false}, need to open it
    await openSection(user, "Model Parameters");
    // model param should be visible (systemMessage is hidden)
    expect(screen.getByDisplayValue("model")).toBeInTheDocument();
    expect(screen.getByDisplayValue("gpt-4o")).toBeInTheDocument();
  });

  // ── Agent Mode ──────────────────────────────────────────────────────────────

  it("shows enable built-in tools checkbox", () => {
    renderWithProviders(
      <LlmEditor data={agentConfig} onChange={onChange} />
    );
    const checkbox = screen.getByTestId("enable-builtin-tools");
    expect(checkbox).toBeChecked();
  });

  it("shows All Tools mode by default when built-in tools enabled", () => {
    renderWithProviders(
      <LlmEditor data={agentConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("tool-mode-all")).toBeInTheDocument();
    expect(screen.getByTestId("all-tools-info")).toBeInTheDocument();
  });

  it("switches to Select Specific mode", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <LlmEditor data={agentConfig} onChange={onChange} />
    );
    await user.click(screen.getByTestId("tool-mode-specific"));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        tasks: [
          expect.objectContaining({
            builtInToolsWhitelist: expect.arrayContaining(["calculator", "datetime"]),
          }),
        ],
      })
    );
  });

  it("shows tool source info callout when both sources enabled", () => {
    renderWithProviders(
      <LlmEditor data={agentConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("tool-sources-info")).toBeInTheDocument();
  });

  it("shows HTTP Call Tools checkbox", () => {
    renderWithProviders(
      <LlmEditor data={agentConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("enable-httpcall-tools")).toBeChecked();
  });

  it("shows MCP Call Tools checkbox", () => {
    renderWithProviders(
      <LlmEditor data={agentConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("enable-mcpcall-tools")).toBeChecked();
  });

  it("toggles HTTP Call Tools off", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <LlmEditor data={agentConfig} onChange={onChange} />
    );
    await user.click(screen.getByTestId("enable-httpcall-tools"));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        tasks: [expect.objectContaining({ enableHttpCallTools: false })],
      })
    );
  });

  it("shows A2A agent card with URL", () => {
    renderWithProviders(
      <LlmEditor data={agentConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("a2a-agent-0")).toBeInTheDocument();
    expect(
      screen.getByDisplayValue("https://remote.example.com/a2a/agents/agent1")
    ).toBeInTheDocument();
  });

  it("shows add A2A agent button", () => {
    renderWithProviders(
      <LlmEditor data={agentConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("add-a2a-agent")).toBeInTheDocument();
  });

  it("adds a new A2A agent", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <LlmEditor data={agentConfig} onChange={onChange} />
    );
    await user.click(screen.getByTestId("add-a2a-agent"));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        tasks: [
          expect.objectContaining({
            a2aAgents: expect.arrayContaining([
              expect.objectContaining({ url: "" }),
            ]),
          }),
        ],
      })
    );
  });

  it("shows tool URI inputs", () => {
    renderWithProviders(
      <LlmEditor data={agentConfig} onChange={onChange} />
    );
    expect(
      screen.getByDisplayValue("eddi://ai.labs.apicalls/apicallstore/apicalls/abc123")
    ).toBeInTheDocument();
  });

  it("shows Add Tool URI button", () => {
    renderWithProviders(
      <LlmEditor data={agentConfig} onChange={onChange} />
    );
    expect(screen.getByText("Add Tool URI")).toBeInTheDocument();
  });

  it("shows history limit input inside agent mode section", () => {
    renderWithProviders(
      <LlmEditor data={agentConfig} onChange={onChange} />
    );
    // Agent mode section is defaultOpen when isAgent=true, so history limit is visible
    expect(screen.getByDisplayValue("20")).toBeInTheDocument();
  });

  // ── Prompt Safety ───────────────────────────────────────────────────────────

  it("shows counterweight checkbox when enabled", () => {
    renderWithProviders(
      <LlmEditor data={safetyConfig} onChange={onChange} />
    );
    const cw = screen.getByTestId("counterweight-enabled");
    expect(cw).toBeChecked();
  });

  it("shows counterweight level select", () => {
    renderWithProviders(
      <LlmEditor data={safetyConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("counterweight-level")).toHaveValue("cautious");
  });

  it("shows counterweight placement select", () => {
    renderWithProviders(
      <LlmEditor data={safetyConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("counterweight-placement")).toHaveValue("suffix");
  });

  it("changes counterweight level to strict", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <LlmEditor data={safetyConfig} onChange={onChange} />
    );
    await user.selectOptions(screen.getByTestId("counterweight-level"), "strict");
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        tasks: [
          expect.objectContaining({
            counterweight: expect.objectContaining({ level: "strict" }),
          }),
        ],
      })
    );
  });

  it("shows identity masking checkbox when enabled", () => {
    renderWithProviders(
      <LlmEditor data={safetyConfig} onChange={onChange} />
    );
    const im = screen.getByTestId("identity-masking-enabled");
    expect(im).toBeChecked();
  });

  it("shows identity masking rules", () => {
    renderWithProviders(
      <LlmEditor data={safetyConfig} onChange={onChange} />
    );
    expect(
      screen.getByDisplayValue("Never reveal you are an AI")
    ).toBeInTheDocument();
  });

  it("shows Add Rule button for identity masking", () => {
    renderWithProviders(
      <LlmEditor data={safetyConfig} onChange={onChange} />
    );
    expect(screen.getByText("Add Rule")).toBeInTheDocument();
  });

  it("enables counterweight from disabled state", async () => {
    const user = userEvent.setup();
    const noSafetyConfig: LlmConfig = {
      tasks: [
        {
          type: "openai",
          actions: [],
          parameters: { systemMessage: "" },
        },
      ],
    };
    renderWithProviders(
      <LlmEditor data={noSafetyConfig} onChange={onChange} />
    );
    // Prompt Safety section is defaultOpen=false when no safety features enabled.
    // Need to open it first.
    await openSection(user, "Prompt Safety");
    await user.click(screen.getByTestId("counterweight-enabled"));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        tasks: [
          expect.objectContaining({
            counterweight: expect.objectContaining({ enabled: true }),
          }),
        ],
      })
    );
  });

  // ── Execution section ───────────────────────────────────────────────────────

  it("shows parallel execution checkbox when section is opened", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <LlmEditor data={executionConfig} onChange={onChange} />
    );
    // Execution section is defaultOpen={false}
    await openSection(user, "Execution");
    expect(screen.getByTestId("enable-parallel-execution")).toBeChecked();
  });

  it("shows tool response default chars input in execution section", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <LlmEditor data={executionConfig} onChange={onChange} />
    );
    await openSection(user, "Execution");
    expect(screen.getByTestId("tool-response-default-chars")).toHaveValue(50000);
  });

  it("shows tool response strategy select", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <LlmEditor data={executionConfig} onChange={onChange} />
    );
    await openSection(user, "Execution");
    expect(screen.getByTestId("tool-response-strategy")).toHaveValue("truncate");
  });

  it("shows rate limiting controls when enabled", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <LlmEditor data={executionConfig} onChange={onChange} />
    );
    await openSection(user, "Execution");
    expect(screen.getByText("Rate Limiting")).toBeInTheDocument();
    expect(screen.getByText("Per-Tool Rate Limits")).toBeInTheDocument();
  });

  it("changes tool response strategy to summarize", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <LlmEditor data={executionConfig} onChange={onChange} />
    );
    await openSection(user, "Execution");
    await user.selectOptions(
      screen.getByTestId("tool-response-strategy"),
      "summarize"
    );
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        tasks: [
          expect.objectContaining({
            toolResponseLimits: expect.objectContaining({
              truncationStrategy: "summarize",
            }),
          }),
        ],
      })
    );
  });

  // ── Budget section ──────────────────────────────────────────────────────────

  it("shows budget controls after opening Budget section", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <LlmEditor data={executionConfig} onChange={onChange} />
    );
    await openSection(user, "Budget & Costs");
    expect(screen.getByText("Cost Tracking")).toBeInTheDocument();
    expect(screen.getByText("Tool Caching")).toBeInTheDocument();
  });

  // ── System prompt preview ───────────────────────────────────────────────────

  it("toggles system prompt preview mode", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <LlmEditor data={populatedConfig} onChange={onChange} />
    );
    const toggle = screen.getByTestId("system-prompt-preview-toggle");
    expect(toggle).toHaveTextContent("Preview");
    await user.click(toggle);
    // After clicking, should switch to "Edit" label
    expect(screen.getByText("Edit")).toBeInTheDocument();
  });

  // ── ReadOnly mode ───────────────────────────────────────────────────────────

  it("hides remove task button in readOnly mode", () => {
    renderWithProviders(
      <LlmEditor data={populatedConfig} onChange={onChange} readOnly />
    );
    expect(screen.queryByRole("button", { name: "Remove Task" })).not.toBeInTheDocument();
  });

  it("disables model type select in readOnly mode", () => {
    renderWithProviders(
      <LlmEditor data={populatedConfig} onChange={onChange} readOnly />
    );
    expect(screen.getByTestId("model-type-select")).toBeDisabled();
  });

  // ── Add Parameter ───────────────────────────────────────────────────────────

  it("adds a new model parameter", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <LlmEditor data={populatedConfig} onChange={onChange} />
    );
    await openSection(user, "Model Parameters");
    await user.click(screen.getByText("Add Parameter"));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        tasks: [
          expect.objectContaining({
            parameters: expect.objectContaining({
              model: "gpt-4o",
              // A new param key like "param3" was added
            }),
          }),
        ],
      })
    );
  });

  // ── Pre/Post Instructions ──────────────────────────────────────────────────

  it("shows Pre/Post Instructions section when configured", () => {
    const config: LlmConfig = {
      tasks: [
        {
          type: "openai",
          actions: [],
          parameters: { systemMessage: "" },
          preRequest: {
            propertyInstructions: [
              {
                name: "context.testProp",
                value: "testValue",
                scope: "step",
                valueType: "string",
              },
            ],
          },
        },
      ],
    };
    renderWithProviders(<LlmEditor data={config} onChange={onChange} />);
    expect(screen.getByTestId("pre-post-section")).toBeInTheDocument();
  });

  // ── Counterweight strict mode ────────────────────────────────────────────

  it("shows strict mode info banner when level is strict", () => {
    const strictConfig: LlmConfig = {
      tasks: [
        {
          type: "openai",
          actions: [],
          parameters: { systemMessage: "" },
          counterweight: {
            enabled: true,
            level: "strict",
            placement: "suffix",
          },
        },
      ],
    };
    renderWithProviders(
      <LlmEditor data={strictConfig} onChange={onChange} />
    );
    expect(
      screen.getByText(/Strict mode is automatically downgraded/i)
    ).toBeInTheDocument();
  });

  it("does not show strict mode info when level is cautious", () => {
    renderWithProviders(
      <LlmEditor data={safetyConfig} onChange={onChange} />
    );
    expect(
      screen.queryByText(/Strict mode is automatically downgraded/i)
    ).not.toBeInTheDocument();
  });

  // ── Counterweight custom instructions ────────────────────────────────────

  it("shows custom instructions hint when none exist", () => {
    renderWithProviders(
      <LlmEditor data={safetyConfig} onChange={onChange} />
    );
    expect(
      screen.getByText("Leave empty to use the built-in preset for the selected level.")
    ).toBeInTheDocument();
  });

  it("shows custom instructions override banner when instructions exist", () => {
    const configWithCustom: LlmConfig = {
      tasks: [
        {
          type: "openai",
          actions: [],
          parameters: { systemMessage: "" },
          counterweight: {
            enabled: true,
            level: "cautious",
            customInstructions: ["Do not hallucinate"],
          },
        },
      ],
    };
    renderWithProviders(
      <LlmEditor data={configWithCustom} onChange={onChange} />
    );
    expect(
      screen.getByText("Custom instructions override the preset level text entirely.")
    ).toBeInTheDocument();
  });

  it("adds a custom counterweight instruction", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <LlmEditor data={safetyConfig} onChange={onChange} />
    );
    await user.click(screen.getByText("Add Instruction"));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        tasks: [
          expect.objectContaining({
            counterweight: expect.objectContaining({
              customInstructions: [""],
            }),
          }),
        ],
      })
    );
  });

  // ── Identity masking empty rules warning ─────────────────────────────────

  it("shows empty rules warning when masking enabled with no rules", () => {
    const maskingNoRules: LlmConfig = {
      tasks: [
        {
          type: "openai",
          actions: [],
          parameters: { systemMessage: "" },
          identityMasking: {
            enabled: true,
            rules: [],
          },
        },
      ],
    };
    renderWithProviders(
      <LlmEditor data={maskingNoRules} onChange={onChange} />
    );
    expect(
      screen.getByText(/Masking is enabled but has no rules/i)
    ).toBeInTheDocument();
  });

  it("does not show empty rules warning when rules exist", () => {
    renderWithProviders(
      <LlmEditor data={safetyConfig} onChange={onChange} />
    );
    expect(
      screen.queryByText(/Masking is enabled but has no rules/i)
    ).not.toBeInTheDocument();
  });

  it("adds an identity masking rule", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <LlmEditor data={safetyConfig} onChange={onChange} />
    );
    await user.click(screen.getByText("Add Rule"));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        tasks: [
          expect.objectContaining({
            identityMasking: expect.objectContaining({
              rules: ["Never reveal you are an AI", ""],
            }),
          }),
        ],
      })
    );
  });

  it("enables identity masking from disabled state", async () => {
    const user = userEvent.setup();
    const noSafetyConfig: LlmConfig = {
      tasks: [
        {
          type: "openai",
          actions: [],
          parameters: { systemMessage: "" },
        },
      ],
    };
    renderWithProviders(
      <LlmEditor data={noSafetyConfig} onChange={onChange} />
    );
    await openSection(user, "Prompt Safety");
    await user.click(screen.getByTestId("identity-masking-enabled"));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        tasks: [
          expect.objectContaining({
            identityMasking: expect.objectContaining({ enabled: true }),
          }),
        ],
      })
    );
  });

  // ── Budget section interactions ──────────────────────────────────────────

  it("shows max budget input with value", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <LlmEditor data={executionConfig} onChange={onChange} />
    );
    await openSection(user, "Budget & Costs");
    expect(screen.getByDisplayValue("5")).toBeInTheDocument();
  });

  it("shows cost tracking checkbox checked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <LlmEditor data={executionConfig} onChange={onChange} />
    );
    await openSection(user, "Budget & Costs");
    expect(screen.getByText("Cost Tracking")).toBeInTheDocument();
  });

  it("shows tool caching checkbox checked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <LlmEditor data={executionConfig} onChange={onChange} />
    );
    await openSection(user, "Budget & Costs");
    expect(screen.getByText("Tool Caching")).toBeInTheDocument();
  });

  // ── Execution section: parallel timeout ──────────────────────────────────

  it("shows parallel execution timeout when parallel is enabled", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <LlmEditor data={executionConfig} onChange={onChange} />
    );
    await openSection(user, "Execution");
    expect(screen.getByDisplayValue("60000")).toBeInTheDocument();
  });

  it("shows max tool iterations input", async () => {
    const user = userEvent.setup();
    const configWithMaxIter: LlmConfig = {
      tasks: [
        {
          type: "openai",
          actions: [],
          parameters: { systemMessage: "" },
          maxToolIterations: 15,
        },
      ],
    };
    renderWithProviders(
      <LlmEditor data={configWithMaxIter} onChange={onChange} />
    );
    await openSection(user, "Execution");
    expect(screen.getByDisplayValue("15")).toBeInTheDocument();
  });

  // ── Execution section: rate limiting ─────────────────────────────────────

  it("shows default rate limit input when rate limiting enabled", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <LlmEditor data={executionConfig} onChange={onChange} />
    );
    await openSection(user, "Execution");
    expect(screen.getByDisplayValue("100")).toBeInTheDocument();
  });

  it("shows per-tool rate limit entries", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <LlmEditor data={executionConfig} onChange={onChange} />
    );
    await openSection(user, "Execution");
    expect(screen.getByDisplayValue("my_tool")).toBeInTheDocument();
    expect(screen.getByDisplayValue("50")).toBeInTheDocument();
  });

  it("adds a tool rate limit entry", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <LlmEditor data={executionConfig} onChange={onChange} />
    );
    await openSection(user, "Execution");
    await user.click(screen.getByText("Add Tool Rate"));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        tasks: [
          expect.objectContaining({
            toolRateLimits: expect.objectContaining({
              my_tool: 50,
              tool: 100,
            }),
          }),
        ],
      })
    );
  });

  // ── Execution section: per-tool response limits ──────────────────────────

  it("shows per-tool response limit entries", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <LlmEditor data={executionConfig} onChange={onChange} />
    );
    await openSection(user, "Execution");
    expect(screen.getByDisplayValue("heavy_tool")).toBeInTheDocument();
    expect(screen.getByDisplayValue("10000")).toBeInTheDocument();
  });

  it("adds a per-tool response limit entry", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <LlmEditor data={executionConfig} onChange={onChange} />
    );
    await openSection(user, "Execution");
    await user.click(screen.getByText("Add Tool Limit"));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        tasks: [
          expect.objectContaining({
            toolResponseLimits: expect.objectContaining({
              perToolLimits: expect.objectContaining({
                heavy_tool: 10000,
                tool: 50000,
              }),
            }),
          }),
        ],
      })
    );
  });

  // ── Execution section: summarizer model ──────────────────────────────────

  it("shows summarizer model input when strategy is summarize", async () => {
    const user = userEvent.setup();
    const summarizeConfig: LlmConfig = {
      tasks: [
        {
          type: "openai",
          actions: [],
          parameters: { systemMessage: "" },
          toolResponseLimits: {
            truncationStrategy: "summarize",
            summarizerModel: "gpt-4o-mini",
          },
        },
      ],
    };
    renderWithProviders(
      <LlmEditor data={summarizeConfig} onChange={onChange} />
    );
    await openSection(user, "Execution");
    expect(screen.getByDisplayValue("gpt-4o-mini")).toBeInTheDocument();
    expect(
      screen.getByText(/The summarizer uses the same provider/i)
    ).toBeInTheDocument();
  });

  it("does not show summarizer model input when strategy is truncate", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <LlmEditor data={executionConfig} onChange={onChange} />
    );
    await openSection(user, "Execution");
    expect(
      screen.queryByText(/The summarizer uses the same provider/i)
    ).not.toBeInTheDocument();
  });

  // ── Counterweight placement change ───────────────────────────────────────

  it("changes counterweight placement to prefix", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <LlmEditor data={safetyConfig} onChange={onChange} />
    );
    await user.selectOptions(screen.getByTestId("counterweight-placement"), "prefix");
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        tasks: [
          expect.objectContaining({
            counterweight: expect.objectContaining({ placement: "prefix" }),
          }),
        ],
      })
    );
  });

  // ── Tool response default chars change ───────────────────────────────────

  it("shows tool response limits section header", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <LlmEditor data={executionConfig} onChange={onChange} />
    );
    await openSection(user, "Execution");
    expect(screen.getByText("Tool Response Limits")).toBeInTheDocument();
  });

  // ── A2A agent name and timeout ───────────────────────────────────────────

  it("shows A2A agent display name", () => {
    renderWithProviders(
      <LlmEditor data={agentConfig} onChange={onChange} />
    );
    expect(screen.getByDisplayValue("Remote Agent")).toBeInTheDocument();
  });

  it("shows A2A agent timeout value", () => {
    renderWithProviders(
      <LlmEditor data={agentConfig} onChange={onChange} />
    );
    expect(screen.getByDisplayValue("30000")).toBeInTheDocument();
  });
});
