import { Toaster } from "sonner";
import { useTheme } from "./theme-provider";

/**
 * The app's toast container, following the Manager's theme.
 *
 * Sonner does not read the `dark` class on `<html>`; it takes its own `theme`
 * prop and defaults to light. Rendered bare, every toast stayed light-on-dark in
 * dark mode. Must sit inside `ThemeProvider`.
 */
export function ThemedToaster() {
  const { resolvedTheme } = useTheme();
  return <Toaster position="bottom-right" richColors closeButton theme={resolvedTheme} />;
}
