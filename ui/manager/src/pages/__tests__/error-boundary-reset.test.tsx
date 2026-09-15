import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { render, screen } from "@testing-library/react";
import { ErrorBoundary } from "@/components/ui/error-boundary";

// Suppress React error boundary console noise in tests
const originalConsoleError = console.error;
beforeAll(() => {
  console.error = (...args: unknown[]) => {
    if (typeof args[0] === "string" && args[0].includes("Error: Uncaught"))
      return;
    if (typeof args[0] === "string" && args[0].includes("The above error"))
      return;
    originalConsoleError(...args);
  };
});
afterAll(() => {
  console.error = originalConsoleError;
});

function ThrowingChild({ shouldThrow }: { shouldThrow: boolean }) {
  if (shouldThrow) throw new Error("Test render error");
  return <div data-testid="child-content">Child OK</div>;
}

describe("ErrorBoundary", () => {
  it("catches render errors and shows fallback", () => {
    render(
      <ErrorBoundary>
        <ThrowingChild shouldThrow />
      </ErrorBoundary>,
    );

    expect(screen.getByTestId("error-boundary-fallback")).toBeInTheDocument();
    expect(screen.getByText("Test render error")).toBeInTheDocument();
  });

  it("resets when resetKey changes", () => {
    const { rerender } = render(
      <ErrorBoundary resetKey="a">
        <ThrowingChild shouldThrow />
      </ErrorBoundary>,
    );

    // Error should be caught
    expect(screen.getByTestId("error-boundary-fallback")).toBeInTheDocument();

    // Re-render with a different resetKey and a non-throwing child
    rerender(
      <ErrorBoundary resetKey="b">
        <ThrowingChild shouldThrow={false} />
      </ErrorBoundary>,
    );

    // Error should be cleared, children should render
    expect(
      screen.queryByTestId("error-boundary-fallback"),
    ).not.toBeInTheDocument();
    expect(screen.getByTestId("child-content")).toBeInTheDocument();
    expect(screen.getByText("Child OK")).toBeInTheDocument();
  });

  it("does NOT reset when resetKey stays the same", () => {
    const { rerender } = render(
      <ErrorBoundary resetKey="a">
        <ThrowingChild shouldThrow />
      </ErrorBoundary>,
    );

    // Error should be caught
    expect(screen.getByTestId("error-boundary-fallback")).toBeInTheDocument();

    // Re-render with the SAME resetKey but a non-throwing child
    rerender(
      <ErrorBoundary resetKey="a">
        <ThrowingChild shouldThrow={false} />
      </ErrorBoundary>,
    );

    // Error should persist because resetKey didn't change
    expect(screen.getByTestId("error-boundary-fallback")).toBeInTheDocument();
    expect(
      screen.queryByTestId("child-content"),
    ).not.toBeInTheDocument();
  });
});
