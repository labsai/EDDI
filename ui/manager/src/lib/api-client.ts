const BASE_URL = window.location.origin;

interface ApiError {
  status: number;
  message: string;
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

  private async request<T>(
    method: string,
    path: string,
    body?: unknown
  ): Promise<T> {
    const url = `${this.baseUrl}${path}`;
    const response = await fetch(url, {
      method,
      headers: this.headers,
      body: body ? JSON.stringify(body) : undefined,
    });

    if (!response.ok) {
      const error: ApiError = {
        status: response.status,
        message: response.statusText,
      };
      try {
        const errorBody = await response.json();
        error.message = errorBody.message || response.statusText;
      } catch {
        // ignore JSON parse errors
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

  post<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>("POST", path, body);
  }

  put<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>("PUT", path, body);
  }

  patch<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>("PATCH", path, body);
  }

  delete<T>(path: string): Promise<T> {
    return this.request<T>("DELETE", path);
  }
}

export const api = new ApiClient(BASE_URL);
export type { ApiError };
