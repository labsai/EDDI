import * as React from "react";
import { cn } from "../../lib/utils";

/**
 * The one layout for a cluster of action buttons in a table row, card footer or
 * toolbar.
 *
 * The contract (asserted by `row-actions.test.tsx`):
 * - one row, end-aligned, a single `gap-2` between every button;
 * - every direct button / link is the same height (`h-8`, the `sm` Button) and
 *   never shrinks or breaks its label across lines;
 * - when the space runs out the cluster wraps *whole groups* (`RowActionsGroup`),
 *   never a lone button, so a wrap reads as a deliberate second row, not a
 *   ragged tail.
 *
 * Put the secondary actions (Review, View, Edit) in one `RowActionsGroup` and
 * the decisions (Approve, Reject, Cancel) in another; the order is the same on
 * every row, so the buttons line up from row to row.
 */
export const RowActions = React.forwardRef<HTMLDivElement, React.HTMLAttributes<HTMLDivElement>>(
  ({ className, ...props }, ref) => (
    <div
      ref={ref}
      data-row-actions=""
      className={cn("flex flex-wrap items-center justify-end gap-2 [&>*]:shrink-0 [&>*]:whitespace-nowrap", className)}
      {...props}
    />
  ),
);
RowActions.displayName = "RowActions";

/** Buttons that stay together on one line when the cluster wraps. */
export const RowActionsGroup = React.forwardRef<HTMLDivElement, React.HTMLAttributes<HTMLDivElement>>(
  ({ className, ...props }, ref) => (
    <div
      ref={ref}
      data-row-actions-group=""
      className={cn(
        "flex flex-nowrap items-center gap-2 [&>*]:h-8 [&>*]:shrink-0 [&>*]:whitespace-nowrap",
        className,
      )}
      {...props}
    />
  ),
);
RowActionsGroup.displayName = "RowActionsGroup";
