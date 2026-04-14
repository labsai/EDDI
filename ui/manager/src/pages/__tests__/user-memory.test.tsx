import { describe, it, expect, vi } from "vitest";
import { screen, waitFor, fireEvent } from "@testing-library/react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { UserMemoryPage } from "@/pages/user-memory";


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
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test-memory">
          <UserMemoryPage />
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe("UserMemoryPage", () => {
  it("renders the page container", () => {
    renderPage();
    expect(screen.getByTestId("user-memory-page")).toBeInTheDocument();
  });

  it("renders the page title", () => {
    renderPage();
    expect(screen.getByText("User Memory")).toBeInTheDocument();
  });

  it("renders the user ID input", () => {
    renderPage();
    expect(screen.getByTestId("memory-user-id")).toBeInTheDocument();
  });

  it("shows empty state when no user ID is entered", () => {
    renderPage();
    expect(screen.getByText(/Enter a User ID/)).toBeInTheDocument();
  });

  it("shows memory entries after entering a user ID", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderPage();
    const input = screen.getByTestId("memory-user-id");
    fireEvent.change(input, { target: { value: "user-123" } });

    // Advance past debounce
    vi.advanceTimersByTime(600);

    await waitFor(() => {
      expect(screen.getByTestId("memory-list")).toBeInTheDocument();
    });
    vi.useRealTimers();
  });

  it("shows stats cards after loading memories", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderPage();
    fireEvent.change(screen.getByTestId("memory-user-id"), { target: { value: "user-123" } });
    vi.advanceTimersByTime(600);

    await waitFor(() => {
      expect(screen.getByTestId("memory-stats")).toBeInTheDocument();
    });
    vi.useRealTimers();
  });

  it("shows category filter tabs", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderPage();
    fireEvent.change(screen.getByTestId("memory-user-id"), { target: { value: "user-123" } });
    vi.advanceTimersByTime(600);

    await waitFor(() => {
      expect(screen.getByTestId("category-all")).toBeInTheDocument();
      expect(screen.getByTestId("category-preference")).toBeInTheDocument();
      expect(screen.getByTestId("category-fact")).toBeInTheDocument();
      expect(screen.getByTestId("category-context")).toBeInTheDocument();
    });
    vi.useRealTimers();
  });

  it("renders individual memory entries with correct data", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderPage();
    fireEvent.change(screen.getByTestId("memory-user-id"), { target: { value: "user-123" } });
    vi.advanceTimersByTime(600);

    await waitFor(() => {
      expect(screen.getByText("preferred_language")).toBeInTheDocument();
      expect(screen.getByText("account_type")).toBeInTheDocument();
      expect(screen.getByText("last_order_id")).toBeInTheDocument();
    });
    vi.useRealTimers();
  });

  it("filters memories by search text", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderPage();
    fireEvent.change(screen.getByTestId("memory-user-id"), { target: { value: "user-123" } });
    vi.advanceTimersByTime(600);

    await waitFor(() => {
      expect(screen.getByTestId("memory-list")).toBeInTheDocument();
    });

    fireEvent.change(screen.getByTestId("memory-search"), { target: { value: "preferred" } });

    await waitFor(() => {
      expect(screen.getByText("preferred_language")).toBeInTheDocument();
      expect(screen.queryByText("account_type")).not.toBeInTheDocument();
    });
    vi.useRealTimers();
  });

  it("shows delete all button", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderPage();
    fireEvent.change(screen.getByTestId("memory-user-id"), { target: { value: "user-123" } });
    vi.advanceTimersByTime(600);

    await waitFor(() => {
      expect(screen.getByTestId("delete-all-memories")).toBeInTheDocument();
    });
    vi.useRealTimers();
  });
});
