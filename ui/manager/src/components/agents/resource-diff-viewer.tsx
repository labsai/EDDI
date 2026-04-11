import { useMemo } from "react";
import { useTranslation } from "react-i18next";
import { diffLines } from "diff";
import { Equal } from "lucide-react";

interface ResourceDiffViewerProps {
  sourceContent: string | null;
  targetContent: string | null;
}

/**
 * Side-by-side-ish diff viewer for JSON resource content.
 * Uses jsdiff's diffLines to compute a unified colored diff.
 */
export function ResourceDiffViewer({
  sourceContent,
  targetContent,
}: ResourceDiffViewerProps) {
  const { t } = useTranslation();

  const diff = useMemo(() => {
    if (!sourceContent && !targetContent) return null;

    const source = formatJson(sourceContent);
    const target = formatJson(targetContent);

    if (source === target) return "identical";

    return diffLines(target || "", source || "");
  }, [sourceContent, targetContent]);

  if (diff === null) return null;

  if (diff === "identical") {
    return (
      <div className="flex items-center gap-2 px-4 py-3 text-sm text-muted-foreground bg-secondary/30 rounded-lg">
        <Equal className="h-4 w-4" />
        {t("importDialog.contentIdentical", "Content identical")}
      </div>
    );
  }

  return (
    <div className="overflow-auto rounded-lg border bg-card text-xs font-mono max-h-80">
      <div className="flex items-center justify-between px-3 py-1.5 border-b bg-secondary/50 text-[10px] text-muted-foreground">
        <span>{t("importDialog.targetContent", "Target")} → {t("importDialog.sourceContent", "Source")}</span>
      </div>
      <div className="p-0">
        {diff.map((change, i) => {
          const lines = change.value.split("\n").filter((_l, idx, arr) => {
            // Remove trailing empty line from each chunk
            if (idx === arr.length - 1 && _l === "") return false;
            return true;
          });

          return lines.map((line, j) => (
            <div
              key={`${i}-${j}`}
              className={
                change.added
                  ? "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400 border-s-2 border-emerald-500"
                  : change.removed
                    ? "bg-red-500/10 text-red-700 dark:text-red-400 border-s-2 border-red-500"
                    : "text-muted-foreground border-s-2 border-transparent"
              }
            >
              <span className="inline-block w-6 text-end pe-2 text-muted-foreground/50 select-none">
                {change.added ? "+" : change.removed ? "−" : " "}
              </span>
              {line}
            </div>
          ));
        })}
      </div>
    </div>
  );
}

/** Deep-sort all keys recursively for stable diffing */
function formatJson(content: string | null): string {
  if (!content) return "";
  try {
    const parsed = JSON.parse(content);
    return JSON.stringify(deepSortKeys(parsed), null, 2);
  } catch {
    return content;
  }
}

function deepSortKeys(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(deepSortKeys);
  if (value !== null && typeof value === "object") {
    return Object.keys(value as Record<string, unknown>)
      .sort()
      .reduce<Record<string, unknown>>((acc, key) => {
        acc[key] = deepSortKeys((value as Record<string, unknown>)[key]);
        return acc;
      }, {});
  }
  return value;
}
