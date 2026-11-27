import { Toaster } from "sonner";
import { useTheme } from "../theme/theme-context";

export interface ThemedToasterProps {
  /** Whether the page reads right to left. The app knows its language; this package does not. */
  rtl?: boolean;
}

/**
 * Toasts follow the resolved theme — sonner defaults to light and ignores `.dark`.
 * They also sit in the reading direction's trailing corner: sonner takes a
 * physical `position`, so `bottom-right` stayed on the right in Arabic, where
 * it covers the start of the layout instead of its end.
 *
 * Moved from the Manager in phase 3a. `toast()` itself is sonner's.
 */
export function ThemedToaster({ rtl = false }: ThemedToasterProps) {
  const { resolvedTheme } = useTheme();
  return (
    <Toaster
      position={rtl ? "bottom-left" : "bottom-right"}
      dir={rtl ? "rtl" : "ltr"}
      richColors
      closeButton
      theme={resolvedTheme}
    />
  );
}
