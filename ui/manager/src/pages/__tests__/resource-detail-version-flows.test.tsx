import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { ResourceDetailPage } from "@/pages/resource-detail";
import { renderPage } from "@/test/test-utils";
import { server } from "@/test/mocks/server";

const toastError = vi.fn();
vi.mock("sonner", () => ({
  toast: Object.assign(vi.fn(), {
    success: vi.fn(),
    error: (...args: unknown[]) => toastError(...args),
    warning: vi.fn(),
  }),
}));

const ROUTE = "/manage/resources/:type/:id";

async function editAndSave(user: ReturnType<typeof userEvent.setup>) {
  await waitFor(() => expect(screen.getByTestId("model-type-select")).toBeInTheDocument());
  await user.selectOptions(screen.getByTestId("model-type-select"), "azure-openai");
  await waitFor(() => expect(screen.getByTestId("dirty-indicator")).toBeInTheDocument());
  await user.click(screen.getByTestId("save-btn"));
}

describe("ResourceDetailPage — the agent pins another workflow version", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  /** The agent uses wf1 at version 2; the page is opened from wf1 version 3. */
  function stubPinnedAgent() {
    const writes: { agent?: { workflows?: string[] }; workflowPuts: number } = { workflowPuts: 0 };
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
      http.put("*/workflowstore/workflows/:id", () => {
        writes.workflowPuts++;
        return new HttpResponse(null, {
          status: 200,
          headers: { Location: "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=4" },
        });
      }),
      http.get("*/agentstore/agents/:id", () =>
        HttpResponse.json({
          workflows: ["eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=2"],
        }),
      ),
      http.put("*/agentstore/agents/:id", async ({ request }) => {
        writes.agent = (await request.json()) as { workflows?: string[] };
        return new HttpResponse(null, {
          status: 200,
          headers: { Location: "eddi://ai.labs.agent/agentstore/agents/agent1?version=2" },
        });
      }),
    );
    return writes;
  }

  it("says what is wrong and offers to update the agent, instead of 'reload'", async () => {
    const writes = stubPinnedAgent();
    renderPage(
      "/manage/resources/llm/res1?wfId=wf1&wfVer=3&agentId=agent1&agentVer=1",
      <ResourceDetailPage />,
      ROUTE,
    );
    const user = userEvent.setup();
    await editAndSave(user);

    await waitFor(() => expect(toastError).toHaveBeenCalledTimes(1));
    const [message, options] = toastError.mock.calls[0]!;
    expect(String(message)).toContain("version 2");
    expect(String(message)).not.toMatch(/reload/i);
    expect(options.action.label).toBeTruthy();
    // Refused before anything was written.
    expect(writes.workflowPuts).toBe(0);
    expect(writes.agent).toBeUndefined();

    // Accepting repoints the agent at the workflow version this save creates.
    options.action.onClick();
    await waitFor(() => expect(writes.agent).toBeDefined());
    expect(writes.agent!.workflows).toEqual([
      "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=4",
    ]);
  });
});

describe("ResourceDetailPage — viewers", () => {
  it("shows a viewer the config without save controls", async () => {
    server.use(
      http.get("*/descriptorstore/descriptors/:id", ({ request }) => {
        const version = Number(new URL(request.url).searchParams.get("version") ?? 1);
        return HttpResponse.json({
          resource: `eddi://ai.labs.llm/llmstore/llms/res1?version=${version}`,
          name: "Shared LLM",
          description: "",
          createdOn: 1,
          lastModifiedOn: 2,
          callerLevel: "VIEW",
        });
      }),
    );
    renderPage(
      "/manage/resources/llm/res1?wfId=wf1&wfVer=1&agentId=agent1&agentVer=1",
      <ResourceDetailPage />,
      ROUTE,
    );
    await screen.findByText("Shared LLM");
    await screen.findByTestId("config-editor-layout");
    expect(screen.queryByTestId("save-btn")).not.toBeInTheDocument();
    expect(screen.queryByTestId("save-test-btn")).not.toBeInTheDocument();
    expect(screen.queryByTestId("compatible-version-checkbox")).not.toBeInTheDocument();
    expect(screen.queryByTestId("resource-title-edit")).not.toBeInTheDocument();
  });
});

describe("ResourceDetailPage — the version the link names", () => {
  beforeEach(() => {
    server.use(http.get("*/llmstore/llms/:id/currentversion", () => HttpResponse.json(3)));
  });

  it("opens the named version and offers the newest", async () => {
    renderPage("/manage/resources/llm/res1?version=2", <ResourceDetailPage />, ROUTE);
    await waitFor(() => expect(screen.getByTestId("version-picker")).toHaveValue("2"));
    expect(screen.getByTestId("old-version-notice")).toBeInTheDocument();

    await userEvent.setup().click(screen.getByRole("button", { name: "Switch to latest" }));
    await waitFor(() => expect(screen.getByTestId("version-picker")).toHaveValue("3"));
    expect(screen.queryByTestId("old-version-notice")).not.toBeInTheDocument();
  });

  it("opens the latest when the link names none", async () => {
    renderPage("/manage/resources/llm/res1", <ResourceDetailPage />, ROUTE);
    await waitFor(() => expect(screen.getByTestId("version-picker")).toHaveValue("3"));
    expect(screen.queryByTestId("old-version-notice")).not.toBeInTheDocument();
  });

  it("ignores a version the resource does not have", async () => {
    renderPage("/manage/resources/llm/res1?version=99", <ResourceDetailPage />, ROUTE);
    await waitFor(() => expect(screen.getByTestId("version-picker")).toHaveValue("3"));
  });
});

describe("ResourceDetailPage — renaming", () => {
  it("renames the resource in place", async () => {
    const patches: unknown[] = [];
    server.use(
      http.patch("*/descriptorstore/descriptors/:id", async ({ request }) => {
        patches.push(await request.json());
        return new HttpResponse(null, { status: 200 });
      }),
    );
    renderPage("/manage/resources/llm/res1", <ResourceDetailPage />, ROUTE);
    const user = userEvent.setup();
    await user.click(await screen.findByTestId("resource-title-edit"));
    const name = screen.getByTestId("resource-title-name");
    await user.clear(name);
    await user.type(name, "Friendly LLM");
    await user.click(screen.getByTestId("resource-title-save"));
    await waitFor(() => expect(patches).toHaveLength(1));
    expect(patches[0]).toMatchObject({ document: { name: "Friendly LLM" } });
  });
});
