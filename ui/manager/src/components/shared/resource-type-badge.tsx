/** Colored badge for resource types — shared by import/export/sync UIs */
const colors: Record<string, string> = {
  agent: "bg-purple-500/10 text-purple-500",
  workflow: "bg-blue-500/10 text-blue-500",
  behavior: "bg-amber-500/10 text-amber-500",
  rules: "bg-amber-500/10 text-amber-500",
  httpcalls: "bg-green-500/10 text-green-500",
  apicalls: "bg-green-500/10 text-green-500",
  langchain: "bg-pink-500/10 text-pink-500",
  llm: "bg-pink-500/10 text-pink-500",
  output: "bg-cyan-500/10 text-cyan-500",
  property: "bg-orange-500/10 text-orange-500",
  propertysetter: "bg-orange-500/10 text-orange-500",
  dictionary: "bg-teal-500/10 text-teal-500",
  regulardictionary: "bg-teal-500/10 text-teal-500",
  mcpcalls: "bg-indigo-500/10 text-indigo-500",
  rag: "bg-violet-500/10 text-violet-500",
  snippet: "bg-rose-500/10 text-rose-500",
  snippets: "bg-rose-500/10 text-rose-500",
};

export function ResourceTypeBadge({ type }: { type: string }) {
  // Remove file extension suffix if present (e.g., "behavior" from "behavior.json")
  const cleanType = type.replace(/\.json$/, "");

  return (
    <span
      className={`inline-flex rounded-full px-2 py-0.5 text-xs font-medium ${
        colors[cleanType] || "bg-secondary text-muted-foreground"
      }`}
    >
      {cleanType}
    </span>
  );
}
