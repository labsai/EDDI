import type { ConflictDetails } from "../../lib/conflict-details";

/**
 * What a refused delete (HTTP 409) says still uses the resource: the server's own
 * sentence and every list it carried. Server text is rendered as text, verbatim.
 */
export function ConflictDetailsPanel({
  details,
  testId = "conflict-details",
}: {
  details: ConflictDetails;
  testId?: string;
}) {
  return (
    <div
      className="rounded-lg border border-destructive/40 bg-destructive/5 p-3 text-start text-xs"
      role="alert"
      data-testid={testId}
    >
      <p className="font-medium text-foreground" data-testid={`${testId}-message`}>
        {details.message}
      </p>
      {details.lists.map((list) => (
        <div key={list.field} className="mt-2" data-testid={`${testId}-list`}>
          {list.field && <p className="font-mono text-[10px] text-muted-foreground">{list.field}</p>}
          <ul className="mt-0.5 list-disc space-y-0.5 ps-5 text-muted-foreground">
            {list.items.map((item, i) => (
              <li key={i} className="break-all">
                {item}
              </li>
            ))}
          </ul>
        </div>
      ))}
    </div>
  );
}
