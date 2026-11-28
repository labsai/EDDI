import { useState, useEffect } from "react";

/**
 * Debounce a value — returns a stable value that only updates
 * after `delay` ms of inactivity.
 */
export function useDebounce<T>(value: T, delay = 400): T {
  const [debounced, setDebounced] = useState(value);

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delay);
    return () => clearTimeout(timer);
  }, [value, delay]);

  return debounced;
}
