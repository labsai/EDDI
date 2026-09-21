import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { ErrorState } from "@/components/shared/error-state";

describe("ErrorState", () => {
  it("renders the error message", () => {
    renderWithProviders(<ErrorState message="Something went wrong" />);
    expect(screen.getByText("Something went wrong")).toBeInTheDocument();
  });

  it("renders the alert icon", () => {
    renderWithProviders(<ErrorState message="Error occurred" />);
    const icon = document.querySelector("svg.lucide-circle-alert");
    expect(icon).not.toBeNull();
  });

  it("renders retry button when onRetry is provided", () => {
    renderWithProviders(
      <ErrorState message="Error" onRetry={vi.fn()} />
    );
    expect(screen.getByText("Retry")).toBeInTheDocument();
  });

  it("does not render retry button when onRetry is not provided", () => {
    renderWithProviders(<ErrorState message="Error" />);
    expect(screen.queryByText("Retry")).not.toBeInTheDocument();
  });

  it("calls onRetry when retry button is clicked", async () => {
    const onRetry = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <ErrorState message="Error" onRetry={onRetry} />
    );
    await user.click(screen.getByText("Retry"));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it("uses custom retry label when provided", () => {
    renderWithProviders(
      <ErrorState
        message="Error"
        onRetry={vi.fn()}
        retryLabel="Try Again"
      />
    );
    expect(screen.getByText("Try Again")).toBeInTheDocument();
    expect(screen.queryByText("Retry")).not.toBeInTheDocument();
  });

  it("defaults retry label to 'Retry'", () => {
    renderWithProviders(
      <ErrorState message="Error" onRetry={vi.fn()} />
    );
    expect(screen.getByText("Retry")).toBeInTheDocument();
  });
});
