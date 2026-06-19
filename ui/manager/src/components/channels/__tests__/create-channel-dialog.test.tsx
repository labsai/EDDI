import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { CreateChannelDialog } from "@/components/channels/create-channel-dialog";

describe("CreateChannelDialog", () => {
  it("renders nothing when open is false", () => {
    const { container } = renderWithProviders(
      <CreateChannelDialog open={false} onOpenChange={vi.fn()} />
    );
    // No dialog rendered
    expect(container.querySelector("[role='dialog']")).toBeNull();
  });

  it("renders dialog when open is true", () => {
    const { container } = renderWithProviders(
      <CreateChannelDialog open={true} onOpenChange={vi.fn()} />
    );
    expect(container.querySelector("[role='dialog']")).not.toBeNull();
    expect(screen.getByText("Create Channel Integration")).toBeInTheDocument();
  });

  it("shows step 1 with name and channel type fields", () => {
    renderWithProviders(
      <CreateChannelDialog open={true} onOpenChange={vi.fn()} />
    );
    expect(screen.getByTestId("create-channel-name")).toBeInTheDocument();
    expect(screen.getByTestId("create-channel-type")).toBeInTheDocument();
    expect(screen.getByText("Name")).toBeInTheDocument();
    expect(screen.getByText("Channel Type")).toBeInTheDocument();
  });

  it("disables Next button when name is empty", () => {
    renderWithProviders(
      <CreateChannelDialog open={true} onOpenChange={vi.fn()} />
    );
    const nextBtn = screen.getByTestId("create-channel-next");
    expect(nextBtn).toBeDisabled();
  });

  it("enables Next button when name is entered", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <CreateChannelDialog open={true} onOpenChange={vi.fn()} />
    );
    await user.type(screen.getByTestId("create-channel-name"), "My Channel");
    const nextBtn = screen.getByTestId("create-channel-next");
    expect(nextBtn).not.toBeDisabled();
  });

  it("navigates to step 2 (credentials) when Next is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <CreateChannelDialog open={true} onOpenChange={vi.fn()} />
    );
    await user.type(screen.getByTestId("create-channel-name"), "My Channel");
    await user.click(screen.getByTestId("create-channel-next"));

    // Step 2 shows channel ID field
    expect(screen.getByTestId("create-channel-id")).toBeInTheDocument();
    expect(screen.getByText("Slack Channel ID")).toBeInTheDocument();
  });

  it("navigates to step 3 (target) from step 2", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <CreateChannelDialog open={true} onOpenChange={vi.fn()} />
    );

    // Step 1: enter name
    await user.type(screen.getByTestId("create-channel-name"), "My Channel");
    await user.click(screen.getByTestId("create-channel-next"));

    // Step 2: enter channel ID
    await user.type(screen.getByTestId("create-channel-id"), "C0123ABC");
    await user.click(screen.getByTestId("create-channel-next"));

    // Step 3: target
    expect(screen.getByTestId("create-target-agent")).toBeInTheDocument();
    expect(screen.getByText("Default Agent ID")).toBeInTheDocument();
  });

  it("shows Back button on step 2", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <CreateChannelDialog open={true} onOpenChange={vi.fn()} />
    );
    await user.type(screen.getByTestId("create-channel-name"), "My Channel");
    await user.click(screen.getByTestId("create-channel-next"));

    expect(screen.getByText("Back")).toBeInTheDocument();
  });

  it("navigates back from step 2 to step 1", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <CreateChannelDialog open={true} onOpenChange={vi.fn()} />
    );
    await user.type(screen.getByTestId("create-channel-name"), "My Channel");
    await user.click(screen.getByTestId("create-channel-next"));

    // Click back
    await user.click(screen.getByText("Back"));
    expect(screen.getByTestId("create-channel-name")).toBeInTheDocument();
  });

  it("shows Create Channel button on step 3", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <CreateChannelDialog open={true} onOpenChange={vi.fn()} />
    );

    await user.type(screen.getByTestId("create-channel-name"), "My Channel");
    await user.click(screen.getByTestId("create-channel-next"));
    await user.type(screen.getByTestId("create-channel-id"), "C0123ABC");
    await user.click(screen.getByTestId("create-channel-next"));

    expect(screen.getByTestId("create-channel-submit")).toBeInTheDocument();
    expect(screen.getByText("Create Channel")).toBeInTheDocument();
  });

  it("uses defaultAgentId when provided", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <CreateChannelDialog
        open={true}
        onOpenChange={vi.fn()}
        defaultAgentId="agent-xyz"
      />
    );

    await user.type(screen.getByTestId("create-channel-name"), "My Channel");
    await user.click(screen.getByTestId("create-channel-next"));
    await user.type(screen.getByTestId("create-channel-id"), "C0123ABC");
    await user.click(screen.getByTestId("create-channel-next"));

    expect(screen.getByTestId("create-target-agent")).toHaveValue("agent-xyz");
  });

  it("shows step progress dots", () => {
    renderWithProviders(
      <CreateChannelDialog open={true} onOpenChange={vi.fn()} />
    );
    // 3 step dots
    const dots = document.querySelectorAll(".rounded-full.h-2.w-2");
    expect(dots.length).toBe(3);
  });

  it("shows hint text for channel ID field", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <CreateChannelDialog open={true} onOpenChange={vi.fn()} />
    );
    await user.type(screen.getByTestId("create-channel-name"), "My Channel");
    await user.click(screen.getByTestId("create-channel-next"));

    expect(
      screen.getByText(
        "Right-click a Slack channel → View channel details → copy the Channel ID"
      )
    ).toBeInTheDocument();
  });
});
