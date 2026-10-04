import { useId, type ReactNode } from "react";
import { cn } from "@/lib/utils";

/** What a {@link Field} hands its control so the label and hint reach it. */
export interface FieldControlProps {
  id: string;
  /** Set when the field has a hint, so the control is described by it. */
  "aria-describedby"?: string;
}

/**
 * A labelled form control: the `<label>` is tied to the control by `htmlFor`
 * and the optional hint by `aria-describedby`, so a screen reader announces both
 * and clicking the label focuses the control. A placeholder is not a label.
 *
 * `children` is a render function because the id has to land on the control
 * itself, which may be an `<input>`, a `<select>`, or a composite (the secret
 * picker) that forwards `id` to the element the user actually focuses.
 */
export function Field({
  label,
  hint,
  className,
  labelClassName,
  children,
}: {
  label: ReactNode;
  hint?: ReactNode;
  className?: string;
  labelClassName?: string;
  children: (control: FieldControlProps) => ReactNode;
}) {
  const id = useId();
  const hintId = hint ? `${id}-hint` : undefined;
  return (
    <div className={cn("space-y-1", className)}>
      <label
        htmlFor={id}
        className={cn("block text-xs font-medium text-muted-foreground", labelClassName)}
      >
        {label}
      </label>
      {children({ id, "aria-describedby": hintId })}
      {hint && (
        <p id={hintId} className="text-[11px] text-muted-foreground">
          {hint}
        </p>
      )}
    </div>
  );
}
