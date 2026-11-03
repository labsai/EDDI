import { describe, it, expect, beforeEach } from "vitest";
import { screen, fireEvent, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { renderPage } from "@/test/test-utils";
import { resetModelPriceMock } from "@/test/mocks/model-prices-handlers";
import { ModelPricesPage } from "../model-prices";

describe("ModelPricesPage", () => {
  beforeEach(() => resetModelPriceMock());

  it("lists the catalog entries, the policy in force and the unpriced sightings", async () => {
    renderPage("/manage/model-prices", <ModelPricesPage />);
    expect(await screen.findByTestId("model-prices-page")).toBeInTheDocument();
    expect(screen.getAllByTestId("model-prices-row")).toHaveLength(2);
    expect(screen.getByTestId("model-prices-policy-select")).toHaveValue("ALLOW");
    expect(await screen.findAllByTestId("model-prices-sighting")).toHaveLength(1);
  });

  it("adds an entry through the side sheet and saves the whole catalog with its revision", async () => {
    let sent: { revision?: number; entries?: unknown[] } | null = null;
    let ifMatch: string | null = null;
    server.use(
      http.put("*/administration/model-prices", async ({ request }) => {
        ifMatch = request.headers.get("If-Match");
        sent = (await request.json()) as typeof sent;
        return HttpResponse.json({ ...(sent as object), revision: 2 });
      }),
    );
    renderPage("/manage/model-prices", <ModelPricesPage />);
    fireEvent.click(await screen.findByTestId("model-prices-add"));
    const sheet = screen.getByTestId("mp-sheet");
    fireEvent.change(within(sheet).getByTestId("mp-sheet-provider"), { target: { value: "anthropic" } });
    fireEvent.change(within(sheet).getByTestId("mp-sheet-model"), { target: { value: "claude-sonnet-4-6*" } });
    fireEvent.change(within(sheet).getByTestId("mp-sheet-inputPricePer1M"), { target: { value: "3" } });
    fireEvent.click(within(sheet).getByTestId("mp-sheet-apply"));

    expect(screen.getAllByTestId("model-prices-row")).toHaveLength(3);
    expect(screen.getByTestId("model-prices-dirty")).toBeInTheDocument();
    fireEvent.click(screen.getByTestId("model-prices-save"));

    await waitFor(() => expect(sent).not.toBeNull());
    expect(ifMatch).toBe("1");
    expect(sent!.revision).toBe(1);
    expect(sent!.entries).toHaveLength(3);
  });

  it("will not apply an entry the validator refuses", async () => {
    renderPage("/manage/model-prices", <ModelPricesPage />);
    fireEvent.click(await screen.findByTestId("model-prices-add"));
    const sheet = screen.getByTestId("mp-sheet");
    fireEvent.change(within(sheet).getByTestId("mp-sheet-provider"), { target: { value: "openai" } });
    fireEvent.change(within(sheet).getByTestId("mp-sheet-model"), { target: { value: "gpt*4o" } });
    expect(within(sheet).getByTestId("mp-sheet-problems")).toBeInTheDocument();
    expect(within(sheet).getByTestId("mp-sheet-apply")).toBeDisabled();
  });

  it("shows the conflict banner on a 409 instead of overwriting", async () => {
    server.use(
      http.put("*/administration/model-prices", () => HttpResponse.json({ error: "changed", currentRevision: 5 }, { status: 409 })),
    );
    renderPage("/manage/model-prices", <ModelPricesPage />);
    fireEvent.change(await screen.findByTestId("model-prices-policy-select"), { target: { value: "REFUSE" } });
    fireEvent.click(screen.getByTestId("model-prices-save"));
    expect(await screen.findByTestId("model-prices-conflict")).toBeInTheDocument();
  });

  it("previews the reference prices as a diff and adds only what is new or changed", async () => {
    renderPage("/manage/model-prices", <ModelPricesPage />);
    fireEvent.click(await screen.findByTestId("model-prices-load-reference"));
    const preview = await screen.findByTestId("model-prices-reference-preview");
    // gpt-4o is unchanged; claude-sonnet-4-6* is new
    expect(within(preview).getByTestId("model-prices-reference-diff").children).toHaveLength(1);
    fireEvent.click(within(preview).getByTestId("model-prices-reference-apply"));
    expect(screen.getAllByTestId("model-prices-row")).toHaveLength(3);
  });

  it("shows a failed sightings request with a retry instead of 'None seen'", async () => {
    server.use(http.get("*/administration/model-prices/unpriced", () => HttpResponse.json({ error: "down" }, { status: 500 })));
    renderPage("/manage/model-prices", <ModelPricesPage />);
    expect(await screen.findByTestId("model-prices-unpriced-error")).toBeInTheDocument();
    expect(screen.queryByTestId("model-prices-unpriced-empty")).toBeNull();
    expect(screen.getByTestId("model-prices-unpriced-retry")).toBeInTheDocument();
  });

  it("prefills the sheet from an unpriced sighting", async () => {
    renderPage("/manage/model-prices", <ModelPricesPage />);
    fireEvent.click(await screen.findByTestId("model-prices-sighting-add"));
    const sheet = screen.getByTestId("mp-sheet");
    expect(within(sheet).getByTestId("mp-sheet-provider")).toHaveValue("mistral");
    expect(within(sheet).getByTestId("mp-sheet-model")).toHaveValue("mistral-large-latest");
  });

  it("does not offer a policy looser than the operator's floor", async () => {
    server.use(
      http.get("*/administration/model-prices", () =>
        HttpResponse.json({
          catalog: { revision: 1, unpricedPolicy: "REFUSE", entries: [] },
          effectivePolicy: "REFUSE",
          floorPolicy: "REFUSE_WHEN_BUDGETED",
          knownProviders: ["openai"],
          staleAfterDays: 120,
          referenceAsOf: null,
        }),
      ),
    );
    renderPage("/manage/model-prices", <ModelPricesPage />);
    const select = await screen.findByTestId("model-prices-policy-select");
    const allow = within(select).getByRole("option", { name: "ALLOW" });
    expect(allow).toBeDisabled();
    expect(screen.getByTestId("model-prices-floor")).toBeInTheDocument();
    expect(screen.getByTestId("model-prices-empty")).toBeInTheDocument();
  });
});
