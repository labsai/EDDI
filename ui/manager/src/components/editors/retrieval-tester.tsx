import { useState } from "react";
import { useTranslation } from "react-i18next";
import { AlertCircle, Loader2, Search } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ChunkList } from "@/components/editors/ingestion-source-contents";
import { useKnowledgeSearch } from "@/hooks/use-ingestion-sources";
import type { IngestionSource } from "@/lib/api/ingestion-sources";

/**
 * Asks the knowledge base a question the way an agent's retrieval would, and
 * shows what comes back with its scores — so "why did the agent answer that?"
 * can be checked without starting a conversation.
 */
export function RetrievalTester({
  kbId,
  version,
  sources,
  defaultMaxResults,
  defaultMinScore,
  hasUnsavedChanges,
}: {
  kbId: string;
  version: number;
  sources: IngestionSource[];
  defaultMaxResults?: number;
  defaultMinScore?: number;
  hasUnsavedChanges?: boolean;
}) {
  const { t } = useTranslation();
  const [query, setQuery] = useState("");
  const [sourceId, setSourceId] = useState("");
  const [maxResults, setMaxResults] = useState(String(defaultMaxResults ?? 5));
  const [minScore, setMinScore] = useState(String(defaultMinScore ?? 0));
  const search = useKnowledgeSearch(kbId, version);

  const savedSources = sources.filter((source) => source.id);
  const submit = () => {
    if (!query.trim()) return;
    search.mutate({
      query: query.trim(),
      sourceId: sourceId || undefined,
      maxResults: numberOr(maxResults, undefined),
      minScore: numberOr(minScore, undefined),
    });
  };

  return (
    <div className="space-y-3" data-testid="retrieval-tester">
      <p className="text-xs text-muted-foreground">
        {t(
          "ragEditor.retrieval.hint",
          "Search this knowledge base exactly as an agent's retrieval does. Each search costs one embedding call; nothing is stored.",
        )}
      </p>
      <form
        className="space-y-2"
        onSubmit={(event) => {
          event.preventDefault();
          submit();
        }}
      >
        <div className="relative">
          <Search
            className="absolute start-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground"
            aria-hidden="true"
          />
          <Input
            className="ps-9"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            maxLength={2000}
            placeholder={t("ragEditor.retrieval.placeholder", "Ask what a user would ask…")}
            aria-label={t("ragEditor.retrieval.query", "Query")}
            data-testid="retrieval-query"
          />
        </div>
        <div className="grid gap-2 sm:grid-cols-[2fr_1fr_1fr_auto] sm:items-end">
          <label className="block space-y-1 text-xs">
            <span className="font-medium text-foreground/80">{t("ragEditor.retrieval.source", "Source")}</span>
            <select
              value={sourceId}
              onChange={(e) => setSourceId(e.target.value)}
              className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
              data-testid="retrieval-source"
            >
              <option value="">{t("ragEditor.retrieval.allSources", "Whole knowledge base")}</option>
              {savedSources.map((source) => (
                <option key={source.id} value={source.id}>
                  {source.name || source.id}
                </option>
              ))}
            </select>
          </label>
          <label className="block space-y-1 text-xs">
            <span className="font-medium text-foreground/80">{t("ragEditor.retrieval.maxResults", "Results")}</span>
            <Input
              type="number"
              min={1}
              max={50}
              value={maxResults}
              onChange={(e) => setMaxResults(e.target.value)}
              data-testid="retrieval-max-results"
            />
          </label>
          <label className="block space-y-1 text-xs">
            <span className="font-medium text-foreground/80">{t("ragEditor.retrieval.minScore", "Min score")}</span>
            <Input
              type="number"
              min={0}
              max={1}
              step={0.05}
              value={minScore}
              onChange={(e) => setMinScore(e.target.value)}
              data-testid="retrieval-min-score"
            />
          </label>
          <Button type="submit" disabled={!query.trim() || search.isPending} data-testid="retrieval-search">
            {search.isPending ? <Loader2 className="animate-spin" /> : <Search />}
            {t("ragEditor.retrieval.search", "Search")}
          </Button>
        </div>
      </form>

      {hasUnsavedChanges && (
        <p className="flex items-start gap-1.5 text-[11px] text-amber-700 dark:text-amber-400">
          <AlertCircle className="mt-0.5 h-3 w-3 shrink-0" />
          {t(
            "ragEditor.retrieval.unsaved",
            "The search uses the saved knowledge base — its embedding model and store — not the edits on screen.",
          )}
        </p>
      )}

      {search.isError && (
        <p className="flex items-start gap-1.5 text-[11px] text-destructive" role="alert" data-testid="retrieval-error">
          <AlertCircle className="mt-0.5 h-3 w-3 shrink-0" />
          {t("ragEditor.retrieval.failed", "The search failed: {{error}}", { error: search.error.message })}
        </p>
      )}

      {search.data && (
        <div className="space-y-2" data-testid="retrieval-results">
          <p className="text-xs text-muted-foreground">
            {t("ragEditor.retrieval.resultCount", "{{results}} chunk(s) at score {{minScore}} or above", {
              results: search.data.results.length,
              minScore: search.data.minScore,
            })}
          </p>
          {search.data.results.length === 0 ? (
            <p className="text-xs italic text-muted-foreground" data-testid="retrieval-no-results">
              {t(
                "ragEditor.retrieval.noResults",
                "Nothing matched. Lower the minimum score, or check that the sources have run.",
              )}
            </p>
          ) : (
            <ChunkList chunks={search.data.results} showScore />
          )}
        </div>
      )}
    </div>
  );
}

function numberOr(value: string, fallback: number | undefined): number | undefined {
  const parsed = Number(value);
  return value.trim() === "" || !Number.isFinite(parsed) ? fallback : parsed;
}
