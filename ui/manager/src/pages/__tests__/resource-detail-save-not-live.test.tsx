import { describe, it, expect, vi, afterEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { Toaster } from "sonner";
import userEvent from "@testing-library/user-event";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { ResourceDetailPage } from "@/pages/resource-detail";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

/**
 * A plain "Save" in the config editor leaves the change **not live**.
 *
 * It cascades resource → workflow → agent and stops; the running agent keeps
 * serving the version it was deployed with. Measured on an eligibility gate
 * with the ceiling lowered from 150,000 to 50,000 and a case of 85,000: after a
 * plain Save the gate still passed, while the resource/workflow/agent versions
 * had advanced to v4/v5 with the deployment stuck at v3.
 *
 * It nonetheless toasted "Saved successfully", so anyone who took that at face
 * value had a config that was saved and not live. (The neighbouring "Save and
 * Deploy" action does deploy, and polls for up to 30 s — this is about the
 * plain Save that sits beside it.)
 */

/*
 * sonner's toast binds a pointerdown handler that calls
 * `event.target.setPointerCapture`, which jsdom does not implement — clicking
 * the toast's action button throws an uncaught TypeError that vitest reports as
 * an unattributed error beside a passing suite. Stubbing the three Pointer
 * Capture methods is the usual jsdom workaround.
 */
for (const name of ["setPointerCapture", "releasePointerCapture", "hasPointerCapture"] as const) {
  if (!(name in Element.prototype)) {
    Object.defineProperty(Element.prototype, name, {
      value: name === "hasPointerCapture" ? () => false : () => {},
      writable: true,
      configurable: true,
    });
  }
}

function renderEditor() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });

  return render(
    <MemoryRouter
      initialEntries={[
        "/manage/resources/llm/res1?wfId=wf1&wfVer=1&agentId=agent1&agentVer=1",
      ]}
    >
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
          <Routes>
            <Route path="/manage/resources/:type/:id" element={<ResourceDetailPage />} />
          </Routes>
          <Toaster />
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

/** The cascade chain a Save walks: resource → workflow → agent. */
function stubCascade(onDeploy?: (url: string) => void) {
  server.use(
    http.put("*/llmstore/llms/:id", () =>
      new HttpResponse(null, {
        status: 200,
        headers: { Location: "eddi://ai.labs.llm/llmstore/llms/res1?version=2" },
      }),
    ),
    http.get("*/workflowstore/workflows/:id", () =>
      HttpResponse.json({
        workflowSteps: [{ config: { uri: "eddi://ai.labs.llm/llmstore/llms/res1?version=1" } }],
      }),
    ),
    http.put("*/workflowstore/workflows/:id", () =>
      new HttpResponse(null, {
        status: 200,
        headers: {
          Location: "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=2",
        },
      }),
    ),
    http.get("*/agentstore/agents/:id", () =>
      HttpResponse.json({
        name: "test-agent",
        workflows: ["eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1"],
      }),
    ),
    http.put("*/agentstore/agents/:id", () =>
      new HttpResponse(null, {
        status: 200,
        headers: { Location: "eddi://ai.labs.agent/agentstore/agents/agent1?version=7" },
      }),
    ),
    http.post("*/administration/:env/deploy/:agentId", ({ request }) => {
      onDeploy?.(request.url);
      return new HttpResponse(null, { status: 200 });
    }),
  );
}

async function saveAChange(user: ReturnType<typeof userEvent.setup>) {
  await waitFor(() => {
    expect(screen.getByTestId("model-type-select")).toBeInTheDocument();
  });
  await user.selectOptions(screen.getByTestId("model-type-select"), "azure-openai");
  await waitFor(() => {
    expect(screen.getByTestId("dirty-indicator")).toBeInTheDocument();
  });
  await user.click(screen.getByTestId("save-btn"));
}

const originalConsoleError = console.error;
afterEach(() => {
  console.error = originalConsoleError;
  vi.restoreAllMocks();
});

describe("ResourceDetailPage — a plain Save says it is not live", () => {
  it("does not report a plain success", async () => {
    stubCascade();
    renderEditor();
    const user = userEvent.setup();

    await saveAChange(user);

    await waitFor(() => {
      expect(screen.getByText("Saved — not yet live")).toBeInTheDocument();
    });
    expect(screen.queryByText("Saved successfully")).not.toBeInTheDocument();
  });

  it("explains that the running agent still serves the old version", async () => {
    stubCascade();
    renderEditor();
    const user = userEvent.setup();

    await saveAChange(user);

    await waitFor(() => {
      expect(screen.getByText(/still serves the deployed version/i)).toBeInTheDocument();
    });
  });

  it("offers a Deploy action that deploys the version the cascade produced", async () => {
    const deployed: string[] = [];
    stubCascade((url) => deployed.push(url));
    renderEditor();
    const user = userEvent.setup();

    await saveAChange(user);

    const deploy = await screen.findByRole("button", { name: "Deploy" });
    await user.click(deploy);

    await waitFor(() => {
      expect(deployed).toHaveLength(1);
    });
    // The agent version the cascade just wrote (Location header: version=7),
    // not the stale one the URL carried.
    expect(deployed[0]).toContain("/deploy/agent1");
    expect(deployed[0]).toContain("version=7");
  });

  it("surfaces a failed deploy instead of claiming it worked", async () => {
    stubCascade();
    server.use(
      http.post("*/administration/:env/deploy/:agentId", () =>
        HttpResponse.json({ message: "no such version" }, { status: 400 }),
      ),
    );
    console.error = vi.fn();
    renderEditor();
    const user = userEvent.setup();

    await saveAChange(user);
    await user.click(await screen.findByRole("button", { name: "Deploy" }));

    // The failure has to reach the operator: an action that silently does
    // nothing is worse than the toast that prompted it.
    await waitFor(() => {
      expect(screen.getByText(/no such version/i)).toBeInTheDocument();
    });
    expect(screen.queryByText("Deployment started")).not.toBeInTheDocument();
  });
});
