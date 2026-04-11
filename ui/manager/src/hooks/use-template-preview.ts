import { useMutation } from "@tanstack/react-query";
import {
  previewTemplate,
  type TemplatePreviewRequest,
  type TemplatePreviewResponse,
} from "@/lib/api/template-preview";

/**
 * TanStack Query mutation for previewing a Qute template.
 *
 * Usage:
 * ```tsx
 * const { mutate, data, isPending } = useTemplatePreview();
 * mutate({ template: "Hello {properties.userName}", conversationId: "conv-123" });
 * ```
 */
export function useTemplatePreview() {
  return useMutation<TemplatePreviewResponse, Error, TemplatePreviewRequest>({
    mutationFn: previewTemplate,
  });
}
