import { useMemo } from "react";
import { useQueries } from "@tanstack/react-query";
import { useEnrichedGroupDescriptors } from "@/hooks/use-groups";
import { useAgentDescriptors } from "@/hooks/use-agents";
import {
  listGroupConversations,
  type GroupConversation,
  type DiscussionStyle,
  type GroupConversationState,
  type TranscriptEntryType,
} from "@/lib/api/groups";

// ─── Types ───────────────────────────────────────────────────────

export interface AgentStat {
  agentId: string;
  displayName: string;
  sessions: number;
  contributions: number;
  totalContentLength: number;
  errors: number;
}

export interface DayActivity {
  date: string; // YYYY-MM-DD
  count: number;
}

export interface StyleCount {
  style: DiscussionStyle;
  count: number;
}

export interface OutcomeCount {
  state: GroupConversationState;
  count: number;
}

export interface PhaseCount {
  type: TranscriptEntryType;
  count: number;
}

export interface RecentDiscussion {
  id: string;
  groupId: string;
  groupName: string;
  question: string;
  state: GroupConversationState;
  memberCount: number;
  created: string;
  durationMs: number | null;
}

export interface BoardroomAnalytics {
  // KPIs
  totalDiscussions: number;
  completionRate: number; // 0–100
  activeExperts: number;
  totalExperts: number;
  avgDurationMs: number;
  avgTeamSize: number;

  // Charts
  agentStats: AgentStat[];
  dailyActivity: DayActivity[];
  styleDistribution: StyleCount[];
  outcomeDistribution: OutcomeCount[];
  phaseDistribution: PhaseCount[];
  recentDiscussions: RecentDiscussion[];

  // Meta
  isLoading: boolean;
  groupCount: number;
}

// ─── Helpers ─────────────────────────────────────────────────────

const STALE_TIME = 5 * 60 * 1000; // 5 minutes
const CONVERSATIONS_PER_GROUP = 50;

function toDateKey(ts: string | number): string {
  const d = new Date(ts);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

function durationMs(conv: GroupConversation): number | null {
  if (!conv.created || !conv.lastModified) return null;
  const start = new Date(conv.created).getTime();
  const end = new Date(conv.lastModified).getTime();
  return end > start ? end - start : null;
}

function getLast30Days(): string[] {
  const days: string[] = [];
  const now = new Date();
  for (let i = 29; i >= 0; i--) {
    const d = new Date(now);
    d.setDate(d.getDate() - i);
    days.push(toDateKey(d.toISOString()));
  }
  return days;
}

// ─── Hook ────────────────────────────────────────────────────────

export function useBoardroomAnalytics(): BoardroomAnalytics {
  const { data: groups, isLoading: groupsLoading } =
    useEnrichedGroupDescriptors(200);
  const { data: agents, isLoading: agentsLoading } = useAgentDescriptors(200);

  // Parallel fetch conversations for each group
  const groupIds = useMemo(
    () => (groups ?? []).map((g) => g.id),
    [groups],
  );

  const conversationQueries = useQueries({
    queries: groupIds.map((groupId) => ({
      queryKey: ["boardroom-analytics", "conversations", groupId],
      queryFn: () => listGroupConversations(groupId, CONVERSATIONS_PER_GROUP),
      staleTime: STALE_TIME,
      enabled: !groupsLoading && groupIds.length > 0,
    })),
  });

  const conversationsLoading = conversationQueries.some((q) => q.isLoading);
  const isLoading = groupsLoading || agentsLoading || conversationsLoading;

  return useMemo(() => {
    if (isLoading || !groups || !agents) {
      return {
        totalDiscussions: 0,
        completionRate: 0,
        activeExperts: 0,
        totalExperts: 0,
        avgDurationMs: 0,
        avgTeamSize: 0,
        agentStats: [],
        dailyActivity: [],
        styleDistribution: [],
        outcomeDistribution: [],
        phaseDistribution: [],
        recentDiscussions: [],
        isLoading: true,
        groupCount: 0,
      };
    }

    // Flatten all conversations
    const allConversations: (GroupConversation & {
      _groupName: string;
      _memberCount: number;
    })[] = [];

    for (let i = 0; i < groups.length; i++) {
      const group = groups[i];
      const convs = conversationQueries[i]?.data ?? [];
      for (const conv of convs) {
        allConversations.push({
          ...conv,
          _groupName: group.name || "Untitled",
          _memberCount: group.memberCount,
        });
      }
    }

    // ── KPIs ──────────────────────────────────────────────────────
    const totalDiscussions = allConversations.length;
    const completedCount = allConversations.filter(
      (c) => c.state === "COMPLETED",
    ).length;
    const completionRate =
      totalDiscussions > 0
        ? Math.round((completedCount / totalDiscussions) * 100)
        : 0;

    // Duration
    const durations = allConversations
      .map(durationMs)
      .filter((d): d is number => d !== null && d > 0);
    const avgDurationMs =
      durations.length > 0
        ? Math.round(durations.reduce((a, b) => a + b, 0) / durations.length)
        : 0;

    // Team size
    const teamSizes = groups.map((g) => g.memberCount).filter((n) => n > 0);
    const avgTeamSize =
      teamSizes.length > 0
        ? Math.round(
            (teamSizes.reduce((a, b) => a + b, 0) / teamSizes.length) * 10,
          ) / 10
        : 0;

    // ── Agent stats ───────────────────────────────────────────────
    const agentMap = new Map<
      string,
      {
        displayName: string;
        sessions: Set<string>;
        contributions: number;
        totalContentLength: number;
        errors: number;
      }
    >();

    const activeAgentIds = new Set<string>();

    for (const conv of allConversations) {
      const seenInConv = new Set<string>();
      for (const entry of conv.transcript ?? []) {
        if (!entry.speakerAgentId) continue;

        activeAgentIds.add(entry.speakerAgentId);

        let stat = agentMap.get(entry.speakerAgentId);
        if (!stat) {
          stat = {
            displayName: entry.speakerDisplayName || entry.speakerAgentId,
            sessions: new Set<string>(),
            contributions: 0,
            totalContentLength: 0,
            errors: 0,
          };
          agentMap.set(entry.speakerAgentId, stat);
        }

        if (!seenInConv.has(entry.speakerAgentId)) {
          stat.sessions.add(conv.id);
          seenInConv.add(entry.speakerAgentId);
        }

        if (
          entry.type !== "QUESTION" &&
          entry.type !== "ERROR" &&
          entry.type !== "SKIPPED"
        ) {
          stat.contributions++;
          stat.totalContentLength += (entry.content ?? "").length;
        }

        if (entry.type === "ERROR" || entry.type === "SKIPPED") {
          stat.errors++;
        }
      }
    }

    const agentStats: AgentStat[] = Array.from(agentMap.entries())
      .map(([agentId, s]) => ({
        agentId,
        displayName: s.displayName,
        sessions: s.sessions.size,
        contributions: s.contributions,
        totalContentLength: s.totalContentLength,
        errors: s.errors,
      }))
      .sort((a, b) => b.contributions - a.contributions);

    // ── Daily activity (30 days) ──────────────────────────────────
    const last30 = getLast30Days();
    const dayCounts = new Map<string, number>();
    for (const day of last30) dayCounts.set(day, 0);

    for (const conv of allConversations) {
      if (conv.created) {
        const key = toDateKey(conv.created);
        if (dayCounts.has(key)) {
          dayCounts.set(key, (dayCounts.get(key) ?? 0) + 1);
        }
      }
    }

    const dailyActivity: DayActivity[] = last30.map((date) => ({
      date,
      count: dayCounts.get(date) ?? 0,
    }));

    // ── Style distribution ────────────────────────────────────────
    const styleCounts = new Map<DiscussionStyle, number>();
    for (const group of groups) {
      if (group.style) {
        styleCounts.set(group.style, (styleCounts.get(group.style) ?? 0) + 1);
      }
    }
    const styleDistribution: StyleCount[] = Array.from(
      styleCounts.entries(),
    )
      .map(([style, count]) => ({ style, count }))
      .sort((a, b) => b.count - a.count);

    // ── Outcome distribution ──────────────────────────────────────
    const outcomeCounts = new Map<GroupConversationState, number>();
    for (const conv of allConversations) {
      outcomeCounts.set(conv.state, (outcomeCounts.get(conv.state) ?? 0) + 1);
    }
    const outcomeDistribution: OutcomeCount[] = Array.from(
      outcomeCounts.entries(),
    )
      .map(([state, count]) => ({ state, count }))
      .sort((a, b) => b.count - a.count);

    // ── Phase distribution ────────────────────────────────────────
    const phaseCounts = new Map<TranscriptEntryType, number>();
    for (const conv of allConversations) {
      for (const entry of conv.transcript ?? []) {
        phaseCounts.set(entry.type, (phaseCounts.get(entry.type) ?? 0) + 1);
      }
    }
    const phaseDistribution: PhaseCount[] = Array.from(
      phaseCounts.entries(),
    )
      .map(([type, count]) => ({ type, count }))
      .sort((a, b) => b.count - a.count);

    // ── Recent discussions (last 10) ──────────────────────────────
    const recentDiscussions: RecentDiscussion[] = allConversations
      .sort(
        (a, b) =>
          new Date(b.created).getTime() - new Date(a.created).getTime(),
      )
      .slice(0, 10)
      .map((conv) => ({
        id: conv.id,
        groupId: conv.groupId,
        groupName: conv._groupName,
        question:
          conv.originalQuestion || "Untitled",
        state: conv.state,
        memberCount: conv._memberCount,
        created: conv.created,
        durationMs: durationMs(conv),
      }));

    return {
      totalDiscussions,
      completionRate,
      activeExperts: activeAgentIds.size,
      totalExperts: agents.length,
      avgDurationMs,
      avgTeamSize,
      agentStats,
      dailyActivity,
      styleDistribution,
      outcomeDistribution,
      phaseDistribution,
      recentDiscussions,
      isLoading: false,
      groupCount: groups.length,
    };
  }, [isLoading, groups, agents, conversationQueries]);
}
