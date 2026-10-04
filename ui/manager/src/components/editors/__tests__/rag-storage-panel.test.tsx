import { describe, it, expect, vi } from "vitest";
import { useState } from "react";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { RagStoragePanel } from "../rag-storage-panel";
import {
  idLayoutLocation,
  isReservedLocation,
  locationParameter,
  locationProblem,
  usesIdNamespace,
} from "@/lib/rag-storage";

describe("rag-storage rules (mirror of KnowledgeBaseStorage / KnowledgeBaseStorageGuard)", () => {
  it("names the location parameter per store type", () => {
    expect(locationParameter("pgvector")).toBe("table");
    expect(locationParameter("mongodb-atlas")).toBe("collectionName");
    expect(locationParameter("qdrant")).toBe("collectionName");
    expect(locationParameter("chroma")).toBe("collectionName");
    expect(locationParameter("elasticsearch")).toBe("indexName");
    expect(locationParameter("in-memory")).toBeNull();
  });

  it("flags a ${…} reference before anything else", () => {
    expect(locationProblem("pgvector", "${vars:kb-table}")).toBe("reference");
    expect(locationProblem("qdrant", "kb_${vars:x}")).toBe("reference");
  });

  it("refuses names in EDDI's own namespace in any dot-separated part and any case", () => {
    expect(isReservedLocation("eddi_kb_alpha")).toBe(true);
    expect(isReservedLocation("public.EDDI_KB_alpha")).toBe(true);
    expect(isReservedLocation("eddi_kbid_123")).toBe(true);
    expect(isReservedLocation("my_eddi_kb")).toBe(false);
    expect(locationProblem("qdrant", "Eddi_Kb_x")).toBe("reserved");
  });

  it("requires a plain, optionally schema-qualified pgvector identifier", () => {
    expect(locationProblem("pgvector", "docs")).toBeNull();
    expect(locationProblem("pgvector", "rag.docs_1")).toBeNull();
    expect(locationProblem("pgvector", '"eddi_kb_alpha"')).toBe("pgIdentifier");
    expect(locationProblem("pgvector", "docs; drop table x")).toBe("pgIdentifier");
    expect(locationProblem("pgvector", "/**/eddi_kb_alpha")).toBe("pgIdentifier");
    // Other stores do not put the name into SQL.
    expect(locationProblem("qdrant", "kb-product-docs")).toBeNull();
  });

  it("allows a blank location — the knowledge base's own default", () => {
    expect(locationProblem("pgvector", "")).toBeNull();
    expect(locationProblem("pgvector", undefined)).toBeNull();
  });

  it("derives the per-id default location like the server", () => {
    expect(idLayoutLocation("pgvector", "64F1aB")).toBe("eddi_kbid_64f1ab");
    expect(idLayoutLocation("pgvector", "x".repeat(80))).toHaveLength(63);
    expect(idLayoutLocation("qdrant", "a-b")).toBe("eddi_kbid_a_b");
    expect(idLayoutLocation("in-memory", "a")).toBeNull();
    expect(idLayoutLocation("pgvector", undefined)).toBeNull();
  });

  it("reads absent and 'name' as the 6.5 layout", () => {
    expect(usesIdNamespace("id")).toBe(true);
    expect(usesIdNamespace("name")).toBe(false);
    expect(usesIdNamespace(undefined)).toBe(false);
  });
});

type Cfg = { name?: string; storeType?: string; storeParameters?: Record<string, string>; storeNamespace?: string };

function Harness({ initial, spy, readOnly }: { initial: Cfg; spy?: (c: Cfg) => void; readOnly?: boolean }) {
  const [data, setData] = useState(initial);
  return (
    <RagStoragePanel
      data={data}
      resourceId="64f1ab"
      readOnly={readOnly}
      onChange={(c) => {
        setData(c);
        spy?.(c);
      }}
    />
  );
}

describe("RagStoragePanel", () => {
  it("flags a ${…} location with the server's reasoning", () => {
    renderWithProviders(<Harness initial={{ storeType: "pgvector", storeParameters: { table: "${vars:kb}" }, storeNamespace: "id" }} />);
    const alert = screen.getByTestId("rag-location-reference");
    expect(alert).toHaveAttribute("role", "alert");
    expect(alert).toHaveTextContent(/stops ingesting and retrieving until it is saved once with the literal name/);
  });

  it("flags a reserved name", () => {
    renderWithProviders(<Harness initial={{ storeType: "qdrant", storeParameters: { collectionName: "eddi_kb_alpha" }, storeNamespace: "id" }} />);
    expect(screen.getByTestId("rag-location-reserved")).toHaveTextContent(/eddi_kb/);
  });

  it("shows nothing for an in-memory store", () => {
    renderWithProviders(<Harness initial={{ storeType: "in-memory" }} />);
    expect(screen.queryByTestId("rag-storage-panel")).not.toBeInTheDocument();
  });

  it("shows the own-store layout and its default location, with no migration offered", () => {
    renderWithProviders(<Harness initial={{ name: "docs", storeType: "pgvector", storeNamespace: "id" }} />);
    expect(screen.getByTestId("rag-storage-layout")).toHaveAttribute("data-layout", "id");
    expect(screen.getByTestId("rag-storage-location")).toHaveTextContent("eddi_kbid_64f1ab");
    expect(screen.queryByTestId("rag-migrate-own-store")).not.toBeInTheDocument();
  });

  it("migrates a 6.5 knowledge base only after the warning is confirmed", async () => {
    const spy = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<Harness initial={{ name: "docs", storeType: "pgvector" }} spy={spy} />);
    expect(screen.getByTestId("rag-storage-layout")).toHaveAttribute("data-layout", "name");

    await user.click(screen.getByTestId("rag-migrate-own-store"));
    expect(spy).not.toHaveBeenCalled();
    expect(screen.getByText(/documents added through \/ingest must be ingested again/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Move when I save" }));

    expect(spy).toHaveBeenLastCalledWith(expect.objectContaining({ storeNamespace: "id", name: "docs" }));
    expect(screen.getByTestId("rag-storage-layout")).toHaveAttribute("data-layout", "id");
  });

  it("warns that a migrated knowledge base with its own table starts empty", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness initial={{ name: "docs", storeType: "pgvector", storeParameters: { table: "shared_docs" } }} />);
    await user.click(screen.getByTestId("rag-migrate-own-store"));
    expect(screen.getByText(/it starts EMPTY/)).toBeInTheDocument();
  });

  it("offers no migration when read-only", () => {
    renderWithProviders(<Harness initial={{ name: "docs", storeType: "pgvector" }} readOnly />);
    expect(screen.queryByTestId("rag-migrate-own-store")).not.toBeInTheDocument();
  });
});
