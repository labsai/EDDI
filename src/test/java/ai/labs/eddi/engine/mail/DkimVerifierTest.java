/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import ai.labs.eddi.engine.mail.DkimResult.Outcome;
import ai.labs.eddi.engine.mail.DkimTestSigner.Options;
import ai.labs.eddi.engine.mail.DkimTestSigner.TestKey;
import ai.labs.eddi.engine.mail.DkimVerifier.Verification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import static ai.labs.eddi.engine.mail.DkimTestSigner.CRLF;
import static ai.labs.eddi.engine.mail.DkimTestSigner.ed25519;
import static ai.labs.eddi.engine.mail.DkimTestSigner.message;
import static ai.labs.eddi.engine.mail.DkimTestSigner.rsa2048;
import static ai.labs.eddi.engine.mail.DkimTestSigner.sign;
import static ai.labs.eddi.engine.mail.DkimTestSigner.standardMessage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DkimVerifierTest {

    static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** One signature, signed with the default RSA key under example.com/sel. */
    private static DkimResult verifyOne(byte[] signed, FakeDns dns) {
        Verification verification = new DkimVerifier(dns.resolver(), CLOCK).verify(signed, dns.resolver().newBudget());
        assertNull(verification.messageFailure(), verification.messageFailure());
        assertEquals(1, verification.results().size(), verification.results().toString());
        return verification.results().get(0);
    }

    private static FakeDns dnsFor(TestKey key) {
        return new FakeDns().txt("sel._domainkey.example.com", key.record());
    }

    private static void assertFail(DkimResult result, String reasonFragment) {
        assertEquals(Outcome.FAIL, result.outcome(), result.reason());
        assertTrue(result.reason().contains(reasonFragment), "reason was: " + result.reason());
    }

    @Nested
    @DisplayName("RFC 8463 Appendix A: the published Ed25519 + RSA example")
    class Rfc8463 {

        private Verification verify(byte[] raw) {
            FakeDns dns = new FakeDns().load("/mail/dkim/rfc8463.dns");
            MailDnsResolver resolver = dns.resolver();
            return new DkimVerifier(resolver, CLOCK).verify(raw, resolver.newBudget());
        }

        @Test
        @DisplayName("both signatures of the vector verify, byte for byte as published")
        void bothVerify() {
            Verification verification = verify(MailFixtures.bytes("dkim/rfc8463.eml"));

            assertEquals(2, verification.signaturesFound());
            assertEquals(1, verification.fromFieldCount());
            DkimResult ed = verification.results().get(0);
            DkimResult rsa = verification.results().get(1);
            assertEquals(Outcome.PASS, ed.outcome(), ed.reason());
            assertEquals("ed25519-sha256", ed.algorithm());
            assertEquals("brisbane", ed.selector());
            assertEquals("football.example.com", ed.domain());
            assertEquals(Outcome.PASS, rsa.outcome(), rsa.reason());
            assertEquals("test", rsa.selector());
        }

        @Test
        @DisplayName("the same message with bare LF line endings (as stored on Unix) still verifies")
        void lfLineEndings() {
            byte[] lf = bytes(text(MailFixtures.bytes("dkim/rfc8463.eml")).replace(CRLF, "\n"));

            Verification verification = verify(lf);

            assertEquals(Outcome.PASS, verification.results().get(0).outcome());
            assertEquals(Outcome.PASS, verification.results().get(1).outcome());
        }

        @Test
        @DisplayName("one changed body character breaks both body hashes")
        void bodyTampered() {
            byte[] tampered = bytes(text(MailFixtures.bytes("dkim/rfc8463.eml")).replace("hungry", "Hungry"));

            Verification verification = verify(tampered);

            assertFail(verification.results().get(0), "body hash");
            assertFail(verification.results().get(1), "body hash");
        }

        @Test
        @DisplayName("a changed signed header breaks both signatures")
        void headerTampered() {
            byte[] tampered = bytes(text(MailFixtures.bytes("dkim/rfc8463.eml")).replace("Is dinner ready?", "Is lunch ready?"));

            Verification verification = verify(tampered);

            assertFail(verification.results().get(0), "does not verify");
            assertFail(verification.results().get(1), "does not verify");
        }

        @Test
        @DisplayName("over-signing: a second From added on top breaks both (h= lists from twice)")
        void overSignedFrom() {
            byte[] forged = bytes(text(MailFixtures.bytes("dkim/rfc8463.eml")).replace("From: Joe SixPack",
                    "From: Mallory <mallory@football.example.com>" + CRLF + "From: Joe SixPack"));

            Verification verification = verify(forged);

            assertEquals(2, verification.fromFieldCount());
            assertFail(verification.results().get(0), "does not verify");
            assertFail(verification.results().get(1), "does not verify");
        }

        @Test
        @DisplayName("relaxed header canonicalisation absorbs re-folding and extra spaces in transit")
        void relaxedSurvivesRefolding() {
            byte[] refolded = bytes(text(MailFixtures.bytes("dkim/rfc8463.eml")).replace("Subject: Is dinner ready?",
                    "Subject:   Is dinner" + CRLF + "\t ready?   "));

            Verification verification = verify(refolded);

            assertEquals(Outcome.PASS, verification.results().get(0).outcome());
            assertEquals(Outcome.PASS, verification.results().get(1).outcome());
        }
    }

    @Nested
    @DisplayName("RFC 6376 §3.4.6: the canonicalisation example")
    class CanonicalisationExample {

        // The example's header fields and body, with a From added (a signature must
        // cover From).
        private static final String HEADERS = "From: a@example.com" + CRLF + "A: X" + CRLF + "B : Y\t" + CRLF + "\tZ  " + CRLF;
        private static final String BODY = " C " + CRLF + "D \t E" + CRLF + CRLF + CRLF;

        /**
         * Signs with the canonical forms the RFC prints (not computed by any code
         * here), so the verifier passes only if it canonicalises exactly like the RFC.
         */
        private DkimResult signAndVerify(String c, String canonicalHeaders, String canonicalBody) {
            TestKey key = rsa2048();
            String bh = Base64.getEncoder().encodeToString(DkimTestSigner.sha256(bytes(canonicalBody)));
            String sigValue = " v=1; a=rsa-sha256; c=" + c + "; d=example.com; s=sel; h=from:a:b; bh=" + bh + "; b=";
            String canonicalSig = c.startsWith("relaxed") ? "dkim-signature:" + sigValue.strip() : "DKIM-Signature:" + sigValue;
            byte[] b = DkimTestSigner.signRaw(key, bytes(canonicalHeaders + canonicalSig));
            String raw = "DKIM-Signature:" + sigValue + Base64.getEncoder().encodeToString(b) + CRLF + HEADERS + CRLF + BODY;
            return verifyOne(bytes(raw), dnsFor(key));
        }

        @Test
        @DisplayName("relaxed/relaxed matches the RFC's canonical output")
        void relaxed() {
            DkimResult result = signAndVerify("relaxed/relaxed", "from:a@example.com" + CRLF + "a:X" + CRLF + "b:Y Z" + CRLF,
                    " C" + CRLF + "D E" + CRLF);
            assertEquals(Outcome.PASS, result.outcome(), result.reason());
        }

        @Test
        @DisplayName("simple/simple matches the RFC's canonical output")
        void simple() {
            DkimResult result = signAndVerify("simple/simple", HEADERS, " C " + CRLF + "D \t E" + CRLF);
            assertEquals(Outcome.PASS, result.outcome(), result.reason());
        }

        @Test
        @DisplayName("a canonical form that differs from the RFC's by one space fails (the vectors are not vacuous)")
        void offByOneSpace() {
            DkimResult result = signAndVerify("relaxed/relaxed", "from:a@example.com" + CRLF + "a:X" + CRLF + "b:Y  Z" + CRLF,
                    " C" + CRLF + "D E" + CRLF);
            assertFail(result, "does not verify");
        }
    }

    static Stream<Arguments> keysAndCanonicalisations() {
        return Stream.of("rsa", "ed25519").flatMap(k -> Stream.of("simple/simple", "simple/relaxed", "relaxed/simple", "relaxed/relaxed")
                .map(c -> Arguments.of(k, c)));
    }

    private static TestKey key(String name) {
        return "rsa".equals(name) ? rsa2048() : ed25519();
    }

    @Nested
    @DisplayName("messages signed in the test")
    class SelfSigned {

        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("ai.labs.eddi.engine.mail.DkimVerifierTest#keysAndCanonicalisations")
        @DisplayName("every key type with every canonicalisation verifies")
        void allCombinations(String keyName, String c) {
            TestKey key = key(keyName);
            byte[] signed = sign(standardMessage(), new Options().key(key).canonicalisation(c));

            DkimResult result = verifyOne(signed, dnsFor(key));

            assertEquals(Outcome.PASS, result.outcome(), result.reason());
            assertEquals("example.com", result.domain());
        }

        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("ai.labs.eddi.engine.mail.DkimVerifierTest#keysAndCanonicalisations")
        @DisplayName("folded headers, trailing whitespace, 8-bit text, an empty body and no final CRLF")
        void awkwardShapes(String keyName, String c) {
            TestKey key = key(keyName);
            byte[] folded = message("From: =?UTF-8?Q?J=C3=BCrgen?= <juergen@example.com>", "To: bob@example.org",
                    "Subject: A very long subject that a client folds", "\tonto a second line   ", "\t and a third",
                    "Date: Fri, 23 Oct 2026 10:00:00 +0000", "Message-ID: <fold@example.com>", "", "Grüße  aus   Köln \t ", "",
                    "  indented", "", "", "");
            byte[] eightBitHeader = bytes(text(message("From: Jürgen <juergen@example.com>", "Subject: Äpfel", "", "x"))
                    .replace("x\r\n", "x"));
            byte[] emptyBody = message("From: alice@example.com", "Subject: nothing", "");
            byte[] headerOnly = bytes("From: alice@example.com" + CRLF + "Subject: no blank line");
            for (byte[] raw : List.of(folded, eightBitHeader, emptyBody, headerOnly)) {
                byte[] signed = sign(raw, new Options().key(key).canonicalisation(c).headers("from", "subject"));
                DkimResult result = verifyOne(signed, dnsFor(key));
                assertEquals(Outcome.PASS, result.outcome(), result.reason() + " for " + text(raw));
            }
        }

        @Test
        @DisplayName("trailing empty lines added after signing are ignored by both body canonicalisations")
        void trailingEmptyLinesIgnored() {
            for (String c : List.of("simple/simple", "relaxed/relaxed")) {
                byte[] signed = sign(standardMessage(), new Options().canonicalisation(c));
                byte[] padded = bytes(text(signed) + CRLF + CRLF + CRLF);
                assertEquals(Outcome.PASS, verifyOne(padded, dnsFor(rsa2048())).outcome(), c);
            }
        }

        @Test
        @DisplayName("relaxed: lines holding only whitespace at the end of the body count as empty lines")
        void trailingWhitespaceOnlyLines() {
            byte[] signed = sign(standardMessage(), new Options().canonicalisation("relaxed/relaxed"));
            byte[] padded = bytes(text(signed) + "   " + CRLF + "\t" + CRLF + " \t " + CRLF);

            assertEquals(Outcome.PASS, verifyOne(padded, dnsFor(rsa2048())).outcome());
        }

        @Test
        @DisplayName("whitespace changed in transit: relaxed still verifies, simple does not")
        void whitespaceInTransit() {
            byte[] relaxed = sign(standardMessage(), new Options().canonicalisation("relaxed/relaxed"));
            byte[] simple = sign(standardMessage(), new Options().canonicalisation("simple/simple"));
            String change = "the numbers are in.";
            String changed = "the  numbers are in. \t";

            assertEquals(Outcome.PASS, verifyOne(bytes(text(relaxed).replace(change, changed)), dnsFor(rsa2048())).outcome());
            assertFail(verifyOne(bytes(text(simple).replace(change, changed)), dnsFor(rsa2048())), "body hash");
        }

        @Test
        @DisplayName("a bare PKCS#1 RSAPublicKey in p= is accepted as well as SubjectPublicKeyInfo")
        void pkcs1Key() {
            TestKey key = rsa2048();
            byte[] spki = key.pair().getPublic().getEncoded();
            // The SPKI of an RSA key ends with the BIT STRING that holds the PKCS#1
            // structure: 24 bytes of header for a 2048-bit key.
            byte[] pkcs1 = Arrays.copyOfRange(spki, 24, spki.length);
            FakeDns dns = new FakeDns().txt("sel._domainkey.example.com", "v=DKIM1; p=" + Base64.getEncoder().encodeToString(pkcs1));

            DkimResult result = verifyOne(sign(standardMessage(), new Options()), dns);

            assertEquals(Outcome.PASS, result.outcome(), result.reason());
        }

    }

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("l= is refused even when the signature is otherwise valid (body-extension attack)")
        void bodyLengthTag() {
            byte[] signed = sign(standardMessage(), new Options().extraTags("l=10"));
            assertFail(verifyOne(signed, dnsFor(rsa2048())), "l=");
        }

        @Test
        @DisplayName("rsa-sha1 is refused (RFC 8301)")
        void rsaSha1() {
            byte[] signed = sign(standardMessage(), new Options().algorithmTag("rsa-sha1"));
            assertFail(verifyOne(signed, dnsFor(rsa2048())), "rsa-sha1");
        }

        @Test
        @DisplayName("an unknown algorithm is refused")
        void unknownAlgorithm() {
            byte[] signed = sign(standardMessage(), new Options().algorithmTag("rsa-sha512"));
            assertFail(verifyOne(signed, dnsFor(rsa2048())), "unsupported");
        }

        @Test
        @DisplayName("a 512-bit RSA key is refused although the signature verifies")
        void shortKey() {
            TestKey weak = DkimTestSigner.rsa(512);
            byte[] signed = sign(standardMessage(), new Options().key(weak));
            assertFail(verifyOne(signed, dnsFor(weak)), "1024");
        }

        @Test
        @DisplayName("a 1024-bit RSA key is accepted (the floor is inclusive)")
        void exactly1024() {
            TestKey key = DkimTestSigner.rsa(1024);
            assertEquals(1024, ((RSAPublicKey) key.pair().getPublic()).getModulus().bitLength());
            assertEquals(Outcome.PASS, verifyOne(sign(standardMessage(), new Options().key(key)), dnsFor(key)).outcome());
        }

        @Test
        @DisplayName("a revoked key (empty p=) fails")
        void revokedKey() {
            FakeDns dns = new FakeDns().txt("sel._domainkey.example.com", "v=DKIM1; k=rsa; p=");
            assertFail(verifyOne(sign(standardMessage(), new Options()), dns), "revoked");
        }

        @Test
        @DisplayName("a key in testing mode (t=y) is not a pass")
        void testingKey() {
            FakeDns dns = new FakeDns().txt("sel._domainkey.example.com", rsa2048().record() + "; t=y");
            DkimResult result = verifyOne(sign(standardMessage(), new Options()), dns);
            assertEquals(Outcome.TESTING, result.outcome());
        }

        @Test
        @DisplayName("a t=s key refuses an i= in a subdomain of d=")
        void strictIdentityKey() {
            FakeDns dns = new FakeDns().txt("sel._domainkey.example.com", rsa2048().record() + "; t=s");
            byte[] sub = sign(standardMessage(), new Options().extraTags("i=alice@mail.example.com"));
            byte[] same = sign(standardMessage(), new Options().extraTags("i=alice@example.com"));

            assertFail(verifyOne(sub, dns), "t=s");
            assertEquals(Outcome.PASS, verifyOne(same, dns).outcome());
        }

        @Test
        @DisplayName("i= outside d= fails; i= in a subdomain of d= passes")
        void identityOutsideDomain() {
            assertFail(verifyOne(sign(standardMessage(), new Options().extraTags("i=@example.org")), dnsFor(rsa2048())), "i=");
            assertFail(verifyOne(sign(standardMessage(), new Options().extraTags("i=@badexample.com")), dnsFor(rsa2048())), "i=");
            assertEquals(Outcome.PASS,
                    verifyOne(sign(standardMessage(), new Options().extraTags("i=@news.example.com")), dnsFor(rsa2048())).outcome());
        }

        @Test
        @DisplayName("an expired x= fails; one in the future passes")
        void expiry() {
            long now = NOW.getEpochSecond();
            byte[] expired = sign(standardMessage(), new Options().extraTags("t=" + (now - 7200) + "; x=" + (now - 3600)));
            byte[] valid = sign(standardMessage(), new Options().extraTags("t=" + (now - 7200) + "; x=" + (now + 3600)));

            assertFail(verifyOne(expired, dnsFor(rsa2048())), "expired");
            assertEquals(Outcome.PASS, verifyOne(valid, dnsFor(rsa2048())).outcome());
        }

        @Test
        @DisplayName("x= and t= are judged at the instant passed in, not at the clock")
        void evaluatedAt() {
            long now = NOW.getEpochSecond();
            byte[] expiredNow = sign(standardMessage(), new Options().extraTags("t=" + (now - 7200) + "; x=" + (now - 3600)));
            byte[] signedLater = sign(standardMessage(), new Options().extraTags("t=" + (now - 600)));
            FakeDns dns = dnsFor(rsa2048());
            DkimVerifier verifier = new DkimVerifier(dns.resolver(), CLOCK);

            assertEquals(Outcome.PASS,
                    verifier.verify(expiredNow, dns.resolver().newBudget(), NOW.minusSeconds(5400)).results().get(0).outcome());
            DkimResult beforeSigning = verifier.verify(signedLater, dns.resolver().newBudget(), NOW.minusSeconds(3600)).results().get(0);
            assertFail(beforeSigning, "future");
        }

        @Test
        @DisplayName("t= in the future, or x= before t=, fails")
        void timestampSanity() {
            long now = NOW.getEpochSecond();
            assertFail(verifyOne(sign(standardMessage(), new Options().extraTags("t=" + (now + 86_400))), dnsFor(rsa2048())), "future");
            assertFail(verifyOne(sign(standardMessage(), new Options().extraTags("t=" + (now - 100) + "; x=" + (now - 200))),
                    dnsFor(rsa2048())), "before t=");
            assertFail(verifyOne(sign(standardMessage(), new Options().extraTags("t=12ab")), dnsFor(rsa2048())), "t=");
        }

        @ParameterizedTest
        @ValueSource(strings = {"abc", "-5", "1e9", "99999999999999999999", "9223372036854775808", "1234567890123"})
        @DisplayName("a non-numeric or overflowing t= or x= fails the signature instead of throwing")
        void malformedTimestamps(String bad) {
            assertFail(verifyOne(sign(standardMessage(), new Options().extraTags("t=" + bad)), dnsFor(rsa2048())), "t=");
            assertFail(verifyOne(sign(standardMessage(), new Options().extraTags("x=" + bad)), dnsFor(rsa2048())), "x=");
        }

        @Test
        @DisplayName("a signature that does not cover From fails")
        void fromNotSigned() {
            byte[] signed = sign(standardMessage(), new Options().headers("to", "subject"));
            assertFail(verifyOne(signed, dnsFor(rsa2048())), "From");
        }

        @Test
        @DisplayName("an Ed25519 signature checked against an RSA key record fails on the key type")
        void keyTypeMismatch() {
            byte[] signed = sign(standardMessage(), new Options().key(ed25519()));
            assertFail(verifyOne(signed, dnsFor(rsa2048())), "key type");
        }

        @Test
        @DisplayName("a key restricted to sha1 (h=) or to another service (s=) fails")
        void keyRestrictions() {
            FakeDns sha1 = new FakeDns().txt("sel._domainkey.example.com", "v=DKIM1; h=sha1; p=" + rsa2048().publicKeyBase64());
            FakeDns service = new FakeDns().txt("sel._domainkey.example.com", "v=DKIM1; s=other; p=" + rsa2048().publicKeyBase64());
            FakeDns versionLate = new FakeDns().txt("sel._domainkey.example.com", "k=rsa; v=DKIM1; p=" + rsa2048().publicKeyBase64());

            assertFail(verifyOne(sign(standardMessage(), new Options()), sha1), "sha256");
            assertFail(verifyOne(sign(standardMessage(), new Options()), service), "email");
            assertFail(verifyOne(sign(standardMessage(), new Options()), versionLate), "DKIM1");
        }

        @Test
        @DisplayName("two key records for one selector fail as ambiguous")
        void twoKeyRecords() {
            FakeDns dns = dnsFor(rsa2048()).txt("sel._domainkey.example.com", ed25519().record());
            assertFail(verifyOne(sign(standardMessage(), new Options()), dns), "more than one key");
        }

        @Test
        @DisplayName("a duplicated tag or a malformed tag list fails")
        void malformedTags() {
            byte[] signed = sign(standardMessage(), new Options());
            byte[] duplicated = bytes(text(signed).replace("v=1;", "v=1; v=1;"));
            byte[] noEquals = bytes(text(signed).replace("v=1;", "v=1; garbage;"));

            assertFail(verifyOne(duplicated, dnsFor(rsa2048())), "malformed");
            assertFail(verifyOne(noEquals, dnsFor(rsa2048())), "malformed");
        }

        @Test
        @DisplayName("a body appended after signing fails, even under relaxed canonicalisation")
        void appendedBody() {
            byte[] signed = sign(standardMessage(), new Options());
            byte[] appended = bytes(text(signed) + "P.S. wire the money to account 1234" + CRLF);
            assertFail(verifyOne(appended, dnsFor(rsa2048())), "body hash");
        }
    }

    @Nested
    @DisplayName("header selection")
    class HeaderSelection {

        @Test
        @DisplayName("a header named in h= but absent binds its absence: adding it later breaks the signature")
        void absentSignedHeader() {
            byte[] signed = sign(standardMessage(), new Options().headers("from", "subject", "reply-to"));
            byte[] injected = bytes(text(signed).replace("Subject:", "Reply-To: mallory@evil.example" + CRLF + "Subject:"));

            assertEquals(Outcome.PASS, verifyOne(signed, dnsFor(rsa2048())).outcome());
            assertFail(verifyOne(injected, dnsFor(rsa2048())), "does not verify");
        }

        @Test
        @DisplayName("a From added on top of a once-signed From is not hashed: DKIM passes, which is why From is counted")
        void unsignedFromOnTop() {
            byte[] signed = sign(standardMessage(), new Options());
            byte[] forged = bytes("From: CEO <ceo@example.com>" + CRLF + text(signed));

            Verification verification = new DkimVerifier(dnsFor(rsa2048()).resolver(), CLOCK).verify(forged,
                    dnsFor(rsa2048()).resolver().newBudget());

            assertEquals(Outcome.PASS, verification.results().get(0).outcome());
            assertEquals(2, verification.fromFieldCount());
        }

        @Test
        @DisplayName("a From added below the signed one is what bottom-up selection hashes: the signature breaks")
        void unsignedFromBelow() {
            byte[] signed = sign(standardMessage(), new Options());
            byte[] forged = bytes(text(signed).replace("To: bob@example.org", "To: bob@example.org" + CRLF + "From: ceo@example.com"));

            assertFail(verifyOne(forged, dnsFor(rsa2048())), "does not verify");
        }

        @Test
        @DisplayName("over-signing h=from:from refuses a second From wherever it is added")
        void overSigning() {
            byte[] signed = sign(standardMessage(), new Options().headers("from", "from", "subject"));
            byte[] onTop = bytes("From: CEO <ceo@example.com>" + CRLF + text(signed));

            assertEquals(Outcome.PASS, verifyOne(signed, dnsFor(rsa2048())).outcome());
            assertFail(verifyOne(onTop, dnsFor(rsa2048())), "does not verify");
        }

        @Test
        @DisplayName("of two signed instances of a header, the bottom one is hashed first")
        void bottomUpOrder() {
            byte[] raw = message("Received: from a", "Received: from b", "From: alice@example.com", "", "body");
            byte[] signed = sign(raw, new Options().headers("from", "received", "received"));
            byte[] swapped = bytes(text(signed).replace("Received: from a" + CRLF + "Received: from b",
                    "Received: from b" + CRLF + "Received: from a"));

            assertEquals(Outcome.PASS, verifyOne(signed, dnsFor(rsa2048())).outcome());
            assertFail(verifyOne(swapped, dnsFor(rsa2048())), "does not verify");
        }
    }

    @Nested
    @DisplayName("DNS outcomes")
    class Dns {

        @Test
        @DisplayName("no key (NXDOMAIN) is a failure; a timeout or SERVFAIL is a temporary error")
        void lookupOutcomes() {
            byte[] signed = sign(standardMessage(), new Options());

            assertFail(verifyOne(signed, new FakeDns()), "no key");
            assertEquals(Outcome.TEMPERROR, verifyOne(signed, new FakeDns().timeout("sel._domainkey.example.com")).outcome());
            assertEquals(Outcome.TEMPERROR, verifyOne(signed, new FakeDns().servfail("sel._domainkey.example.com")).outcome());
        }

        @Test
        @DisplayName("an oversize key record is refused")
        void oversize() {
            FakeDns dns = new FakeDns().txt("sel._domainkey.example.com", "v=DKIM1; p=" + "A".repeat(MailDnsResolver.MAX_RECORD_LENGTH));
            assertFail(verifyOne(sign(standardMessage(), new Options()), dns), "too large");
        }

        @Test
        @DisplayName("a spent budget is a temporary error and sends no query")
        void budgetSpent() {
            FakeDns dns = dnsFor(rsa2048());
            MailDnsResolver resolver = dns.resolver();
            Verification verification = new DkimVerifier(resolver, CLOCK).verify(sign(standardMessage(), new Options()),
                    new MailDnsResolver.Budget(0));

            assertEquals(Outcome.TEMPERROR, verification.results().get(0).outcome());
            assertTrue(verification.results().get(0).reason().contains("budget"));
            assertEquals(0, dns.queries.get());
        }

        @Test
        @DisplayName("a tampered body is caught before any lookup is spent")
        void bodyCheckedBeforeLookup() {
            FakeDns dns = dnsFor(rsa2048());
            byte[] tampered = bytes(text(sign(standardMessage(), new Options())).replace("Alice" + CRLF, "Mallory" + CRLF));

            assertFail(verifyOne(tampered, dns), "body hash");
            assertEquals(0, dns.queries.get());
        }

        @Test
        @DisplayName("an invalid selector or domain is refused without a lookup")
        void invalidNames() {
            FakeDns dns = dnsFor(rsa2048());
            assertFail(verifyOne(sign(standardMessage(), new Options().selector("bad selector")), dns), "s=");
            assertFail(verifyOne(sign(standardMessage(), new Options().domain("localhost")), dns), "d=");
            assertEquals(0, dns.queries.get());
        }
    }

    @Nested
    @DisplayName("bounds")
    class Bounds {

        @Test
        @DisplayName("at most five signatures are evaluated; the rest are counted")
        void signatureCap() {
            byte[] raw = standardMessage();
            for (int i = 0; i < 7; i++) {
                raw = sign(raw, new Options());
            }
            FakeDns dns = dnsFor(rsa2048());

            Verification verification = new DkimVerifier(dns.resolver(), CLOCK).verify(raw, dns.resolver().newBudget());

            assertEquals(7, verification.signaturesFound());
            assertEquals(DkimVerifier.MAX_SIGNATURES, verification.results().size());
        }

        @Test
        @DisplayName("a header block over the limit, or one starting with a continuation line, refuses the message")
        void malformedOrHugeHeaderBlock() {
            DkimVerifier verifier = new DkimVerifier(new FakeDns().resolver(), CLOCK);
            byte[] huge = bytes("X-Pad: " + "a".repeat(MailLimits.DEFAULT_MAX_HEADER_BYTES + 10) + CRLF + CRLF + "body");
            byte[] continuation = bytes(" folded" + CRLF + "From: a@example.com" + CRLF + CRLF);
            byte[] notAField = bytes("From a@example.com Fri Oct 23" + CRLF + "From: a@example.com" + CRLF + CRLF);

            assertNotNull(verifier.verify(huge, new MailDnsResolver.Budget(10)).messageFailure());
            assertNotNull(verifier.verify(continuation, new MailDnsResolver.Budget(10)).messageFailure());
            assertNotNull(verifier.verify(notAField, new MailDnsResolver.Budget(10)).messageFailure());
            assertNotNull(verifier.verify(new byte[0], new MailDnsResolver.Budget(10)).messageFailure());
        }

        @Test
        @DisplayName("an RSA key with a huge public exponent is refused")
        void hugeExponent() throws Exception {
            BigInteger modulus = ((RSAPublicKey) rsa2048().pair().getPublic()).getModulus();
            RSAPublicKey odd = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(modulus, BigInteger.TWO.pow(100).add(BigInteger.ONE)));
            FakeDns dns = new FakeDns().txt("sel._domainkey.example.com",
                    "v=DKIM1; p=" + Base64.getEncoder().encodeToString(odd.getEncoded()));

            assertFail(verifyOne(sign(standardMessage(), new Options()), dns), "outside the accepted size");
        }
    }
}
