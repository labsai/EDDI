import { api } from "@/lib/api-client";

// ─── Types ───────────────────────────────────────────────────────────────────

export interface TemplatePreviewRequest {
  template: string;
  conversationId?: string;
}

export interface TemplatePreviewResponse {
  /** The fully resolved template text, or null if an error occurred */
  resolved: string | null;
  /** Flattened dot-path variable names available in the template data */
  availableVariables: string[];
  /** Map of variable dot-paths to their actual values */
  variableValues: Record<string, unknown>;
  /** Error message if resolution failed */
  error: string | null;
}

// ─── API ─────────────────────────────────────────────────────────────────────

/**
 * Preview a Qute template by resolving it against real conversation data
 * (if conversationId is provided) or built-in sample defaults.
 *
 * Uses the real backend Qute engine for 100% accurate rendering.
 */
export async function previewTemplate(
  request: TemplatePreviewRequest
): Promise<TemplatePreviewResponse> {
  return api.post<TemplatePreviewResponse>(
    "/administration/preview/template",
    request
  );
}
