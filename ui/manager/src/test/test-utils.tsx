import { render, type RenderOptions } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import type { ReactElement } from "react";

function createTestQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
}

interface ExtendedRenderOptions extends Omit<RenderOptions, "wrapper"> {
  initialRoute?: string;
}

export function renderWithProviders(
  ui: ReactElement,
  { initialRoute = "/", ...options }: ExtendedRenderOptions = {}
) {
  const queryClient = createTestQueryClient();

  function Wrapper({ children }: { children: React.ReactNode }) {
    return (
      <MemoryRouter initialEntries={[initialRoute]}>
        <QueryClientProvider client={queryClient}>
          <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
            {children}
          </ThemeProvider>
        </QueryClientProvider>
      </MemoryRouter>
    );
  }

  return {
    ...render(ui, { wrapper: Wrapper, ...options }),
    queryClient,
  };
}

/**
 * Render a page component inside MemoryRouter with a route pattern.
 * Centralises boilerplate duplicated across 10+ page-level test files.
 *
 * @param path - The initial URL, e.g. "/manage/resources/behavior/res1"
 * @param element - The JSX element to render at the route
 * @param routePattern - The React Router pattern, defaults to `path`
 * @param client - A prepared QueryClient, for the cases the default cannot
 *   express: a cache primed *before* the first render (a page reached from a
 *   list that seeds it), or a non-default `staleTime` (the default here is 0,
 *   under which every entry is stale immediately and a refetch papers over
 *   anything that depends on cached data surviving).
 */
export function renderPage(
  path: string,
  element: ReactElement,
  routePattern?: string,
  client?: QueryClient
) {
  const queryClient = client ?? createTestQueryClient();

  return {
    ...render(
      <MemoryRouter initialEntries={[path]}>
        <QueryClientProvider client={queryClient}>
          <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
            <Routes>
              <Route path={routePattern ?? path} element={element} />
            </Routes>
          </ThemeProvider>
        </QueryClientProvider>
      </MemoryRouter>
    ),
    queryClient,
  };
}

export { default as userEvent } from "@testing-library/user-event";
