import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { ResourcesPage } from "@/pages/resources";
import { ResourceListPage } from "@/pages/resource-list";
import { ResourceDetailPage } from "@/pages/resource-detail";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { render } from "@testing-library/react";

function renderWithRoute(path: string, element: React.ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });

  return render(
    <MemoryRouter initialEntries={[path]}>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
          <Routes>
            <Route path="/manage/resources" element={element} />
            <Route path="/manage/resources/:type" element={element} />
            <Route path="/manage/resources/:type/:id" element={element} />
          </Routes>
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>
  );
}

describe("ResourcesPage (Hub)", () => {
  it("renders page heading", () => {
    renderWithProviders(<ResourcesPage />);
    expect(screen.getByText("Resources")).toBeInTheDocument();
  });

  it("renders subtitle", () => {
    renderWithProviders(<ResourcesPage />);
    expect(
      screen.getByText(
        "Manage behavior rules, dictionaries, outputs, and more"
      )
    ).toBeInTheDocument();
  });

  it("renders all 6 resource type cards", () => {
    renderWithProviders(<ResourcesPage />);
    expect(screen.getByTestId("resource-types-grid")).toBeInTheDocument();
    expect(screen.getByText("Behavior Rules")).toBeInTheDocument();
    expect(screen.getByText("HTTP Calls")).toBeInTheDocument();
    expect(screen.getByText("Output Sets")).toBeInTheDocument();
    expect(screen.getByText("Dictionaries")).toBeInTheDocument();
    expect(screen.getByText("LangChain")).toBeInTheDocument();
    expect(screen.getByText("Property Setter")).toBeInTheDocument();
  });

  it("renders type descriptions", () => {
    renderWithProviders(<ResourcesPage />);
    expect(
      screen.getByText("Define conversation flow rules and decision logic")
    ).toBeInTheDocument();
    expect(
      screen.getByText("Configure external API integrations and webhooks")
    ).toBeInTheDocument();
  });
});

describe("ResourceListPage", () => {
  it("renders heading for behavior type", () => {
    renderWithRoute(
      "/manage/resources/behavior",
      <ResourceListPage />
    );
    expect(screen.getByText("Behavior Rules")).toBeInTheDocument();
  });

  it("renders search input", () => {
    renderWithRoute(
      "/manage/resources/behavior",
      <ResourceListPage />
    );
    expect(screen.getByTestId("resource-search")).toBeInTheDocument();
  });

  it("renders create button", () => {
    renderWithRoute(
      "/manage/resources/behavior",
      <ResourceListPage />
    );
    expect(screen.getByTestId("create-resource-btn")).toBeInTheDocument();
  });

  it("renders back link", () => {
    renderWithRoute(
      "/manage/resources/behavior",
      <ResourceListPage />
    );
    expect(screen.getByTestId("back-to-resources")).toBeInTheDocument();
  });

  it("shows error for unknown type", () => {
    renderWithRoute(
      "/manage/resources/unknown",
      <ResourceListPage />
    );
    expect(screen.getByText("Unknown resource type")).toBeInTheDocument();
  });

  it("loads and displays resource cards", async () => {
    renderWithRoute(
      "/manage/resources/behavior",
      <ResourceListPage />
    );
    await waitFor(() => {
      expect(screen.getByTestId("resource-grid")).toBeInTheDocument();
    });
  });
});

describe("ResourceDetailPage", () => {
  it("renders heading for behavior type", () => {
    renderWithRoute(
      "/manage/resources/behavior/res1",
      <ResourceDetailPage />
    );
    expect(screen.getByText("Behavior Rules")).toBeInTheDocument();
  });

  it("renders back link", () => {
    renderWithRoute(
      "/manage/resources/behavior/res1",
      <ResourceDetailPage />
    );
    expect(screen.getByTestId("back-to-list")).toBeInTheDocument();
  });

  it("renders delete button", () => {
    renderWithRoute(
      "/manage/resources/behavior/res1",
      <ResourceDetailPage />
    );
    expect(screen.getByText("Delete")).toBeInTheDocument();
  });

  it("renders duplicate button", () => {
    renderWithRoute(
      "/manage/resources/behavior/res1",
      <ResourceDetailPage />
    );
    expect(screen.getByText("Duplicate")).toBeInTheDocument();
  });

  it("loads and displays raw JSON config", async () => {
    renderWithRoute(
      "/manage/resources/behavior/res1",
      <ResourceDetailPage />
    );
    await waitFor(() => {
      expect(screen.getByText("Raw Configuration")).toBeInTheDocument();
    });
  });
});
