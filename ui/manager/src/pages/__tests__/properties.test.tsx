import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { PropertiesPage } from "@/pages/properties";
import userEvent from "@testing-library/user-event";
import { MOCK_PROPERTIES_EMPTY_USER } from "@/test/mocks/handlers";

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
});

afterEach(() => {
  vi.useRealTimers();
});

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
    renderPage();
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    await user.type(screen.getByTestId("properties-user-id"), "user-123");
    vi.advanceTimersByTime(600);

    await waitFor(() => {
      expect(screen.getByTestId("properties-table")).toBeInTheDocument();
    });
  });

  it("renders property entries with type badges", async () => {
    renderPage();
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    await user.type(screen.getByTestId("properties-user-id"), "user-123");
    vi.advanceTimersByTime(600);

    await waitFor(() => {
      expect(screen.getByText("user_name")).toBeInTheDocument();
      expect(screen.getByText("Jane Doe")).toBeInTheDocument();
      // Type badges (multiple entries may share the same type)
      expect(screen.getAllByText("string").length).toBeGreaterThanOrEqual(1);
      expect(screen.getAllByText("number").length).toBeGreaterThanOrEqual(1);
      expect(screen.getAllByText("boolean").length).toBeGreaterThanOrEqual(1);
    });
  });

  it("types raw values by their JavaScript type — the endpoint sends no Property wrapper", async () => {
    renderPage();
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    await user.type(screen.getByTestId("properties-user-id"), "user-123");
    vi.advanceTimersByTime(600);

    await waitFor(() => {
      expect(within(screen.getByTestId("property-age")).getByText("number")).toBeInTheDocument();
    });
    expect(within(screen.getByTestId("property-age")).getByText("32")).toBeInTheDocument();
    expect(within(screen.getByTestId("property-tags")).getByText("array")).toBeInTheDocument();
    expect(within(screen.getByTestId("property-preferences")).getByText("object")).toBeInTheDocument();
    expect(screen.queryByText("null")).not.toBeInTheDocument();
  });

  it("shows an empty table, not an error, for a user with no properties (204)", async () => {
    renderPage();
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    await user.type(screen.getByTestId("properties-user-id"), MOCK_PROPERTIES_EMPTY_USER);
    vi.advanceTimersByTime(600);

    await waitFor(() => {
      expect(screen.getByTestId("properties-table")).toBeInTheDocument();
    });
    expect(screen.getByText("No properties found")).toBeInTheDocument();
  });

  it("shows property count footer", async () => {
    renderPage();
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    await user.type(screen.getByTestId("properties-user-id"), "user-123");
    vi.advanceTimersByTime(600);

    await waitFor(() => {
      expect(screen.getByText("10 properties")).toBeInTheDocument();
    });
  });

  it("filters properties by search text", async () => {
    renderPage();
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    await user.type(screen.getByTestId("properties-user-id"), "user-123");
    vi.advanceTimersByTime(600);

    await waitFor(() => {
      expect(screen.getByTestId("properties-table")).toBeInTheDocument();
    });

    await user.type(screen.getByTestId("properties-search"), "user_name");

    await waitFor(() => {
      expect(screen.getByText("user_name")).toBeInTheDocument();
      expect(screen.queryByText("is_vip")).not.toBeInTheDocument();
    });
  });
});
