// Group-discussion load for EDDI (docs/group-conversations.md). Run through `loadtest.py group`.
//
// Environment:
//   BASE_URL     EDDI base URL inside the compose network (default http://eddi:7070)
//   GROUP_ID     group config to discuss in (required)
//   VUS          concurrent discussions
//   DISCUSSIONS  discussions per VU, back to back
//   OUT          summary file name under /results
//
// Each iteration is one POST /groups/{id}/conversations, which answers (201) when the discussion has
// ended. Every transcript entry that a member or the moderator spoke is one member turn (one
// pipeline run with one LLM call); ERROR and SKIPPED entries are counted apart.
import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';

const BASE = __ENV.BASE_URL || 'http://eddi:7070';
const GROUP = __ENV.GROUP_ID;
const VUS = parseInt(__ENV.VUS || '10', 10);
const DISCUSSIONS = parseInt(__ENV.DISCUSSIONS || '3', 10);
const TIMEOUT = __ENV.HTTP_TIMEOUT || '600s';

const discussionDuration = new Trend('discussion_duration', true);
const discussionsOk = new Counter('discussions_ok');
const discussionsFailed = new Counter('discussions_failed');
const memberTurns = new Counter('member_turns');
const memberTurnsFailed = new Counter('member_turns_failed');
const httpErrors = new Counter('discussion_http_errors');

// Entries that are not a member speaking: the question itself and the bookkeeping types.
const NOT_A_TURN = new Set(['QUESTION', 'ERROR', 'SKIPPED']);

export const options = {
  scenarios: { discussions: { executor: 'per-vu-iterations', vus: VUS, iterations: DISCUSSIONS, maxDuration: __ENV.MAX_DURATION || '60m' } },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  if (!GROUP) throw new Error('GROUP_ID is required');
}

export default function () {
  const body = JSON.stringify({
    question: `Discussion ${exec.vu.iterationInScenario} from vu ${exec.vu.idInTest}: which option is best?`,
    userId: `loadtest-vu${exec.vu.idInTest}`,
  });
  const res = http.post(`${BASE}/groups/${GROUP}/conversations`, body, {
    headers: { 'Content-Type': 'application/json' },
    timeout: TIMEOUT,
  });
  if (res.status !== 200 && res.status !== 201) {
    httpErrors.add(1, { status: String(res.status) });
    return;
  }
  let gc;
  try {
    gc = res.json();
  } catch (e) {
    httpErrors.add(1, { status: 'unparsable' });
    return;
  }
  let turns = 0;
  let failed = 0;
  for (const entry of gc.transcript || []) {
    if (entry.type === 'ERROR' || entry.type === 'SKIPPED') failed++;
    else if (!NOT_A_TURN.has(entry.type)) turns++;
  }
  memberTurns.add(turns);
  memberTurnsFailed.add(failed);
  if (gc.state === 'COMPLETED' && failed === 0) {
    discussionsOk.add(1);
    discussionDuration.add(res.timings.duration);
  } else {
    discussionsFailed.add(1);
  }
}

export function handleSummary(data) {
  const out = __ENV.OUT || 'k6-groups-summary.json';
  return { [`/results/${out}`]: JSON.stringify(data, null, 2) };
}
