import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { PromptPreview } from "@/components/editors/prompt-preview";

// ── Shared mock state ──────────────────────────────────────────────────────

const mockPreview = vi.fn();
let mockHookReturn: {
  mutate: typeof mockPreview;
  data: unknown;
  isPending: boolean;
  error: unknown;
};

vi.mock("@/hooks/use-template-preview", () => ({
  useTemplatePreview: () => mockHookReturn,
}));

vi.mock("@/lib/api/conversations", () => ({
  getConversationDescriptors: vi.fn().mockResolvedValue([]),
  parseConversationUri: vi.fn((uri: string) => uri.split("/").pop() ?? uri),
}));

describe("PromptPreview", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockHookReturn = {
      mutate: mockPreview,
      data: null,
      isPending: false,
      error: null,
    };
  });

  it("renders with data-testid prompt-preview", () => {
    renderWithProviders(<PromptPreview template="Hello {name}" />);
    expect(screen.getByTestId("prompt-preview")).toBeInTheDocument();
  });

  it("shows conversation picker", () => {
    renderWithProviders(<PromptPreview template="Hello {name}" />);
    expect(
      screen.getByTestId("preview-conversation-picker")
    ).toBeInTheDocument();
  });

  it("shows refresh button", () => {
    renderWithProviders(<PromptPreview template="Hello {name}" />);
    expect(screen.getByTestId("preview-refresh")).toBeInTheDocument();
  });

  it("shows sample data disclaimer when no conversation selected", () => {
    renderWithProviders(<PromptPreview template="Hello {name}" />);
    expect(
      screen.getByText(/Preview uses the real Qute engine with built-in sample data/)
    ).toBeInTheDocument();
  });

  it("triggers preview on mount", () => {
    renderWithProviders(<PromptPreview template="Hello {name}" />);
    expect(mockPreview).toHaveBeenCalledWith({
      template: "Hello {name}",
      conversationId: undefined,
    });
  });

  it("does not trigger preview when template is empty", () => {
    renderWithProviders(<PromptPreview template="" />);
    expect(mockPreview).not.toHaveBeenCalled();
  });

  it("shows refresh button with Refresh text", () => {
    renderWithProviders(<PromptPreview template="Hello" />);
    expect(screen.getByText("Refresh")).toBeInTheDocument();
  });
});

describe("PromptPreview with resolved data", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockHookReturn = {
      mutate: mockPreview,
      data: {
        resolved: "Hello World",
        availableVariables: ["properties.name"],
        variableValues: { "properties.name": "World" },
      },
      isPending: false,
      error: null,
    };
  });

  it("shows resolved prompt", () => {
    renderWithProviders(<PromptPreview template="Hello {properties.name}" />);
    expect(screen.getByTestId("resolved-prompt")).toBeInTheDocument();
    expect(screen.getByText("Resolved Prompt")).toBeInTheDocument();
  });

  it("shows copy button", () => {
    renderWithProviders(<PromptPreview template="Hello {properties.name}" />);
    expect(screen.getByTestId("preview-copy")).toBeInTheDocument();
    expect(screen.getByText("Copy")).toBeInTheDocument();
  });

  it("shows resolved prompt content", () => {
    renderWithProviders(<PromptPreview template="Hello {properties.name}" />);
    expect(screen.getByTestId("resolved-prompt-content")).toBeInTheDocument();
  });

  it("shows variable reference panel", () => {
    renderWithProviders(<PromptPreview template="Hello {properties.name}" />);
    expect(screen.getByTestId("variable-reference")).toBeInTheDocument();
    expect(screen.getByText("Available Variables")).toBeInTheDocument();
  });

  it("shows variable count badge", () => {
    renderWithProviders(<PromptPreview template="Hello {properties.name}" />);
    expect(screen.getByText("1")).toBeInTheDocument();
  });

  it("toggles variable reference panel open", async () => {
    const user = userEvent.setup();
    renderWithProviders(<PromptPreview template="Hello {properties.name}" />);
    await user.click(screen.getByText("Available Variables"));
    // Should show variable chip
    expect(screen.getByTestId("var-chip-properties.name")).toBeInTheDocument();
    expect(screen.getByText("{properties.name}")).toBeInTheDocument();
  });

  it("groups variables by prefix", async () => {
    const user = userEvent.setup();
    mockHookReturn.data = {
      resolved: "Hello World",
      availableVariables: ["properties.name", "memory.count"],
      variableValues: { "properties.name": "World", "memory.count": 5 },
    };
    renderWithProviders(<PromptPreview template="Hello {properties.name}" />);
    await user.click(screen.getByText("Available Variables"));
    expect(screen.getByText("Properties")).toBeInTheDocument();
    expect(screen.getByText("Memory")).toBeInTheDocument();
  });
});

describe("PromptPreview with error", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockHookReturn = {
      mutate: mockPreview,
      data: {
        error: "Template syntax error at line 3",
        resolved: null,
        availableVariables: [],
        variableValues: {},
      },
      isPending: false,
      error: null,
    };
  });

  it("shows error state when data has error", () => {
    renderWithProviders(<PromptPreview template="Hello {invalid" />);
    expect(screen.getByTestId("preview-error")).toBeInTheDocument();
    expect(screen.getByText("Template resolution error")).toBeInTheDocument();
    expect(screen.getByText("Template syntax error at line 3")).toBeInTheDocument();
  });
});

describe("PromptPreview with mutation error", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockHookReturn = {
      mutate: mockPreview,
      data: null,
      isPending: false,
      error: new Error("Network failure"),
    };
  });

  it("shows mutation error", () => {
    renderWithProviders(<PromptPreview template="Hello {name}" />);
    expect(screen.getByTestId("preview-error")).toBeInTheDocument();
    expect(screen.getByText("Network failure")).toBeInTheDocument();
  });
});

describe("PromptPreview loading state", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockHookReturn = {
      mutate: mockPreview,
      data: null,
      isPending: true,
      error: null,
    };
  });

  it("shows loading state", () => {
    renderWithProviders(<PromptPreview template="Hello {name}" />);
    expect(screen.getByText(/Resolving template/)).toBeInTheDocument();
  });
});
