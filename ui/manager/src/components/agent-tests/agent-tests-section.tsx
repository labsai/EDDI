import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { ChevronDown, ChevronRight, FlaskConical, Loader2, Play } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useResourceDescriptors } from "@/hooks/use-resources";
import { useAgentTestRuns, useStartAgentTestRun } from "@/hooks/use-agent-tests";
import { parseResourceUri } from "@/lib/api/resources";
import { getErrorMessage } from "@/lib/api-client";
import { formatDuration, formatUsd } from "@/lib/utils";
import type { AgentTestRun } from "@/lib/api/agent-tests";
import { RunStatusBadge } from "./run-status";
import { RunResultSheet } from "./run-result-sheet";

function RunRow({ run, onOpen }: { run: AgentTestRun; onOpen: () => void }) {
  const { t } = useTranslation();
  const started = run.startedAt ? new Date(run.startedAt).toLocaleString() : "—";
  return (
    <li>
      <button
        type="button"
        onClick={onOpen}
        className="flex w-full flex-wrap items-center gap-x-3 gap-y-1 rounded-lg border border-border bg-background/50 px-3 py-2 text-start transition-colors hover:bg-secondary/60"
        data-testid="agent-test-run-row"
        data-run-id={run.id}
      >
        <RunStatusBadge status={run.status} />
        <span className="min-w-0 flex-1 truncate text-sm font-medium text-foreground">
          {run.suiteName ?? run.suiteId}
          <span className="ms-1 text-xs font-normal text-muted-foreground">
            {run.suiteVersion !== undefined && `v${run.suiteVersion}`}
            {run.agentVersion !== undefined && ` → ${t("agentTests.agentVersion", { version: run.agentVersion, defaultValue: "agent v{{version}}" })}`}
          </span>
        </span>
        {run.status !== "RUNNING" && run.casesTotal !== undefined && run.cases && run.cases.length > 0 && (
          <span className="text-xs text-muted-foreground">
            {t("agentTests.casesPassed", {
              passed: run.casesPassed ?? 0,
              total: run.casesTotal,
              defaultValue: "{{passed}}/{{total}} conversations",
            })}
          </span>
        )}
        {run.totalCostUsd !== undefined && run.status !== "RUNNING" && (
          <span className="text-xs text-muted-foreground">{formatUsd(run.totalCostUsd)}</span>
        )}
        {run.durationMs !== undefined && (
          <span className="text-xs text-muted-foreground">{formatDuration(run.durationMs)}</span>
        )}
        <span className="text-xs text-muted-foreground">{started}</span>
        {run.startedBy && <span className="text-xs text-muted-foreground">{run.startedBy}</span>}
      </button>
    </li>
  );
}

/**
 * Agent detail → "Golden conversations": pick a suite, run it against this
 * agent version, read the history. Collapsed by default (progressive
 * disclosure) — the header still says how the latest run went.
 */
export function AgentTestsSection({ agentId, agentVersion }: { agentId: string; agentVersion: number }) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);
  const [suiteKey, setSuiteKey] = useState("");
  const [openRunId, setOpenRunId] = useState<string | null>(null);

  // Held back until the section is opened: a closed section must cost nothing.
  const { data: suiteDescriptors, isLoading: suitesLoading } = useResourceDescriptors("testsuite", 100, 0, "", expanded);
  const { data: runs, isLoading: runsLoading, isError: runsError } = useAgentTestRuns(agentId);
  const startRun = useStartAgentTestRun();

  const suites = useMemo(
    () =>
      (suiteDescriptors ?? []).map((d) => {
        const { id, version } = parseResourceUri(d.resource);
        return { id, version, name: d.name || id };
      }),
    [suiteDescriptors]
  );
  const selected = suites.find((s) => s.id === suiteKey) ?? suites[0];
  const latest = runs?.[0];
  const running = runs?.some((r) => r.status === "RUNNING") ?? false;

  function handleRun() {
    if (!selected) return;
    startRun.mutate(
      { suiteId: selected.id, suiteVersion: selected.version, agentId, agentVersion },
      {
        onSuccess: (run) => {
          toast.success(t("agentTests.started", "Test run started"));
          setOpenRunId(run.id);
        },
        // 400 empty suite, 404, 409 deploy conflict, 429 too many runs — the
        // backend's message already says which and why.
        onError: (err) => toast.error(getErrorMessage(err)),
      }
    );
  }

  return (
    <section className="rounded-xl border bg-card shadow-sm" data-testid="agent-tests-section">
      <h2 className="text-lg font-semibold text-foreground">
        <button
          type="button"
          onClick={() => setExpanded(!expanded)}
          aria-expanded={expanded}
          aria-controls="agent-tests-content"
          className="flex w-full flex-wrap items-center gap-2 p-5 text-start"
        >
          {expanded ? (
            <ChevronDown className="h-4 w-4 text-muted-foreground" aria-hidden="true" />
          ) : (
            <ChevronRight className="h-4 w-4 text-muted-foreground" aria-hidden="true" />
          )}
          <FlaskConical className="h-5 w-5 text-lime-600" aria-hidden="true" />
          {t("agentTests.title", "Golden conversations")}
          {latest && <RunStatusBadge status={latest.status} />}
        </button>
      </h2>

      {expanded && (
        <div id="agent-tests-content" className="space-y-4 border-t border-border p-5">
          <p className="text-sm text-muted-foreground">
            {t(
              "agentTests.intro",
              "Replay scripted conversations against this agent and check what must hold afterwards — actions, properties, tool calls, cost. Runs deploy the version to the test environment; production is never touched."
            )}
          </p>

          <div className="flex flex-wrap items-end gap-3">
            <div className="min-w-56 flex-1">
              <label htmlFor="agent-test-suite" className="mb-1 block text-xs font-medium text-muted-foreground">
                {t("agentTests.suite", "Test suite")}
              </label>
              <select
                id="agent-test-suite"
                value={selected?.id ?? ""}
                onChange={(e) => setSuiteKey(e.target.value)}
                disabled={suites.length === 0}
                className="h-9 w-full rounded-md border border-input bg-background px-2 text-sm text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                data-testid="agent-test-suite-select"
              >
                {suites.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name} (v{s.version})
                  </option>
                ))}
              </select>
            </div>
            <Button
              onClick={handleRun}
              disabled={!selected || startRun.isPending || running}
              data-testid="agent-test-run-btn"
            >
              {startRun.isPending || running ? (
                <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
              ) : (
                <Play className="h-4 w-4" aria-hidden="true" />
              )}
              {running ? t("agentTests.runningShort", "Running…") : t("agentTests.run", "Run suite")}
            </Button>
            <Link
              to="/manage/resources/testsuite"
              className="pb-2 text-sm text-primary hover:underline"
              data-testid="agent-test-manage-suites"
            >
              {t("agentTests.manageSuites", "Manage suites")}
            </Link>
          </div>
          {!suitesLoading && suites.length === 0 && (
            <p className="text-sm text-muted-foreground" data-testid="agent-tests-no-suites">
              {t("agentTests.noSuites", "No test suites yet. Create one under Resources → Test Suites.")}
            </p>
          )}
          {selected && (
            <p className="text-xs text-muted-foreground">
              {t("agentTests.runsVersion", {
                version: agentVersion,
                defaultValue: "Runs the saved agent version {{version}} against the suite’s current version.",
              })}
            </p>
          )}

          <div>
            <h3 className="mb-2 text-sm font-semibold text-foreground">{t("agentTests.history", "Run history")}</h3>
            {runsLoading && <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" aria-label={t("common.loading", "Loading")} />}
            {runsError && (
              <p className="text-sm text-destructive" role="alert">
                {t("agentTests.historyError", "The run history could not be loaded.")}
              </p>
            )}
            {!runsLoading && !runsError && (runs ?? []).length === 0 && (
              <p className="text-sm text-muted-foreground" data-testid="agent-tests-no-runs">
                {t("agentTests.noRuns", "No runs yet for this agent.")}
              </p>
            )}
            <ul className="space-y-1.5">
              {(runs ?? []).map((r) => (
                <RunRow key={r.id} run={r} onOpen={() => setOpenRunId(r.id)} />
              ))}
            </ul>
          </div>
        </div>
      )}

      <RunResultSheet runId={openRunId} onClose={() => setOpenRunId(null)} fallback={runs?.find((r) => r.id === openRunId)} />
    </section>
  );
}
