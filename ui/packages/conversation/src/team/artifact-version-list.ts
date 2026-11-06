import type { SharedArtifact } from "@eddi/core/groups";

/** One version of an artifact as the versions view shows it. */
export interface ArtifactVersionView {
  version: number;
  content: string;
  editorAgentId: string | null;
  /** When this content was written; for the current version, the artifact's last update. */
  at: string | null;
}

/**
 * An artifact's versions, newest first: the current content, then each
 * superseded revision the server still keeps. A revision carrying the current
 * version number (it should not happen) and duplicate numbers are kept once —
 * the newest wins — so a version can be picked by its number.
 */
export function artifactVersions(artifact: SharedArtifact): ArtifactVersionView[] {
  const seen = new Set<number>();
  const out: ArtifactVersionView[] = [];
  const push = (v: ArtifactVersionView) => {
    if (!Number.isFinite(v.version) || seen.has(v.version)) return;
    seen.add(v.version);
    out.push(v);
  };
  push({
    version: artifact.version,
    content: artifact.content ?? "",
    editorAgentId: artifact.lastEditorAgentId ?? null,
    at: artifact.updatedAt ?? artifact.createdAt ?? null,
  });
  const history = [...(artifact.history ?? [])].sort((a, b) => b.version - a.version);
  for (const revision of history) {
    push({
      version: revision.version,
      content: revision.content ?? "",
      editorAgentId: revision.editorAgentId ?? null,
      at: revision.at ?? null,
    });
  }
  return out;
}
