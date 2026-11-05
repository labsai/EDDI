/* ──────────────────────────────────────────────
   ChatWidget — the embeddable chat
   Wires the route, the embedding (tokens, colours, config) and the agent's
   profile to the shared AgentConversation (@eddi/conversation), and composes
   the shared conversation components with the widget's own chrome.
   ────────────────────────────────────────────── */

import { useEffect, useLayoutEffect, useMemo, useRef, useState, useCallback } from "react";
import { MessageSquarePlus, MessagesSquare, Redo2, Square, Undo2 } from "lucide-react";

import { useChatState, useChatDispatch, useChatController, DEFAULT_PLACEHOLDER } from "@/store/chat-store";
import { t, getLocale } from "@/i18n";
import { useChatRoute } from "@/location";
import { ChatHeader } from "./ChatHeader";
import {
  Composer,
  Message,
  MessageList,
  PausedCard,
  QuickReplies,
  RatingCard,
  SecretInput,
  ThinkingIndicator,
  TypingIndicator,
  createConversationTransport,
  createManagedTransport,
  indicatorStatusText,
} from "@eddi/conversation";

import {
  fetchAgentProfile,
  setBaseUrl,
  setAuthToken,
  parseRatingProfile,
  ratableSteps,
  isPausedState,
  type ApprovalStatus,
  type ConversationSnapshot,
} from "@eddi/core";
import { parseAllowedTokenOrigins, startEmbedAuth } from "@/api/embed-auth";
import { readUrlToken } from "@/api/chat-config";
import { isDemoMode, createDemoTransport, demoRatingClient, DEMO_RATING_PROFILE } from "@/api/demo-api";
import { httpRatingClient } from "@/api/rating-api";
import { useRatings } from "@/hooks/useRatings";
import { useHitlPolling } from "@/hooks/useHitlPolling";
import type { ChatConfig } from "@/types";
import { END_REASON_AGENT_VERSION_RETIRED } from "@/types";

/** How long to wait for a postMessage token before starting without one. */
const TOKEN_WAIT_MS = 5000;
/** How often to repeat the ready signal while waiting, in case the parent was not listening yet. */
const READY_REPEAT_MS = 1000;

/**
 * Color query params → CSS variable mappings.
 * Usage: ?accentColor=%2300ff88&bgColor=%23111111
 * (URL-encode # as %23)
 */
const COLOR_PARAM_MAP: Record<string, string[]> = {
  accentColor:  ["--chat-accent", "--chat-user-bg", "--chat-input-focus"],
  accentHover:  ["--chat-accent-hover"],
  bgColor:      ["--chat-bg"],
  surfaceColor: ["--chat-surface"],
  textColor:    ["--chat-text"],
  textMuted:    ["--chat-text-muted"],
  agentBg:        ["--chat-agent-bg"],
  agentBorder:    ["--chat-agent-border"],
  agentText:      ["--chat-agent-text"],
  userBg:       ["--chat-user-bg"],
  userText:     ["--chat-user-text"],
  inputBg:      ["--chat-input-bg"],
  inputBorder:  ["--chat-input-border"],
  borderColor:  ["--chat-border"],
  headerBg:     ["--chat-surface"],
  fontFamily:   ["--chat-font"],
};

/**
 * Whether a `?…Color=` value is a plain colour. The value lands in a CSS
 * custom property, and a custom property takes ANY token sequence — so an
 * unchecked `?bgColor=url(https://tracker/x)` made the widget fetch a URL the
 * link's author chose, and a garbage value silently broke the theme. Accepted:
 * hex (#rgb, #rgba, #rrggbb, #rrggbbaa), rgb()/rgba()/hsl()/hsla() with plain
 * numeric arguments, and named colours (letters only — `transparent`,
 * `rebeccapurple`). Nothing with a URL, quote, semicolon or nested function.
 */
export function isSafeCssColor(value: string): boolean {
  const v = value.trim();
  if (!v || v.length > 64) return false;
  if (/^#(?:[0-9a-f]{3,4}|[0-9a-f]{6}|[0-9a-f]{8})$/i.test(v)) return true;
  if (/^(?:rgb|rgba|hsl|hsla)\(\s*[-+0-9.%\s,/deg]+\)$/i.test(v)) return true;
  return /^[a-z]{3,30}$/i.test(v);
}

/**
 * Whether a `?fontFamily=` value is a plain font list: names, spaces, commas,
 * hyphens and quotes — no parentheses, so no url() or other function.
 */
export function isSafeFontFamily(value: string): boolean {
  const v = value.trim();
  return !!v && v.length <= 200 && /^[a-z0-9 ,'"_-]+$/i.test(v);
}

/**
 * Apply one accent colour as CSS custom properties: the accent itself, the
 * user bubble and the input focus ring (as COLOR_PARAM_MAP maps
 * `?accentColor=`), plus the soft and quick-reply variants derived from it.
 * Ignored unless it passes isSafeCssColor. The one path every accent source
 * takes — `?accentColor=`, `ChatConfig.accentColor`, the deployment brand.
 */
export function applyAccentColor(value: string | null | undefined): void {
  if (!value || !isSafeCssColor(value)) return;
  const root = document.documentElement;
  const color = value.trim();
  for (const cssVar of COLOR_PARAM_MAP.accentColor) {
    root.style.setProperty(cssVar, color);
  }
  // color-mix works for any CSS color — a named color, rgb(), hsl() — where
  // appending a hex alpha only ever worked for a 6-digit #rrggbb.
  const tint = (percent: number) =>
    `color-mix(in srgb, ${color} ${percent}%, transparent)`;
  root.style.setProperty("--chat-accent-soft", tint(13));
  root.style.setProperty("--chat-qr-border", tint(40));
  root.style.setProperty("--chat-qr-bg", tint(18));
  root.style.setProperty("--chat-qr-text", color);
  root.style.setProperty("--chat-qr-hover", tint(33));
}

/** Apply color overrides from query params as CSS custom properties */
export function applyColorOverrides(params: URLSearchParams): void {
  const root = document.documentElement;
  for (const [param, vars] of Object.entries(COLOR_PARAM_MAP)) {
    const value = params.get(param);
    if (!value) continue;
    const valid = param === "fontFamily" ? isSafeFontFamily(value) : isSafeCssColor(value);
    if (!valid) {
      console.warn(`[eddi-chat] ignoring ${param}: not a plain ${param === "fontFamily" ? "font list" : "colour"}`);
      continue;
    }
    if (param === "accentColor") {
      // Also derives the soft and quick-reply variants, from a valid accent only.
      applyAccentColor(value);
      continue;
    }
    for (const cssVar of vars) {
      root.style.setProperty(cssVar, value.trim());
    }
  }
}

/**
 * True when `raw` parses, against this page's origin, to a URL on that same
 * origin whose pathname is exactly `raw` and which carries no query or fragment.
 * `http.ts` builds request URLs by concatenating `${base}${path}`, so a `?` or
 * `#` in the base turns the real path into a query string
 * (`/administration/cluster/caches/resync?` + `/agents/...`) and the widget
 * fires a state-changing request at a path the page owner never named. `..`
 * segments fail the pathname comparison because the URL parser collapses them.
 */
function isCleanSameOriginPath(raw: string): boolean {
  try {
    const url = new URL(raw, window.location.origin);
    return (
      url.origin === window.location.origin &&
      url.search === "" &&
      url.hash === "" &&
      url.pathname === raw
    );
  } catch {
    return false;
  }
}

/**
 * Sanitize the `?apiServer=` param so it can never redirect API traffic
 * off-origin. This is an ALLOW-list, not a deny-list: the value must be a clean
 * same-origin absolute path (a single leading `/`, as the dev proxy and sub-path
 * deployments use). A deny-list was bypassable — `?apiServer=%09https://attacker`
 * decodes to a leading TAB, which no scheme/`//`/`\` test matches, but the WHATWG
 * URL parser strips before resolving, turning `${base}${path}` into the attacker
 * origin and leaking the bearer token. So anything carrying a control character
 * or whitespace (the smuggling vector), a backslash, or not starting with a
 * single `/` is rejected. Beyond that the value must round-trip through
 * `new URL(raw, origin)` unchanged with no `?`, `#` or `%` (see
 * `isCleanSameOriginPath`), so it cannot smuggle a query string into the
 * concatenated request URL.
 */
export function sanitizeApiServer(raw: string | null): string | null {
  if (!raw) return null;
  const rejected =
    // Matching control characters is the point: they are the smuggling vector.
    // eslint-disable-next-line no-control-regex
    /[\u0000- \u007f\\]/.test(raw) || // control chars, whitespace, DEL, backslash
    !raw.startsWith("/") || // must be an absolute path...
    raw.startsWith("//") || // ...but not a protocol-relative //host
    raw.includes("%"); // no percent-encoded segments (%2F, %2e%2e, %09 ...)
  if (rejected || !isCleanSameOriginPath(raw)) {
    console.warn(
      "[eddi-chat] ignoring apiServer query param: only a clean same-origin path (single leading '/') is allowed",
    );
    return null;
  }
  return raw;
}

// Re-exported: the helpers' tests and older imports name it from here.
export { parseAllowedTokenOrigins };

/** Read feature toggles from query parameters */
export function parseConfigFromQuery(params: URLSearchParams): Partial<ChatConfig> {
  const cfg: Partial<ChatConfig> = {};
  if (params.get("hideUndo") === "true") cfg.enableUndo = false;
  if (params.get("hideRedo") === "true") cfg.enableRedo = false;
  if (params.get("hideNewConversation") === "true") cfg.enableNewConversation = false;
  if (params.get("hideQuickReplies") === "true") cfg.enableQuickReplies = false;
  if (params.get("hideStreaming") === "true") cfg.enableStreaming = false;
  if (params.get("hideLogo") === "true") cfg.showLogo = false;
  if (params.get("hideAgentName") === "true") cfg.showAgentName = false;
  // Only the three modes the theme hook knows. Anything else was written into
  // data-theme verbatim, matched no stylesheet and left the widget unstyled.
  const theme = params.get("theme");
  if (theme === "dark" || theme === "light" || theme === "system") cfg.theme = theme;
  if (params.get("title")) cfg.title = params.get("title")!;
  const accent = params.get("accentColor");
  if (accent && isSafeCssColor(accent)) cfg.accentColor = accent.trim();
  return cfg;
}

export function ChatWidget() {
  const state = useChatState();
  const dispatch = useChatDispatch();
  const controller = useChatController();

  const {
    params: { environment, agentId, userId: userIdParam, intent },
    searchParams,
    replaceSearch,
  } = useChatRoute();
  /**
   * `?token=` is read ONCE and then removed from the address bar. Left there,
   * a bearer token sat in the visible URL, the history entry, and anything the
   * user copied or bookmarked. It is honoured only when the operator set
   * `eddi.chat.url-token.enabled` (readUrlToken warns and yields null
   * otherwise); the parameter is stripped either way.
   */
  const [urlToken] = useState(() => readUrlToken(searchParams));
  const tokenOriginParam = searchParams.get("tokenOrigin");
  /**
   * False while a token is still expected from the embedding page. With
   * `?tokenOrigin=` set the host sends the bearer token by postMessage AFTER
   * the iframe loads, so starting at once ran the start request (and the
   * profile lookup) unauthenticated and failed with a 401 the user could only
   * clear by reloading. The widget therefore says it is ready and waits.
   */
  const [tokenReady, setTokenReady] = useState(
    () =>
      parseAllowedTokenOrigins(searchParams).length === 0 ||
      !!urlToken ||
      !!state.config.authToken,
  );
  const tokenReadyRef = useRef(tokenReady);
  useEffect(() => {
    tokenReadyRef.current = tokenReady;
  }, [tokenReady]);
  useEffect(() => {
    if (!searchParams.has("token")) return;
    const next = new URLSearchParams(searchParams);
    next.delete("token");
    replaceSearch(next);
  }, [searchParams, replaceSearch]);

  const userId =
    userIdParam ?? searchParams.get("userId") ?? undefined;
  const apiServer = searchParams.get("apiServer");
  const isManagedAgent = !!intent;
  const isDemo = isDemoMode(environment, agentId);

  const initializedRef = useRef(false);
  /** Inline "start a new conversation?" confirmation is showing. */
  const [confirmingRestart, setConfirmingRestart] = useState(false);
  /** Bumped to move focus into the composer once the field that replaced it goes. */
  const [composerFocusSignal, setComposerFocusSignal] = useState(0);

  /* ─── Apply query param config on mount ──────── */
  useEffect(() => {
    const queryCfg = parseConfigFromQuery(searchParams);
    if (Object.keys(queryCfg).length > 0) {
      dispatch({ type: "SET_CONFIG", config: queryCfg });
    }
  }, [searchParams, dispatch]);

  /* ─── Apply the accent, then the URL's colours ── */
  // config.accentColor is the embedder's, the brand's, or `?accentColor=`'s
  // (which SET_CONFIG above put there) — precedence in src/api/brand.ts. The
  // URL's colour params go on last, so a `?userBg=` still beats the
  // user-bubble colour the accent derives.
  useEffect(() => {
    applyAccentColor(state.config.accentColor);
    applyColorOverrides(searchParams);
  }, [state.config.accentColor, searchParams]);

  /* ─── Set base URL + auth on mount ──────────── */
  useEffect(() => {
    setBaseUrl(sanitizeApiServer(apiServer) ?? state.config.apiBaseUrl ?? "");
  }, [apiServer, state.config.apiBaseUrl]);

  useEffect(() => {
    // Query param is a convenience for embedding; config is the real channel.
    // `urlToken` was read once and stripped from the address above, so it does
    // not linger in the address bar, history, a Referer header or access logs.
    //
    // NOTE (residual risk): accepting a token straight from the URL is a
    // login-CSRF vector — a crafted link can silently authenticate the widget as
    // someone else's session — and the first request logs it before the strip
    // above. That is why `urlToken` is null unless the operator opted in with
    // eddi.chat.url-token.enabled. Prefer the postMessage handshake below, or
    // config.
    //
    // Only a token that is actually present is installed: clearing it here
    // would wipe one the host handed over by postMessage.
    const explicit = urlToken ?? state.config.authToken ?? null;
    if (explicit) setAuthToken(explicit);
  }, [urlToken, state.config.authToken]);

  /* ─── Token hand-off from an allow-listed host page ──
     See api/embed-auth.ts for the protocol. The widget announces itself to
     the parent (at an allow-listed origin, never "*"), accepts a token only
     from that parent at an allow-listed origin, asks again before the token
     expires, and answers a 401 by asking for a fresh one and repeating the
     request once — so a conversation started before the host's first token
     arrived, or a turn sent after it expired, still goes through. */
  useEffect(() => {
    const allowedOrigins = parseAllowedTokenOrigins(
      new URLSearchParams(tokenOriginParam ? { tokenOrigin: tokenOriginParam } : {}),
    );
    if (allowedOrigins.length === 0) return;
    const embed = startEmbedAuth({ allowedOrigins, onToken: () => setTokenReady(true) });
    // Repeat the ready signal while the start is held: the parent's listener
    // may not exist yet when the first one goes out.
    const repeat = setInterval(() => {
      if (tokenReadyRef.current) clearInterval(repeat);
      else embed.announce();
    }, READY_REPEAT_MS);
    // A parent that never answers must not leave the widget on a blank start
    // for good: go ahead unauthenticated, so the failure is the clear 401 copy.
    const giveUp = setTimeout(() => setTokenReady(true), TOKEN_WAIT_MS);
    return () => {
      embed.stop();
      clearInterval(repeat);
      clearTimeout(giveUp);
    };
  }, [tokenOriginParam]);

  /* ─── Review notice ─────────────────────────── */
  // Whether the profile lookup below has answered. Nothing is sent until it
  // has: otherwise a slow profile let somebody type before they were told the
  // conversation may be read. A failure (an EDDI without the endpoint) settles
  // it too, so it only ever waits for the request, never blocks on it.
  const [profileSettled, setProfileSettled] = useState(false);
  // Read at send time by the controller (`canSend`), which quick replies and
  // the requested field call directly. Synced after commit.
  const profileSettledRef = useRef(false);
  useEffect(() => {
    profileSettledRef.current = profileSettled;
  }, [profileSettled]);
  // The agent behind a managed route, which names an intent rather than an
  // agent: undefined until the managed conversation has loaded; null when it
  // named none.
  const profileAgentId = agentId ?? (isManagedAgent ? state.openedAgentId : null);
  useEffect(() => {
    // A new target starts without the previous one's notice, and a slower answer
    // for the previous target cannot overwrite the new one's.
    dispatch({ type: "SET_REVIEW_NOTICE", notice: null });
    dispatch({ type: "SET_RATING_PROFILE", profile: isDemo ? DEMO_RATING_PROFILE : null });
    if (!tokenReady) {
      // The lookup is authenticated; asked before the token arrived it would
      // fail and read as "no notice".
      setProfileSettled(false);
      return;
    }
    if (profileAgentId === undefined && !isDemo) {
      // A managed conversation still loading: its agent is not known yet.
      setProfileSettled(false);
      return;
    }
    if (isDemo || !profileAgentId) {
      setProfileSettled(true);
      return;
    }
    setProfileSettled(false);
    let cancelled = false;
    fetchAgentProfile(profileAgentId, environment ?? "production")
      .then((profile) => {
        if (cancelled) return;
        dispatch({ type: "SET_REVIEW_NOTICE", notice: profile?.reviewNotice ?? null });
        // Absent on an older server, or when the deployed version asks for no
        // ratings: no rating control is shown.
        dispatch({ type: "SET_RATING_PROFILE", profile: parseRatingProfile(profile?.rating) });
      })
      .catch(() => { /* no profile endpoint: no notice, no ratings */ })
      .finally(() => {
        if (!cancelled) setProfileSettled(true);
      });
    return () => {
      cancelled = true;
    };
  }, [profileAgentId, environment, isDemo, dispatch, tokenReady]);

  /* ─── Ratings ───────────────────────────────── */
  // Nothing renders unless the agent's profile asks for ratings.
  const ratings = useRatings({
    client: isDemo ? demoRatingClient : httpRatingClient,
    conversationId: state.conversationId,
    profile: state.ratingProfile,
    messages: state.messages,
    conversationState: state.conversationState,
  });
  const ratableByPosition = useMemo(() => ratableSteps(state.messages), [state.messages]);
  const clearRatedStep = ratings.clearStep;

  /**
   * Show the agent's name, once per open, from the conversation read itself —
   * the snapshot carries it for anyone allowed to use the agent, including a
   * user holding only `eddi-user`. With no name the header shows the logo,
   * titled with the configured `title`.
   */
  const loadAgentName = useCallback(
    (snapshot: Partial<ConversationSnapshot>, gen: number) => {
      if (isDemo || state.config.showAgentName === false) return;
      const name = snapshot.agentName;
      if (typeof name !== "string" || !name.trim()) return;
      if (controller.isCurrent(gen)) dispatch({ type: "SET_AGENT_NAME", name });
    },
    [controller, dispatch, isDemo, state.config.showAgentName],
  );

  /* ─── The route, as the controller's transport ─ */
  const transport = useMemo(() => {
    if (isDemo) return createDemoTransport();
    if (isManagedAgent && intent && userId) return createManagedTransport({ intent, userId });
    if (environment && agentId) return createConversationTransport({ environment, agentId, userId });
    return null;
  }, [isDemo, isManagedAgent, intent, userId, environment, agentId]);

  // Before any effect below can open, send or poll: a layout effect runs ahead
  // of every passive one.
  useLayoutEffect(() => {
    if (transport) controller.setOptions({ transport });
    controller.setOptions({
      streaming: !!state.config.enableStreaming,
      canSend: () => profileSettledRef.current,
      environment,
      onStepRerun: clearRatedStep,
      t,
    });
    controller.onOpened = loadAgentName;
  }, [controller, transport, state.config.enableStreaming, environment, clearRatedStep, loadAgentName]);

  /* ─── Auto-start conversation ───────────────── */
  // Held until the token the host promised has arrived (or the wait ran out).
  useEffect(() => {
    if (!tokenReady || initializedRef.current || !transport) return;
    initializedRef.current = true;
    void controller.open(false);
  }, [tokenReady, transport, controller]);

  /* ─── Actions ───────────────────────────────── */
  const handleSend = useCallback(
    (text: string, isSecret?: boolean) => {
      void controller.send(text, isSecret);
    },
    [controller],
  );

  const handleQuickReply = useCallback(
    (value: string) => {
      // The clicked button unmounts with the replies; keep a keyboard user's
      // place by moving focus to the composer once it is enabled again.
      setComposerFocusSignal((n) => n + 1);
      void controller.send(value);
    },
    [controller],
  );

  const handleRestart = useCallback(async () => {
    setConfirmingRestart(false);
    await controller.restart();
  }, [controller]);

  // Cancelling the confirmation unmounts the focused button; give focus back
  // to the control that opened it.
  const closeRestartConfirm = useCallback(() => {
    setConfirmingRestart(false);
    requestAnimationFrame(() =>
      document.querySelector<HTMLElement>('[data-testid="restart-btn"]')?.focus(),
    );
  }, []);

  /**
   * The toolbar's New conversation. It discards the transcript (and, on the
   * managed route, ends the conversation server-side), and it sits next to
   * Stop — so when there is something to lose it asks first. An untouched
   * conversation has nothing to lose and restarts at once.
   */
  const requestRestart = useCallback(() => {
    if (state.messages.some((m) => m.role === "user")) {
      setConfirmingRestart(true);
    } else {
      void handleRestart();
    }
  }, [state.messages, handleRestart]);

  /* ─── HITL: watch a paused conversation ─────── */
  const isPaused = isPausedState(state.conversationState);

  const handleApprovalStatus = useCallback(
    (status: ApprovalStatus | null) => controller.setApprovalStatus(status),
    [controller],
  );
  const handlePauseResolved = useCallback(
    (isStale: () => boolean) => controller.refreshAfterPause(isStale),
    [controller],
  );

  useHitlPolling({
    conversationId: state.conversationId,
    paused: isPaused && !isDemo,
    onStatus: handleApprovalStatus,
    onResolved: handlePauseResolved,
  });

  const handleStop = useCallback(() => {
    // The Stop button unmounts when the turn ends; hand focus to the composer.
    setComposerFocusSignal((n) => n + 1);
    void controller.stop();
  }, [controller]);

  /* ─── Composer focus handoff ────────────────── */
  // The agent-requested field replaces the composer, and leaving it (sending or
  // cancelling) unmounts whatever had focus. Moving it to the composer keeps a
  // keyboard user's place.
  const handleRequestedFieldSend = useCallback(
    (value: string, isSecret: boolean) => {
      setComposerFocusSignal((n) => n + 1);
      void controller.send(value, isSecret);
    },
    [controller],
  );
  const handleRequestedFieldCancel = useCallback(() => {
    dispatch({ type: "CLEAR_INPUT_FIELD" });
    setComposerFocusSignal((n) => n + 1);
  }, [dispatch]);

  /* ─── Derived state ─────────────────────────── */
  const isStuck =
    state.conversationState === "ERROR" ||
    state.conversationState === "EXECUTION_INTERRUPTED";
  const historyBusy = state.historyBusy;
  // Once the reply's text is arriving, the bubble itself shows the agent at
  // work; dots below it would be a second "agent is typing".
  const lastMessage = state.messages[state.messages.length - 1];
  const replyIsStreaming =
    !!lastMessage?.isStreaming && lastMessage.role !== "user" && !!lastMessage.content;
  const isEnded = state.conversationState === "ENDED";
  const showInput =
    !isEnded && (state.quickReplies.length === 0 || state.messages.length > 0);
  const showStartRetry = state.startFailed && !state.conversationId && !isEnded;
  const sentCount = useMemo(() => state.messages.filter((m) => m.role === "user").length, [state.messages]);
  // Shown in the empty state too: an agent with no greeting has no messages yet,
  // and the header's "Rate this conversation" has to open the card for it.
  const ratingCard =
    ratings.cardOpen && ratings.profile && !isPaused ? (
      <RatingCard
        key={state.conversationId ?? "none"}
        profile={ratings.profile}
        current={ratings.conversationRating}
        onSubmit={ratings.rateConversation}
        onWithdraw={ratings.withdrawConversation}
        onClose={ratings.closeCard}
      />
    ) : null;

  return (
    <div className="chat-root">
      <ChatHeader
        onRateConversation={
          ratings.profile?.conversationRating && state.conversationId ? ratings.openCard : undefined
        }
      />
      {state.reviewNotice && (
        <div className="chat-review-notice" role="note" data-testid="chat-review-notice">
          {state.reviewNotice}
        </div>
      )}

      <MessageList
        className="chat-messages"
        testId="chat-transcript"
        label={t("conversation.transcript.label")}
        busy={state.isProcessing}
        content={state.messages}
        sentCount={sentCount}
        between={
          <>
            {/* The indicator's announcement. It sits OUTSIDE the transcript
                because the transcript is aria-busy for the whole turn, and a
                status inside a busy region may never be announced — which would
                silence "Using calculator…", a state that only exists mid-turn.
                Always mounted: a live region added together with its text is
                often not read. */}
            <div
              className="chat-sr-only"
              role="status"
              aria-live="polite"
              data-testid="chat-activity-status"
            >
              {!isPaused && (state.isThinking || state.isEscalating || state.activeTool)
                ? indicatorStatusText(state.isEscalating, state.activeTool, t)
                : ""}
            </div>

            {/* A refused rating (rolled back) says why. Always mounted, so the
                message is announced when it appears. */}
            <div className="chat-toast-region" role="status" aria-live="polite" data-testid="rating-toast-region">
              {ratings.toast && (
                <div className="chat-toast" data-testid="rating-toast">
                  {ratings.toast}
                </div>
              )}
            </div>
          </>
        }
      >
        {state.messages.length === 0 && !state.isProcessing && !isPaused ? (
          <>
            <div className="chat-empty">
              <MessagesSquare className="chat-empty__icon" size="1em" />
              {/* Keyed on whether a conversation exists, not on whether it has
                  messages: an agent with no greeting is READY, not "starting". */}
              <p className="chat-empty__text">
                {state.conversationId
                  ? t("transcript.ready")
                  : t("transcript.starting")}
              </p>
            </div>
            {ratingCard}
          </>
        ) : (
          <>
            {state.messages.map((msg, i) => {
              const ratingStep = ratings.profile?.messageRating ? ratableByPosition[i] : null;
              return (
                <Message
                  key={msg.id}
                  message={msg}
                  enableMarkdown={state.config.enableMarkdown !== false}
                  enableMath={state.config.enableMath !== false}
                  enableCodeHighlight={state.config.enableCodeHighlight !== false}
                  agentName={state.agentName}
                  ratingStep={ratingStep}
                  rating={ratingStep !== null ? ratings.stepRatings[ratingStep] : undefined}
                  ratingProfile={ratings.profile}
                  onRateStep={ratings.rateStep}
                />
              );
            })}

            {ratingCard}

            {isPaused ? (
              // From the stream's own news, at once: approval-status may not have
              // the pause stored yet, and the composer is locked meanwhile.
              <PausedCard
                status={state.approvalStatus}
                onCancel={() => void controller.cancel()}
                cancelDisabled={state.isProcessing}
                locale={getLocale()}
              />
            ) : (
              <>
                {(state.isThinking || state.isEscalating || state.activeTool) && (
                  <ThinkingIndicator
                    escalating={state.isEscalating}
                    tool={state.activeTool}
                  />
                )}
                {state.isProcessing &&
                  !historyBusy &&
                  !state.isThinking &&
                  !state.isEscalating &&
                  !state.activeTool &&
                  !replyIsStreaming && <TypingIndicator />}
              </>
            )}
          </>
        )}
      </MessageList>

      {!isEnded && !isPaused &&
        state.config.enableQuickReplies !== false && (
          <QuickReplies
            replies={state.quickReplies}
            onSelect={handleQuickReply}
            disabled={state.isProcessing || !profileSettled}
          />
        )}

      {isStuck && (
        <div className="recovery-banner" role="status" data-testid="recovery-banner">
          <span className="recovery-banner__text">
            {state.conversationState === "EXECUTION_INTERRUPTED"
              ? t("recovery.interrupted")
              : t("recovery.error")}
          </span>
          <div className="recovery-banner__actions">
            <button
              className="recovery-banner__btn"
              onClick={() => void controller.retry()}
              disabled={state.isProcessing}
              data-testid="recovery-retry"
            >
              {t("recovery.retry")}
            </button>
            <button
              className="recovery-banner__btn"
              onClick={handleRestart}
              disabled={state.isProcessing}
              data-testid="recovery-restart"
            >
              {t("recovery.restart")}
            </button>
          </div>
        </div>
      )}

      {showStartRetry && (
        <div className="recovery-banner" data-testid="start-failed">
          <div className="recovery-banner__actions">
            <button
              className="recovery-banner__btn"
              onClick={() => void controller.retryStart()}
              data-testid="start-retry"
            >
              {t("start.retry")}
            </button>
          </div>
        </div>
      )}

      {isEnded ? (
        <div className="chat-ended" data-testid="chat-ended">
          {state.endReason === END_REASON_AGENT_VERSION_RETIRED ? (
            // Ended because the agent was updated in a way this conversation
            // could not follow — say so, rather than a bare "ended".
            <span
              className="chat-ended__label"
              role="status"
              data-testid="chat-ended-retired"
            >
              {t("ended.retired")}
            </span>
          ) : (
            <span className="chat-ended__label">{t("ended.label")}</span>
          )}
          <button className="chat-ended__restart" onClick={handleRestart}>
            {t("ended.restart")}
          </button>
        </div>
      ) : (
        showInput && (
          <div className="chat-input-area">
            {/* Action bar — undo / redo / restart near the input */}
            {state.conversationId && (
              <div className="chat-actions">
                <div className="chat-actions__left">
                  {state.config.enableUndo !== false && (
                    <button
                      className="chat-actions__btn"
                      onClick={() => void controller.undo()}
                      disabled={!state.undoAvailable || state.isProcessing}
                      title={t("undo.title")}
                      aria-label={t("undo.title")}
                      data-testid="undo-btn"
                      style={{ opacity: state.undoAvailable && !state.isProcessing ? 1 : 0.35 }}
                    >
                      <Undo2 size="1em" />
                    </button>
                  )}
                  {state.config.enableRedo !== false && (
                    <button
                      className="chat-actions__btn"
                      onClick={() => void controller.redo()}
                      disabled={!state.redoAvailable || state.isProcessing}
                      title={t("redo.title")}
                      aria-label={t("redo.title")}
                      data-testid="redo-btn"
                      style={{ opacity: state.redoAvailable && !state.isProcessing ? 1 : 0.35 }}
                    >
                      <Redo2 size="1em" />
                    </button>
                  )}
                </div>
                <div className="chat-actions__right">
                  {state.isProcessing && !historyBusy && !isPaused && (
                    <button
                      className="chat-actions__btn chat-actions__btn--stop"
                      onClick={handleStop}
                      title={t("action.stop")}
                      aria-label={t("action.stop")}
                      data-testid="chat-stop"
                    >
                      <Square size="1em" fill="currentColor" />
                    </button>
                  )}
                  {state.config.enableNewConversation !== false && (
                    <button
                      className="chat-actions__btn"
                      onClick={requestRestart}
                      title={t("action.new")}
                      aria-label={t("action.new")}
                      aria-expanded={confirmingRestart}
                      data-testid="restart-btn"
                    >
                      {/* Not a circular arrow: that reads as retry, and this discards the
                          conversation. The Manager uses the same icon for it. */}
                      <MessageSquarePlus size="1em" />
                    </button>
                  )}
                </div>
              </div>
            )}

            {confirmingRestart && (
              <div
                className="chat-confirm"
                role="alertdialog"
                aria-label={t("action.new")}
                aria-describedby="chat-confirm-text"
                data-testid="restart-confirm"
                onKeyDown={(e) => {
                  if (e.key === "Escape") closeRestartConfirm();
                }}
              >
                <span id="chat-confirm-text" className="chat-confirm__text">
                  {t("action.newConfirm")}
                </span>
                <div className="chat-confirm__actions">
                  <button
                    type="button"
                    className="chat-confirm__btn chat-confirm__btn--danger"
                    onClick={handleRestart}
                    data-testid="restart-confirm-yes"
                  >
                    {t("action.newYes")}
                  </button>
                  <button
                    type="button"
                    className="chat-confirm__btn"
                    onClick={closeRestartConfirm}
                    autoFocus
                    data-testid="restart-confirm-cancel"
                  >
                    {t("action.newCancel")}
                  </button>
                </div>
              </div>
            )}

            {state.activeInputField ? (
              <SecretInput
                label={state.activeInputField.label}
                placeholder={state.activeInputField.placeholder}
                defaultValue={state.activeInputField.defaultValue}
                subType={state.activeInputField.subType}
                onSend={handleRequestedFieldSend}
                onCancel={handleRequestedFieldCancel}
                disabled={state.isProcessing}
                autoFocus={state.messages.some((m) => m.role === "user")}
              />
            ) : (
              <Composer
                onSend={handleSend}
                disabled={(!state.conversationId && !isManagedAgent) || isPaused || !profileSettled}
                conversationId={state.conversationId}
                focusSignal={composerFocusSignal}
                placeholder={
                  state.config.placeholder && state.config.placeholder !== DEFAULT_PLACEHOLDER
                    ? state.config.placeholder
                    : undefined
                }
              />
            )}
          </div>
        )
      )}
    </div>
  );
}
