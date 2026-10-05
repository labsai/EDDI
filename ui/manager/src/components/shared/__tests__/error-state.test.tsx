import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { ErrorState } from "@/components/shared/error-state";
import { ApiClientError } from "@/lib/api-client";

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

  describe("with an error object", () => {
    const err = (status: number, message: string) => new ApiClientError(status, message);

    it.each([
      [403, "You don't have permission to view this."],
      [404, "Not found — it may have been deleted."],
      [0, "Can't reach EDDI. Check your connection and try again."],
      [401, "Your session has expired. Sign in again."],
    ])("maps HTTP %i to its own headline", (status, headline) => {
      renderWithProviders(
        <ErrorState message="Something went wrong" error={err(status, "x")} />,
      );
      expect(screen.getByText(headline)).toBeInTheDocument();
      expect(screen.queryByText("Something went wrong")).not.toBeInTheDocument();
    });

    it("keeps the caller's message for statuses without wording and shows the server detail", () => {
      renderWithProviders(
        <ErrorState message="Could not load agents" error={err(500, "Store unavailable: mongo timeout")} />,
      );
      expect(screen.getByText("Could not load agents")).toBeInTheDocument();
      expect(screen.getByTestId("error-state-detail")).toHaveTextContent(
        "Store unavailable: mongo timeout",
      );
    });

    it("does not echo a bare status phrase as detail", () => {
      renderWithProviders(<ErrorState error={err(404, "Not Found")} />);
      expect(screen.queryByTestId("error-state-detail")).not.toBeInTheDocument();
    });
  });

  it("defaults retry label to 'Retry'", () => {
    renderWithProviders(
      <ErrorState message="Error" onRetry={vi.fn()} />
    );
    expect(screen.getByText("Retry")).toBeInTheDocument();
  });
});
