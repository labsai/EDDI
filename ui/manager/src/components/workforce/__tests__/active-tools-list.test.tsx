import { describe, it, expect } from "vitest";
import { screen, fireEvent, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { renderWithProviders } from "@/test/test-utils";
import { ActiveToolsList } from "@/components/workforce/active-tools-list";
import { parseToolResourceRefs } from "@/lib/agent-tool-refs";

const apiUri = (id: string, v = 1) => `eddi://ai.labs.apicalls/apicallstore/apicalls/${id}?version=${v}`;
const ids = Array.from({ length: 6 }, (_, i) => `6aed002c91a0000000000${i}`);
const [ID0, ID1, , , , ID5] = ids as [string, string, string, string, string, string];

describe("parseToolResourceRefs", () => {
  it("parses id, version and slug, dropping blanks and duplicates", () => {
    const refs = parseToolResourceRefs([
      apiUri(ID0, 2),
      apiUri(ID0, 2),
      " ",
      `eddi://ai.labs.mcpcalls/mcpcallsstore/mcpcalls/${ID1}?version=3`,
      "eddi://ai.labs.unknown/nostore/things/abc?version=1",
    ]);
    expect(refs.map((r) => [r.id, r.version, r.slug])).toEqual([
      [ID0, 2, "apicalls"],
      [ID1, 3, "mcpcalls"],
      ["abc", 1, null],
    ]);
  });

  it("returns an empty list for no tools", () => {
    expect(parseToolResourceRefs(undefined)).toEqual([]);
  });
});

describe("ActiveToolsList", () => {
  it("shows tool resources by descriptor name, linked, and collapses a long list", async () => {
    server.use(
      http.get("*/descriptorstore/descriptors/:id", ({ params }) =>
        HttpResponse.json({ name: `Tool ${String(params.id).slice(-1)}`, resource: "" })
      )
    );
    renderWithProviders(<ActiveToolsList task={{ tools: ids.map((id) => apiUri(id)) }} />);

    expect(await screen.findByText("Tool 0")).toBeInTheDocument();
    const row = screen.getByTestId(`active-tool-${ID0}`);
    expect(within(row).getByRole("link")).toHaveAttribute("href", `/manage/resources/apicalls/${ID0}`);
    expect(row).not.toHaveTextContent("eddi://");
    expect(screen.queryByTestId(`active-tool-${ID5}`)).not.toBeInTheDocument();
    expect(screen.getByTestId("active-tools-count")).toHaveTextContent("6");

    fireEvent.click(screen.getByTestId("active-tools-toggle"));
    expect(await screen.findByText("Tool 5")).toBeInTheDocument();
  });

  it("falls back to a short id when the descriptor cannot be read", async () => {
    server.use(http.get("*/descriptorstore/descriptors/:id", () => new HttpResponse(null, { status: 404 })));
    renderWithProviders(<ActiveToolsList task={{ tools: [apiUri(ID0)] }} />);
    expect(await screen.findByText(`${ID0.slice(0, 10)}…`)).toBeInTheDocument();
  });

  it("groups built-ins and category switches, and does not list capabilities", () => {
    renderWithProviders(
      <ActiveToolsList
        task={{ builtInToolsWhitelist: ["calculator", "datetime"], enableHttpCallTools: true }}
        enableMemoryTools
      />
    );
    const builtin = screen.getByTestId("active-tools-builtin");
    expect(builtin).toHaveTextContent("calculator");
    expect(builtin).toHaveTextContent("datetime");
    expect(screen.getByTestId("active-tools-switches").children).toHaveLength(2);
    expect(screen.getByTestId("active-tools-count")).toHaveTextContent("4");
  });

  it("shows the empty state when nothing is configured", () => {
    renderWithProviders(<ActiveToolsList task={{}} />);
    expect(screen.getByTestId("active-tools-empty")).toBeInTheDocument();
  });
});
