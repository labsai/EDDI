import { useState, useEffect, type Dispatch, type SetStateAction } from "react";

/**
 * A boolean state hook that persists its value to localStorage.
 * Reads the initial value from localStorage on mount and rehydrates
 * whenever the key changes. Writes back on change.
 * All localStorage access is wrapped in try/catch for restricted environments.
 */
export function usePersistedBoolean(
  key: string,
  defaultValue: boolean,
): [boolean, Dispatch<SetStateAction<boolean>>] {
  const [value, setValue] = useState(() => readFromStorage(key, defaultValue));

  // Rehydrate when the key changes (e.g. component reused with a different key)
  useEffect(() => {
    setValue(readFromStorage(key, defaultValue));
  }, [key, defaultValue]);

  // Persist to localStorage whenever key or value changes
  useEffect(() => {
    try {
      localStorage.setItem(key, String(value));
    } catch {
      /* storage unavailable — silently degrade */
    }
  }, [key, value]);

  return [value, setValue];
}

/** Read a boolean from localStorage, returning defaultValue on miss or error. */
function readFromStorage(key: string, defaultValue: boolean): boolean {
  try {
    const saved = localStorage.getItem(key);
    return saved !== null ? saved === "true" : defaultValue;
  } catch {
    return defaultValue;
  }
}
