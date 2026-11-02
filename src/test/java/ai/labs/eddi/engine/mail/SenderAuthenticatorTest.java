/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import ai.labs.eddi.engine.mail.DkimResult.Outcome;
import ai.labs.eddi.engine.mail.DkimTestSigner.Options;
import ai.labs.eddi.engine.mail.MailAuthentication.Verdict;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Random;
import java.util.Set;

import static ai.labs.eddi.engine.mail.DkimTestSigner.CRLF;
import static ai.labs.eddi.engine.mail.DkimTestSigner.message;
import static ai.labs.eddi.engine.mail.DkimTestSigner.rsa2048;
import static ai.labs.eddi.engine.mail.DkimTestSigner.sign;
import static ai.labs.eddi.engine.mail.DkimTestSigner.standardMessage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SenderAuthenticatorTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private SenderAuthenticator authenticator(FakeDns dns) {
        MailDnsResolver resolver = dns.resolver(registry);
        return new SenderAuthenticator(new DkimVerifier(resolver, DkimVerifierTest.CLOCK), new DmarcAlignment(resolver), resolver,
                registry, Duration.ofDays(7), DkimVerifierTest.CLOCK);
    }

    private static FakeDns keys() {
        return new FakeDns().txt("sel._domainkey.example.com", rsa2048().record()).txt("sel._domainkey.example.co.uk", rsa2048().record())
                .txt("sel._domainkey.lists.example.org", rsa2048().record()).txt("sel._domainkey.co.uk", rsa2048().record());
    }

    private static InboundMail parse(byte[] raw) {
        try {
            return MailFixtures.parser().parse(raw, MailLimits.defaults(), true);
        } catch (MailParseException e) {
            throw new IllegalStateException(e);
        }
    }

    private MailAuthentication authenticate(FakeDns dns, byte[] raw) {
        return authenticator(dns).authenticate(raw, parse(raw));
    }

    private static byte[] from(String address, String body) {
        return message("From: " + address, "To: bob@example.org", "Subject: Hello", "Date: Fri, 23 Oct 2026 10:00:00 +0000",
                "Message-ID: <m1@example.com>", "", body);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private double counted(String result) {
        var counter = registry.find(SenderAuthenticator.METER_AUTH).tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    /**
     * A parsed message as some other parser might have made it, to test each guard
     * on its own.
     */
    static InboundMail synthetic(long rawSize, MailAddress from, Set<MailAnomaly> anomalies) {
        return new InboundMail("<x@example.com>", false, null, null, from, null, null, null, null, null, null, null, false, false, null, null,
                null, null, anomalies, rawSize);
    }

    @Nested
    @DisplayName("verdicts")
    class Verdicts {

        @Test
        @DisplayName("a signature of the From domain is an aligned pass, and is counted")
        void alignedPass() {
            MailAuthentication auth = authenticate(keys(), sign(standardMessage(), new Options()));

            assertEquals(Verdict.ALIGNED_PASS, auth.verdict(), auth.reason());
            assertTrue(auth.isAuthenticated());
            assertEquals("example.com", auth.signingDomain());
            assertEquals(1, auth.signatures().size());
            assertFalse(auth.temporary());
            assertEquals(1, counted("aligned_pass"));
        }

        @Test
        @DisplayName("the RFC 8463 vector is an aligned pass for football.example.com")
        void rfc8463() {
            byte[] raw = MailFixtures.bytes("dkim/rfc8463.eml");
            MailAuthentication auth = authenticate(new FakeDns().load("/mail/dkim/rfc8463.dns"), raw);

            assertEquals(Verdict.ALIGNED_PASS, auth.verdict(), auth.reason());
            assertEquals("football.example.com", auth.signingDomain());
            assertEquals(2, auth.signatures().size());
        }

        @Test
        @DisplayName("a list signing as itself is a pass, but unaligned")
        void unaligned() {
            byte[] raw = sign(from("alice@example.com", "via the list"), new Options().domain("lists.example.org"));
            MailAuthentication auth = authenticate(keys(), raw);

            assertEquals(Verdict.PASS_UNALIGNED, auth.verdict());
            assertEquals("lists.example.org", auth.signingDomain());
            assertFalse(auth.isAuthenticated());
            assertEquals(1, counted("pass_unaligned"));
        }

        @Test
        @DisplayName("an unsigned message is none; a tampered one is fail")
        void noneAndFail() {
            assertEquals(Verdict.NONE, authenticate(keys(), standardMessage()).verdict());
            byte[] tampered = bytes(text(sign(standardMessage(), new Options())).replace("numbers", "invoices"));
            MailAuthentication fail = authenticate(keys(), tampered);
            assertEquals(Verdict.FAIL, fail.verdict());
            assertTrue(fail.reason().contains("body hash"), fail.reason());
            assertEquals(1, counted("none"));
            assertEquals(1, counted("fail"));
        }

        @Test
        @DisplayName("one failing and one passing aligned signature: aligned pass")
        void mixedSignatures() {
            byte[] good = sign(standardMessage(), new Options());
            byte[] both = bytes("DKIM-Signature: v=1; a=rsa-sha256; d=example.com; s=gone; h=from; bh=AAAA; b=AAAA" + CRLF + text(good));
            MailAuthentication auth = authenticate(keys(), both);

            assertEquals(Verdict.ALIGNED_PASS, auth.verdict());
            assertEquals(Outcome.FAIL, auth.signatures().get(0).outcome());
            assertEquals(Outcome.PASS, auth.signatures().get(1).outcome());
        }

        @Test
        @DisplayName("only testing-mode signatures: none (RFC 6376 treats them as unsigned)")
        void testingOnly() {
            FakeDns dns = new FakeDns().txt("sel._domainkey.example.com", rsa2048().record() + "; t=y");
            assertEquals(Verdict.NONE, authenticate(dns, sign(standardMessage(), new Options())).verdict());
        }

        @Test
        @DisplayName("a key lookup that times out is a temporary fail")
        void temporary() {
            MailAuthentication auth = authenticate(new FakeDns().timeout("sel._domainkey.example.com"), sign(standardMessage(), new Options()));
            assertEquals(Verdict.FAIL, auth.verdict());
            assertTrue(auth.temporary());
        }
    }

    @Nested
    @DisplayName("DMARC alignment through the facade")
    class Alignment {

        @Test
        @DisplayName("a subdomain From aligns with its organisational domain's signature under relaxed")
        void subdomainRelaxed() {
            byte[] raw = sign(from("alice@mail.example.co.uk", "hi"), new Options().domain("example.co.uk"));
            MailAuthentication auth = authenticate(keys(), raw);
            assertEquals(Verdict.ALIGNED_PASS, auth.verdict(), auth.reason());
            assertEquals("example.co.uk", auth.signingDomain());
        }

        @Test
        @DisplayName("strict alignment on the From domain's record refuses the same message")
        void subdomainStrict() {
            FakeDns dns = keys().txt("_dmarc.mail.example.co.uk", "v=DMARC1; p=reject; adkim=s");
            byte[] raw = sign(from("alice@mail.example.co.uk", "hi"), new Options().domain("example.co.uk"));
            assertEquals(Verdict.PASS_UNALIGNED, authenticate(dns, raw).verdict());
        }

        @Test
        @DisplayName("strict alignment inherited from the organisational domain's record refuses it too")
        void orgFallbackStrict() {
            FakeDns dns = keys().txt("_dmarc.example.co.uk", "v=DMARC1; p=reject; adkim=s");
            byte[] raw = sign(from("alice@mail.example.co.uk", "hi"), new Options().domain("example.co.uk"));
            assertEquals(Verdict.PASS_UNALIGNED, authenticate(dns, raw).verdict());
        }

        @Test
        @DisplayName("a signature by a public suffix (d=co.uk) never aligns")
        void publicSuffixSigner() {
            byte[] raw = sign(from("alice@example.co.uk", "hi"), new Options().domain("co.uk"));
            assertEquals(Verdict.PASS_UNALIGNED, authenticate(keys(), raw).verdict());
        }

        @Test
        @DisplayName("a DMARC lookup failure: exact-domain signatures still align, subdomain ones are unaligned and temporary")
        void dmarcTemporary() {
            FakeDns exact = keys().timeout("_dmarc.example.com");
            FakeDns sub = keys().timeout("_dmarc.mail.example.com");
            MailAuthentication same = authenticate(exact, sign(standardMessage(), new Options()));
            MailAuthentication child = authenticate(sub, sign(from("alice@mail.example.com", "hi"), new Options()));

            assertEquals(Verdict.ALIGNED_PASS, same.verdict());
            assertEquals(Verdict.PASS_UNALIGNED, child.verdict());
            assertTrue(child.temporary());
        }
    }

    @Nested
    @DisplayName("header injection")
    class Injection {

        @Test
        @DisplayName("an unsigned second From on top of a validly signed message is never an aligned pass")
        void secondFrom() {
            byte[] signed = sign(standardMessage(), new Options());
            byte[] forged = bytes("From: CEO <ceo@example.com>" + CRLF + text(signed));
            MailAuthentication auth = authenticate(keys(), forged);

            assertEquals(Outcome.PASS, auth.signatures().get(0).outcome(), "the signature itself still verifies");
            assertEquals(Verdict.FAIL, auth.verdict());
            assertTrue(auth.reason().contains("From"), auth.reason());
        }

        @Test
        @DisplayName("one From with two mailboxes is never an aligned pass")
        void twoMailboxes() {
            byte[] raw = sign(from("alice@example.com, ceo@example.com", "hi"), new Options());
            InboundMail parsed = parse(raw);
            assertTrue(parsed.anomalies().contains(MailAnomaly.MULTIPLE_FROM));
            assertEquals(Verdict.FAIL, authenticator(keys()).authenticate(raw, parsed).verdict());
        }

        @Test
        @DisplayName("a message without From whose signature covers its absence is never an aligned pass")
        void noFrom() {
            byte[] raw = sign(message("To: bob@example.org", "Subject: anonymous", "", "hi"), new Options().headers("from", "subject"));
            MailAuthentication auth = authenticate(keys(), raw);
            assertEquals(Outcome.PASS, auth.signatures().get(0).outcome());
            assertEquals(Verdict.FAIL, auth.verdict());
        }

        @Test
        @DisplayName("a second, unsigned Subject on a validly signed message is never an aligned pass")
        void duplicatedSubject() {
            byte[] signed = sign(standardMessage(), new Options());
            byte[] forged = bytes("Subject: URGENT: new bank details" + CRLF + text(signed));
            MailAuthentication auth = authenticate(keys(), forged);

            assertEquals(Outcome.PASS, auth.signatures().get(0).outcome());
            assertEquals(Verdict.FAIL, auth.verdict());
            assertTrue(auth.reason().contains("more than once"), auth.reason());
        }

        @Test
        @DisplayName("the raw From count refuses a second From even when the parser reported a single sender")
        void rawCountGuard() {
            byte[] forged = bytes("From: CEO <ceo@example.com>" + CRLF + text(sign(standardMessage(), new Options())));
            InboundMail lenient = synthetic(forged.length, new MailAddress("alice@example.com", null), EnumSet.noneOf(MailAnomaly.class));

            MailAuthentication auth = authenticator(keys()).authenticate(forged, lenient);

            assertEquals(Verdict.FAIL, auth.verdict());
            assertTrue(auth.reason().contains("From"), auth.reason());
        }

        @Test
        @DisplayName("a parser's MULTIPLE_FROM anomaly refuses the message even when the raw bytes hold one From field")
        void anomalyGuard() {
            byte[] signed = sign(standardMessage(), new Options());
            InboundMail flagged = synthetic(signed.length, new MailAddress("alice@example.com", null), EnumSet.of(MailAnomaly.MULTIPLE_FROM));

            assertEquals(Verdict.FAIL, authenticator(keys()).authenticate(signed, flagged).verdict());
            InboundMail clean = synthetic(signed.length, new MailAddress("alice@example.com", null), EnumSet.noneOf(MailAnomaly.class));
            assertEquals(Verdict.ALIGNED_PASS, authenticator(keys()).authenticate(signed, clean).verdict());
        }

        @Test
        @DisplayName("raw bytes that are not the parsed message are refused")
        void mismatchedBytes() {
            byte[] signed = sign(standardMessage(), new Options());
            InboundMail other = parse(standardMessage());
            assertEquals(Verdict.FAIL, authenticator(keys()).authenticate(signed, other).verdict());
        }
    }

    @Nested
    @DisplayName("judged as of delivery")
    class Delivery {

        private final long now = DkimVerifierTest.NOW.getEpochSecond();

        private byte[] signed(long t, long x) {
            return sign(standardMessage(), new Options().extraTags("t=" + t + "; x=" + x));
        }

        private MailAuthentication at(byte[] raw, Instant deliveredAt) {
            return authenticator(keys()).authenticate(raw, parse(raw), deliveredAt, null);
        }

        @Test
        @DisplayName("a short x= that has expired since delivery still passes when judged at the delivery instant")
        void expiredSinceDelivery() {
            byte[] raw = signed(now - 2 * 86_400, now - 3600);

            assertEquals(Verdict.FAIL, authenticator(keys()).authenticate(raw, parse(raw)).verdict(), "without a delivery instant");
            assertEquals(Verdict.FAIL, at(raw, null).verdict(), "a null delivery instant means now");
            assertEquals(Verdict.ALIGNED_PASS, at(raw, DkimVerifierTest.NOW.minus(Duration.ofDays(1))).verdict());
        }

        @Test
        @DisplayName("a delivery instant older than max-delivery-age is clamped to it: an ancient signature stays expired")
        void tooOldIsClamped() {
            // Valid until 8 days ago; claimed delivered 10 days ago; judged at 7 days ago.
            byte[] raw = signed(now - 11 * 86_400, now - 8 * 86_400);

            MailAuthentication auth = at(raw, DkimVerifierTest.NOW.minus(Duration.ofDays(10)));

            assertEquals(Verdict.FAIL, auth.verdict());
            assertTrue(auth.reason().contains("expired"), auth.reason());
            // Inside the window, the same kind of signature passes.
            byte[] recent = signed(now - 6 * 86_400, now - 5 * 86_400);
            assertEquals(Verdict.ALIGNED_PASS, at(recent, DkimVerifierTest.NOW.minus(Duration.ofDays(6))).verdict());
        }

        @Test
        @DisplayName("a delivery instant in the future is clamped to now: a postdated signature stays in the future")
        void futureIsClamped() {
            byte[] raw = signed(now + 86_400, now + 10 * 86_400);

            MailAuthentication auth = at(raw, DkimVerifierTest.NOW.plus(Duration.ofDays(2)));

            assertEquals(Verdict.FAIL, auth.verdict());
            assertTrue(auth.reason().contains("future"), auth.reason());
        }

        @Test
        @DisplayName("the effective instant is the delivery instant bounded to [now - max age, now]")
        void effectiveInstant() {
            SenderAuthenticator authenticator = authenticator(keys());
            Instant n = DkimVerifierTest.NOW;
            assertEquals(n, authenticator.effectiveInstant(null));
            assertEquals(n, authenticator.effectiveInstant(n.plusSeconds(1)));
            assertEquals(n.minusSeconds(3600), authenticator.effectiveInstant(n.minusSeconds(3600)));
            assertEquals(n.minus(Duration.ofDays(7)), authenticator.effectiveInstant(n.minus(Duration.ofDays(30))));
            assertEquals(n.minus(Duration.ofDays(7)), authenticator.effectiveInstant(n.minus(Duration.ofDays(7))));
        }
    }

    @Nested
    @DisplayName("provider fallback")
    class Fallback {

        private record Provider(boolean dkimPass, String signingDomain) implements SignedProviderVerdict {
            @Override
            public String provider() {
                return "ses";
            }
        }

        @Test
        @DisplayName("without raw bytes, a signed provider pass for the From domain is an aligned pass")
        void noRawBytes() {
            InboundMail parsed = parse(standardMessage());
            MailAuthentication auth = authenticator(keys()).authenticate(null, parsed, new Provider(true, "example.com"));
            assertEquals(Verdict.ALIGNED_PASS, auth.verdict());
            assertTrue(auth.reason().contains("ses"));
        }

        @Test
        @DisplayName("without raw bytes and without a provider verdict: fail")
        void noRawNoProvider() {
            assertEquals(Verdict.FAIL, authenticator(keys()).authenticate(null, parse(standardMessage())).verdict());
        }

        @Test
        @DisplayName("when every key lookup failed temporarily, the provider verdict is used")
        void temporaryLocal() {
            byte[] raw = sign(standardMessage(), new Options());
            FakeDns dns = new FakeDns().timeout("sel._domainkey.example.com");
            MailAuthentication auth = authenticator(dns).authenticate(raw, parse(raw), new Provider(true, "example.com"));
            assertEquals(Verdict.ALIGNED_PASS, auth.verdict());
        }

        @Test
        @DisplayName("a provider pass never overrides a local failure or a local none")
        void neverOverridesLocal() {
            byte[] tampered = bytes(text(sign(standardMessage(), new Options())).replace("numbers", "invoices"));
            Provider pass = new Provider(true, "example.com");
            assertEquals(Verdict.FAIL, authenticator(keys()).authenticate(tampered, parse(tampered), pass).verdict());
            assertEquals(Verdict.NONE, authenticator(keys()).authenticate(standardMessage(), parse(standardMessage()), pass).verdict());
        }

        @Test
        @DisplayName("a provider pass for another domain is unaligned; a provider fail is fail")
        void providerDetails() {
            InboundMail parsed = parse(standardMessage());
            assertEquals(Verdict.PASS_UNALIGNED,
                    authenticator(keys()).authenticate(null, parsed, new Provider(true, "lists.example.org")).verdict());
            assertEquals(Verdict.FAIL, authenticator(keys()).authenticate(null, parsed, new Provider(false, "example.com")).verdict());
            assertEquals(Verdict.FAIL, authenticator(keys()).authenticate(null, parsed, new Provider(true, "not a domain")).verdict());
        }
    }

    @Nested
    @DisplayName("hostile input")
    class Hostile {

        @Test
        @DisplayName("random bytes never throw and never pass")
        void randomBytes() {
            Random random = new Random(42);
            SenderAuthenticator authenticator = authenticator(keys());
            for (int i = 0; i < 300; i++) {
                byte[] raw = new byte[random.nextInt(2000) + 1];
                random.nextBytes(raw);
                if (i % 3 == 0) {
                    raw = bytes("DKIM-Signature: " + text(raw).replace("\r", "").replace("\n", "") + CRLF + "From: a@example.com" + CRLF
                            + CRLF + "x");
                }
                MailAuthentication auth = authenticator.authenticate(raw,
                        synthetic(raw.length, new MailAddress("a@example.com", null), EnumSet.noneOf(MailAnomaly.class)));
                assertNotEquals(Verdict.ALIGNED_PASS, auth.verdict());
                assertNotEquals(Verdict.PASS_UNALIGNED, auth.verdict());
            }
            assertNull(authenticator.authenticate(new byte[0], null).signingDomain());
        }

        @Test
        @DisplayName("thousands of header lines, fifty signatures and a large body finish within the time budget")
        void performance() {
            StringBuilder headers = new StringBuilder();
            for (int i = 0; i < 5000; i++) {
                headers.append("X-Filler-").append(i % 50).append(": value ").append(i).append(CRLF);
            }
            String body = ("All work and no play makes Jack a dull boy.   \t " + CRLF).repeat(200_000);
            byte[] raw = bytes(text(from("alice@example.com", "")).replace(CRLF + CRLF, CRLF + headers + CRLF) + body);
            // Fifty valid signatures over a hundred header names each (one signature,
            // repeated: none covers DKIM-Signature, so each copy verifies). The cap
            // stops at five.
            String names = String.join(":", Collections.nCopies(99, "x-filler-1")) + ":from";
            String signedText = text(sign(raw, new Options().headers(names.split(":"))));
            String signature = signedText.substring(0, signedText.length() - raw.length);
            byte[] message = bytes(signature.repeat(50) + text(raw));
            InboundMail parsed = parse(message);
            FakeDns dns = keys();
            SenderAuthenticator authenticator = authenticator(dns);

            MailAuthentication auth = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> authenticator.authenticate(message, parsed));

            assertEquals(Verdict.ALIGNED_PASS, auth.verdict(), auth.reason());
            assertEquals(DkimVerifier.MAX_SIGNATURES, auth.signatures().size());
            assertTrue(dns.queries.get() <= 10, "queries: " + dns.queries.get());
        }

        @Test
        @DisplayName("fifty signatures with distinct selectors cannot spend more than the lookup budget")
        void budgetAcrossSignatures() {
            byte[] signed = standardMessage();
            for (int i = 0; i < 50; i++) {
                signed = sign(signed, new Options().selector("s" + i));
            }
            FakeDns dns = keys();
            authenticate(dns, signed);
            assertTrue(dns.queries.get() <= 10, "queries: " + dns.queries.get());
        }
    }
}
