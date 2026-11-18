/* ──────────────────────────────────────────────
   ChatLocationProvider — the address bar, for the widget's own routing
   (src/location.ts). Without `initialPath` it follows `window.location`
   (and back/forward); with one it keeps the location in memory, the way
   react-router's MemoryRouter served the tests.
   ────────────────────────────────────────────── */

import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";

import { ChatLocationContext, type ChatLocation, type ChatLocationApi } from "@/location";

function browserLocation(): ChatLocation {
  return { pathname: window.location.pathname, search: window.location.search };
}

function fromPath(path: string): ChatLocation {
  const url = new URL(path, "http://widget.invalid");
  return { pathname: url.pathname, search: url.search };
}

export function ChatLocationProvider({
  initialPath,
  children,
}: {
  /** Memory mode, for tests: start here and never touch `window.history`. */
  initialPath?: string;
  children: ReactNode;
}) {
  const memory = initialPath !== undefined;
  const [location, setLocation] = useState<ChatLocation>(() =>
    memory ? fromPath(initialPath) : browserLocation(),
  );

  useEffect(() => {
    if (memory) return;
    const onPop = () => setLocation(browserLocation());
    window.addEventListener("popstate", onPop);
    return () => window.removeEventListener("popstate", onPop);
  }, [memory]);

  const replaceSearch = useCallback(
    (next: URLSearchParams) => {
      const query = next.toString();
      const search = query ? `?${query}` : "";
      if (!memory) {
        window.history.replaceState(
          window.history.state,
          "",
          window.location.pathname + search + window.location.hash,
        );
      }
      setLocation((prev) => (prev.search === search ? prev : { ...prev, search }));
    },
    [memory],
  );

  const replacePath = useCallback(
    (pathname: string) => {
      if (!memory) window.history.replaceState(window.history.state, "", pathname);
      setLocation({ pathname, search: "" });
    },
    [memory],
  );

  const api = useMemo<ChatLocationApi>(
    () => ({ location, replaceSearch, replacePath }),
    [location, replaceSearch, replacePath],
  );
  return <ChatLocationContext.Provider value={api}>{children}</ChatLocationContext.Provider>;
}
