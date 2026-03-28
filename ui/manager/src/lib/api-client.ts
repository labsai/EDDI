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
        const errorBody = await response.json();
        // Backend may use `message`, `errorMessage`, or `detail`
        error.message =
          errorBody.message ||
          errorBody.errorMessage ||
          errorBody.detail ||
          response.statusText;
      } catch {
        // Non-JSON error body — keep statusText
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

    return response.json();
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
