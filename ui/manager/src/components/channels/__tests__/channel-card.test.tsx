import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { ChannelCard } from "@/components/channels/channel-card";
import type { EnrichedChannelDescriptor } from "@/lib/api/channels";

const baseChannel: EnrichedChannelDescriptor = {
  id: "ch-001",
  version: 1,
  name: "Support Slack",
  resource: "eddi://ai.labs.channel/channelstore/channels/ch-001?version=1",
  lastModifiedOn: Date.now() - 86400000,
  createdOn: Date.now() - 86400000 * 2,
  channelType: "slack",
  channelId: "C0123ABC",
  targetCount: 3,
};

describe("ChannelCard", () => {
  it("renders the channel name", () => {
    renderWithProviders(
      <ChannelCard
        channel={baseChannel}
        onDelete={vi.fn()}
        onDuplicate={vi.fn()}
      />
    );
    expect(screen.getByText("Support Slack")).toBeInTheDocument();
  });

  it("renders the channel ID hash", () => {
    renderWithProviders(
      <ChannelCard
        channel={baseChannel}
        onDelete={vi.fn()}
        onDuplicate={vi.fn()}
      />
    );
    expect(screen.getByText("C0123ABC")).toBeInTheDocument();
  });

  it("has correct data-testid", () => {
    renderWithProviders(
      <ChannelCard
        channel={baseChannel}
        onDelete={vi.fn()}
        onDuplicate={vi.fn()}
      />
    );
    expect(screen.getByTestId("channel-card-ch-001")).toBeInTheDocument();
  });

  it("shows type badge with capitalized label", () => {
    renderWithProviders(
      <ChannelCard
        channel={baseChannel}
        onDelete={vi.fn()}
        onDuplicate={vi.fn()}
      />
    );
    expect(screen.getByText("Slack")).toBeInTheDocument();
  });

  it("shows target count badge", () => {
    renderWithProviders(
      <ChannelCard
        channel={baseChannel}
        onDelete={vi.fn()}
        onDuplicate={vi.fn()}
      />
    );
    // The badge contains "3 targets" across multiple text nodes
    expect(screen.getByText(/targets/)).toBeInTheDocument();
  });

  it("shows singular 'target' for count of 1", () => {
    const singleTarget = { ...baseChannel, targetCount: 1 };
    renderWithProviders(
      <ChannelCard
        channel={singleTarget}
        onDelete={vi.fn()}
        onDuplicate={vi.fn()}
      />
    );
    // The badge contains "1 target" across text nodes
    const badge = screen.getByText(/target$/);
    expect(badge).toBeInTheDocument();
  });

  it("renders version number", () => {
    renderWithProviders(
      <ChannelCard
        channel={baseChannel}
        onDelete={vi.fn()}
        onDuplicate={vi.fn()}
      />
    );
    expect(screen.getByText("v1")).toBeInTheDocument();
  });

  it("shows 'Unnamed Channel' for empty name", () => {
    const unnamed = { ...baseChannel, name: "" };
    renderWithProviders(
      <ChannelCard
        channel={unnamed}
        onDelete={vi.fn()}
        onDuplicate={vi.fn()}
      />
    );
    expect(screen.getByText("Unnamed Channel")).toBeInTheDocument();
  });

  it("calls onDuplicate when duplicate button is clicked", async () => {
    const onDuplicate = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <ChannelCard
        channel={baseChannel}
        onDelete={vi.fn()}
        onDuplicate={onDuplicate}
      />
    );
    // Duplicate button has title="Duplicate"
    const dupBtn = screen.getByTitle("Duplicate");
    await user.click(dupBtn);
    expect(onDuplicate).toHaveBeenCalledWith("ch-001", 1);
  });

  it("calls onDelete when delete button is clicked", async () => {
    const onDelete = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <ChannelCard
        channel={baseChannel}
        onDelete={onDelete}
        onDuplicate={vi.fn()}
      />
    );
    const deleteBtn = screen.getByTitle("Delete");
    await user.click(deleteBtn);
    expect(onDelete).toHaveBeenCalledWith("ch-001", 1);
  });

  it("shows 'Unknown' when channelType is empty", () => {
    const unknownType = { ...baseChannel, channelType: "" };
    renderWithProviders(
      <ChannelCard
        channel={unknownType}
        onDelete={vi.fn()}
        onDuplicate={vi.fn()}
      />
    );
    expect(screen.getByText("Unknown")).toBeInTheDocument();
  });

  it("does not show channelId hash if channelId is empty", () => {
    const noChannelId = { ...baseChannel, channelId: "" };
    renderWithProviders(
      <ChannelCard
        channel={noChannelId}
        onDelete={vi.fn()}
        onDuplicate={vi.fn()}
      />
    );
    expect(screen.queryByText("C0123ABC")).not.toBeInTheDocument();
  });

  it("has role=button and tabIndex", () => {
    renderWithProviders(
      <ChannelCard
        channel={baseChannel}
        onDelete={vi.fn()}
        onDuplicate={vi.fn()}
      />
    );
    const card = screen.getByTestId("channel-card-ch-001");
    expect(card).toHaveAttribute("role", "button");
    expect(card).toHaveAttribute("tabindex", "0");
  });
});
