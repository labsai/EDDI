/**
 * What makes a knowledge base's stored vectors stale when it changes.
 *
 * Vectors are only comparable inside one embedding space: a different provider,
 * model or dimension puts new queries in a space the stored chunks were never
 * embedded in, and a different chunk size or overlap leaves old chunks cut the
 * old way. The backend does not re-embed on a config save, so the editor says so
 * while the author is still free to back out.
 */

export interface EmbeddingSettings {
  embeddingProvider?: string;
  embeddingParameters?: Record<string, string>;
  chunkStrategy?: string;
  chunkSize?: number;
  chunkOverlap?: number;
}

export type EmbeddingChange = "provider" | "model" | "dimension" | "chunking";

/** The parameter names providers use for the model / deployment and for the vector size. */
const MODEL_KEYS = ["model", "modelName", "deploymentName"];
const DIMENSION_KEYS = ["dimension", "dimensions", "outputDimensionality"];

function firstParam(params: Record<string, string> | undefined, keys: string[]): string {
  for (const key of keys) {
    const value = params?.[key];
    if (value !== undefined && String(value).trim() !== "") return String(value).trim();
  }
  return "";
}

/** Which embedding-space or chunking settings differ between the saved and the edited configuration. */
export function embeddingChanges(before: EmbeddingSettings, after: EmbeddingSettings): EmbeddingChange[] {
  const changes: EmbeddingChange[] = [];
  if ((before.embeddingProvider ?? "openai") !== (after.embeddingProvider ?? "openai")) changes.push("provider");
  if (firstParam(before.embeddingParameters, MODEL_KEYS) !== firstParam(after.embeddingParameters, MODEL_KEYS)) {
    changes.push("model");
  }
  if (
    firstParam(before.embeddingParameters, DIMENSION_KEYS) !== firstParam(after.embeddingParameters, DIMENSION_KEYS)
  ) {
    changes.push("dimension");
  }
  if (
    (before.chunkStrategy ?? "").trim().toLowerCase() !== (after.chunkStrategy ?? "").trim().toLowerCase() ||
    (before.chunkSize ?? null) !== (after.chunkSize ?? null) ||
    (before.chunkOverlap ?? null) !== (after.chunkOverlap ?? null)
  ) {
    changes.push("chunking");
  }
  return changes;
}
