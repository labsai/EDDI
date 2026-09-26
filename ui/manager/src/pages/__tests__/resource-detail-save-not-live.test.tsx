import { describe, it, expect, vi, afterEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { Toaster, toast } from "sonner";
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
          {/*
            * Toasts must outlive the test, not sonner's 4-second default.
            * "offers a Deploy action…" has to render the toast, find its action
            * button and click it; on a loaded CI runner that whole sequence took
            * 15 s, so the toast auto-dismissed underneath it and the click landed
            * on a detached node — the assertion then failed on an empty deploy
            * list, which reads like the action being broken rather than gone.
            */}
          <Toaster duration={600_000} />
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
    // The agent a save produced references the workflow version that save
    // produced (v2) — the cascade checks the reference before it writes.
    http.get("*/agentstore/agents/:id", ({ request }) =>
      HttpResponse.json({
        name: "test-agent",
        workflows: [
          `eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=${
            new URL(request.url).searchParams.get("version") === "1" ? 1 : 2
          }`,
        ],
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
  // sonner's toast store is module-global and survives unmount, so with the long
  // duration above a toast from one test is still on screen for the next --
  // "Found multiple elements with the role button and name Deploy". Clear it.
  toast.dismiss();
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
    // not the stale one the URL carried. The action is withheld entirely when
    // the cascade reports no agent version, so it can never deploy a revision
    // that does not contain this edit — unreachable through the cascade today
    // (it always reaches the agent when a cascade context exists), which is why
    // there is no case for it here.
    expect(deployed[0]).toContain("/deploy/agent1");
    expect(deployed[0]).toContain("version=7");
  });

  /**
   * Two saves must not leave two Deploy actions on screen.
   *
   * Each toast's action closes over the agent version ITS save produced, so a
   * stale toast beside a fresh one is a button that silently deploys the older
   * configuration over the newer one in production — from a control that reads
   * as being about the save just made. A stable toast id makes the second save
   * replace the first rather than stack beside it.
   */
  it("replaces the previous prompt, so only the latest version can be deployed", async () => {
    const deployed: string[] = [];
    stubCascade((url) => deployed.push(url));
    renderEditor();
    const user = userEvent.setup();

    await saveAChange(user);
    await screen.findByRole("button", { name: "Deploy" });

    // A second save, reporting a newer agent version.
    server.use(
      http.put("*/agentstore/agents/:id", () =>
        new HttpResponse(null, {
          status: 200,
          headers: { Location: "eddi://ai.labs.agent/agentstore/agents/agent1?version=9" },
        }),
      ),
    );
    await user.selectOptions(screen.getByTestId("model-type-select"), "ollama");
    await waitFor(() => {
      expect(screen.getByTestId("dirty-indicator")).toBeInTheDocument();
    });
    await user.click(screen.getByTestId("save-btn"));

    // Exactly one prompt, and it deploys the NEWER version.
    await waitFor(() => {
      expect(screen.getAllByRole("button", { name: "Deploy" })).toHaveLength(1);
    });
    await user.click(screen.getByRole("button", { name: "Deploy" }));

    await waitFor(() => {
      expect(deployed).toHaveLength(1);
    });
    expect(deployed[0]).toContain("version=9");
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
