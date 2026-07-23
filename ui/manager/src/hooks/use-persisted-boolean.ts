import { useState, useEffect } from "react";

/**
 * A boolean state hook that persists its value to localStorage.
 * Reads the initial value from localStorage on mount and writes back on change.
 * All localStorage access is wrapped in try/catch for restricted environments.
 */
export function usePersistedBoolean(
  key: string,
  defaultValue: boolean,
): [boolean, React.Dispatch<React.SetStateAction<boolean>>] {
  const [value, setValue] = useState(() => {
    try {
      const saved = localStorage.getItem(key);
      return saved !== null ? saved === "true" : defaultValue;
    } catch {
      return defaultValue;
    }
  });

  useEffect(() => {
    try {
      localStorage.setItem(key, String(value));
    } catch {
      /* storage unavailable — silently degrade */
    }
  }, [key, value]);

  return [value, setValue];
}
