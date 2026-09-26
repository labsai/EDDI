import { useState, useCallback } from 'react';
import type { DiscussionStyle } from '@/lib/api/groups';
import { useAuth } from '@/hooks/use-auth';
import { userScopedKey } from '@/lib/user-storage';

export interface DiscussionTemplate {
  id: string;
  name: string;
  description: string;
  style: DiscussionStyle;
  members: Array<{ displayName: string; role: string; agentId?: string }>;
  maxRounds: number;
  createdAt: string;
}

const STORAGE_KEY = 'workforce-templates';

function readTemplates(key: string): DiscussionTemplate[] {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(key) || '[]');
    return Array.isArray(parsed) ? (parsed as DiscussionTemplate[]) : [];
  } catch {
    return [];
  }
}

function writeTemplates(key: string, templates: DiscussionTemplate[]) {
  try {
    localStorage.setItem(key, JSON.stringify(templates));
  } catch {
    // localStorage may be full or unavailable (private browsing)
  }
}

export function useTemplates() {
  // Per signed-in user, so the next person on a shared browser does not get
  // this user's saved templates. Deliberately NOT cleared at logout: they exist
  // only here, and clearing them would destroy the user's work.
  const { user } = useAuth();
  const storageKey = userScopedKey(STORAGE_KEY, user?.username);

  const [state, setState] = useState(() => ({
    key: storageKey,
    templates: readTemplates(storageKey),
  }));
  if (state.key !== storageKey) {
    setState({ key: storageKey, templates: readTemplates(storageKey) });
  }
  // On the render that switched keys React discards this output and renders
  // again with the reloaded list, so the stale value is never committed.
  const templates = state.templates;

  const setTemplates = useCallback(
    (update: (prev: DiscussionTemplate[]) => DiscussionTemplate[]) => {
      setState(prev => {
        const next = update(prev.templates);
        writeTemplates(prev.key, next);
        return { ...prev, templates: next };
      });
    },
    [],
  );

  const saveTemplate = useCallback((template: Omit<DiscussionTemplate, 'id' | 'createdAt'>) => {
    const newTemplate: DiscussionTemplate = {
      ...template,
      id: crypto.randomUUID(),
      createdAt: new Date().toISOString(),
    };
    setTemplates(prev => {
      return [newTemplate, ...prev];
    });
    return newTemplate;
  }, [setTemplates]);

  const deleteTemplate = useCallback((id: string) => {
    setTemplates(prev => {
      return prev.filter(t => t.id !== id);
    });
  }, [setTemplates]);

  const getTemplate = useCallback((id: string) => {
    return templates.find(t => t.id === id);
  }, [templates]);

  return { templates, saveTemplate, deleteTemplate, getTemplate };
}
