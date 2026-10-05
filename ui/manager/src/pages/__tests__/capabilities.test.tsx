import { describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { CapabilitiesPage } from "@/pages/capabilities";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";

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
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test-capabilities">
          <CapabilitiesPage />
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe("CapabilitiesPage", () => {
  it("renders the page container", () => {
    renderPage();
    expect(screen.getByTestId("capabilities-page")).toBeInTheDocument();
  });

  it("renders the page title", () => {
    renderPage();
    expect(screen.getByText(/Capability (Registry|Discovery)/i)).toBeInTheDocument();
  });

  it("renders the search input", () => {
    renderPage();
    expect(screen.getByTestId("capability-search")).toBeInTheDocument();
  });

  it("renders the strategy selector", () => {
    renderPage();
    expect(screen.getByTestId("capability-strategy")).toBeInTheDocument();
  });

  it("shows skills grid after loading", async () => {
    renderPage();
    await waitFor(() => {
      expect(screen.getByTestId("skills-grid")).toBeInTheDocument();
    });
  });

  it("renders skill buttons from mock data", async () => {
    renderPage();
    await waitFor(() => {
      // These skill names come from the existing ALL_SKILLS mock in handlers.ts
      expect(screen.getByTestId("skill-customer-support")).toBeInTheDocument();
      expect(screen.getByTestId("skill-faq")).toBeInTheDocument();
      expect(screen.getByTestId("skill-contract-analysis")).toBeInTheDocument();
    });
  });

  it("shows the registry overview table", async () => {
    renderPage();
    await waitFor(() => {
      expect(screen.getByTestId("registry-table")).toBeInTheDocument();
    });
  });

  it("renders registry rows for each skill", async () => {
    renderPage();
    await waitFor(() => {
      expect(screen.getByTestId("registry-row-customer-support")).toBeInTheDocument();
      expect(screen.getByTestId("registry-row-faq")).toBeInTheDocument();
      expect(screen.getByTestId("registry-row-contract-analysis")).toBeInTheDocument();
    });
  });

  it("expands a registry row to show agents", async () => {
    renderPage();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("registry-row-customer-support")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("registry-row-customer-support"));

    await waitFor(() => {
      expect(screen.getByTestId("registry-expanded-customer-support")).toBeInTheDocument();
    });
  });

  it("shows matching agents when a skill pill is clicked", async () => {
    renderPage();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("skill-customer-support")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("skill-customer-support"));

    await waitFor(() => {
      expect(screen.getByTestId("capability-results")).toBeInTheDocument();
      expect(screen.getByText("Matching Agents")).toBeInTheDocument();
    });
  });
});

describe("CapabilitiesPage — names, labels and empty state", () => {
  it("shows the agent's name, not its raw id, and no external-link icon", async () => {
    server.use(
      http.get("*/agentstore/agents/descriptors", () =>
        HttpResponse.json([
          {
            resource: "eddi://ai.labs.agent/agentstore/agents/agent1?version=1",
            name: "Support Agent",
            createdOn: 1,
            lastModifiedOn: 1,
          },
        ]),
      ),
    );
    renderPage();
    const user = userEvent.setup();
    await user.click(await screen.findByTestId("registry-row-customer-support"));

    const link = await screen.findByRole("link", { name: "Support Agent" });
    expect(link).toHaveAttribute("href", "/manage/agentview/agent1");
    expect(link.querySelector("svg")).toBeNull();
  });

  it("marks expandable rows with aria-expanded", async () => {
    renderPage();
    const user = userEvent.setup();
    const row = await screen.findByTestId("registry-row-faq");
    expect(row).toHaveAttribute("aria-expanded", "false");
    await user.click(row);
    expect(row).toHaveAttribute("aria-expanded", "true");
  });

  it("labels the search and strategy controls", () => {
    renderPage();
    expect(screen.getByRole("textbox", { name: "Search skills" })).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Match strategy" })).toBeInTheDocument();
  });

  it("explains an empty registry instead of rendering only a heading", async () => {
    server.use(http.get("*/capabilities/skills", () => HttpResponse.json([])));
    renderPage();
    expect(await screen.findByTestId("registry-empty")).toHaveTextContent(
      "No capabilities registered yet",
    );
  });
});
