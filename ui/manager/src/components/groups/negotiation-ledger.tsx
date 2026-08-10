import { useTranslation } from "react-i18next";
import { Handshake, ArrowLeftRight } from "lucide-react";
import { cn, hashColor, getInitials } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import type { NegotiationState } from "@/lib/api/groups";

interface NegotiationLedgerProps {
  negotiation: NegotiationState;
  /** agentId → display name, for readable names instead of raw ids. */
  memberDisplayNames?: Record<string, string>;
  className?: string;
}

/**
 * The negotiation table (I11): open/superseded proposals and the concession
 * ledger, read from the persisted `GroupConversation.negotiation` — there is no
 * incremental SSE event for it, so this only has data once the discussion is
 * fetched/reloaded (same as the I9 windowing indicator).
 */
export function NegotiationLedger({ negotiation, memberDisplayNames, className }: NegotiationLedgerProps) {
  const { t } = useTranslation();
  const name = (agentId: string) => memberDisplayNames?.[agentId] ?? agentId;

  // Defensive `?? []`: the backend always initializes both lists, but a ledger
  // is not worth a white screen if a future shape omits one.
  const proposals = negotiation.proposals ?? [];
  const concessions = negotiation.concessions ?? [];

  if (proposals.length === 0 && concessions.length === 0) {
    return null;
  }

  return (
    <div
      className={cn("rounded-xl border border-emerald-500/30 bg-emerald-500/5 p-4", className)}
      data-testid="negotiation-ledger"
    >
      <h3 className="mb-3 flex items-center gap-1.5 text-sm font-semibold text-foreground">
        <Handshake className="h-4 w-4 text-emerald-600 dark:text-emerald-400" aria-hidden="true" />
        {t("groups.negotiationTableTitle", "Negotiation Table")}
      </h3>

      {proposals.length > 0 && (
        <div className="space-y-1.5">
          {proposals.map((p) => (
            <div
              key={p.id}
              className={cn(
                "rounded-lg border bg-background/60 p-2.5",
                p.status === "OPEN" ? "border-emerald-500/30" : "border-border opacity-70",
              )}
              data-testid={`negotiation-proposal-${p.id}`}
            >
              <div className="flex flex-wrap items-center gap-1.5">
                <div
                  className={cn(
                    "flex h-5 w-5 shrink-0 items-center justify-center rounded-full text-[9px] font-bold text-white",
                    hashColor(p.byAgentId),
                  )}
                  aria-hidden="true"
                >
                  {getInitials(name(p.byAgentId))}
                </div>
                <span className="text-xs font-medium text-foreground">{name(p.byAgentId)}</span>
                <span className="font-mono text-[10px] text-muted-foreground">{p.id}</span>
                <Badge
                  variant={p.status === "OPEN" ? "success" : "secondary"}
                  className="text-[9px] px-1.5 py-0"
                  data-testid={`negotiation-proposal-status-${p.id}`}
                >
                  {p.status === "OPEN"
                    ? t("groups.negotiationOpen", "Open")
                    : t("groups.negotiationSuperseded", "Superseded")}
                </Badge>
                <span className="ms-auto text-[10px] text-muted-foreground">
                  {t("groups.negotiationRound", "Round {{round}}", { round: p.round })}
                </span>
              </div>
              <p className="mt-1.5 text-xs text-foreground">{p.terms}</p>
              {p.acceptedBy.length > 0 && (
                <p className="mt-1.5 text-[10px] text-muted-foreground">
                  {t("groups.negotiationAcceptedBy", "Accepted by: {{names}}", {
                    names: p.acceptedBy.map(name).join(", "),
                  })}
                </p>
              )}
            </div>
          ))}
        </div>
      )}

      {concessions.length > 0 && (
        <div className="mt-3 border-t border-emerald-500/20 pt-3">
          <h4 className="mb-1.5 flex items-center gap-1 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
            <ArrowLeftRight className="h-3 w-3" aria-hidden="true" />
            {t("groups.concessionLedgerTitle", "Concession ledger")}
          </h4>
          <ul className="space-y-1.5">
            {concessions.map((c, idx) => (
              <li
                key={`${c.byAgentId}-${c.round}-${idx}`}
                className="rounded-lg border border-border bg-background/60 p-2 text-xs"
                data-testid={`negotiation-concession-${idx}`}
              >
                <span className="font-medium text-foreground">{name(c.byAgentId)}</span>{" "}
                {t("groups.negotiationGaveUp", "gave up")}{" "}
                <span className="text-foreground">{c.gaveUp}</span>{" "}
                {t("groups.negotiationInReturnFor", "in return for")}{" "}
                <span className="text-foreground">{c.inReturnFor}</span>
                {c.refProposalId && (
                  <span className="ms-1 font-mono text-[10px] text-muted-foreground">
                    ({c.refProposalId})
                  </span>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
