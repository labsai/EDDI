import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { AgentPicker } from "@/components/shared/agent-picker";

describe("AgentPicker", () => {
  const defaultProps = {
    value: "",
    onChange: vi.fn(),
  };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders search input when no value", () => {
    renderWithProviders(<AgentPicker {...defaultProps} />);
    expect(
      screen.getByPlaceholderText("Select Agent")
    ).toBeInTheDocument();
  });

  it("shows custom placeholder", () => {
    renderWithProviders(
      <AgentPicker {...defaultProps} placeholder="Pick an agent" />
    );
    expect(
      screen.getByPlaceholderText("Pick an agent")
    ).toBeInTheDocument();
  });

  it("shows selected agent chip when value is set", async () => {
    renderWithProviders(
      <AgentPicker {...defaultProps} value="agent1" />
    );
    // The agent ID is shown in the chip
    await waitFor(() => {
      expect(screen.getByText("agent1")).toBeInTheDocument();
    });
  });

  it("shows clear button when value is set and not readOnly", async () => {
    renderWithProviders(
      <AgentPicker {...defaultProps} value="agent1" />
    );
    await waitFor(() => {
      expect(
        screen.getByLabelText("Clear selection")
      ).toBeInTheDocument();
    });
  });

  it("hides clear button when readOnly", async () => {
    renderWithProviders(
      <AgentPicker {...defaultProps} value="agent1" readOnly />
    );
    await waitFor(() => {
      expect(screen.getByText("agent1")).toBeInTheDocument();
    });
    expect(
      screen.queryByLabelText("Clear selection")
    ).not.toBeInTheDocument();
  });

  it("calls onChange with empty string when clear is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <AgentPicker {...defaultProps} value="agent1" />
    );
    await waitFor(() => {
      expect(
        screen.getByLabelText("Clear selection")
      ).toBeInTheDocument();
    });
    await user.click(screen.getByLabelText("Clear selection"));
    expect(defaultProps.onChange).toHaveBeenCalledWith("");
  });

  it("opens popup when input is focused", async () => {
    const user = userEvent.setup();
    renderWithProviders(<AgentPicker {...defaultProps} />);
    const input = screen.getByPlaceholderText("Select Agent");
    await user.click(input);

    // Popup should show agents or loading
    await waitFor(() => {
      const popup = document.querySelector("[class*='absolute']");
      expect(popup).not.toBeNull();
    });
  });

  it("shows agent list in popup", async () => {
    const user = userEvent.setup();
    renderWithProviders(<AgentPicker {...defaultProps} />);
    const input = screen.getByPlaceholderText("Select Agent");
    await user.click(input);

    // Wait for agents to load
    await waitFor(() => {
      // Should find agent buttons with data-agent-item
      const items = document.querySelectorAll("[data-agent-item]");
      expect(items.length).toBeGreaterThan(0);
    });
  });

  it("calls onChange when agent is selected from popup", async () => {
    const user = userEvent.setup();
    renderWithProviders(<AgentPicker {...defaultProps} />);
    const input = screen.getByPlaceholderText("Select Agent");
    await user.click(input);

    await waitFor(() => {
      const items = document.querySelectorAll("[data-agent-item]");
      expect(items.length).toBeGreaterThan(0);
    });

    // Click first agent
    const firstItem = document.querySelector("[data-agent-item]") as HTMLElement;
    await user.click(firstItem);
    expect(defaultProps.onChange).toHaveBeenCalled();
  });

  it("does not open popup when readOnly", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <AgentPicker {...defaultProps} readOnly />
    );
    // readOnly with no value still shows input... but opening should be blocked
    // Actually, when readOnly=true and value is empty, it renders the input
    // with openPopup that returns early
    const input = screen.getByPlaceholderText("Select Agent");
    await user.click(input);

    // Should NOT show popup content
    const items = document.querySelectorAll("[data-agent-item]");
    expect(items.length).toBe(0);
  });
});
