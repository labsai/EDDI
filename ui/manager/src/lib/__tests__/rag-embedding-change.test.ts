import { describe, it, expect } from "vitest";
import { embeddingChanges } from "@/lib/rag-embedding-change";

const base = {
  embeddingProvider: "openai",
  embeddingParameters: { model: "text-embedding-3-small", dimension: "1536" },
  chunkSize: 512,
  chunkOverlap: 50,
};

describe("embeddingChanges", () => {
  it("is empty for an unchanged configuration, whatever else is edited", () => {
    const edited = { ...base, embeddingParameters: { ...base.embeddingParameters, apiKey: "x" } };
    expect(embeddingChanges(base, edited)).toEqual([]);
  });

  it("reports provider, model, dimension and chunking changes", () => {
    expect(embeddingChanges(base, { ...base, embeddingProvider: "ollama" })).toEqual(["provider"]);
    expect(
      embeddingChanges(base, { ...base, embeddingParameters: { ...base.embeddingParameters, model: "other" } }),
    ).toEqual(["model"]);
    expect(
      embeddingChanges(base, { ...base, embeddingParameters: { ...base.embeddingParameters, dimension: "3072" } }),
    ).toEqual(["dimension"]);
    expect(embeddingChanges(base, { ...base, chunkSize: 1024 })).toEqual(["chunking"]);
  });

  it("treats an absent provider as the default, so opening a legacy config is not a change", () => {
    expect(embeddingChanges({ ...base, embeddingProvider: undefined }, base)).toEqual([]);
  });
});
