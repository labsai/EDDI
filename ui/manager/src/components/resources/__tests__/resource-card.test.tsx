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
        onDuplicate={vi.fn()}
        onDelete={onDelete}
      />
    );
    await user.click(screen.getByTestId("resource-menu-res-456"));
    await user.click(screen.getByText("Delete"));
    expect(onDelete).toHaveBeenCalledWith("res-456", 2);
  });

  it("draws the icon of its own type, not the rules icon", () => {
    // The card's private icon map used to know six of the ten types and fell
    // back to GitBranch, so a RAG card was drawn as a rule set.
    const { container } = renderWithProviders(
      <ResourceCard
        item={baseItem}
        typeSlug="rag"
        onDuplicate={vi.fn()}
        onDelete={vi.fn()}
      />
    );
    expect(container.querySelector("svg.lucide-library")).not.toBeNull();
    expect(container.querySelector("svg.lucide-git-branch")).toBeNull();
  });

  it("uses the unknown-type icon for a type it does not know", () => {
    const { container } = renderWithProviders(
      <ResourceCard
        item={baseItem}
        typeSlug="not-a-type"
        onDuplicate={vi.fn()}
        onDelete={vi.fn()}
      />
    );
    expect(container.querySelector("svg.lucide-package")).not.toBeNull();
  });

  it("shows Just now for very recent items", () => {
    const recentItem = { ...baseItem, lastModifiedOn: Date.now() - 5000 };
    renderWithProviders(
      <ResourceCard
        item={recentItem}
        typeSlug="rules"
        onDuplicate={vi.fn()}
        onDelete={vi.fn()}
      />
    );
    expect(screen.getByText("Just now")).toBeInTheDocument();
  });
});
