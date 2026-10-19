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
//   DELAY_MS   the mock LLM's delay for this run; turn_overhead = turn latency minus it
//   RETRY_503  0 (default): a 503 ends the iteration. N > 0: on 503 sleep Retry-After (capped at
//              RETRY_CAP_MS, default 10000, ±RETRY_JITTER, default 0.2) and resend the same turn,
//              at most N attempts in all; then the turn counts as abandoned. Conversation starts
//              back off the same way (starts_refused)
//   OUT        summary file name under /results
//   NODE_ADDRS cluster runs: comma-separated ip:port of every replica behind the load balancer.
//              The balancer names the replica that answered in X-Loadtest-Upstream; each turn
//              metric is tagged with it, and a no-op threshold per node makes k6 put the per-node
//              submetrics in the summary, which is how the driver reports latency per node.
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
const NODE_ADDRS = (__ENV.NODE_ADDRS || '').split(',').filter((a) => a);

const DELAY_MS = parseInt(__ENV.DELAY_MS || '0', 10);
const RETRY_503 = parseInt(__ENV.RETRY_503 || '0', 10);
const RETRY_CAP_MS = parseInt(__ENV.RETRY_CAP_MS || '10000', 10);
const RETRY_DEFAULT_MS = parseInt(__ENV.RETRY_DEFAULT_MS || '1000', 10);
const RETRY_JITTER = parseFloat(__ENV.RETRY_JITTER || '0.2');

const turnDuration = new Trend('turn_duration', true);
// EDDI's own share of a turn: its latency minus the time the mock LLM was told to wait. One turn
// is one LLM call here, so this is everything the platform adds (HTTP, pipeline, persistence).
const turnOverhead = new Trend('turn_overhead', true);
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
// Retry mode (RETRY_503 > 0): a client that honours 503 + Retry-After, as a well-behaved SDK does.
// Without it, load shedding reads as collapsed goodput: each refused VU fires again at once.
const turnTotalDuration = new Trend('turn_total_duration', true);
const turnAttempts = new Trend('turn_attempts');
const turnsAbandoned = new Counter('turns_abandoned');
const startsRefused = new Counter('starts_refused');

// The replica that answered (cluster runs); untagged when there is no balancer in front.
function nodeTags(res) {
  const upstream = res.headers['X-Loadtest-Upstream'] || res.headers['x-loadtest-upstream'];
  return upstream ? { node: upstream.split(',').pop().trim() } : {};
}

// Retry-After in seconds (EDDI sends delta-seconds), capped, with ±JITTER so that refused VUs
// do not come back in lockstep. Missing or unparsable: RETRY_DEFAULT_MS.
function backoffMs(res) {
  const header = res.headers['Retry-After'] || res.headers['retry-after'];
  const seconds = parseFloat(header);
  const base = Number.isFinite(seconds) && seconds >= 0 ? seconds * 1000 : RETRY_DEFAULT_MS;
  const capped = Math.min(base, RETRY_CAP_MS);
  return Math.max(0, capped * (1 + RETRY_JITTER * (2 * Math.random() - 1)));
}

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

// Thresholds that always pass: they exist only so the summary carries the per-node submetrics.
function perNodeThresholds() {
  const t = {};
  for (const addr of NODE_ADDRS) {
    t[`turn_duration{node:${addr}}`] = ['max>=0'];
    for (const c of ['turns_ok', 'turns_503', 'turns_409', 'turns_5xx_other', 'turns_app_error']) {
      t[`${c}{node:${addr}}`] = ['count>=0'];
    }
  }
  return t;
}

export const options = {
  scenarios: { turns: executor() },
  thresholds: perNodeThresholds(),
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  // Requests are only issued to EDDI; nothing here is a threshold, the driver reads the numbers.
  discardResponseBodies: false,
};

function startConversation(tag) {
  let res;
  // Starts are admitted like turns, so in retry mode a refused start backs off the same way.
  for (let attempt = 1; ; attempt++) {
    res = http.post(`${BASE}/agents/${AGENT}/start?userId=loadtest-${tag}`, null, { timeout: TIMEOUT, tags: { kind: 'start' } });
    if (res.status !== 503 || RETRY_503 === 0 || attempt >= RETRY_503) break;
    startsRefused.add(1);
    sleep(backoffMs(res) / 1000);
  }
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
  const started = Date.now();
  let res;
  let attempt = 0;
  for (;;) {
    attempt++;
    res = http.post(`${BASE}/agents/${conv}?returnCurrentStepOnly=true`, body, {
      headers: { 'Content-Type': 'application/json' },
      timeout: TIMEOUT,
      tags: { kind: 'turn' },
    });
    // Off by default (RETRY_503=0): a refusal used the iteration up, as in every earlier result.
    if (res.status !== 503 || RETRY_503 === 0) break;
    turns503.add(1, nodeTags(res));
    if (attempt >= RETRY_503) {
      turnsAbandoned.add(1);
      turnAttempts.add(attempt);
      if (THINK_MS > 0) sleep(THINK_MS / 1000);
      return;
    }
    sleep(backoffMs(res) / 1000);
  }
  const s = res.status;
  if (RETRY_503 > 0) turnAttempts.add(attempt);
  const tags = nodeTags(res);
  if (s === 200) {
    let error = null;
    try {
      error = res.json('error');
    } catch (e) {
      error = 'unparsable response';
    }
    if (error) {
      turnsAppError.add(1, tags);
    } else {
      turnsOk.add(1, tags);
      turnDuration.add(res.timings.duration, tags);
      turnOverhead.add(res.timings.duration - DELAY_MS);
      // What the user waited, refusals and backoff included; equals turn_duration without retries.
      turnTotalDuration.add(RETRY_503 > 0 ? Date.now() - started : res.timings.duration);
    }
  } else if (s === 503) turns503.add(1, tags);
  else if (s === 429) turns429.add(1);
  else if (s === 408) turns408.add(1);
  else if (s === 409) turns409.add(1, tags);
  else if (s >= 500) turns5xx.add(1, tags);
  else if (s === 0) turnsNoResponse.add(1);
  else turnsOther.add(1);
  if (THINK_MS > 0) sleep(THINK_MS / 1000);
}

export function handleSummary(data) {
  const out = __ENV.OUT || 'k6-summary.json';
  return { [`/results/${out}`]: JSON.stringify(data, null, 2) };
}
