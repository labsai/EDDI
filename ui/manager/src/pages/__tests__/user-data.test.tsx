import { describe, it, expect } from "vitest";
import { screen, fireEvent } from "@testing-library/react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { UserDataPage } from "@/pages/user-data";

function renderPage(initialRoute = "/manage/userdata") {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <MemoryRouter initialEntries={[initialRoute]}>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test-userdata">
          <UserDataPage />
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe("UserDataPage", () => {
  it("renders the page container", () => {
    renderPage();
    expect(screen.getByTestId("user-data-page")).toBeInTheDocument();
  });

  it("renders the page title", () => {
    renderPage();
    expect(screen.getByText("User Data")).toBeInTheDocument();
  });

  it("renders all three tabs", () => {
    renderPage();
    expect(screen.getByTestId("tab-memories")).toBeInTheDocument();
    expect(screen.getByTestId("tab-properties")).toBeInTheDocument();
    expect(screen.getByTestId("tab-conversations")).toBeInTheDocument();
  });

  it("shows the Memories tab by default", () => {
    renderPage();
    // Memories tab should be active and render the user-memory-page testid
    expect(screen.getByTestId("user-memory-page")).toBeInTheDocument();
    // Properties should NOT be rendered
    expect(screen.queryByTestId("properties-page")).not.toBeInTheDocument();
  });

  it("does not show the standalone h1 header of the embedded Memories component", () => {
    renderPage();
    // The standalone h1 "User Memory" should NOT exist as a heading
    // (it only appears inside the h1 which is hidden when embedded)
    // But the tab label uses the same i18n key, so check the heading role instead
    const headings = screen.getAllByRole("heading", { level: 1 });
    // Should only have the parent User Data heading, not a User Memory h1
    expect(headings).toHaveLength(1);
    expect(headings[0]!.textContent).toContain("User Data");
  });

  it("switches to Properties tab on click", () => {
    renderPage();
    fireEvent.click(screen.getByTestId("tab-properties"));

    expect(screen.getByTestId("properties-page")).toBeInTheDocument();
    expect(screen.queryByTestId("user-memory-page")).not.toBeInTheDocument();
  });

  it("does not show the standalone h1 header of the embedded Properties component", () => {
    renderPage();
    fireEvent.click(screen.getByTestId("tab-properties"));

    // Only one h1 should exist: the parent User Data heading
    const headings = screen.getAllByRole("heading", { level: 1 });
    expect(headings).toHaveLength(1);
    expect(headings[0]!.textContent).toContain("User Data");
  });

  it("switches to Conversations tab on click", () => {
    renderPage();
    fireEvent.click(screen.getByTestId("tab-conversations"));

    expect(screen.getByTestId("user-conversations-page")).toBeInTheDocument();
    expect(screen.queryByTestId("user-memory-page")).not.toBeInTheDocument();
  });

  it("renders tab bar with proper ARIA tablist role", () => {
    renderPage();
    expect(screen.getByRole("tablist")).toBeInTheDocument();
  });

  it("marks only the active tab as aria-selected", () => {
    renderPage();

    const memoriesTab = screen.getByTestId("tab-memories");
    const propertiesTab = screen.getByTestId("tab-properties");

    expect(memoriesTab.getAttribute("aria-selected")).toBe("true");
    expect(propertiesTab.getAttribute("aria-selected")).toBe("false");

    fireEvent.click(propertiesTab);

    expect(memoriesTab.getAttribute("aria-selected")).toBe("false");
    expect(propertiesTab.getAttribute("aria-selected")).toBe("true");
  });

  it("respects tab query parameter from URL", () => {
    renderPage("/manage/userdata?tab=properties");
    expect(screen.getByTestId("properties-page")).toBeInTheDocument();
  });

  it("defaults to memories for invalid tab query parameter", () => {
    renderPage("/manage/userdata?tab=invalid");
    expect(screen.getByTestId("user-memory-page")).toBeInTheDocument();
  });
});
