import { api, ApiClientError } from "../api-client";
import type { BrandAssetSlot } from "../brand";
import type { BrandColorValues } from "../brand-css";

/**
 * The deployment's brand, as `/administration/branding` (eddi-admin) stores and
 * answers it — `BrandingView` and its parts on the backend. Fields the server
 * leaves `null` are omitted from its JSON, so every one is optional here.
 */

export type BrandSource = "database" | "properties" | "default";

/** The content hash (`sha256:…`) of each slot's own image, or absent. */
export type BrandAssetHashes = Partial<Record<BrandAssetSlot, string | null>>;

/** What is stored: a missing or `null` field is unset. */
export interface BrandDocument {
  revision: number;
  productName?: string | null;
  shortName?: string | null;
  tagline?: string | null;
  colors?: Partial<BrandColorValues> | null;
  poweredBy?: boolean | null;
  showUpdateCheck?: boolean | null;
  assets?: BrandAssetHashes | null;
  updatedAt?: string | null;
  updatedBy?: string | null;
}

/** The brand in effect, after the seed properties and the defaults. */
export interface ResolvedBrand {
  revision: number;
  source?: string;
  locked?: boolean;
  branded: boolean;
  productName: string;
  shortName: string;
  tagline?: string | null;
  colors?: Partial<BrandColorValues> | null;
  poweredBy: boolean;
  showUpdateCheck: boolean;
  assets?: BrandAssetHashes | null;
}

export interface BrandingView {
  document?: BrandDocument | null;
  effective: ResolvedBrand;
  locked: boolean;
  /** The backend serialises the enum by name; compare case-insensitively. */
  source: string;
  warnings?: string[] | null;
}

/** The `PUT` body: a full replacement of the text fields, colours and toggles. */
export interface BrandUpdate {
  revision: number;
  productName: string | null;
  shortName: string | null;
  tagline: string | null;
  colors: BrandColorValues;
  poweredBy: boolean | null;
  showUpdateCheck: boolean | null;
}

export interface BrandViolation {
  field: string;
  message: string;
}

/** A refused branding write, with what the server said about it. */
export interface BrandingErrorInfo {
  status: number;
  message: string;
  violations: BrandViolation[];
  /** Set on a 409 revision conflict. */
  currentRevision: number | null;
}

/** Reads the structured body of a refused write (`RestBrandingAdmin.answer`). */
export function brandingErrorInfo(error: unknown): BrandingErrorInfo | null {
  if (!(error instanceof ApiClientError)) return null;
  const body = error.body && typeof error.body === "object" ? (error.body as Record<string, unknown>) : {};
  const violations = Array.isArray(body.violations)
    ? body.violations.filter(
        (v): v is BrandViolation =>
          !!v && typeof v === "object" && typeof (v as BrandViolation).field === "string" &&
          typeof (v as BrandViolation).message === "string",
      )
    : [];
  return {
    status: error.status,
    message: typeof body.error === "string" ? body.error : error.message,
    violations,
    currentRevision: typeof body.currentRevision === "number" ? body.currentRevision : null,
  };
}

const BASE = "/administration/branding";

export function getBranding(): Promise<BrandingView> {
  return api.get<BrandingView>(BASE);
}

export function updateBranding(update: BrandUpdate): Promise<BrandingView> {
  return api.put<BrandingView>(BASE, update);
}

export function resetBranding(): Promise<BrandingView> {
  return api.delete<BrandingView>(BASE);
}

/**
 * Upload one image as the raw request body. The server decides the type from
 * the magic bytes, so the `Content-Type` sent is only informative.
 */
export async function uploadBrandAsset(slot: BrandAssetSlot, file: Blob): Promise<BrandingView> {
  const data = await file.arrayBuffer();
  return api.put<BrandingView>(`${BASE}/assets/${slot}`, data, {
    "Content-Type": file.type || "application/octet-stream",
  });
}

export function deleteBrandAsset(slot: BrandAssetSlot): Promise<BrandingView> {
  return api.delete<BrandingView>(`${BASE}/assets/${slot}`);
}

/** The file name the login theme is saved under. */
export const KEYCLOAK_THEME_FILE_NAME = "brand-keycloak-theme.zip";

/**
 * Fetch the Keycloak login theme ZIP for the brand in effect
 * (`GET /administration/branding/keycloak-theme.zip`). A blob, not JSON, so it
 * goes through `fetch` with the same auth header `ApiClient` sends.
 */
export async function fetchKeycloakTheme(): Promise<Blob> {
  const res = await fetch(`${api.getBaseUrl()}${BASE}/keycloak-theme.zip`, { headers: api.getAuthHeader() });
  if (!res.ok) {
    throw new ApiClientError(res.status, `Download failed: ${res.status} ${res.statusText}`.trim());
  }
  return res.blob();
}

/** Save the login theme ZIP through the browser's download. */
export async function downloadKeycloakTheme(): Promise<void> {
  const blob = await fetchKeycloakTheme();
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = KEYCLOAK_THEME_FILE_NAME;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(a.href);
}
