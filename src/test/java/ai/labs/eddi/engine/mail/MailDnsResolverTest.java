/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import ai.labs.eddi.engine.mail.MailDnsResolver.RawAnswer;
import ai.labs.eddi.engine.mail.MailDnsResolver.Status;
import ai.labs.eddi.engine.mail.MailDnsResolver.TxtLookup;
import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.SOARecord;
import org.xbill.DNS.Section;
import org.xbill.DNS.TXTRecord;
import org.xbill.DNS.spi.DnsjavaInetAddressResolverProvider;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MailDnsResolverTest {

    /** A ticker the test moves by hand. */
    static final class ManualTicker implements Ticker {
        final AtomicLong nanos = new AtomicLong();

        @Override
        public long read() {
            return nanos.get();
        }

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }

    @Nested
    @DisplayName("cache, budget and limits")
    class Resolver {

        private final ManualTicker ticker = new ManualTicker();
        private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        private final AtomicLong queries = new AtomicLong();

        private MailDnsResolver resolver(Function<String, RawAnswer> answers) {
            return new MailDnsResolver(name -> {
                queries.incrementAndGet();
                return answers.apply(name);
            }, 100, Duration.ofHours(1), 3, registry, ticker);
        }

        private double count(String result, boolean cached) {
            var counter = registry.find(MailDnsResolver.METER_LOOKUPS).tag("result", result).tag("cached", Boolean.toString(cached))
                    .counter();
            return counter == null ? 0 : counter.count();
        }

        @Test
        @DisplayName("an answer is cached for its TTL, then fetched again")
        void honoursTtl() {
            MailDnsResolver resolver = resolver(n -> new RawAnswer(Status.FOUND, List.of("v=DMARC1; p=none"), Duration.ofSeconds(300)));

            assertEquals(Status.FOUND, resolver.lookupTxt("_dmarc.example.com", resolver.newBudget()).status());
            ticker.advance(Duration.ofSeconds(299));
            assertEquals(List.of("v=DMARC1; p=none"), resolver.lookupTxt("_dmarc.example.com", resolver.newBudget()).records());
            assertEquals(1, queries.get());
            ticker.advance(Duration.ofSeconds(2));
            resolver.lookupTxt("_dmarc.example.com", resolver.newBudget());
            assertEquals(2, queries.get());
            assertEquals(1, count("found", true));
            assertEquals(2, count("found", false));
        }

        @Test
        @DisplayName("a TTL over the cap is capped")
        void capsTtl() {
            MailDnsResolver resolver = resolver(n -> new RawAnswer(Status.FOUND, List.of("x"), Duration.ofDays(7)));
            resolver.lookupTxt("a.example.com", resolver.newBudget());
            ticker.advance(Duration.ofHours(1).plusSeconds(1));
            resolver.lookupTxt("a.example.com", resolver.newBudget());
            assertEquals(2, queries.get());
        }

        @Test
        @DisplayName("negative answers are cached; temporary failures are not")
        void negativeAndTemporary() {
            MailDnsResolver resolver = resolver(n -> n.startsWith("missing")
                    ? new RawAnswer(Status.NOT_FOUND, List.of(), null)
                    : new RawAnswer(Status.TEMPFAIL, List.of(), null));

            resolver.lookupTxt("missing.example.com", resolver.newBudget());
            resolver.lookupTxt("missing.example.com", resolver.newBudget());
            assertEquals(1, queries.get());
            resolver.lookupTxt("flaky.example.com", resolver.newBudget());
            resolver.lookupTxt("flaky.example.com", resolver.newBudget());
            assertEquals(3, queries.get());
        }

        @Test
        @DisplayName("a source that throws is a temporary failure, not an exception")
        void sourceThrows() {
            MailDnsResolver resolver = new MailDnsResolver(n -> {
                throw new IllegalStateException("boom");
            }, 10, Duration.ofHours(1), 3, registry, ticker);
            assertEquals(Status.TEMPFAIL, resolver.lookupTxt("a.example.com", resolver.newBudget()).status());
        }

        @Test
        @DisplayName("the budget limits network lookups per message; cache hits are free")
        void budget() {
            MailDnsResolver resolver = resolver(n -> new RawAnswer(Status.FOUND, List.of("x"), Duration.ofMinutes(5)));
            MailDnsResolver.Budget budget = resolver.newBudget();
            assertEquals(3, budget.remaining());

            resolver.lookupTxt("a.example.com", budget);
            resolver.lookupTxt("a.example.com", budget);
            resolver.lookupTxt("b.example.com", budget);
            resolver.lookupTxt("c.example.com", budget);
            TxtLookup refused = resolver.lookupTxt("d.example.com", budget);

            assertEquals(Status.BUDGET_EXHAUSTED, refused.status());
            assertEquals(3, queries.get());
            assertEquals(0, budget.remaining());
            assertEquals(Status.BUDGET_EXHAUSTED, resolver.lookupTxt("e.example.com", null).status());
        }

        @Test
        @DisplayName("an oversize record or too many records are refused")
        void oversize() {
            MailDnsResolver big = resolver(n -> new RawAnswer(Status.FOUND, List.of("a".repeat(MailDnsResolver.MAX_RECORD_LENGTH + 1)), null));
            MailDnsResolver many = resolver(
                    n -> new RawAnswer(Status.FOUND, Collections.nCopies(MailDnsResolver.MAX_RECORDS + 1, "x"), null));
            MailDnsResolver exact = resolver(n -> new RawAnswer(Status.FOUND, List.of("a".repeat(MailDnsResolver.MAX_RECORD_LENGTH)), null));

            assertEquals(Status.OVERSIZE, big.lookupTxt("a.example.com", big.newBudget()).status());
            assertEquals(Status.OVERSIZE, many.lookupTxt("a.example.com", many.newBudget()).status());
            assertEquals(Status.FOUND, exact.lookupTxt("a.example.com", exact.newBudget()).status());
        }

        @Test
        @DisplayName("names that are not DNS names are refused without a query")
        void invalidNames() {
            MailDnsResolver resolver = resolver(n -> new RawAnswer(Status.FOUND, List.of("x"), null));
            for (String name : new String[]{null, "", "a..b", "a b.example.com", "-a.example.com", "x".repeat(64) + ".com",
                    "a\r\n.example.com"}) {
                assertEquals(Status.INVALID_NAME, resolver.lookupTxt(name, resolver.newBudget()).status(), String.valueOf(name));
            }
            assertEquals(0, queries.get());
            assertEquals("_dmarc.example.com.", MailDnsResolver.normalise("_DMARC.Example.com."));
        }
    }

    @Nested
    @DisplayName("dnsjava against a local DNS server")
    class Dnsjava {

        private DatagramSocket socket;
        private Thread server;
        private final Map<String, Function<Message, Message>> zone = new ConcurrentHashMap<>();

        private String start() throws SocketException {
            socket = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            server = new Thread(() -> {
                byte[] buffer = new byte[4096];
                while (!socket.isClosed()) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        socket.receive(packet);
                        Message query = new Message(Arrays.copyOf(packet.getData(), packet.getLength()));
                        String name = query.getQuestion().getName().toString();
                        Function<Message, Message> handler = zone.get(name);
                        if (handler == null) {
                            continue; // no answer: the client times out
                        }
                        byte[] out = handler.apply(query).toWire();
                        socket.send(new DatagramPacket(out, out.length, packet.getSocketAddress()));
                    } catch (Exception e) {
                        // closed
                    }
                }
            }, "fake-dns");
            server.setDaemon(true);
            server.start();
            return "127.0.0.1:" + socket.getLocalPort();
        }

        @AfterEach
        void stop() {
            if (socket != null) {
                socket.close();
            }
        }

        private static Message reply(Message query, int rcode) {
            Message response = new Message(query.getHeader().getID());
            response.getHeader().setFlag(Flags.QR);
            response.getHeader().setRcode(rcode);
            response.addRecord(query.getQuestion(), Section.QUESTION);
            return response;
        }

        private static Name name(String text) {
            try {
                return Name.fromString(text);
            } catch (Exception e) {
                throw new IllegalArgumentException(e);
            }
        }

        @Test
        @DisplayName("a record's strings are joined; the TTL is the record's; NXDOMAIN takes the SOA minimum")
        void answers() throws Exception {
            String server = start();
            zone.put("sel._domainkey.example.com.", q -> {
                Message r = reply(q, Rcode.NOERROR);
                r.addRecord(new TXTRecord(name("sel._domainkey.example.com."), DClass.IN, 120, List.of("v=DKIM1; p=AAA", "BBB", "CCC")),
                        Section.ANSWER);
                return r;
            });
            zone.put("_dmarc.missing.example.", q -> {
                Message r = reply(q, Rcode.NXDOMAIN);
                r.addRecord(new SOARecord(name("example."), DClass.IN, 3600, name("ns.example."), name("admin.example."), 1, 3600, 600,
                        86400, 300), Section.AUTHORITY);
                return r;
            });
            zone.put("_dmarc.broken.example.", q -> reply(q, Rcode.SERVFAIL));
            DnsjavaTxtSource source = new DnsjavaTxtSource(List.of(server), Duration.ofMillis(500));

            RawAnswer found = source.query("sel._domainkey.example.com.");
            assertEquals(Status.FOUND, found.status());
            assertEquals(List.of("v=DKIM1; p=AAABBBCCC"), found.records());
            assertEquals(Duration.ofSeconds(120), found.ttl());

            RawAnswer missing = source.query("_dmarc.missing.example.");
            assertEquals(Status.NOT_FOUND, missing.status());
            assertEquals(Duration.ofSeconds(300), missing.ttl());

            assertEquals(Status.TEMPFAIL, source.query("_dmarc.broken.example.").status());
        }

        @Test
        @DisplayName("an unanswered query times out and is a temporary failure through the resolver")
        void timeout() throws Exception {
            String server = start();
            MailDnsResolver resolver = new MailDnsResolver(new DnsjavaTxtSource(List.of(server), Duration.ofMillis(300)), 10,
                    Duration.ofHours(1), 10, new SimpleMeterRegistry(), Ticker.systemTicker());

            long started = System.nanoTime();
            assertEquals(Status.TEMPFAIL, resolver.lookupTxt("silent.example.com", resolver.newBudget()).status());
            // It failed because nobody answered, not because the query never left.
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() >= 250, "the query failed before its timeout");
        }

        @Test
        @DisplayName("a NOERROR answer without TXT records is not found")
        void noData() throws Exception {
            String server = start();
            zone.put("empty.example.", q -> reply(q, Rcode.NOERROR));
            DnsjavaTxtSource source = new DnsjavaTxtSource(List.of(server), Duration.ofMillis(500));

            RawAnswer answer = source.query("empty.example.");
            assertEquals(Status.NOT_FOUND, answer.status());
            assertNull(answer.ttl());
        }
    }

    @Test
    @DisplayName("server addresses: host, host:port, [v6], [v6]:port")
    void serverAddresses() {
        assertEquals(53, DnsjavaTxtSource.address("10.0.0.53").getPort());
        assertEquals(5353, DnsjavaTxtSource.address("10.0.0.53:5353").getPort());
        assertEquals(53, DnsjavaTxtSource.address("[::1]").getPort());
        assertEquals(5300, DnsjavaTxtSource.address("[::1]:5300").getPort());
        assertEquals(53, DnsjavaTxtSource.address("::1").getPort());
        assertThrows(IllegalArgumentException.class, () -> DnsjavaTxtSource.address("[::1"));
        assertThrows(IllegalArgumentException.class, () -> DnsjavaTxtSource.address("10.0.0.1:0"));
        assertThrows(NumberFormatException.class, () -> DnsjavaTxtSource.address("10.0.0.1:x"));
    }

    @Test
    @DisplayName("an unusable server setting fails lookups temporarily instead of throwing")
    void unusableServer() {
        MailDnsResolver resolver = new MailDnsResolver(new DnsjavaTxtSource(List.of("10.0.0.1:99999"), Duration.ofMillis(100)), 10,
                Duration.ofHours(1), 10, new SimpleMeterRegistry(), Ticker.systemTicker());
        assertEquals(Status.TEMPFAIL, resolver.lookupTxt("a.example.com", resolver.newBudget()).status());
    }

    @Test
    @DisplayName("dnsjava's InetAddressResolverProvider is on the class path but not switched on")
    void jvmResolverUntouched() throws Exception {
        // dnsjava's jar registers a java.net.spi.InetAddressResolverProvider, which
        // the JDK loads for ALL host-name resolution. It hands back the JDK's own
        // resolver unless this property is true. If dnsjava ever drops that switch,
        // this constant disappears and the test stops compiling or passing.
        assertEquals("org.dnsjava.spi.enable", DnsjavaInetAddressResolverProvider.ENABLE_SPI);
        assertEquals(false, Boolean.getBoolean("org.dnsjava.spi.enable"));
    }
}
