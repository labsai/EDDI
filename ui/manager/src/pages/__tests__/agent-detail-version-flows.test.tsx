import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { AgentDetailPage } from "@/pages/agent-detail";
import { server } from "@/test/mocks/server";

const toastSuccess = vi.fn();
const toastError = vi.fn();
vi.mock("sonner", () => ({
  toast: Object.assign(vi.fn(), {
    success: (...args: unknown[]) => toastSuccess(...args),
    error: (...args: unknown[]) => toastError(...args),
    warning: vi.fn(),
  }),
}));

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

describe("AgentDetailPage — deployment badge", () => {
  it("says what is live when the viewed version is not", async () => {
    server.use(
      http.get("*/administration/:env/deploymentstatus/:agentId", () =>
        HttpResponse.json({ status: "NOT_FOUND" }),
      ),
      http.get("*/administration/:env/deploymentstatus", ({ params }) =>
        HttpResponse.json(
          params.env === "production"
            ? [{ environment: "production", agentId: "agent1", agentVersion: 7, status: "READY" }]
            : [],
        ),
      ),
    );
    renderAgentDetail();
    await waitFor(() =>
      expect(screen.getByTestId("deployment-status")).toHaveTextContent("Live: v7 · Viewing v1 (not deployed)"),
    );
  });

  it("still says plain 'Not deployed' when nothing is live anywhere", async () => {
    server.use(
      http.get("*/administration/:env/deploymentstatus/:agentId", () =>
        HttpResponse.json({ status: "NOT_FOUND" }),
      ),
      http.get("*/administration/:env/deploymentstatus", () => HttpResponse.json([])),
    );
    renderAgentDetail();
    await waitFor(() => expect(screen.getByTestId("deployment-status")).toHaveTextContent("Not deployed"));
    expect(screen.getByTestId("deployment-status")).not.toHaveTextContent("Live:");
  });

  it("names the environment on the header Undeploy when both are live", async () => {
    server.use(
      http.get("*/administration/:env/deploymentstatus/:agentId", () => HttpResponse.json({ status: "READY" })),
    );
    renderAgentDetail();
    await waitFor(() => expect(screen.getByTestId("deploy-btn")).toHaveTextContent("Undeploy from production"));
  });

  it("shows the version once in the header", async () => {
    renderAgentDetail("agent3");
    await screen.findByTestId("version-badge");
    // The id line used to repeat the selector's "vN" right beside it.
    const header = screen.getByTestId("version-badge").closest("div.space-y-2")!;
    expect(within(header as HTMLElement).getAllByText(/^v\d+$/)).toHaveLength(1);
  });
});

describe("AgentDetailPage — workflows", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("links each workflow at the version this agent references", async () => {
    renderAgentDetail("agent1");
    await screen.findByText("Support Ticket Pipeline");
    const link = screen
      .getAllByRole("link")
      .find((a) => a.getAttribute("href")?.startsWith("/manage/workflowview/wf1"))!;
    const url = new URL(link.getAttribute("href")!, "http://x");
    expect(url.pathname).toBe("/manage/workflowview/wf1");
    // agent1 references wf1 at version 2 — the link must say so.
    expect(url.searchParams.get("version")).toBe("2");
    expect(url.searchParams.get("agentId")).toBe("agent1");
  });

  it("shows the workflow's name, not only its id", async () => {
    renderAgentDetail("agent1");
    expect(await screen.findByText("Support Ticket Pipeline")).toBeInTheDocument();
  });

  it("asks before removing a workflow, and Cancel removes nothing", async () => {
    let puts = 0;
    server.use(
      http.put("*/agentstore/agents/:id", () => {
        puts++;
        return new HttpResponse(null, {
          status: 200,
          headers: { Location: "/agentstore/agents/agent1?version=2" },
        });
      }),
    );
    renderAgentDetail("agent1");
    const user = userEvent.setup();
    await user.click(await screen.findByTestId("remove-workflow-wf1"));
    await screen.findByText("Remove workflow from this agent?");
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    await new Promise((r) => setTimeout(r, 50));
    expect(puts).toBe(0);
  });

  it("labels the remove button for assistive technology", async () => {
    renderAgentDetail("agent1");
    expect(await screen.findByRole("button", { name: "Remove workflow from agent" })).toBeInTheDocument();
  });
});

describe("AgentDetailPage — section saves", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("says which version a section save created and that it is not live", async () => {
    server.use(
      http.put("*/agentstore/agents/:id", () =>
        new HttpResponse(null, {
          status: 200,
          headers: { Location: "eddi://ai.labs.agent/agentstore/agents/agent1?version=2" },
        }),
      ),
    );
    renderAgentDetail("agent1");
    const user = userEvent.setup();
    const section = await screen.findByTestId("a2a-section");
    await user.click(within(section).getByRole("button", { name: /Agent-to-Agent/ }));
    await user.type(await screen.findByTestId("a2a-skill-input"), "new-skill");
    await user.click(screen.getByRole("button", { name: "Add skill" }));

    await waitFor(() => expect(toastSuccess).toHaveBeenCalled());
    const [title, options] = toastSuccess.mock.calls[0]!;
    expect(String(title)).toBe("Saved as v2 — not live yet");
    // The one action that closes the gap.
    expect(options.action.label).toBe("Deploy");
  });
});

describe("AgentDetailPage — collapsible sections", () => {
  it("exposes the expanded state, with the heading wrapping the button", async () => {
    renderAgentDetail("agent1");
    const section = await screen.findByTestId("a2a-section");
    const button = within(section).getByRole("button", { name: /Agent-to-Agent/ });
    expect(button).toHaveAttribute("aria-expanded", "false");
    // A heading inside a button is invalid; the button belongs inside the heading.
    expect(button.closest("h2")).not.toBeNull();
    expect(button.querySelector("h2")).toBeNull();

    await userEvent.setup().click(button);
    expect(button).toHaveAttribute("aria-expanded", "true");
    expect(button).toHaveAttribute("aria-controls");
  });

  it("gives the A2A controls names", async () => {
    renderAgentDetail("agent1");
    const user = userEvent.setup();
    const section = await screen.findByTestId("a2a-section");
    await user.click(within(section).getByRole("button", { name: /Agent-to-Agent/ }));
    // Labels reach their inputs.
    expect(await screen.findByLabelText("Agent Description")).toBeInTheDocument();
    expect(screen.getByLabelText("A2A Skills")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Add skill" })).toBeInTheDocument();
    // The preview describes this agent, not a hard-coded platform version.
    await user.click(screen.getByTestId("a2a-card-toggle"));
    const preview = document.querySelector("pre")!.textContent!;
    expect(preview).not.toContain("6.0.0");
    expect(preview).toContain("Support");
  });
});

describe("AgentDetailPage — renaming", () => {
  it("renames the agent in place, on its newest version", async () => {
    const patches: string[] = [];
    server.use(
      http.patch("*/descriptorstore/descriptors/:id", async ({ request }) => {
        patches.push(request.url + " " + JSON.stringify(await request.json()));
        return new HttpResponse(null, { status: 200 });
      }),
    );
    renderAgentDetail("agent1");
    const user = userEvent.setup();
    await user.click(await screen.findByTestId("agent-title-edit"));
    const name = screen.getByTestId("agent-title-name");
    await user.clear(name);
    await user.type(name, "Renamed agent");
    await user.click(screen.getByTestId("agent-title-save"));
    await waitFor(() => expect(patches.length).toBeGreaterThan(0));
    expect(patches[0]).toContain('"name":"Renamed agent"');
  });
});
