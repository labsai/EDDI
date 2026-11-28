/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.outbound;

import ai.labs.eddi.engine.httpclient.outbound.OutboundEngine.PeerCheck;
import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;

import java.net.InetAddress;

/**
 * The rules for the address a connection actually reached — the one fact a
 * DNS-rebinding name cannot race, because it is read after the connection is
 * open and before the request is written.
 * <p>
 * They are the address-level half of {@link SsrfPolicy}:
 * {@link SsrfPolicy#STRICT} refuses a private, loopback, link-local or
 * cloud-metadata peer; {@link SsrfPolicy#METADATA_ONLY} refuses a
 * cloud-metadata or link-local one. Kept here, once, so the facade and the
 * httpcalls client ({@code HttpClientModule}, until it moves onto the facade)
 * read an address the same way.
 */
public final class ConnectedPeers {

    private ConnectedPeers() {
    }

    /** Refuses a private, loopback, link-local or cloud-metadata peer. */
    static final PeerCheck STRICT = peer -> {
        requireReadable(peer);
        if (UrlValidationUtils.isMetadataAddress(peer)) {
            throw metadataRefusal(peer);
        }
        if (UrlValidationUtils.isPrivateAddress(peer)) {
            throw new OutboundBlockedException("Request refused: the connection reached " + peer.getHostAddress()
                    + ", a private/internal address (the name resolved differently when it was validated)");
        }
    };

    /** Refuses a cloud-metadata or link-local peer. */
    static final PeerCheck METADATA_ONLY = peer -> {
        if (peer != null && UrlValidationUtils.isMetadataAddress(peer)) {
            throw metadataRefusal(peer);
        }
    };

    /**
     * The peer check matching a hop's SSRF policy, or {@code null} for none.
     * {@link SsrfPolicy#HOPS_ONLY} trusts the initial URL, so only its redirect
     * hops are held to the strict rule.
     *
     * @param redirectHop
     *            whether the hop follows a redirect
     */
    static PeerCheck forPolicy(SsrfPolicy policy, boolean redirectHop) {
        return switch (policy) {
            case STRICT -> STRICT;
            case HOPS_ONLY -> redirectHop ? STRICT : null;
            case METADATA_ONLY -> METADATA_ONLY;
            case NONE, FOLLOW_GLOBAL_FLAG -> null;
        };
    }

    /**
     * Parses a connection's remote address — an IP literal, possibly in brackets,
     * possibly with an IPv6 zone ({@code fe80::1%eth0}, the zone says nothing about
     * where the address sits). Never resolves a name.
     *
     * @return the address, or {@code null} when {@code literal} is blank or not an
     *         IP literal
     */
    public static InetAddress parse(String literal) {
        if (literal == null || literal.isBlank()) {
            return null;
        }
        String bare = literal.startsWith("[") && literal.endsWith("]") ? literal.substring(1, literal.length() - 1) : literal;
        int zone = bare.indexOf('%');
        if (zone > 0) {
            bare = bare.substring(0, zone);
        }
        try {
            return InetAddress.ofLiteral(bare);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void requireReadable(InetAddress peer) {
        if (peer == null) {
            throw new OutboundBlockedException(
                    "Request refused: the address the connection reached cannot be read, so it cannot be checked");
        }
    }

    private static OutboundBlockedException metadataRefusal(InetAddress peer) {
        return new OutboundBlockedException("Request refused: the connection reached " + peer.getHostAddress()
                + ", a cloud instance-metadata or link-local address");
    }
}
