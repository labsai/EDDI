/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.outbound;

/**
 * Whether redirects are followed, and if so how many and with which method
 * rule. Redirects are always followed by {@link OutboundHttp} itself, hop by
 * hop, never by an engine.
 */
public sealed interface RedirectPolicy permits RedirectPolicy.Never, RedirectPolicy.Follow {

    /** Every 3xx is returned to the caller as the response, Location and all. */
    RedirectPolicy NEVER = new Never();

    static RedirectPolicy follow(int maxHops, MethodRule rule) {
        return new Follow(maxHops, rule);
    }

    record Never() implements RedirectPolicy {
    }

    record Follow(int maxHops, MethodRule rule) implements RedirectPolicy {
        public Follow {
            if (maxHops < 1) {
                throw new IllegalArgumentException("maxHops must be at least 1, was " + maxHops);
            }
            if (rule == null) {
                throw new IllegalArgumentException("rule must not be null");
            }
        }
    }
}
