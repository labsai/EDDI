const BASE_URL = window.location.origin;

export interface ApiError {
  status: number;
  message: string;
  url?: string;
}

/** Type guard to check if an error is an ApiError from our client */
export function isApiError(error: unknown): error is ApiError {
  return (
    typeof error === "object" &&
    error !== null &&
    "status" in error &&
    "message" in error
  );
}

/**
 * Longest error message worth surfacing. Backend validator messages are one
 * sentence; anything past this is a stack trace or an HTML page, and pasting
 * either into a toast tells the user nothing.
 */
const MAX_ERROR_MESSAGE_CHARS = 400;

/**
 * Pull a displayable message out of an error response body.
 *
 * Two body shapes are real here. JSON errors come keyed `message`,
 * `errorMessage`, `detail` or `error` — the last is what the group template and
 * workspace endpoints use, and reading only the first three is why every
 * "Missing role assignment(s): …" used to reach the user as a bare
 * "Bad Request". Plain-text errors come from the global exception mappers
 * (save-time validator 400s, version-conflict 409s, store-failure 500s), where
 * the body IS the message.
 *
 * Returns null when the body carries nothing worth showing, so the caller keeps
 * the HTTP status phrase — notably for a reverse proxy's HTML error page, which
 * is markup rather than a message and must not be dumped into a toast.
 */
function extractErrorMessage(body: string): string | null {
  const text = body.trim();
  if (!text) return null;

  try {
    const parsed = JSON.parse(text);
    if (parsed && typeof parsed === "object") {
      const candidate =
        (parsed as Record<string, unknown>).message ??
        (parsed as Record<string, unknown>).errorMessage ??
        (parsed as Record<string, unknown>).detail ??
        (parsed as Record<string, unknown>).error;
      if (typeof candidate === "string" && candidate.trim()) {
        return truncateMessage(candidate.trim());
      }
    }
    // Valid JSON, but no recognizable message field (or a bare literal like
    // `null`/`123`) — the status phrase is more informative than the raw body.
    return null;
  } catch {
    // Not JSON. Markup is never a message.
    if (text.startsWith("<")) return null;
    return truncateMessage(text);
  }
}

function truncateMessage(message: string): string {
  return message.length > MAX_ERROR_MESSAGE_CHARS
    ? `${message.slice(0, MAX_ERROR_MESSAGE_CHARS)}…`
    : message;
}

/** Extract a human-readable message from any caught error */
export function getErrorMessage(error: unknown): string {
  if (isApiError(error)) {
    return `${error.message} (HTTP ${error.status})`;
  }
  if (error instanceof Error) return error.message;
  return String(error);
}

class ApiClient {
  private baseUrl: string;
  private headers: Record<string, string> = {
    "Content-Type": "application/json",
  };

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl;
  }

  setAuthToken(token: string) {
    this.headers["Authorization"] = `Bearer ${token}`;
  }

  clearAuthToken() {
    delete this.headers["Authorization"];
  }

  /** Get current auth header (if set). Used by modules that need raw fetch (SSE, text/plain). */
  getAuthHeader(): Record<string, string> {
    const auth = this.headers["Authorization"];
    return auth ? { Authorization: auth } : {};
  }

  /** Get the base URL for building raw fetch URLs */
  getBaseUrl(): string {
    return this.baseUrl;
  }

  private async request<T>(
    method: string,
    path: string,
    body?: unknown,
    requestHeaders?: Record<string, string>
  ): Promise<T> {
    const url = `${this.baseUrl}${path}`;

    const mergedHeaders = { ...this.headers, ...requestHeaders };

    let response: Response;
    try {
      response = await fetch(url, {
        method,
        headers: mergedHeaders,
        body: body !== undefined
          ? (typeof body === "string" ? body : JSON.stringify(body))
          : undefined,
      });
    } catch (networkError) {
      // Network failure (offline, DNS, CORS, etc.)
      const error: ApiError = {
        status: 0,
        message:
          networkError instanceof Error
            ? `Network error: ${networkError.message}`
            : "Network error: unable to reach server",
        url,
      };
      throw error;
    }

    if (!response.ok) {
      const error: ApiError = {
        status: response.status,
        message: response.statusText,
        url,
      };
      try {
        const extracted = extractErrorMessage(await response.text());
        if (extracted) error.message = extracted;
      } catch {
        // Body unreadable — keep statusText.
      }
      throw error;
    }

    // Handle 202 Accepted and 204 No Content (empty body responses)
    if (response.status === 202 || response.status === 204) {
      return undefined as T;
    }

    // Handle Location header (POST 201, PUT 200 with new version)
    const location = response.headers.get("Location");
    if (location && (response.status === 200 || response.status === 201)) {
      // Try to also parse JSON body if present, merge with location
      try {
        const body = await response.json();
        return { ...body, location } as T;
      } catch {
        return { location } as T;
      }
    }

    // Handle empty body responses (e.g. DELETE returning 200 with no body)
    const contentLength = response.headers.get("Content-Length");
    if (contentLength === "0") {
      return undefined as T;
    }

    // Try parsing JSON, gracefully handle empty or non-JSON bodies
    const text = await response.text();
    if (!text) {
      return undefined as T;
    }
    try {
      return JSON.parse(text) as T;
    } catch {
      // Non-JSON body on a success response — treat as an error rather
      // than silently returning undefined (which would be cached by
      // TanStack Query as valid data, hiding the real problem).
      const error: ApiError = {
        status: response.status,
        message: "Unexpected non-JSON response",
        url,
      };
      throw error;
    }
  }

  get<T>(path: string): Promise<T> {
    return this.request<T>("GET", path);
  }

  post<T>(path: string, body?: unknown, headers?: Record<string, string>): Promise<T> {
    return this.request<T>("POST", path, body, headers);
  }

  put<T>(path: string, body?: unknown, headers?: Record<string, string>): Promise<T> {
    return this.request<T>("PUT", path, body, headers);
  }

  patch<T>(path: string, body?: unknown, headers?: Record<string, string>): Promise<T> {
    return this.request<T>("PATCH", path, body, headers);
  }

  delete<T>(path: string): Promise<T> {
    return this.request<T>("DELETE", path);
  }
}

export const api = new ApiClient(BASE_URL);
