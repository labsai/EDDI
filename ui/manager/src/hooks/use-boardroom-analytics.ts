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

export interface AnalyticsFilters {
  outcome?: GroupConversationState | null;
  style?: DiscussionStyle | null;
  date?: string | null;
}

export interface BoardroomAnalytics {
  // KPIs
  totalDiscussions: number;
  unfilteredTotal: number; // total before filters, for comparison
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
  hasError: boolean;
  groupCount: number;
  isFiltered: boolean;

  // Unfiltered counts for filter dropdowns
  outcomeCounts: Partial<Record<GroupConversationState, number>>;
  styleCounts: Partial<Record<DiscussionStyle, number>>;
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

// ─── Enriched conversation type ──────────────────────────────────

interface EnrichedConversation extends GroupConversation {
  _groupName: string;
  _memberCount: number;
  _style: DiscussionStyle | undefined;
}

// ─── Hook ────────────────────────────────────────────────────────

export function useBoardroomAnalytics(
  filters?: AnalyticsFilters,
): BoardroomAnalytics {
  const { data: groups, isLoading: groupsLoading } =
    useEnrichedGroupDescriptors(200);
  const { data: agents, isLoading: agentsLoading } = useAgentDescriptors(200);

  // Parallel fetch conversations for each group
  const groupIds = useMemo(
    () => (groups ?? []).map((g) => g.id),
    [groups],
  );

  const { conversationData, conversationsLoading, hasError } = useQueries({
    queries: groupIds.map((groupId) => ({
      queryKey: ["boardroom-analytics", "conversations", groupId],
      queryFn: () => listGroupConversations(groupId, CONVERSATIONS_PER_GROUP),
      staleTime: STALE_TIME,
      enabled: !groupsLoading && groupIds.length > 0,
    })),
    combine: (results) => ({
      conversationData: results.map((r) => r.data ?? []),
      conversationsLoading: results.some((r) => r.isLoading),
      hasError: results.some((r) => r.isError),
    }),
  });

  const isLoading = groupsLoading || agentsLoading || conversationsLoading;

  // Stabilize filter values so useMemo doesn't recompute on every render
  const fOutcome = filters?.outcome ?? null;
  const fStyle = filters?.style ?? null;
  const fDate = filters?.date ?? null;

  return useMemo(() => {
    if (isLoading || !groups || !agents) {
      return {
        totalDiscussions: 0,
        unfilteredTotal: 0,
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
        hasError,
        groupCount: 0,
        isFiltered: false,
        outcomeCounts: {},
        styleCounts: {},
      };
    }

    // Build group style lookup
    const groupStyleMap = new Map<string, DiscussionStyle>();
    for (const group of groups) {
      if (group.style) {
        groupStyleMap.set(group.id, group.style);
      }
    }

    // Flatten all conversations with enrichment
    const allConversations: EnrichedConversation[] = [];

    for (let i = 0; i < groups.length; i++) {
      const group = groups[i];
      const convs = conversationData[i] ?? [];
      for (const conv of convs) {
        allConversations.push({
          ...conv,
          _groupName: group.name || "Untitled",
          _memberCount: group.memberCount,
          _style: group.style,
        });
      }
    }

    const unfilteredTotal = allConversations.length;
    const hasFilters = fOutcome !== null || fStyle !== null || fDate !== null;

    // ── Unfiltered counts for filter dropdowns ────────────────────
    const outcomeCounts: Partial<Record<GroupConversationState, number>> = {};
    for (const conv of allConversations) {
      outcomeCounts[conv.state] = (outcomeCounts[conv.state] ?? 0) + 1;
    }

    const styleCounts: Partial<Record<DiscussionStyle, number>> = {};
    for (const conv of allConversations) {
      if (conv._style) {
        styleCounts[conv._style] = (styleCounts[conv._style] ?? 0) + 1;
      }
    }

    // ── Apply filters ───────────────────────────────────────────
    let filtered = allConversations;

    if (fOutcome) {
      filtered = filtered.filter((c) => c.state === fOutcome);
    }
    if (fStyle) {
      filtered = filtered.filter((c) => c._style === fStyle);
    }
    if (fDate) {
      filtered = filtered.filter(
        (c) => c.created && toDateKey(c.created) === fDate,
      );
    }

    // ── KPIs ──────────────────────────────────────────────────────
    const totalDiscussions = filtered.length;
    const completedCount = filtered.filter(
      (c) => c.state === "COMPLETED",
    ).length;
    const completionRate =
      totalDiscussions > 0
        ? Math.round((completedCount / totalDiscussions) * 100)
        : 0;

    // Duration
    const durations = filtered
      .map(durationMs)
      .filter((d): d is number => d !== null && d > 0);
    const avgDurationMs =
      durations.length > 0
        ? Math.round(durations.reduce((a, b) => a + b, 0) / durations.length)
        : 0;

    // Team size (always from all groups, not filtered)
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

    for (const conv of filtered) {
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

    for (const conv of filtered) {
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
    // Count styles based on filtered conversations' groups (deduplicated)
    const filteredGroupIds = new Set(filtered.map((c) => c.groupId));
    const styleCounts = new Map<DiscussionStyle, number>();
    for (const group of groups) {
      if (group.style && filteredGroupIds.has(group.id)) {
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
    for (const conv of filtered) {
      outcomeCounts.set(conv.state, (outcomeCounts.get(conv.state) ?? 0) + 1);
    }
    const outcomeDistribution: OutcomeCount[] = Array.from(
      outcomeCounts.entries(),
    )
      .map(([state, count]) => ({ state, count }))
      .sort((a, b) => b.count - a.count);

    // ── Phase distribution ────────────────────────────────────────
    const phaseCounts = new Map<TranscriptEntryType, number>();
    for (const conv of filtered) {
      for (const entry of conv.transcript ?? []) {
        phaseCounts.set(entry.type, (phaseCounts.get(entry.type) ?? 0) + 1);
      }
    }
    const phaseDistribution: PhaseCount[] = Array.from(
      phaseCounts.entries(),
    )
      .map(([type, count]) => ({ type, count }))
      .sort((a, b) => b.count - a.count);

    // ── Recent discussions (last 20 to allow filtering headroom) ──
    const recentDiscussions: RecentDiscussion[] = filtered
      .sort(
        (a, b) =>
          new Date(b.created).getTime() - new Date(a.created).getTime(),
      )
      .slice(0, 20)
      .map((conv) => ({
        id: conv.id,
        groupId: conv.groupId,
        groupName: conv._groupName,
        question: conv.originalQuestion || "Untitled",
        state: conv.state,
        memberCount: conv._memberCount,
        created: conv.created,
        durationMs: durationMs(conv),
      }));

    return {
      totalDiscussions,
      unfilteredTotal,
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
      hasError,
      groupCount: groups.length,
      isFiltered: hasFilters,
      outcomeCounts,
      styleCounts,
    };
  }, [isLoading, hasError, groups, agents, conversationData, fOutcome, fStyle, fDate]);
}
