const BASE_URL = window.location.origin;

/**
 * Step 1: Trigger export — backend prepares a zip and returns a Location header.
 * POST /backup/export/{botId}?botVersion={version}
 */
export async function exportBot(
  botId: string,
  version = 1
): Promise<string> {
  const res = await fetch(
    `${BASE_URL}/backup/export/${botId}?botVersion=${version}`,
    { method: "POST" }
  );
  if (!res.ok) {
    throw new Error(`Export failed: ${res.statusText}`);
  }
  // Location header contains the download path, e.g. /backup/export/mybot-abc-1.zip
  const location = res.headers.get("Location");
  if (!location) {
    throw new Error("Export succeeded but no Location header returned");
  }
  return location;
}

/**
 * Step 2: Download the zip file at the given path.
 * GET /backup/export/{filename}
 */
export async function downloadBotZip(downloadPath: string): Promise<void> {
  const url = downloadPath.startsWith("http")
    ? downloadPath
    : `${BASE_URL}${downloadPath}`;

  const res = await fetch(url);
  if (!res.ok) {
    throw new Error(`Download failed: ${res.statusText}`);
  }

  const blob = await res.blob();
  const filename = downloadPath.split("/").pop() || "bot-export.zip";

  // Trigger browser download
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(a.href);
}

/**
 * Combined: export + download in one call.
 */
export async function exportAndDownloadBot(
  botId: string,
  version = 1
): Promise<void> {
  const location = await exportBot(botId, version);
  await downloadBotZip(location);
}

/**
 * Import a bot from a zip file (create new — default strategy).
 * POST /backup/import with Content-Type: application/zip
 * Returns the Location of the newly created bot.
 */
export async function importBot(file: File): Promise<string> {
  const res = await fetch(`${BASE_URL}/backup/import`, {
    method: "POST",
    headers: { "Content-Type": "application/zip" },
    body: file,
  });

  if (!res.ok) {
    throw new Error(`Import failed: ${res.statusText}`);
  }

  const location = res.headers.get("Location");
  return location || "";
}

// ==================== Merge Import Types ====================

export interface ResourceDiff {
  originId: string;
  resourceType: string;
  name: string | null;
  action: "CREATE" | "UPDATE" | "SKIP";
  localId: string | null;
  localVersion: number | null;
}

export interface ImportPreview {
  botOriginId: string | null;
  botName: string | null;
  resources: ResourceDiff[];
}

/**
 * Preview what a merge import would do — does NOT modify data.
 * POST /backup/import/preview
 */
export async function previewImport(file: File): Promise<ImportPreview> {
  const res = await fetch(`${BASE_URL}/backup/import/preview`, {
    method: "POST",
    headers: { "Content-Type": "application/zip" },
    body: file,
  });

  if (!res.ok) {
    throw new Error(`Preview failed: ${res.statusText}`);
  }

  return res.json();
}

/**
 * Import a bot with merge strategy.
 * POST /backup/import?strategy=merge&selectedResources=...
 */
export async function importBotMerge(
  file: File,
  selectedOriginIds?: string[]
): Promise<string> {
  const params = new URLSearchParams({ strategy: "merge" });
  if (selectedOriginIds && selectedOriginIds.length > 0) {
    params.set("selectedResources", selectedOriginIds.join(","));
  }

  const res = await fetch(`${BASE_URL}/backup/import?${params}`, {
    method: "POST",
    headers: { "Content-Type": "application/zip" },
    body: file,
  });

  if (!res.ok) {
    throw new Error(`Merge import failed: ${res.statusText}`);
  }

  const location = res.headers.get("Location");
  return location || "";
}
