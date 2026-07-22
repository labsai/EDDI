import { useState, useCallback } from 'react';
import type { DiscussionStyle } from '@/lib/api/groups';

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

function readTemplates(): DiscussionTemplate[] {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]');
  } catch {
    return [];
  }
}

function writeTemplates(templates: DiscussionTemplate[]) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(templates));
  } catch {
    // localStorage may be full or unavailable (private browsing)
  }
}

export function useTemplates() {
  const [templates, setTemplates] = useState<DiscussionTemplate[]>(readTemplates);

  const saveTemplate = useCallback((template: Omit<DiscussionTemplate, 'id' | 'createdAt'>) => {
    const newTemplate: DiscussionTemplate = {
      ...template,
      id: crypto.randomUUID(),
      createdAt: new Date().toISOString(),
    };
    setTemplates(prev => {
      const next = [newTemplate, ...prev];
      writeTemplates(next);
      return next;
    });
    return newTemplate;
  }, []);

  const deleteTemplate = useCallback((id: string) => {
    setTemplates(prev => {
      const next = prev.filter(t => t.id !== id);
      writeTemplates(next);
      return next;
    });
  }, []);

  const getTemplate = useCallback((id: string) => {
    return templates.find(t => t.id === id);
  }, [templates]);

  return { templates, saveTemplate, deleteTemplate, getTemplate };
}
