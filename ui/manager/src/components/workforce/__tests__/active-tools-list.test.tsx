import { describe, it, expect } from "vitest";
import { screen, fireEvent, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { renderWithProviders } from "@/test/test-utils";
import { ActiveToolsList } from "@/components/workforce/active-tools-list";
import { collectWorkflowToolRefs } from "@/lib/agent-tool-refs";
import type { WorkflowConfiguration, WorkflowExtension } from "@/lib/api/workflows";

const apiUri = (id: string, v = 1) => `eddi://ai.labs.apicalls/apicallstore/apicalls/${id}?version=${v}`;
const mcpUri = (id: string, v = 1) => `eddi://ai.labs.mcpcalls/mcpcallsstore/mcpcalls/${id}?version=${v}`;
const step = (type: string, uri?: string): WorkflowExtension => ({
  type,
  extensions: {},
  config: uri ? { uri } : {},
});
const httpStep = (id: string, v = 1) => step("eddi://ai.labs.httpcalls", apiUri(id, v));
const workflow = (...steps: WorkflowExtension[]): WorkflowConfiguration => ({ workflowSteps: steps });

const ids = Array.from({ length: 6 }, (_, i) => `6aed002c91a0000000000${i}`);
const [ID0, ID1, , , , ID5] = ids as [string, string, string, string, string, string];
const ON = { http: true, mcp: true };

describe("collectWorkflowToolRefs", () => {
  it("collects httpcalls and mcpcalls steps across every workflow, deduplicated", () => {
    const refs = collectWorkflowToolRefs(
      [
        workflow(step("eddi://ai.labs.parser"), httpStep(ID0, 2), httpStep(ID0, 2)),
        undefined,
        workflow(step("eddi://ai.labs.mcpcalls", mcpUri(ID1, 3)), httpStep(ID0, 2)),
      ],
      ON,
    );
    expect(refs.map((r) => [r.id, r.version, r.kind, r.slug])).toEqual([
      [ID0, 2, "http", "apicalls"],
      [ID1, 3, "mcp", "mcpcalls"],
    ]);
  });

  it("gives no slug to a URI naming an unknown store, falling back to the host only without a store", () => {
    const [unknownStore, noStore] = collectWorkflowToolRefs(
      [
        workflow(
          step("eddi://ai.labs.httpcalls", `eddi://ai.labs.apicalls/unknownstore/apicalls/${ID0}?version=1`),
          step("eddi://ai.labs.httpcalls", `eddi://ai.labs.apicalls/${ID1}?version=1`),
        ),
      ],
      ON,
    );
    expect(unknownStore?.slug).toBeNull();
    expect(noStore?.slug).toBe("apicalls");
  });

  it("ignores the apicalls alias step type, as the backend's tool discovery does", () => {
    expect(collectWorkflowToolRefs([workflow(step("eddi://ai.labs.apicalls", apiUri(ID0)))], ON)).toEqual([]);
  });

  it("drops a category its switch turns off, and steps without a config uri", () => {
    const wf = workflow(httpStep(ID0), step("eddi://ai.labs.mcpcalls", mcpUri(ID1)), step("eddi://ai.labs.httpcalls"));
    expect(collectWorkflowToolRefs([wf], { http: false, mcp: true }).map((r) => r.id)).toEqual([ID1]);
    expect(collectWorkflowToolRefs([wf], { http: true, mcp: false }).map((r) => r.id)).toEqual([ID0]);
  });
});

describe("ActiveToolsList", () => {
  it("shows workflow tool sets by descriptor name, linked, and collapses a long list", async () => {
    server.use(
      http.get("*/descriptorstore/descriptors/:id", ({ params }) =>
        HttpResponse.json({ name: `Tool ${String(params.id).slice(-1)}`, resource: "" }),
      ),
    );
    renderWithProviders(<ActiveToolsList task={{}} workflows={[workflow(...ids.map((id) => httpStep(id)))]} />);

    expect(await screen.findByText("Tool 0")).toBeInTheDocument();
    const row = screen.getByTestId(`active-tool-${ID0}`);
    expect(within(row).getByRole("link")).toHaveAttribute("href", `/manage/resources/apicalls/${ID0}?version=1`);
    expect(row).not.toHaveTextContent("eddi://");
    expect(screen.queryByTestId(`active-tool-${ID5}`)).not.toBeInTheDocument();
    expect(screen.getByTestId("active-tools-count")).toHaveTextContent("6");

    fireEvent.click(screen.getByTestId("active-tools-toggle"));
    expect(await screen.findByText("Tool 5")).toBeInTheDocument();
  });

  it("does not list the task's own tools entries, which the backend never resolves", () => {
    renderWithProviders(<ActiveToolsList task={{ tools: [apiUri(ID0)] }} workflows={[]} />);
    expect(screen.queryByTestId(`active-tool-${ID0}`)).not.toBeInTheDocument();
    expect(screen.getByTestId("active-tools-empty")).toBeInTheDocument();
  });

  it("hides workflow HTTP tools when enableHttpCallTools is explicitly false", () => {
    renderWithProviders(
      <ActiveToolsList task={{ enableHttpCallTools: false }} workflows={[workflow(httpStep(ID0))]} />,
    );
    expect(screen.queryByTestId(`active-tool-${ID0}`)).not.toBeInTheDocument();
  });

  it("falls back to a short id when the descriptor cannot be read", async () => {
    server.use(http.get("*/descriptorstore/descriptors/:id", () => new HttpResponse(null, { status: 404 })));
    renderWithProviders(<ActiveToolsList task={{}} workflows={[workflow(httpStep(ID0))]} />);
    expect(await screen.findByText(`${ID0.slice(0, 10)}…`)).toBeInTheDocument();
  });

  it("shows built-ins only when enableBuiltInTools is on", () => {
    const { unmount } = renderWithProviders(
      <ActiveToolsList task={{ builtInToolsWhitelist: ["calculator"] }} workflows={[]} enableMemoryTools />,
    );
    expect(screen.queryByTestId("active-tools-builtin")).not.toBeInTheDocument();
    expect(screen.getByTestId("active-tools-count")).toHaveTextContent("1");
    unmount();

    renderWithProviders(
      <ActiveToolsList
        task={{ enableBuiltInTools: true, builtInToolsWhitelist: ["calculator", "datetime"] }}
        workflows={[]}
        enableMemoryTools
      />,
    );
    const builtin = screen.getByTestId("active-tools-builtin");
    expect(builtin).toHaveTextContent("calculator");
    expect(builtin).toHaveTextContent("datetime");
    expect(screen.getByTestId("active-tools-switches").children).toHaveLength(1);
    expect(screen.getByTestId("active-tools-count")).toHaveTextContent("3");
  });

  it("shows a loading state while workflows load, then the empty state", () => {
    const { rerender } = renderWithProviders(<ActiveToolsList task={{}} workflows={[undefined]} workflowsLoading />);
    expect(screen.getByTestId("active-tools-loading")).toBeInTheDocument();
    rerender(<ActiveToolsList task={{}} workflows={[]} />);
    expect(screen.getByTestId("active-tools-empty")).toBeInTheDocument();
  });
});
