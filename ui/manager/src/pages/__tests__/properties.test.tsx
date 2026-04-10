import { describe, it, expect, vi } from "vitest";
import { screen, waitFor, fireEvent } from "@testing-library/react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { PropertiesPage } from "@/pages/properties";

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test-props">
          <PropertiesPage />
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe("PropertiesPage", () => {
  it("renders the page container", () => {
    renderPage();
    expect(screen.getByTestId("properties-page")).toBeInTheDocument();
  });

  it("renders the page title", () => {
    renderPage();
    expect(screen.getByText("User Properties")).toBeInTheDocument();
  });

  it("shows empty state when no user ID is entered", () => {
    renderPage();
    expect(screen.getByText(/Enter a User ID/)).toBeInTheDocument();
  });

  it("renders the user ID input", () => {
    renderPage();
    expect(screen.getByTestId("properties-user-id")).toBeInTheDocument();
  });

  it("shows properties table after entering user ID", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderPage();
    fireEvent.change(screen.getByTestId("properties-user-id"), { target: { value: "user-123" } });
    vi.advanceTimersByTime(600);

    await waitFor(() => {
      expect(screen.getByTestId("properties-table")).toBeInTheDocument();
    });
    vi.useRealTimers();
  });

  it("renders property entries with type badges", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderPage();
    fireEvent.change(screen.getByTestId("properties-user-id"), { target: { value: "user-123" } });
    vi.advanceTimersByTime(600);

    await waitFor(() => {
      expect(screen.getByText("user_name")).toBeInTheDocument();
      expect(screen.getByText("Jane Doe")).toBeInTheDocument();
      // Type badges
      expect(screen.getByText("string")).toBeInTheDocument();
      expect(screen.getByText("number")).toBeInTheDocument();
      expect(screen.getByText("boolean")).toBeInTheDocument();
    });
    vi.useRealTimers();
  });

  it("shows property count footer", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderPage();
    fireEvent.change(screen.getByTestId("properties-user-id"), { target: { value: "user-123" } });
    vi.advanceTimersByTime(600);

    await waitFor(() => {
      expect(screen.getByText("5 properties")).toBeInTheDocument();
    });
    vi.useRealTimers();
  });

  it("filters properties by search text", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderPage();
    fireEvent.change(screen.getByTestId("properties-user-id"), { target: { value: "user-123" } });
    vi.advanceTimersByTime(600);

    await waitFor(() => {
      expect(screen.getByTestId("properties-table")).toBeInTheDocument();
    });

    fireEvent.change(screen.getByTestId("properties-search"), { target: { value: "user_name" } });

    await waitFor(() => {
      expect(screen.getByText("user_name")).toBeInTheDocument();
      expect(screen.queryByText("is_vip")).not.toBeInTheDocument();
    });
    vi.useRealTimers();
  });
});
