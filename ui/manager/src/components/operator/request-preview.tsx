import { useTranslation } from "react-i18next";
import type { ResolvedRequestPreview } from "@/lib/api/hitl";

interface RequestPreviewProps {
  preview: ResolvedRequestPreview;
  /** Whether this preview is re-checked immediately before execution — see
   *  `PendingToolCallView.requestPinned`. Controls which badge is shown; the
   *  preview content itself renders identically either way. */
  pinned: boolean;
  callId: string;
}

/**
 * The approver's honest view of what a gated call actually resolves to.
 *
 * Backend-verified (`IApiCallExecutor#resolve`), as opposed to
 * `reconstructEndpoint`'s client-side `operationId` guess — used as this
 * component's fallback only when a call carries no preview at all (see
 * `OperatorPage.renderCallExtra`).
 */
export function RequestPreview({ preview, pinned, callId }: RequestPreviewProps) {
  const { t } = useTranslation();
  const queryEntries = Object.entries(preview.queryParams ?? {});
  const headerEntries = Object.entries(preview.headers ?? {});

  return (
    <div className="mb-1.5 space-y-1" data-testid={`request-preview-${callId}`}>
      <p className="flex flex-wrap items-center gap-1.5 font-mono text-[11px] text-foreground">
        <span
          className={
            pinned
              ? "rounded bg-emerald-500/10 px-1 py-0.5 font-sans text-[10px] font-medium text-emerald-600"
              : "rounded bg-amber-500/10 px-1 py-0.5 font-sans text-[10px] font-medium text-amber-600"
          }
          title={
            pinned
              ? t(
                  "operator.approval.verifiedTitle",
                  "Resolved from the actual API call config; re-checked immediately before execution — if the request changes before then, execution is refused.",
                )
              : t(
                  "operator.approval.previewOnlyTitle",
                  "This call runs additional setup steps before executing, so this is a best-effort preview — the actual request may differ and is not re-checked before execution.",
                )
          }
          data-testid={`request-preview-badge-${callId}`}
        >
          {pinned ? t("operator.approval.verified", "verified") : t("operator.approval.previewOnly", "preview")}
        </span>
        {preview.method} {preview.uri}
      </p>
      {queryEntries.length > 0 && (
        <p className="font-mono text-[11px] text-muted-foreground">
          {t("operator.approval.query", "Query")}: {queryEntries.map(([k, v]) => `${k}=${v}`).join(", ")}
        </p>
      )}
      {headerEntries.length > 0 && (
        <p className="font-mono text-[11px] text-muted-foreground">
          {t("operator.approval.headers", "Headers")}: {headerEntries.map(([k, v]) => `${k}: ${v}`).join(", ")}
        </p>
      )}
      {preview.body != null && preview.body !== "" && (
        <pre
          className="max-h-32 overflow-auto rounded bg-muted/60 p-2 text-[11px] leading-relaxed text-foreground"
          data-testid={`request-preview-body-${callId}`}
        >
          {preview.body}
        </pre>
      )}
      {preview.bodyTruncated && (
        <p className="text-[10px] text-muted-foreground">
          {t(
            "operator.approval.bodyTruncated",
            "Body shown truncated for display — approval still covers the full request.",
          )}
        </p>
      )}
    </div>
  );
}
