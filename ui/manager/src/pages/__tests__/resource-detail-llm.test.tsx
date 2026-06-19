import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderPage } from "@/test/test-utils";
import { ResourceDetailPage } from "@/pages/resource-detail";

function renderLlmPage(id = "res1") {
  return renderPage(
    `/manage/resources/llm/${id}`,
    <ResourceDetailPage />,
    "/manage/resources/:type/:id"
  );
}

describe("LangChain Editor", () => {
  // ─── Basic Rendering ─────────────────────────────────────────

  it("renders LLM editor with tasks", async () => {
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByTestId("llm-editor")).toBeInTheDocument();
    });
  });

  it("renders task card with model type select", async () => {
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getAllByTestId("llm-task-editor").length).toBeGreaterThan(0);
    });
  });

  it("renders model type dropdown with openai selected", async () => {
    renderLlmPage();
    await waitFor(() => {
      const selects = screen.getAllByTestId("model-type-select");
      expect(selects.length).toBeGreaterThan(0);
      expect((selects[0] as HTMLSelectElement).value).toBe("openai");
    });
  });

  it("renders mode badge as Agent (has tools)", async () => {
    renderLlmPage();
    await waitFor(() => {
      const badges = screen.getAllByTestId("mode-badge");
      expect(badges.length).toBeGreaterThan(0);
      expect(badges[0]).toHaveTextContent("Agent");
    });
  });

  it("renders system prompt content editor", async () => {
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getAllByTestId("system-prompt").length).toBeGreaterThan(0);
    });
  });

  it("renders enable built-in tools checkbox (checked)", async () => {
    renderLlmPage();
    await waitFor(() => {
      const checkboxes = screen.getAllByTestId("enable-builtin-tools");
      expect(checkboxes.length).toBeGreaterThan(0);
      expect((checkboxes[0] as HTMLInputElement).checked).toBe(true);
    });
  });

  it("renders add task button", async () => {
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByTestId("add-task-btn")).toBeInTheDocument();
    });
  });

  it("renders form tab as default", async () => {
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByTestId("form-view")).toBeInTheDocument();
    });
  });

  it("renders add A2A agent button", async () => {
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByTestId("add-a2a-agent")).toBeInTheDocument();
    });
  });

  it("renders A2A agent config from mock data", async () => {
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByTestId("a2a-agent-0")).toBeInTheDocument();
    });
  });

  it("shows A2A Agents section header", async () => {
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByText("A2A Agents")).toBeInTheDocument();
    });
  });

  it("renders A2A agent URL from mock data", async () => {
    renderLlmPage();
    await waitFor(() => {
      const urlInput = screen.getByDisplayValue("https://remote.example.com/a2a/agents/support");
      expect(urlInput).toBeInTheDocument();
    });
  });

  it("renders A2A agent name from mock data", async () => {
    renderLlmPage();
    await waitFor(() => {
      const nameInput = screen.getByDisplayValue("Support Agent");
      expect(nameInput).toBeInTheDocument();
    });
  });

  it("renders model cascade section header", async () => {
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByText("Model Cascade")).toBeInTheDocument();
    });
  });

  it("renders cascade enable toggle checked", async () => {
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByTestId("cascade-enable")).toBeInTheDocument();
    });
    const toggle = screen.getByTestId("cascade-enable") as HTMLInputElement;
    expect(toggle.checked).toBe(true);
  });

  it("renders cascade step from mock data", async () => {
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByDisplayValue("gpt-5.4-mini")).toBeInTheDocument();
    });
  });

  it("renders budget and costs section header", async () => {
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByText("Budget & Costs")).toBeInTheDocument();
    });
  });

  it("renders execution section header", async () => {
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByText("Execution")).toBeInTheDocument();
    });
  });

  it("renders retry section header", async () => {
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByText("Retry Configuration")).toBeInTheDocument();
    });
  });

  it("renders RAG section header", async () => {
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByText("RAG (Knowledge Retrieval)")).toBeInTheDocument();
    });
  });

  // ─── Prompt Safety Section ──────────────────────────────────

  it("renders Prompt Safety section header", async () => {
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByText("Prompt Safety")).toBeInTheDocument();
    });
  });

  it("renders counterweight toggle from mock data (enabled)", async () => {
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByTestId("counterweight-enabled")).toBeInTheDocument();
    });
    const toggle = screen.getByTestId("counterweight-enabled") as HTMLInputElement;
    expect(toggle.checked).toBe(true);
  });

  it("renders counterweight level dropdown with cautious selected", async () => {
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByTestId("counterweight-level")).toBeInTheDocument();
    });
    const select = screen.getByTestId("counterweight-level") as HTMLSelectElement;
    expect(select.value).toBe("cautious");
  });

  it("renders identity masking toggle (disabled by default)", async () => {
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByTestId("identity-masking-enabled")).toBeInTheDocument();
    });
    const toggle = screen.getByTestId("identity-masking-enabled") as HTMLInputElement;
    expect(toggle.checked).toBe(false);
  });

  it("renders tool response strategy dropdown with truncate", async () => {
    const user = userEvent.setup();
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByText("Execution")).toBeInTheDocument();
    });
    await user.click(screen.getByText("Execution"));
    await waitFor(() => {
      expect(screen.getByTestId("tool-response-strategy")).toBeInTheDocument();
    });
    const select = screen.getByTestId("tool-response-strategy") as HTMLSelectElement;
    expect(select.value).toBe("truncate");
  });

  // ─── RAG Section Interaction Tests ──────────────────────────

  it("renders httpCall RAG input when RAG section is expanded", async () => {
    const user = userEvent.setup();
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByText("RAG (Knowledge Retrieval)")).toBeInTheDocument();
    });

    await user.click(screen.getByText("RAG (Knowledge Retrieval)"));

    await waitFor(() => {
      expect(screen.getByTestId("httpcall-rag")).toBeInTheDocument();
    });
  });

  it("renders enable-workflow-rag checkbox when RAG section is expanded", async () => {
    const user = userEvent.setup();
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByText("RAG (Knowledge Retrieval)")).toBeInTheDocument();
    });

    await user.click(screen.getByText("RAG (Knowledge Retrieval)"));

    await waitFor(() => {
      expect(screen.getByTestId("enable-workflow-rag")).toBeInTheDocument();
    });
  });

  it("renders add KB reference button", async () => {
    const user = userEvent.setup();
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByText("RAG (Knowledge Retrieval)")).toBeInTheDocument();
    });

    await user.click(screen.getByText("RAG (Knowledge Retrieval)"));

    await waitFor(() => {
      expect(screen.getByTestId("add-kb-ref")).toBeInTheDocument();
    });
  });

  it("adds a KB reference when button is clicked", async () => {
    const user = userEvent.setup();
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByText("RAG (Knowledge Retrieval)")).toBeInTheDocument();
    });

    await user.click(screen.getByText("RAG (Knowledge Retrieval)"));

    await waitFor(() => {
      expect(screen.getByTestId("add-kb-ref")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("add-kb-ref"));

    await waitFor(() => {
      expect(screen.getByTestId("kb-name-0")).toBeInTheDocument();
    });
  });

  // ─── Interaction tests ───────────────────────────────────────────────

  it("clicking add task button adds a new task editor", async () => {
    const user = userEvent.setup();
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByTestId("add-task-btn")).toBeInTheDocument();
    });

    const initialTasks = screen.getAllByTestId("llm-task-editor").length;
    await user.click(screen.getByTestId("add-task-btn"));

    await waitFor(() => {
      expect(screen.getAllByTestId("llm-task-editor").length).toBe(
        initialTasks + 1
      );
    });
  });

  it("changing model type to azure-openai", async () => {
    const user = userEvent.setup();
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getAllByTestId("model-type-select").length).toBeGreaterThan(0);
    });

    const select = screen.getAllByTestId("model-type-select")[0] as HTMLSelectElement;
    await user.selectOptions(select, "azure-openai");

    await waitFor(() => {
      expect(select.value).toBe("azure-openai");
    });
  });

  it("switching to JSON tab shows JSON view", async () => {
    const user = userEvent.setup();
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByTestId("tab-json")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("tab-json"));

    await waitFor(() => {
      expect(screen.getByTestId("json-view")).toBeInTheDocument();
    });
  });

  it("changing counterweight level dropdown to strict", async () => {
    const user = userEvent.setup();
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByTestId("counterweight-level")).toBeInTheDocument();
    });

    const select = screen.getByTestId("counterweight-level") as HTMLSelectElement;
    await user.selectOptions(select, "strict");

    await waitFor(() => {
      expect(select.value).toBe("strict");
    });
  });

  it("toggling identity masking enables it", async () => {
    const user = userEvent.setup();
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByTestId("identity-masking-enabled")).toBeInTheDocument();
    });

    const toggle = screen.getByTestId("identity-masking-enabled") as HTMLInputElement;
    expect(toggle.checked).toBe(false);

    await user.click(toggle);

    await waitFor(() => {
      expect(toggle.checked).toBe(true);
    });
  });

  it("toggling counterweight to disabled", async () => {
    const user = userEvent.setup();
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByTestId("counterweight-enabled")).toBeInTheDocument();
    });

    const toggle = screen.getByTestId("counterweight-enabled") as HTMLInputElement;
    expect(toggle.checked).toBe(true);

    await user.click(toggle);

    await waitFor(() => {
      expect(toggle.checked).toBe(false);
    });
  });

  it("marks dirty indicator when task description is changed", async () => {
    const user = userEvent.setup();
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getAllByTestId("llm-task-editor").length).toBeGreaterThan(0);
    });

    // The description input shows "Main AI assistant task" from mock data
    const descInput = screen.getByDisplayValue("Main AI assistant task");
    await user.clear(descInput);
    await user.type(descInput, "Changed description");

    await waitFor(() => {
      expect(screen.getByTestId("dirty-indicator")).toBeInTheDocument();
    });
  });

  it("renders add A2A agent button and clicking it adds an agent row", async () => {
    const user = userEvent.setup();
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByTestId("add-a2a-agent")).toBeInTheDocument();
    });

    const initialAgents = screen.getAllByTestId(/^a2a-agent-/).length;
    await user.click(screen.getByTestId("add-a2a-agent"));

    await waitFor(() => {
      expect(screen.getAllByTestId(/^a2a-agent-/).length).toBe(initialAgents + 1);
    });
  });

  it("changing tool response strategy to paginate", async () => {
    const user = userEvent.setup();
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByText("Execution")).toBeInTheDocument();
    });
    await user.click(screen.getByText("Execution"));
    await waitFor(() => {
      expect(screen.getByTestId("tool-response-strategy")).toBeInTheDocument();
    });

    const select = screen.getByTestId("tool-response-strategy") as HTMLSelectElement;
    await user.selectOptions(select, "paginate");

    await waitFor(() => {
      expect(select.value).toBe("paginate");
    });
  });

  // ─── NEW: Coverage expansion tests ──────────────────────────────────

  it("disables built-in tools checkbox and hides tool selection", async () => {
    const user = userEvent.setup();
    renderLlmPage();
    await waitFor(() => {
      const checkboxes = screen.getAllByTestId("enable-builtin-tools");
      expect((checkboxes[0] as HTMLInputElement).checked).toBe(true);
    });

    await user.click(screen.getAllByTestId("enable-builtin-tools")[0]!);

    await waitFor(() => {
      expect((screen.getAllByTestId("enable-builtin-tools")[0] as HTMLInputElement).checked).toBe(false);
    });
    // Tool selection mode should not be visible when unchecked
    expect(screen.queryByTestId("tool-selection-mode")).not.toBeInTheDocument();
  });

  it("switches to Select Specific tool mode and shows tool chips", async () => {
    userEvent.setup();
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByTestId("tool-mode-specific")).toBeInTheDocument();
    });

    // Mock data has builtInToolsWhitelist: ["calculator", "datetime"]
    // so it's already in "specific" mode
    const specificBtn = screen.getByTestId("tool-mode-specific");
    expect(specificBtn.getAttribute("aria-checked")).toBe("true");

    // The tool chip for "calculator" should be visible and pressed
    expect(screen.getByTestId("tool-chip-calculator")).toBeInTheDocument();
    expect(screen.getByTestId("tool-chip-calculator").getAttribute("aria-pressed")).toBe("true");
  });

  it("clicking All Tools mode clears built-in tool whitelist", async () => {
    const user = userEvent.setup();
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByTestId("tool-mode-all")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("tool-mode-all"));

    await waitFor(() => {
      // All tools info callout should now be visible
      expect(screen.getByTestId("all-tools-info")).toBeInTheDocument();
    });
  });

  it("toggles a tool chip off and on in specific mode", async () => {
    const user = userEvent.setup();
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByTestId("tool-chip-calculator")).toBeInTheDocument();
    });

    // Calculator should be selected (aria-pressed=true)
    const chip = screen.getByTestId("tool-chip-calculator");
    expect(chip.getAttribute("aria-pressed")).toBe("true");

    // Click to deselect
    await user.click(chip);

    await waitFor(() => {
      expect(screen.getByTestId("tool-chip-calculator").getAttribute("aria-pressed")).toBe("false");
    });
  });

  it("unchecks enable-httpcall-tools checkbox", async () => {
    const user = userEvent.setup();
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByTestId("enable-httpcall-tools")).toBeInTheDocument();
    });

    const checkbox = screen.getByTestId("enable-httpcall-tools") as HTMLInputElement;
    expect(checkbox.checked).toBe(true);

    await user.click(checkbox);

    await waitFor(() => {
      expect(checkbox.checked).toBe(false);
    });
  });

  it("unchecks enable-mcpcall-tools checkbox", async () => {
    const user = userEvent.setup();
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByTestId("enable-mcpcall-tools")).toBeInTheDocument();
    });

    const checkbox = screen.getByTestId("enable-mcpcall-tools") as HTMLInputElement;
    expect(checkbox.checked).toBe(true);

    await user.click(checkbox);

    await waitFor(() => {
      expect(checkbox.checked).toBe(false);
    });
  });

  it("renders system prompt preview toggle and toggles preview mode", async () => {
    const user = userEvent.setup();
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByTestId("system-prompt-preview-toggle")).toBeInTheDocument();
    });

    // Initially in edit mode
    expect(screen.getByTestId("system-prompt-preview-toggle")).toHaveTextContent("Preview");

    await user.click(screen.getByTestId("system-prompt-preview-toggle"));

    await waitFor(() => {
      expect(screen.getByTestId("system-prompt-preview-toggle")).toHaveTextContent("Edit");
    });
  });

  it("expands Budget & Costs section and shows cost tracking fields", async () => {
    const user = userEvent.setup();
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByText("Budget & Costs")).toBeInTheDocument();
    });

    await user.click(screen.getByText("Budget & Costs"));

    await waitFor(() => {
      // Verify cost tracking and tool caching labels are visible
      expect(screen.getByText("Cost Tracking")).toBeInTheDocument();
      expect(screen.getByText("Tool Caching")).toBeInTheDocument();
    });
  });

  it("expands Execution section and shows parallel execution checkbox", async () => {
    const user = userEvent.setup();
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByText("Execution")).toBeInTheDocument();
    });

    await user.click(screen.getByText("Execution"));

    await waitFor(() => {
      expect(screen.getByTestId("enable-parallel-execution")).toBeInTheDocument();
    });
  });

  it("enables parallel execution checkbox", async () => {
    const user = userEvent.setup();
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByText("Execution")).toBeInTheDocument();
    });

    await user.click(screen.getByText("Execution"));

    await waitFor(() => {
      expect(screen.getByTestId("enable-parallel-execution")).toBeInTheDocument();
    });

    const checkbox = screen.getByTestId("enable-parallel-execution") as HTMLInputElement;
    expect(checkbox.checked).toBe(false);

    await user.click(checkbox);

    await waitFor(() => {
      expect(checkbox.checked).toBe(true);
    });
  });

  it("sets counterweight level to strict and shows strict info note", async () => {
    const user = userEvent.setup();
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByTestId("counterweight-level")).toBeInTheDocument();
    });

    await user.selectOptions(screen.getByTestId("counterweight-level"), "strict");

    await waitFor(() => {
      // Strict mode should show the info warning about batch execution downgrade
      expect(screen.getByText(/automatically downgraded to Cautious/)).toBeInTheDocument();
    });
  });

  it("renders counterweight placement dropdown with suffix selected", async () => {
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByTestId("counterweight-placement")).toBeInTheDocument();
    });
    const select = screen.getByTestId("counterweight-placement") as HTMLSelectElement;
    expect(select.value).toBe("suffix");
  });

  it("changes counterweight placement to prefix", async () => {
    const user = userEvent.setup();
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByTestId("counterweight-placement")).toBeInTheDocument();
    });

    await user.selectOptions(screen.getByTestId("counterweight-placement"), "prefix");

    await waitFor(() => {
      expect((screen.getByTestId("counterweight-placement") as HTMLSelectElement).value).toBe("prefix");
    });
  });

  it("renders pre/post instructions section", async () => {
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByText("Pre/Post Instructions")).toBeInTheDocument();
    });
  });

  it("renders tool response default chars input", async () => {
    const user = userEvent.setup();
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByText("Execution")).toBeInTheDocument();
    });

    await user.click(screen.getByText("Execution"));

    await waitFor(() => {
      expect(screen.getByTestId("tool-response-default-chars")).toBeInTheDocument();
    });
    const input = screen.getByTestId("tool-response-default-chars") as HTMLInputElement;
    expect(input.value).toBe("50000");
  });

  it("renders tool sources info callout when both sources enabled", async () => {
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByTestId("tool-sources-info")).toBeInTheDocument();
    });
  });

  it("changes model type to ollama", async () => {
    const user = userEvent.setup();
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getAllByTestId("model-type-select").length).toBeGreaterThan(0);
    });

    const select = screen.getAllByTestId("model-type-select")[0] as HTMLSelectElement;
    await user.selectOptions(select, "ollama");

    await waitFor(() => {
      expect(select.value).toBe("ollama");
    });
  });

  it("changes model type to anthropic", async () => {
    const user = userEvent.setup();
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getAllByTestId("model-type-select").length).toBeGreaterThan(0);
    });

    const select = screen.getAllByTestId("model-type-select")[0] as HTMLSelectElement;
    await user.selectOptions(select, "anthropic");

    await waitFor(() => {
      expect(select.value).toBe("anthropic");
    });
  });

  it("renders action tags from mock data (help, chat)", async () => {
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByText("help")).toBeInTheDocument();
      expect(screen.getByText("chat")).toBeInTheDocument();
    });
  });

  it("renders tool URI from mock data", async () => {
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByDisplayValue("eddi://ai.labs.apicalls/apicallstore/apicalls/weather?version=1")).toBeInTheDocument();
    });
  });

  it("selecting summarize strategy shows summarizer model input", async () => {
    const user = userEvent.setup();
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByText("Execution")).toBeInTheDocument();
    });

    await user.click(screen.getByText("Execution"));

    await waitFor(() => {
      expect(screen.getByTestId("tool-response-strategy")).toBeInTheDocument();
    });

    await user.selectOptions(screen.getByTestId("tool-response-strategy"), "summarize");

    await waitFor(() => {
      expect(screen.getByText("Summarizer Model")).toBeInTheDocument();
    });
  });

  it("renders memory section header", async () => {
    renderLlmPage();
    await waitFor(() => {
      expect(screen.getByText("Conversation Memory")).toBeInTheDocument();
    });
  });
});
