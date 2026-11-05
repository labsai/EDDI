import { useTranslation } from "react-i18next";
import { Info } from "lucide-react";
import type { GroupMember, HumanTieBreakConfig, TieBreakTimeoutPolicy } from "@/lib/api/groups";

interface HumanTieBreakFieldsProps {
  /** The phase index, for stable test ids and label ids. */
  idx: number;
  tieBreak: HumanTieBreakConfig | null | undefined;
  /** The group's members — the HUMAN ones are offered as deciders. */
  members: GroupMember[] | null | undefined;
  /** Whether the group names a moderator (MODERATOR_DECIDES on timeout needs one). */
  hasModerator: boolean;
  onChange: (tieBreak: HumanTieBreakConfig) => void;
  inputCls: string;
}

/**
 * Who breaks a tie under `tiePolicy: HUMAN_DECIDES`, how long they have and what
 * a timeout does (8b). The decider is a principal id: one of the group's HUMAN
 * members is offered, but anyone may be named, as with a facilitator's
 * `escalateTo`. Left blank, the group's single HUMAN member decides.
 */
export function HumanTieBreakFields({ idx, tieBreak, members, hasModerator, onChange, inputCls }: HumanTieBreakFieldsProps) {
  const { t } = useTranslation();
  const current: HumanTieBreakConfig = tieBreak ?? {};
  const patch = (p: Partial<HumanTieBreakConfig>) => onChange({ ...current, ...p });
  const humans = (members ?? []).filter((m) => m.memberType === "HUMAN" && m.agentId);
  const deciderMissing = !current.decider?.trim() && humans.length !== 1;
  const listId = `phase-tiebreak-humans-${idx}`;

  return (
    <div className="mt-2 space-y-2" data-testid={`phase-tiebreak-${idx}`}>
      <div className="grid grid-cols-3 gap-2">
        <div>
          <label htmlFor={`phase-tiebreak-decider-${idx}`} className="mb-0.5 block text-[10px] text-muted-foreground">
            {t("groupTieBreak.decider", "Decider")}
          </label>
          <input
            id={`phase-tiebreak-decider-${idx}`}
            list={listId}
            value={current.decider ?? ""}
            placeholder={humans.length === 1 ? humans[0]!.agentId : "founder@example.com"}
            onChange={(e) => patch({ decider: e.target.value.trim() === "" ? null : e.target.value })}
            className={inputCls}
            data-testid={`phase-tiebreak-decider-${idx}`}
          />
          <datalist id={listId}>
            {humans.map((m) => (
              <option key={m.agentId} value={m.agentId}>
                {m.displayName}
              </option>
            ))}
          </datalist>
        </div>
        <div>
          <label htmlFor={`phase-tiebreak-timeout-${idx}`} className="mb-0.5 block text-[10px] text-muted-foreground">
            {t("groupTieBreak.timeout", "Timeout (ISO-8601)")}
          </label>
          <input
            id={`phase-tiebreak-timeout-${idx}`}
            value={current.timeout ?? ""}
            placeholder="PT24H"
            onChange={(e) => patch({ timeout: e.target.value.trim() === "" ? null : e.target.value.trim() })}
            className={inputCls}
            data-testid={`phase-tiebreak-timeout-${idx}`}
          />
        </div>
        <div>
          <label htmlFor={`phase-tiebreak-ontimeout-${idx}`} className="mb-0.5 block text-[10px] text-muted-foreground">
            {t("groupTieBreak.onTimeout", "On timeout")}
          </label>
          <select
            id={`phase-tiebreak-ontimeout-${idx}`}
            value={current.onTimeout ?? "NO_DECISION"}
            onChange={(e) => patch({ onTimeout: e.target.value as TieBreakTimeoutPolicy })}
            className={inputCls}
            data-testid={`phase-tiebreak-ontimeout-${idx}`}
          >
            <option value="NO_DECISION">{t("groups.voteTieNoDecision", "No decision")}</option>
            <option value="MODERATOR_DECIDES" disabled={!hasModerator}>
              {t("groups.voteTieModerator", "Moderator decides")}
            </option>
            <option value="ABORT">{t("groupTieBreak.onTimeoutAbort", "Cancel the discussion")}</option>
          </select>
        </div>
      </div>
      <p
        className={
          deciderMissing
            ? "flex items-start gap-1 text-[10px] text-destructive"
            : "flex items-start gap-1 text-[10px] text-muted-foreground"
        }
        role={deciderMissing ? "alert" : undefined}
        data-testid={`phase-tiebreak-note-${idx}`}
      >
        <Info className="mt-0.5 h-2.5 w-2.5 shrink-0" aria-hidden="true" />
        {deciderMissing
          ? t(
              "groupTieBreak.deciderRequired",
              "Name a decider — the group does not have exactly one HUMAN member to default to.",
            )
          : t(
              "groupTieBreak.note",
              "A tie pauses the discussion until the decider picks one option. Only the decider or an admin can answer.",
            )}
      </p>
    </div>
  );
}
