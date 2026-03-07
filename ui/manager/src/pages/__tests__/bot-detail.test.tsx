import { describe, it, expect } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { BotDetailPage } from "@/pages/bot-detail";

function renderBotDetail(id = "bot1") {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <MemoryRouter initialEntries={[`/manage/botview/${id}`]}>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
          <Routes>
            <Route path="/manage/botview/:id" element={<BotDetailPage />} />
          </Routes>
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>
  );
}

describe("BotDetailPage", () => {
  it("renders bot detail title", async () => {
    renderBotDetail();
    await waitFor(() => {
      expect(screen.getByText("Bot Detail")).toBeInTheDocument();
    });
  });

  it("shows deployment status badge", async () => {
    renderBotDetail();
    await waitFor(() => {
      expect(screen.getByTestId("deployment-status")).toBeInTheDocument();
    });
  });

  it("renders deploy button", async () => {
    renderBotDetail();
    await waitFor(() => {
      expect(screen.getByTestId("deploy-btn")).toBeInTheDocument();
    });
  });

  it("renders duplicate button", async () => {
    renderBotDetail();
    await waitFor(() => {
      expect(screen.getByTestId("duplicate-bot-btn")).toBeInTheDocument();
    });
  });

  it("renders export button", async () => {
    renderBotDetail();
    await waitFor(() => {
      expect(screen.getByTestId("export-bot-btn")).toBeInTheDocument();
    });
  });

  it("renders delete button", async () => {
    renderBotDetail();
    await waitFor(() => {
      expect(screen.getByTestId("delete-bot-btn")).toBeInTheDocument();
    });
  });

  it("renders environment badges section", async () => {
    renderBotDetail();
    await waitFor(() => {
      expect(screen.getByTestId("env-badges")).toBeInTheDocument();
    });
  });

  it("renders add package button", async () => {
    renderBotDetail();
    await waitFor(() => {
      expect(screen.getByTestId("add-package-btn")).toBeInTheDocument();
    });
  });

  it("shows packages section with count", async () => {
    renderBotDetail();
    await waitFor(() => {
      expect(screen.getByText("Packages")).toBeInTheDocument();
    });
  });
});
