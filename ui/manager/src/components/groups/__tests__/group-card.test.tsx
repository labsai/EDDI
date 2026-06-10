import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { GroupCard } from "@/components/groups/group-card";

const baseGroup = {
  id: "grp-123",
  version: 2,
  name: "Product Review Panel",
  description: "A group for product reviews",
  lastModifiedOn: Date.now() - 3600000,
};

describe("GroupCard", () => {
  it("renders the group name", () => {
    renderWithProviders(<GroupCard group={baseGroup} />);
    expect(screen.getByText("Product Review Panel")).toBeInTheDocument();
  });

  it("renders the group description", () => {
    renderWithProviders(<GroupCard group={baseGroup} />);
    expect(screen.getByText("A group for product reviews")).toBeInTheDocument();
  });

  it("renders the group ID", () => {
    renderWithProviders(<GroupCard group={baseGroup} />);
    expect(screen.getByText("grp-123")).toBeInTheDocument();
  });

  it("renders the version badge", () => {
    renderWithProviders(<GroupCard group={baseGroup} />);
    expect(screen.getByText("v2")).toBeInTheDocument();
  });

  it("renders member count badge (default 0)", () => {
    renderWithProviders(<GroupCard group={baseGroup} />);
    expect(screen.getByText("0")).toBeInTheDocument();
  });

  it("renders member count badge with custom value", () => {
    renderWithProviders(<GroupCard group={baseGroup} memberCount={5} />);
    expect(screen.getByText("5")).toBeInTheDocument();
  });

  it("renders style badge when style is provided", () => {
    renderWithProviders(
      <GroupCard group={baseGroup} style="PEER_REVIEW" />
    );
    expect(screen.getByText("Peer Review")).toBeInTheDocument();
  });

  it("renders 'Group' fallback when no style is provided", () => {
    renderWithProviders(<GroupCard group={baseGroup} />);
    expect(screen.getByText("Group")).toBeInTheDocument();
  });

  it("renders 'Unnamed Group' when name is empty", () => {
    const group = { ...baseGroup, name: "" };
    renderWithProviders(<GroupCard group={group} />);
    expect(screen.getByText("Unnamed Group")).toBeInTheDocument();
  });

  it("renders 'No description' when description is empty", () => {
    const group = { ...baseGroup, description: "" };
    renderWithProviders(<GroupCard group={group} />);
    expect(screen.getByText("No description")).toBeInTheDocument();
  });

  it("has data-testid with group id", () => {
    renderWithProviders(<GroupCard group={baseGroup} />);
    expect(screen.getByTestId("group-card-grp-123")).toBeInTheDocument();
  });

  it("links to the group detail page", () => {
    renderWithProviders(<GroupCard group={baseGroup} />);
    const link = screen.getByText("Product Review Panel").closest("a");
    expect(link).toHaveAttribute(
      "href",
      "/manage/groups/grp-123?version=2"
    );
  });

  it("shows context menu on button click", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <GroupCard
        group={baseGroup}
        onDuplicate={vi.fn()}
        onDelete={vi.fn()}
      />
    );

    await user.click(screen.getByTestId("group-menu-grp-123"));
    expect(screen.getByText("Duplicate")).toBeInTheDocument();
    expect(screen.getByText("Delete")).toBeInTheDocument();
  });

  it("calls onDuplicate when duplicate is clicked", async () => {
    const onDuplicate = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <GroupCard group={baseGroup} onDuplicate={onDuplicate} />
    );

    await user.click(screen.getByTestId("group-menu-grp-123"));
    await user.click(screen.getByText("Duplicate"));
    expect(onDuplicate).toHaveBeenCalledWith("grp-123", 2);
  });

  it("calls onDelete when delete is clicked", async () => {
    const onDelete = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <GroupCard group={baseGroup} onDelete={onDelete} />
    );

    await user.click(screen.getByTestId("group-menu-grp-123"));
    await user.click(screen.getByText("Delete"));
    expect(onDelete).toHaveBeenCalledWith("grp-123", 2);
  });

  it("closes menu after clicking an action", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <GroupCard group={baseGroup} onDuplicate={vi.fn()} />
    );

    await user.click(screen.getByTestId("group-menu-grp-123"));
    expect(screen.getByText("Duplicate")).toBeInTheDocument();

    await user.click(screen.getByText("Duplicate"));
    expect(screen.queryByText("Duplicate")).not.toBeInTheDocument();
  });
});
