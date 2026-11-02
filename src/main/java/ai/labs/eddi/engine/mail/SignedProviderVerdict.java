/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

/**
 * A mail provider's own DKIM verdict, delivered to EDDI under a signature EDDI
 * has checked — the fallback of email plan §8.
 * <p>
 * Implement this only where the verdict itself is covered by a signature the
 * transport verified (Amazon SES over a verified SNS message is the example).
 * Mailgun's webhook signature covers a timestamp and a token, not the payload,
 * and Exchange Online's {@code Authentication-Results} has no authserv-id, so
 * neither may implement it (plan §2 #1). No provider is implemented yet.
 * <p>
 * {@link SenderAuthenticator} consults it only when its own check could not
 * run: the raw bytes were not available, or every signature's key lookup failed
 * temporarily. It never overrides a local failure, a local "no signature", or a
 * local pass, and the DMARC alignment of the provider's signing domain is still
 * checked by EDDI against the {@code From} it parsed.
 */
public interface SignedProviderVerdict {

    /** A short, fixed name of the provider ({@code "ses"}), used in the reason. */
    String provider();

    /** Whether the provider reports a passing DKIM signature. */
    boolean dkimPass();

    /** The {@code d=} of that passing signature, as the provider reports it. */
    String signingDomain();
}
