import { beforeEach, describe, expect, it } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { AgentDetailPage } from "@/pages/agent-detail";
import { server } from "@/test/mocks/server";
import { useChatDrawerStore } from "@/hooks/use-chat-drawer";
import { useChatStore } from "@/hooks/use-chat";

function renderAgentDetail(id = "agent1") {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <MemoryRouter initialEntries={[`/manage/agentview/${id}`]}>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
          <Routes>
            <Route path="/manage/agentview/:id" element={<AgentDetailPage />} />
          </Routes>
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

/**
 * The agent page's Chat button opened the drawer without naming the
 * environment, so the drawer defaulted to production and its "New
 * conversation" restarted a test-only agent where it is not deployed.
 */
describe("AgentDetailPage — Chat opens the drawer in the agent's environment", () => {
  beforeEach(() => {
    useChatDrawerStore.getState().close();
    useChatStore.getState().reset();
  });

  it("carries the test environment into the drawer for a test-only agent", async () => {
    let startedIn: string | null = null;
    server.use(
      http.get("*/administration/:env/deploymentstatus/:agentId", ({ params }) =>
        HttpResponse.json({ status: params.env === "test" ? "READY" : "NOT_FOUND" }),
      ),
      http.post("*/agents/:agentId/start", ({ request }) => {
        startedIn = new URL(request.url).searchParams.get("environment");
        return HttpResponse.json({ location: "/agents/conv-test" });
      }),
    );

    renderAgentDetail("agent1");
    const chat = await screen.findByTestId("chat-btn");
    await waitFor(() =>
      expect(screen.getByTestId("external-chat-btn")).toHaveAttribute("href", "/chat/test/agent1"),
    );
    await userEvent.click(chat);

    await waitFor(() => expect(startedIn).toBe("test"));
    expect(useChatDrawerStore.getState().environment).toBe("test");
  });
});
