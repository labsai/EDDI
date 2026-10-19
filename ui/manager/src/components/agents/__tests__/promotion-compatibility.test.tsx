import { describe, it, expect, afterEach, vi } from "vitest";
import { useState } from "react";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import { PromotionCompatibilityPanel } from "@/components/agents/promotion-compatibility-panel";
import { PreviewStep } from "@/components/agents/import-steps/preview-step";
import { writesOverExistingAgent } from "@/lib/promotion-impact";
import {
  executeSync,
  executeSyncBatch,
  importAgentMerge,
  importAgentUpgrade,
  type ImportPreview,
  type PromotionImpact,
} from "@/lib/api/backup";

afterEach(() => {
  server.resetHandlers();
  vi.restoreAllMocks();
});

function impact(over: Partial<PromotionImpact> = {}): PromotionImpact {
  return {
    currentVersion: 4,
    currentGeneration: 2,
    generationIfCompatible: 2,
    generationIfBreaking: 3,
    startsNewChain: false,
    activeConversations: 12,
    followingIfCompatible: 9,
    deployedVersions: [
      { version: 4, compatibilityGeneration: 2, activeConversations: 9, followsIfCompatible: true },
      { version: 2, compatibilityGeneration: 1, activeConversations: 3, followsIfCompatible: false },
    ],
    ...over,
  };
}

function Harness({ value }: { value: PromotionImpact | null | undefined }) {
  const [compatible, setCompatible] = useState(false);
  return (
    <PromotionCompatibilityPanel impact={value} compatible={compatible} onCompatibleChange={setCompatible} />
  );
}

describe("PromotionCompatibilityPanel", () => {
  it("is unticked by default and says every running conversation stays", () => {
    renderWithProviders(<Harness value={impact()} />);

    expect(screen.getByTestId("promotion-compatibility-checkbox")).not.toBeChecked();
    const line = screen.getByTestId("promotion-compatibility-impact");
    expect(line).toHaveAttribute("data-outcome", "STAY");
    expect(line).toHaveTextContent("12");
  });

  it("ticking it reports how many follow, from the same preview, with no further request", async () => {
    renderWithProviders(<Harness value={impact()} />);

    await userEvent.click(screen.getByTestId("promotion-compatibility-checkbox"));

    const line = screen.getByTestId("promotion-compatibility-impact");
    expect(line).toHaveAttribute("data-outcome", "FOLLOW");
    expect(line).toHaveTextContent("9");
    expect(line).toHaveTextContent("12");
  });

  it("a current version that predates generations says its conversations stay, and nothing follows", async () => {
    renderWithProviders(
      <Harness
        value={impact({
          currentGeneration: null,
          startsNewChain: true,
          generationIfCompatible: 1,
          generationIfBreaking: 1,
          followingIfCompatible: 0,
        })}
      />,
    );

    await userEvent.click(screen.getByTestId("promotion-compatibility-checkbox"));

    expect(screen.getByTestId("promotion-compatibility-checkbox-note")).toBeInTheDocument();
    expect(screen.getByTestId("promotion-compatibility-impact")).toHaveAttribute("data-outcome", "STAY");
  });

  it("says nothing about conversations when there are none, or when the counts are withheld", () => {
    const { unmount } = renderWithProviders(<Harness value={impact({ activeConversations: 0, followingIfCompatible: 0 })} />);
    expect(screen.queryByTestId("promotion-compatibility-impact")).not.toBeInTheDocument();
    unmount();

    renderWithProviders(<Harness value={null} />);
    expect(screen.getByTestId("promotion-compatibility-checkbox")).toBeInTheDocument();
    expect(screen.queryByTestId("promotion-compatibility-impact")).not.toBeInTheDocument();
  });
});

describe("writesOverExistingAgent", () => {
  const base: ImportPreview = {
    sourceAgentId: "s",
    sourceAgentName: null,
    targetAgentId: null,
    targetAgentName: null,
    resources: [],
  };

  it("is true with a target, or an agent row that is not a create", () => {
    expect(writesOverExistingAgent({ ...base, targetAgentId: "t" })).toBe(true);
    expect(
      writesOverExistingAgent({
        ...base,
        resources: [
          { sourceId: "a", resourceType: "agent", name: null, action: "UPDATE", targetId: "t", targetVersion: 1, matchStrategy: "originId", sourceContent: null, targetContent: null, workflowIndex: -1 },
        ],
      }),
    ).toBe(true);
  });

  it("is false for a new agent: nothing is running on it", () => {
    expect(
      writesOverExistingAgent({
        ...base,
        resources: [
          { sourceId: "a", resourceType: "agent", name: null, action: "CREATE", targetId: null, targetVersion: null, matchStrategy: null, sourceContent: null, targetContent: null, workflowIndex: -1 },
        ],
      }),
    ).toBe(false);
  });
});

describe("PreviewStep", () => {
  const noop = () => {};
  function renderStep(preview: ImportPreview) {
    return renderWithProviders(
      <PreviewStep
        preview={preview}
        strategy="upgrade"
        selected={new Set()}
        expandedDiff={null}
        workflowOrder={[]}
        isLoading={false}
        error={null}
        compatible={false}
        onCompatibleChange={noop}
        onToggleResource={noop}
        onToggleAll={noop}
        onExpandDiff={noop}
        onMoveWorkflow={noop}
        onBack={noop}
        onExecute={noop}
      />,
    );
  }

  it("offers the choice, with the impact, when the import writes over an existing agent", () => {
    renderStep({
      sourceAgentId: "s",
      sourceAgentName: "Src",
      targetAgentId: "t",
      targetAgentName: "Tgt",
      resources: [],
      conversationImpact: impact(),
    });

    expect(screen.getByTestId("import-compatibility-checkbox")).not.toBeChecked();
    expect(screen.getByTestId("import-compatibility-impact")).toBeInTheDocument();
  });

  it("does not offer it for a new agent", () => {
    renderStep({ sourceAgentId: "s", sourceAgentName: "Src", targetAgentId: null, targetAgentName: null, resources: [] });

    expect(screen.queryByTestId("import-compatibility")).not.toBeInTheDocument();
  });
});

describe("compatible reaches the backend only when asked for", () => {
  function capture(path: string) {
    const seen: URL[] = [];
    server.use(
      http.post(path, ({ request }) => {
        seen.push(new URL(request.url));
        return HttpResponse.json({ agentUri: null, agentUpdated: true, updated: 1, created: 0, skipped: 0, failures: [] }, { status: 201 });
      }),
    );
    return seen;
  }

  it("upgrade import", async () => {
    const seen = capture("*/backup/import");
    await importAgentUpgrade(new File(["z"], "a.zip"), "tgt");
    await importAgentUpgrade(new File(["z"], "a.zip"), "tgt", undefined, undefined, true);

    expect(seen[0]!.searchParams.has("compatible")).toBe(false);
    expect(seen[1]!.searchParams.get("compatible")).toBe("true");
  });

  it("merge import", async () => {
    const seen = capture("*/backup/import");
    await importAgentMerge(new File(["z"], "a.zip"));
    await importAgentMerge(new File(["z"], "a.zip"), undefined, true);

    expect(seen[0]!.searchParams.has("compatible")).toBe(false);
    expect(seen[1]!.searchParams.get("compatible")).toBe("true");
  });

  it("single sync", async () => {
    const seen = capture("*/backup/import/sync");
    await executeSync("https://src", "a", 1, "tgt", null, null, "");
    await executeSync("https://src", "a", 1, "tgt", null, null, "", false, true);

    expect(seen[0]!.searchParams.has("compatible")).toBe(false);
    expect(seen[1]!.searchParams.get("compatible")).toBe("true");
  });

  it("batch sync carries it per request", async () => {
    let body: unknown;
    server.use(
      http.post("*/backup/import/sync/batch", async ({ request }) => {
        body = await request.json();
        return HttpResponse.json([]);
      }),
    );
    await executeSyncBatch(
      "https://src",
      [
        { sourceAgentId: "a", sourceAgentVersion: 1, targetAgentId: "t", selectedResources: null, workflowOrder: null, compatible: true },
        { sourceAgentId: "b", sourceAgentVersion: 1, targetAgentId: "u", selectedResources: null, workflowOrder: null },
      ],
      "",
    ).catch(() => undefined);

    expect(body).toEqual([
      expect.objectContaining({ sourceAgentId: "a", compatible: true }),
      expect.not.objectContaining({ compatible: expect.anything() }),
    ]);
  });
});
