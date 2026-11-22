/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.integrations.email.EmailUserIdentity;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.ext.web.RoutingContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * The origin taint of a conversation: {@link #EMAIL} when its root turn came
 * from a mail, or from a conversation a mail started (email plan v3 §5.3).
 * <p>
 * A turn a mail started must never widen who can make EDDI act, nor decide its
 * own approvals; {@link EscalationGuard} refuses those operations for a tainted
 * turn. The taint is:
 * <ul>
 * <li><b>set</b> where the turn is submitted — the email channel puts
 * {@link #CONTEXT_KEY} into the context of the conversation start and of every
 * turn, a group stores it on the discussion and hands it to its members, a
 * schedule a tainted turn creates or changes carries it in its metadata, and
 * every HTTP request a tainted turn sends ({@code httpcalls},
 * {@code OutboundHttp}, MCP, A2A, the internal loopback) carries it as
 * {@link #HEADER};</li>
 * <li><b>read back</b> from an inbound request: a REST or MCP request carrying
 * {@link #HEADER} runs tainted ({@link #current()}), so a conversation,
 * schedule or group discussion it starts or continues is tainted too — the hop
 * through EDDI's own API does not shed it;</li>
 * <li><b>persisted</b> on the conversation memory and on a group conversation,
 * so a HITL resume next week or a self-continuation still carries it;</li>
 * <li><b>bound</b> per turn on the {@link ResolutionPrincipal}, so it travels
 * wherever that principal does;</li>
 * <li><b>inherited</b> by every conversation a tainted turn starts or
 * continues, whatever its user id;</li>
 * <li><b>sticky</b>: nothing clears it.</li>
 * </ul>
 * Accepting the taint from any caller is safe — it only ever restricts the
 * conversation that carries it — so the context key and the header need no
 * authentication. {@link ClientContextGuard} still reserves the key, because
 * only EDDI itself has a reason to set it.
 */
public final class TurnOrigin {

    /** A mail started the conversation, or one that led to it. */
    public static final String EMAIL = "email";

    /** The context key carrying the taint into a conversation start or a turn. */
    public static final String CONTEXT_KEY = "turnOrigin";

    /**
     * The request header every outbound request of a tainted turn carries the taint
     * in — httpcalls, {@code OutboundHttp}, MCP, A2A, the internal loopback — to
     * EDDI's own API and to third-party APIs alike. It tells a third party no more
     * than "an email drove this request", which is a deliberate departure from
     * {@code AgentCallMarker}, sent only to this deployment: EDDI reached under a
     * host name the self-check does not recognise would otherwise start an
     * untainted conversation. A relay that drops the header sheds the taint.
     */
    public static final String HEADER = "X-Eddi-Turn-Origin";

    /**
     * The request header with which a tainted turn states that a person approved
     * this very call through the HITL tool-approval gate, so the receiving side
     * allows a configuration write ({@link EscalationGuard#CONFIG_PATHS},
     * {@link EscalationGuard#CONFIG_TOOLS}). It counts only next to
     * {@link #HEADER}. The engine owns it: every path that sets the taint header
     * strips a configured value and sets this one only during the approved
     * execution ({@code ApprovedCallScope}). Forging it buys nothing a caller could
     * not have by leaving the taint header off.
     */
    public static final String APPROVED_HEADER = "X-Eddi-Approved-Call";

    private static final ResolutionPrincipalContext PRINCIPALS = new ResolutionPrincipalContext();

    /**
     * A taint bound without a principal, by {@link #callWith}: for work that starts
     * conversations on a thread no turn runs on — the email poller starting a group
     * discussion, a group starting a nested one — where binding a whole principal
     * would also change whose credentials the started conversations may use.
     */
    private static final ThreadLocal<String> BOUND = new ThreadLocal<>();

    /**
     * Where {@link #fromRequest()} reads the inbound request's taint from; replaced
     * only by tests ({@link #useRequestSource}).
     */
    private static volatile Supplier<String> requestSource = TurnOrigin::fromActiveRequest;

    /**
     * Where {@link #inboundApproved()} reads the inbound request's approval from;
     * replaced only by tests ({@link #useApprovedSource}).
     */
    private static volatile Supplier<Boolean> approvedSource = TurnOrigin::approvedFromActiveRequest;

    private TurnOrigin() {
    }

    /**
     * The taint of the work running on this thread, or {@code null}: the bound
     * principal's ({@link #of}), one bound by {@link #callWith}, or the one the
     * inbound REST or MCP request served on this thread carries in {@link #HEADER}
     * ({@link #fromRequest()}). A turn whose user id is an email identity counts as
     * tainted even when its conversation predates the stored taint.
     */
    public static String current() {
        return combine(combine(of(PRINCIPALS.current()), BOUND.get()), fromRequest());
    }

    /**
     * The taint the inbound request served on this thread carries in
     * {@link #HEADER}, or {@code null} — also when there is no request at all (a
     * pool thread, the poller, a unit test). It is bound explicitly where the
     * request enters ({@link #bindInbound}: {@code TaintedRequestFilter} for REST,
     * {@code McpErrorResultInterceptor} for MCP) and read back from the request
     * context; a request context whose taint cannot be read counts as tainted.
     * <p>
     * Taken from any caller, authenticated or not, because it can only restrict:
     * the internal loopback carries no credential, and a request without the header
     * is simply untainted. A header can add the taint and never remove it, since
     * what it says is only ever {@link #combine combined} with the other sources.
     */
    public static String fromRequest() {
        try {
            return requestSource.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * {@link #EMAIL} when any of the header values names it, otherwise
     * {@code null}: a repeated, empty or unknown value never clears a known one.
     */
    public static String fromHeaderValues(List<String> values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (normalise(value) != null) {
                return EMAIL;
            }
        }
        return null;
    }

    /**
     * The request's taint: what {@link #bindInbound} bound on the
     * {@link InboundTurnOrigin} of the active request context, combined with the
     * header of the {@link CurrentVertxRequest} as a second source. No active
     * request context is no request — untainted. An active one whose taint cannot
     * be read fails closed: tainted.
     */
    private static String fromActiveRequest() {
        ArcContainer container = Arc.container();
        if (container == null || !container.isRunning() || !container.requestContext().isActive()) {
            return null;
        }
        try {
            InboundTurnOrigin bound = inboundHolder(container);
            return combine(bound == null ? null : bound.origin(), fromHeaderValues(currentRequestHeader(container, HEADER)));
        } catch (RuntimeException e) {
            return EMAIL;
        }
    }

    /**
     * Binds the taint and approval an inbound request carries on its request
     * context ({@link InboundTurnOrigin}). Does nothing without an active request
     * context; an active one it cannot bind on is an error, so the caller can
     * refuse the request instead of serving it untainted.
     *
     * @return the bound taint
     */
    public static String bindInbound(List<String> originHeaderValues, List<String> approvedHeaderValues) {
        String origin = fromHeaderValues(originHeaderValues);
        ArcContainer container = Arc.container();
        if (origin == null || container == null || !container.isRunning() || !container.requestContext().isActive()) {
            return origin;
        }
        InboundTurnOrigin holder = inboundHolder(container);
        if (holder == null) {
            throw new IllegalStateException("The request's origin taint cannot be bound: no InboundTurnOrigin in the request context");
        }
        holder.bind(origin, approvedFromHeaderValues(approvedHeaderValues));
        return origin;
    }

    /**
     * As {@link #bindInbound(List, List)}, for the HTTP request the active request
     * context serves ({@link CurrentVertxRequest}) — the MCP HTTP transport, which
     * no JAX-RS filter sees, sets it for every tool call.
     */
    public static String bindInboundFromActiveRequest() {
        ArcContainer container = Arc.container();
        if (container == null || !container.isRunning() || !container.requestContext().isActive()) {
            return null;
        }
        return bindInbound(currentRequestHeader(container, HEADER), currentRequestHeader(container, APPROVED_HEADER));
    }

    /**
     * Whether the inbound request served on this thread is tainted and its sender
     * stated that a person approved the call ({@link #APPROVED_HEADER}). Read only
     * by the receiving side's checks, never inside a turn: there the approval is
     * the engine's own {@code ApprovedCallScope}.
     */
    public static boolean inboundApproved() {
        try {
            return approvedSource.get();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Whether any of the {@link #APPROVED_HEADER} values is {@code true} (ignoring
     * case and blanks).
     */
    public static boolean approvedFromHeaderValues(List<String> values) {
        if (values == null) {
            return false;
        }
        for (String value : values) {
            if (value != null && "true".equalsIgnoreCase(value.trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean approvedFromActiveRequest() {
        ArcContainer container = Arc.container();
        if (container == null || !container.isRunning() || !container.requestContext().isActive()) {
            return false;
        }
        InboundTurnOrigin bound = inboundHolder(container);
        return bound != null && bound.approved();
    }

    private static InboundTurnOrigin inboundHolder(ArcContainer container) {
        // Not closed: the bean is request-scoped, and closing a handle may destroy the
        // contextual instance the rest of the request still uses.
        InstanceHandle<InboundTurnOrigin> handle = container.instance(InboundTurnOrigin.class);
        return handle.isAvailable() ? handle.get() : null;
    }

    private static List<String> currentRequestHeader(ArcContainer container, String name) {
        InstanceHandle<CurrentVertxRequest> handle = container.instance(CurrentVertxRequest.class);
        if (!handle.isAvailable()) {
            return null;
        }
        RoutingContext routingContext = handle.get().getCurrent();
        return routingContext == null || routingContext.request() == null ? null : routingContext.request().headers().getAll(name);
    }

    /**
     * Test seam: {@link #fromRequest()} answers from {@code source} instead of the
     * active request until the returned handle is closed.
     */
    static AutoCloseable useRequestSource(Supplier<String> source) {
        Supplier<String> previous = requestSource;
        requestSource = source;
        return () -> requestSource = previous;
    }

    /**
     * Test seam: {@link #inboundApproved()} answers from {@code source} until the
     * returned handle is closed.
     */
    static AutoCloseable useApprovedSource(Supplier<Boolean> source) {
        Supplier<Boolean> previous = approvedSource;
        approvedSource = source;
        return () -> approvedSource = previous;
    }

    /**
     * Runs {@code work} with {@code origin} bound on this thread (nothing bound
     * when it is {@code null}), restoring the previous binding afterwards.
     */
    public static <T> T callWith(String origin, Callable<T> work) throws Exception {
        if (origin == null) {
            return work.call();
        }
        String previous = BOUND.get();
        BOUND.set(combine(previous, origin));
        try {
            return work.call();
        } finally {
            if (previous == null) {
                BOUND.remove();
            } else {
                BOUND.set(previous);
            }
        }
    }

    /** Whether the turn running on this thread is tainted by a mail. */
    public static boolean currentIsEmail() {
        return EMAIL.equals(current());
    }

    /**
     * The taint {@code principal} carries: its origin, or {@link #EMAIL} for an
     * email user id (a conversation stored before the taint was recorded).
     */
    public static String of(ResolutionPrincipal principal) {
        if (principal == null) {
            return null;
        }
        if (principal.origin() != null) {
            return principal.origin();
        }
        return EmailUserIdentity.isEmailIdentity(principal.userId()) ? EMAIL : null;
    }

    /** The taint a context map carries in {@link #CONTEXT_KEY}, or {@code null}. */
    public static String fromContext(Map<String, Context> context) {
        if (context == null) {
            return null;
        }
        Context value = context.get(CONTEXT_KEY);
        return value != null && value.getValue() != null ? normalise(String.valueOf(value.getValue())) : null;
    }

    /**
     * The taint a schedule's metadata carries in {@link #CONTEXT_KEY} (a check-back
     * a tainted turn booked), or {@code null}.
     */
    public static String fromMetadata(Map<String, Object> metadata) {
        Object value = metadata == null ? null : metadata.get(CONTEXT_KEY);
        return value == null ? null : normalise(String.valueOf(value));
    }

    /**
     * The taint a header or a stored value names, or {@code null} for anything that
     * is not a known origin.
     */
    public static String normalise(String value) {
        return value != null && EMAIL.equalsIgnoreCase(value.trim()) ? EMAIL : null;
    }

    /** The stronger of two taints: {@link #EMAIL} when either is. */
    public static String combine(String first, String second) {
        return EMAIL.equals(first) || EMAIL.equals(second) ? EMAIL : null;
    }

    /**
     * {@code metadata} (a schedule's) with {@link #CONTEXT_KEY} set to
     * {@code origin}, as a new mutable map; the argument itself when {@code origin}
     * is {@code null}, so a taint already there is never removed.
     */
    public static Map<String, Object> stampMetadata(Map<String, Object> metadata, String origin) {
        if (origin == null) {
            return metadata;
        }
        Map<String, Object> stamped = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
        stamped.put(CONTEXT_KEY, origin);
        return stamped;
    }

    /**
     * {@code context} with {@link #CONTEXT_KEY} set to {@code origin}, as a new
     * mutable map; the argument itself when {@code origin} is {@code null}.
     */
    public static Map<String, Context> withOrigin(Map<String, Context> context, String origin) {
        if (origin == null) {
            return context;
        }
        Map<String, Context> tainted = context == null ? new LinkedHashMap<>() : new LinkedHashMap<>(context);
        tainted.put(CONTEXT_KEY, new Context(Context.ContextType.string, origin));
        return tainted;
    }
}
