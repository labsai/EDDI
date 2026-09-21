import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { EmptyState } from "@/components/shared/empty-state";
import { Search } from "lucide-react";

describe("EmptyState", () => {
  it("renders the title", () => {
    renderWithProviders(
      <EmptyState icon={Search} title="No results found" />
    );
    expect(screen.getByText("No results found")).toBeInTheDocument();
  });

  it("renders the icon", () => {
    renderWithProviders(
      <EmptyState icon={Search} title="No results" />
    );
    const icon = document.querySelector("svg.lucide-search");
    expect(icon).not.toBeNull();
  });

  it("renders description when provided", () => {
    renderWithProviders(
      <EmptyState
        icon={Search}
        title="No results"
        description="Try adjusting your search"
      />
    );
    expect(screen.getByText("Try adjusting your search")).toBeInTheDocument();
  });

  it("does not render description when not provided", () => {
    renderWithProviders(
      <EmptyState icon={Search} title="No results" />
    );
    expect(
      screen.queryByText("Try adjusting your search")
    ).not.toBeInTheDocument();
  });

  it("renders action button when actionLabel and onAction are provided", () => {
    renderWithProviders(
      <EmptyState
        icon={Search}
        title="Empty"
        actionLabel="Create New"
        onAction={vi.fn()}
      />
    );
    expect(screen.getByText("Create New")).toBeInTheDocument();
  });

  it("does not render action button when only actionLabel is provided (no onAction)", () => {
    renderWithProviders(
      <EmptyState
        icon={Search}
        title="Empty"
        actionLabel="Create New"
      />
    );
    expect(screen.queryByText("Create New")).not.toBeInTheDocument();
  });

  it("does not render action button when only onAction is provided (no actionLabel)", () => {
    renderWithProviders(
      <EmptyState
        icon={Search}
        title="Empty"
        onAction={vi.fn()}
      />
    );
    // No button should appear
    const buttons = document.querySelectorAll("button");
    expect(buttons.length).toBe(0);
  });

  it("calls onAction when action button is clicked", async () => {
    const onAction = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <EmptyState
        icon={Search}
        title="Empty"
        actionLabel="Add Item"
        onAction={onAction}
      />
    );
    await user.click(screen.getByText("Add Item"));
    expect(onAction).toHaveBeenCalledTimes(1);
  });

  it("has a dashed border container", () => {
    const { container } = renderWithProviders(
      <EmptyState icon={Search} title="Empty" />
    );
    const div = container.querySelector(".border-dashed");
    expect(div).not.toBeNull();
  });
});
