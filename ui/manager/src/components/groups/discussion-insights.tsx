import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";
import { ArtifactsPanel } from "./artifacts-panel";
import { NegotiationLedger } from "./negotiation-ledger";
import type {
  ArtifactUpdatedPayload,
  GroupConversation,
  RetroRecordedPayload,
} from "@/lib/api/groups";

interface DiscussionInsightsProps {
  /**
   * The persisted conversation, when one is being viewed. Everything except the
   * live badges reads from here — artifacts, the negotiation ledger and the
   * windowing summary are all persisted-only (no incremental SSE event carries
   * them), and `artifacts` in particular is populated ONLY by the
   * single-conversation GET.
   */
  conversation?: GroupConversation | null;
  /** `retro_recorded` events seen on a live stream (I8). Live-only — the count is not persisted. */
  retroRecorded?: RetroRecordedPayload[];
  /** `artifact_updated` events seen on a live stream (I17). Live-only, metadata only. */
  artifactUpdates?: ArtifactUpdatedPayload[];
  className?: string;
}

/**
 * The cross-cutting "what else happened in this discussion" panels — shared by
 * every surface that renders a group transcript (the Manager's
 * `DiscussionTranscript`, the Workforce board and the Workforce history
 * viewer).
 *
 * Deliberately one component rather than per-surface copies: this codebase has
 * three independent transcript renderers, and the last time a group feature was
 * added to only some of them a DISSENT rendered as an ordinary opinion on two
 * of the three. A shared component makes "every surface shows this" structural
 * instead of a thing to remember.
 *
 * Renders nothing at all when there is nothing to show, so callers can drop it
 * in unconditionally.
 */
export function DiscussionInsights({
  conversation,
  retroRecorded,
  artifactUpdates,
  className,
}: DiscussionInsightsProps) {
  const { t } = useTranslation();

  const artifacts = conversation?.artifacts ?? [];
  const negotiation = conversation?.negotiation ?? null;
  const summaryUpTo = conversation?.summaryUpToIndex ?? 0;
  const retros = retroRecorded ?? [];
  const updates = artifactUpdates ?? [];

  const hasNegotiation =
    !!negotiation &&
    ((negotiation.proposals?.length ?? 0) > 0 || (negotiation.concessions?.length ?? 0) > 0);

  if (
    artifacts.length === 0 &&
    !hasNegotiation &&
    summaryUpTo <= 0 &&
    retros.length === 0 &&
    updates.length === 0
  ) {
    return null;
  }

  return (
    <div className={cn("space-y-3", className)} data-testid="discussion-insights">
      {/* Shared artifacts (I17) — persisted, and only ever populated by the
          single-conversation GET, never the list/stream responses. */}
      {artifacts.length > 0 && <ArtifactsPanel artifacts={artifacts} />}

      {/* Negotiation table (I11) — persisted only; no incremental SSE event
          carries the proposal/concession state. */}
      {hasNegotiation && (
        <NegotiationLedger
          negotiation={negotiation!}
          memberDisplayNames={conversation?.memberDisplayNames}
        />
      )}

      {/* Transcript windowing (I9) — persisted, so it shows on a reloaded
          conversation as well as a live one. */}
      {summaryUpTo > 0 && (
        <div
          className="flex items-start gap-2 rounded-lg border border-border bg-secondary/20 p-2.5 text-xs text-muted-foreground"
          data-testid="transcript-window-summary"
          title={conversation?.transcriptSummary ?? undefined}
        >
          <span aria-hidden="true">📜</span>
          <span>
            {t(
              "groups.transcriptWindowed",
              "The first {{count}} transcript entries were folded into a rolling summary to keep later turns' context bounded. Hover for the summary.",
              { count: summaryUpTo },
            )}
          </span>
        </div>
      )}

      {/* Live-stream badges (I8 / I17). Neither count is persisted, so these
          appear only while the stream that produced them is on screen. */}
      {retros.length > 0 && (
        <div className="flex flex-wrap items-center gap-2" data-testid="retro-recorded-summary">
          {retros.map((r, idx) => (
            <span
              key={`${r.phaseName}-${idx}`}
              className="inline-flex items-center gap-1.5 rounded-full border border-violet-500/30 bg-violet-500/5 px-2.5 py-1 text-xs text-violet-600 dark:text-violet-400"
            >
              🪞{" "}
              {t("groups.retroRecordedBadge", {
                defaultValue: "{{phase}}: {{count}} lesson saved to team memory",
                defaultValue_other: "{{phase}}: {{count}} lessons saved to team memory",
                phase: r.phaseName,
                count: r.lessonsStored,
              })}
            </span>
          ))}
        </div>
      )}

      {updates.length > 0 && (
        <div className="flex flex-wrap items-center gap-2" data-testid="artifact-updates-summary">
          {updates.map((a, idx) => (
            <span
              key={`${a.artifactId}-${a.version}-${idx}`}
              className="inline-flex items-center gap-1.5 rounded-full border border-sky-500/30 bg-sky-500/5 px-2.5 py-1 text-xs text-sky-600 dark:text-sky-400"
            >
              📎{" "}
              {a.created
                ? t("groups.artifactCreatedBadge", "{{name}} created (v{{version}})", { name: a.name, version: a.version })
                : t("groups.artifactUpdatedBadge", "{{name}} updated (v{{version}})", { name: a.name, version: a.version })}
            </span>
          ))}
        </div>
      )}
    </div>
  );
}
