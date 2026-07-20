import { useEffect } from "react";
import { useNavigate, useLocation } from "react-router-dom";

export function BoardroomShortcuts() {
  const navigate = useNavigate();
  const location = useLocation();

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement)?.tagName;
      if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") return;
      if ((e.target as HTMLElement)?.isContentEditable) return;

      if (e.ctrlKey || e.metaKey || e.altKey) return;

      switch (e.key) {
        case "n":
          if (
            location.pathname === "/workforce" ||
            location.pathname === "/workforce/"
          ) {
            e.preventDefault();
            navigate("/workforce/new");
          }
          break;
        case "?":
          e.preventDefault();
          window.dispatchEvent(new CustomEvent("boardroom:show-shortcuts"));
          break;
      }
    };

    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [navigate, location.pathname]);

  return null;
}
