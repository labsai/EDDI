/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import ai.labs.eddi.engine.mail.DmarcAlignment.Mode;
import ai.labs.eddi.engine.mail.DmarcAlignment.Policy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DmarcAlignmentTest {

    private static Policy policy(FakeDns dns, String from) {
        MailDnsResolver resolver = dns.resolver();
        return new DmarcAlignment(resolver).policy(from, resolver.newBudget());
    }

    private static DmarcAlignment alignment() {
        return new DmarcAlignment(new FakeDns().resolver());
    }

    @Test
    @DisplayName("relaxed: same organisational domain aligns, also across subdomains under a multi-label suffix")
    void relaxed() {
        DmarcAlignment a = alignment();
        assertTrue(a.aligned("example.com", "example.com", Mode.RELAXED));
        assertTrue(a.aligned("mail.example.com", "example.com", Mode.RELAXED));
        assertTrue(a.aligned("example.co.uk", "mail.example.co.uk", Mode.RELAXED));
        assertTrue(a.aligned("News.Example.Co.Uk", "shop.example.co.uk", Mode.RELAXED));
        assertFalse(a.aligned("other.co.uk", "example.co.uk", Mode.RELAXED));
        assertFalse(a.aligned("co.uk", "example.co.uk", Mode.RELAXED));
        assertFalse(a.aligned("example.com", "example.org", Mode.RELAXED));
        assertFalse(a.aligned("badexample.com", "example.com", Mode.RELAXED));
    }

    @Test
    @DisplayName("relaxed honours wildcards, exceptions and private suffixes")
    void relaxedWithPslRules() {
        DmarcAlignment a = alignment();
        assertFalse(a.aligned("mallory.github.io", "alice.github.io", Mode.RELAXED));
        assertTrue(a.aligned("www.alice.github.io", "alice.github.io", Mode.RELAXED));
        assertFalse(a.aligned("x.b.ck", "y.b.ck", Mode.RELAXED));
        assertTrue(a.aligned("x.www.ck", "www.ck", Mode.RELAXED));
        assertTrue(a.aligned("a.city.kawasaki.jp", "city.kawasaki.jp", Mode.RELAXED));
    }

    @Test
    @DisplayName("strict: only the exact domain aligns")
    void strict() {
        DmarcAlignment a = alignment();
        assertTrue(a.aligned("example.com", "EXAMPLE.com.", Mode.STRICT));
        assertFalse(a.aligned("mail.example.com", "example.com", Mode.STRICT));
        assertFalse(a.aligned(null, "example.com", Mode.RELAXED));
        assertFalse(a.aligned("example.com", null, Mode.RELAXED));
    }

    @Test
    @DisplayName("the From domain's own record decides adkim")
    void ownRecord() {
        FakeDns dns = new FakeDns().txt("_dmarc.example.com", "v=DMARC1; p=reject; adkim=s");
        Policy policy = policy(dns, "example.com");
        assertEquals(Mode.STRICT, policy.mode());
        assertEquals("example.com", policy.recordDomain());
        assertFalse(policy.temporaryFailure());
    }

    @Test
    @DisplayName("without a record of its own, the organisational domain's record applies (RFC 7489 §6.6.3)")
    void orgFallback() {
        FakeDns dns = new FakeDns().txt("_dmarc.example.co.uk", "v=DMARC1; p=none; adkim=s");
        Policy policy = policy(dns, "mail.example.co.uk");
        assertEquals(Mode.STRICT, policy.mode());
        assertEquals("example.co.uk", policy.recordDomain());
        assertEquals(2, dns.queries.get());
    }

    @Test
    @DisplayName("no record anywhere means relaxed; records that are not DMARC1 are ignored")
    void defaults() {
        FakeDns dns = new FakeDns().txt("_dmarc.example.com", "v=spf1 -all").txt("_dmarc.example.com", "adkim=s; v=DMARC1");
        Policy policy = policy(dns, "example.com");
        assertEquals(Mode.RELAXED, policy.mode());
        assertNull(policy.recordDomain());
    }

    @Test
    @DisplayName("an unknown adkim value is relaxed; the version tag is case-insensitive")
    void lenientValues() {
        assertEquals(Mode.RELAXED, policy(new FakeDns().txt("_dmarc.example.com", "v=DMARC1; adkim=x"), "example.com").mode());
        assertEquals(Mode.STRICT, policy(new FakeDns().txt("_dmarc.example.com", "v=dmarc1; adkim=S"), "example.com").mode());
    }

    @Test
    @DisplayName("two DMARC records: discovery stops, no fallback to the organisational domain")
    void twoRecords() {
        FakeDns dns = new FakeDns().txt("_dmarc.mail.example.com", "v=DMARC1; adkim=s").txt("_dmarc.mail.example.com", "v=DMARC1; p=none")
                .txt("_dmarc.example.com", "v=DMARC1; adkim=s");
        Policy policy = policy(dns, "mail.example.com");
        assertEquals(Mode.RELAXED, policy.mode());
        assertEquals(1, dns.queries.get());
    }

    @Test
    @DisplayName("a temporary failure assumes strict and says so")
    void temporaryFailure() {
        Policy own = policy(new FakeDns().timeout("_dmarc.example.com"), "example.com");
        Policy org = policy(new FakeDns().timeout("_dmarc.example.com"), "mail.example.com");
        assertEquals(Mode.STRICT, own.mode());
        assertTrue(own.temporaryFailure());
        assertEquals(Mode.STRICT, org.mode());
        assertTrue(org.temporaryFailure());
    }
}
