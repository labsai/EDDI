// Turn load for EDDI (planning/throughput-optimization-plan.md, WS0). Run through loadtest.py.
//
// Environment:
//   BASE_URL   EDDI base URL inside the compose network (default http://eddi:7070)
//   AGENT_ID   deployed agent to talk to (required)
//   SCENARIO   steady | burst | same-conversation | long-conversation
//   VUS        concurrent virtual users = concurrent conversations (same-conversation: callers of one)
//   TURNS      turns per VU (steady, long-conversation, same-conversation)
//   THINK_MS   pause between a VU's turns
//   RAMP       burst: ramp-up duration (default 30s); HOLD: time at peak (default 60s)
//   OUT        summary file name under /results
//
// Every VU owns one conversation (except same-conversation), started on its first iteration,
// so concurrent conversations == VUS and each in-flight turn is a distinct conversation.
import http from 'k6/http';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';

const BASE = __ENV.BASE_URL || 'http://eddi:7070';
const AGENT = __ENV.AGENT_ID;
const SCENARIO = __ENV.SCENARIO || 'steady';
const VUS = parseInt(__ENV.VUS || '50', 10);
const TURNS = parseInt(__ENV.TURNS || (SCENARIO === 'long-conversation' ? '200' : '10'), 10);
const THINK_MS = parseInt(__ENV.THINK_MS || '0', 10);
const TIMEOUT = __ENV.HTTP_TIMEOUT || '180s';

const turnDuration = new Trend('turn_duration', true);
const turnsOk = new Counter('turns_ok');
const turns503 = new Counter('turns_503');
const turns429 = new Counter('turns_429');
const turns408 = new Counter('turns_408');
const turns409 = new Counter('turns_409');
const turns5xx = new Counter('turns_5xx_other');
const turnsOther = new Counter('turns_other');
const turnsNoResponse = new Counter('turns_no_response');
// HTTP 200 whose snapshot carries a structured TurnError: the pipeline failed and the client got a
// fallback, so it is neither a successful turn nor part of its latency.
const turnsAppError = new Counter('turns_app_error');
const startFailed = new Counter('start_failed');

function executor() {
  switch (SCENARIO) {
    case 'burst':
      return {
        executor: 'ramping-vus',
        startVUs: 0,
        stages: [
          { duration: __ENV.RAMP || '30s', target: VUS },
          { duration: __ENV.HOLD || '60s', target: VUS },
          { duration: '10s', target: 0 },
        ],
        gracefulRampDown: '120s',
      };
    case 'steady':
    case 'same-conversation':
    case 'long-conversation':
      return { executor: 'per-vu-iterations', vus: VUS, iterations: TURNS, maxDuration: __ENV.MAX_DURATION || '60m' };
    default:
      throw new Error(`unknown SCENARIO ${SCENARIO}`);
  }
}

export const options = {
  scenarios: { turns: executor() },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  // Requests are only issued to EDDI; nothing here is a threshold, the driver reads the numbers.
  discardResponseBodies: false,
};

function startConversation(tag) {
  const res = http.post(`${BASE}/agents/${AGENT}/start?userId=loadtest-${tag}`, null, { timeout: TIMEOUT, tags: { kind: 'start' } });
  const loc = res.headers['Location'] || res.headers['location'] || '';
  const m = /conversations\/([0-9a-f-]+)/.exec(loc);
  if (!m) {
    startFailed.add(1);
    return null;
  }
  return m[1];
}

export function setup() {
  if (!AGENT) throw new Error('AGENT_ID is required');
  if (SCENARIO === 'same-conversation') {
    const conv = startConversation('shared');
    if (!conv) throw new Error('could not start the shared conversation');
    return { shared: conv };
  }
  return {};
}

let myConversation = null;

export default function (data) {
  let conv = data.shared || myConversation;
  if (!conv) {
    conv = myConversation = startConversation(`vu${exec.vu.idInTest}`);
    if (!conv) {
      sleep(1);
      return;
    }
  }
  const body = JSON.stringify({ input: `turn ${exec.vu.iterationInScenario} from vu ${exec.vu.idInTest}` });
  const res = http.post(`${BASE}/agents/${conv}?returnCurrentStepOnly=true`, body, {
    headers: { 'Content-Type': 'application/json' },
    timeout: TIMEOUT,
    tags: { kind: 'turn' },
  });
  const s = res.status;
  if (s === 200) {
    let error = null;
    try {
      error = res.json('error');
    } catch (e) {
      error = 'unparsable response';
    }
    if (error) {
      turnsAppError.add(1);
    } else {
      turnsOk.add(1);
      turnDuration.add(res.timings.duration);
    }
  } else if (s === 503) turns503.add(1);
  else if (s === 429) turns429.add(1);
  else if (s === 408) turns408.add(1);
  else if (s === 409) turns409.add(1);
  else if (s >= 500) turns5xx.add(1);
  else if (s === 0) turnsNoResponse.add(1);
  else turnsOther.add(1);
  if (THINK_MS > 0) sleep(THINK_MS / 1000);
}

export function handleSummary(data) {
  const out = __ENV.OUT || 'k6-summary.json';
  return { [`/results/${out}`]: JSON.stringify(data, null, 2) };
}
