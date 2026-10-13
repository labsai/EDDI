import { Toaster } from "sonner";
import { useTheme } from "./theme-provider";

/** Toasts follow the resolved theme — sonner defaults to light and ignores `.dark`. */
export function ThemedToaster() {
  const { resolvedTheme } = useTheme();
  return <Toaster position="bottom-right" richColors closeButton theme={resolvedTheme} />;
}
