import { createContext, useContext, useEffect, useId } from "react";

/**
 * What a form editor tells the shared editor layout about its own fields.
 *
 * An editor that validates a field inline (a temperature that is not a number,
 * a pattern that does not compile) reports the problem here, and the layout
 * disables Save and says why while any problem is reported. Without a layout
 * around the editor (a test, a read-only embed) reporting is a no-op.
 */
export interface EditorValidity {
  /** Register (reason is a string) or clear (null) the problem owned by `id`. */
  report: (id: string, reason: string | null) => void;
}

export const EditorValidityContext = createContext<EditorValidity | null>(null);

/**
 * Report a field problem for as long as it holds. `reason` is the user-facing
 * explanation; pass null when the field is valid. The report is withdrawn when
 * the editor (or the field) unmounts, so a removed row never blocks Save.
 */
export function useReportInvalid(reason: string | null, scope = ""): void {
  const ctx = useContext(EditorValidityContext);
  const uid = useId();
  const id = `${scope}${uid}`;
  useEffect(() => {
    if (!ctx) return;
    ctx.report(id, reason);
    return () => ctx.report(id, null);
  }, [ctx, id, reason]);
}
