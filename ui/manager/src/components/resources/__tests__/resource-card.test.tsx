import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { ResourceCard } from "@/components/resources/resource-card";

const baseItem = {
  id: "res-456",
  version: 2,
  name: "Greeting Rules",
  description: "Rules for greeting the user",
  lastModifiedOn: Date.now() - 60000, // 1 min ago
  resource: "eddi://ai.labs.rules/rulestore/rulesets/res-456?version=2",
  createdOn: Date.now() - 86400000,
};

describe("ResourceCard", () => {
  it("renders the resource name", () => {
    renderWithProviders(
      <ResourceCard
        item={baseItem}
        typeSlug="rules"
        iconName="GitBranch"
        onDuplicate={vi.fn()}
        onDelete={vi.fn()}
      />
    );
    expect(screen.getByText("Greeting Rules")).toBeInTheDocument();
  });

  it("renders the resource description", () => {
    renderWithProviders(
      <ResourceCard
        item={baseItem}
        typeSlug="rules"
        iconName="GitBranch"
        onDuplicate={vi.fn()}
        onDelete={vi.fn()}
      />
    );
    expect(screen.getByText("Rules for greeting the user")).toBeInTheDocument();
  });

  it("renders the resource ID", () => {
    renderWithProviders(
      <ResourceCard
        item={baseItem}
        typeSlug="rules"
        iconName="GitBranch"
        onDuplicate={vi.fn()}
        onDelete={vi.fn()}
      />
    );
    expect(screen.getByText("res-456")).toBeInTheDocument();
  });

  it("renders version badge", () => {
    renderWithProviders(
      <ResourceCard
        item={baseItem}
        typeSlug="rules"
        iconName="GitBranch"
        onDuplicate={vi.fn()}
        onDelete={vi.fn()}
      />
    );
    expect(screen.getByText("v2")).toBeInTheDocument();
  });

  it("has correct data-testid", () => {
    renderWithProviders(
      <ResourceCard
        item={baseItem}
        typeSlug="rules"
        iconName="GitBranch"
        onDuplicate={vi.fn()}
        onDelete={vi.fn()}
      />
    );
    expect(screen.getByTestId("resource-card-res-456")).toBeInTheDocument();
  });

  it("links to resource detail page with correct type slug", () => {
    renderWithProviders(
      <ResourceCard
        item={baseItem}
        typeSlug="rules"
        iconName="GitBranch"
        onDuplicate={vi.fn()}
        onDelete={vi.fn()}
      />
    );
    const link = screen.getByText("Greeting Rules").closest("a");
    expect(link).toHaveAttribute("href", "/manage/resources/rules/res-456");
  });

  it("shows 'Unnamed Resource' for empty name", () => {
    renderWithProviders(
      <ResourceCard
        item={{ ...baseItem, name: "" }}
        typeSlug="rules"
        iconName="GitBranch"
        onDuplicate={vi.fn()}
        onDelete={vi.fn()}
      />
    );
    expect(screen.getByText("Unnamed Resource")).toBeInTheDocument();
  });

  it("shows 'No description' for empty description", () => {
    renderWithProviders(
      <ResourceCard
        item={{ ...baseItem, description: "" }}
        typeSlug="rules"
        iconName="GitBranch"
        onDuplicate={vi.fn()}
        onDelete={vi.fn()}
      />
    );
    expect(screen.getByText("No description")).toBeInTheDocument();
  });

  it("shows context menu on button click", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ResourceCard
        item={baseItem}
        typeSlug="rules"
        iconName="GitBranch"
        onDuplicate={vi.fn()}
        onDelete={vi.fn()}
      />
    );
    await user.click(screen.getByTestId("resource-menu-res-456"));
    expect(screen.getByText("Duplicate")).toBeInTheDocument();
    expect(screen.getByText("Delete")).toBeInTheDocument();
  });

  it("calls onDuplicate when duplicate is clicked", async () => {
    const onDuplicate = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <ResourceCard
        item={baseItem}
        typeSlug="rules"
        iconName="GitBranch"
        onDuplicate={onDuplicate}
        onDelete={vi.fn()}
      />
    );
    await user.click(screen.getByTestId("resource-menu-res-456"));
    await user.click(screen.getByText("Duplicate"));
    expect(onDuplicate).toHaveBeenCalledWith("res-456", 2);
  });

  it("calls onDelete when delete is clicked", async () => {
    const onDelete = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <ResourceCard
        item={baseItem}
        typeSlug="rules"
        iconName="GitBranch"
        onDuplicate={vi.fn()}
        onDelete={onDelete}
      />
    );
    await user.click(screen.getByTestId("resource-menu-res-456"));
    await user.click(screen.getByText("Delete"));
    expect(onDelete).toHaveBeenCalledWith("res-456", 2);
  });

  it("uses default GitBranch icon for unknown iconName", () => {
    const { container } = renderWithProviders(
      <ResourceCard
        item={baseItem}
        typeSlug="rules"
        iconName="UnknownIcon"
        onDuplicate={vi.fn()}
        onDelete={vi.fn()}
      />
    );
    // Falls back to GitBranch
    const icon = container.querySelector("svg.lucide-git-branch");
    expect(icon).not.toBeNull();
  });

  it("shows Just now for very recent items", () => {
    const recentItem = { ...baseItem, lastModifiedOn: Date.now() - 5000 };
    renderWithProviders(
      <ResourceCard
        item={recentItem}
        typeSlug="rules"
        iconName="GitBranch"
        onDuplicate={vi.fn()}
        onDelete={vi.fn()}
      />
    );
    expect(screen.getByText("Just now")).toBeInTheDocument();
  });
});
