import type { ToolArgumentField } from "@/lib/tool-labels";
import { cn } from "@/lib/utils";

/**
 * A tool call's arguments as label/value rows (see `toolArgumentFields`).
 * Values are plain text — never markdown or HTML: they are model output, and on
 * an approval card they are exactly what will run. An object nests one level of
 * rows per level; arrays of objects and deep objects are shown as JSON text.
 */
export function ToolArgumentFields({
  fields,
  className,
  testId,
}: {
  fields: ToolArgumentField[];
  className?: string;
  testId?: string;
}) {
  return (
    <dl className={cn("space-y-1 text-[11px] leading-relaxed", className)} data-testid={testId}>
      {fields.map((field) => (
        <div key={field.key} className="min-w-0" data-testid={testId ? `${testId}-${field.key}` : undefined}>
          <dt className="font-medium text-muted-foreground" title={field.key}>
            {field.label}
          </dt>
          <dd className="ms-0 min-w-0">
            {Array.isArray(field.value) ? (
              <ToolArgumentFields fields={field.value} className="mt-0.5 border-s border-border ps-2" />
            ) : field.json ? (
              <pre className="max-h-48 overflow-auto whitespace-pre-wrap break-words rounded bg-muted/60 p-1.5 font-mono text-[10px] text-foreground [overflow-wrap:anywhere]">
                {field.value}
              </pre>
            ) : (
              <span className="whitespace-pre-wrap break-words text-foreground [overflow-wrap:anywhere]">
                {field.value === "" ? "—" : field.value}
              </span>
            )}
          </dd>
        </div>
      ))}
    </dl>
  );
}
