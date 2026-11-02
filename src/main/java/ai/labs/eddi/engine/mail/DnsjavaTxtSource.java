/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import ai.labs.eddi.engine.mail.MailDnsResolver.RawAnswer;
import ai.labs.eddi.engine.mail.MailDnsResolver.Status;
import org.jboss.logging.Logger;
import org.xbill.DNS.DClass;
import org.xbill.DNS.ExtendedResolver;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.SOARecord;
import org.xbill.DNS.Section;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.TXTRecord;
import org.xbill.DNS.Type;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The production {@link MailDnsResolver.TxtSource}: one TXT query through
 * dnsjava's resolver.
 * <p>
 * The resolver is created on first use, so a deployment that never
 * authenticates mail never reads {@code /etc/resolv.conf} or resolves a server
 * name. Configured servers are {@code host}, {@code host:port} or
 * {@code [v6]:port}; none means the system's (dnsjava's
 * {@code ResolverConfig}). Truncated UDP answers are retried over TCP by
 * dnsjava. Strings are read as bytes and decoded as ISO-8859-1, so a byte is a
 * character and nothing is escaped or lost: DKIM and DMARC records are ASCII,
 * and anything else fails their own parsers.
 */
final class DnsjavaTxtSource implements MailDnsResolver.TxtSource {

    private static final Logger LOGGER = Logger.getLogger(DnsjavaTxtSource.class);

    private final List<String> servers;
    private final Duration timeout;
    private volatile Resolver resolver;
    private boolean creationFailureLogged;

    DnsjavaTxtSource(List<String> servers, Duration timeout) {
        this.servers = servers == null ? List.of() : servers.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
        this.timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(5) : timeout;
    }

    @Override
    public RawAnswer query(String name) throws Exception {
        Record question = Record.newRecord(Name.fromString(name), Type.TXT, DClass.IN);
        Message response = resolver().send(Message.newQuery(question));
        int rcode = response.getRcode();
        if (rcode == Rcode.NXDOMAIN) {
            return new RawAnswer(Status.NOT_FOUND, List.of(), negativeTtl(response));
        }
        if (rcode != Rcode.NOERROR) {
            return new RawAnswer(Status.TEMPFAIL, List.of(), null);
        }
        List<String> records = new ArrayList<>();
        long ttl = Long.MAX_VALUE;
        for (Record record : response.getSection(Section.ANSWER)) {
            if (record instanceof TXTRecord txt) {
                if (records.size() >= MailDnsResolver.MAX_RECORDS) {
                    return new RawAnswer(Status.OVERSIZE, List.of(), Duration.ofSeconds(Math.min(ttl, txt.getTTL())));
                }
                StringBuilder joined = new StringBuilder();
                for (byte[] part : txt.getStringsAsByteArrays()) {
                    joined.append(new String(part, StandardCharsets.ISO_8859_1));
                    if (joined.length() > MailDnsResolver.MAX_RECORD_LENGTH) {
                        return new RawAnswer(Status.OVERSIZE, List.of(), Duration.ofSeconds(txt.getTTL()));
                    }
                }
                records.add(joined.toString());
                ttl = Math.min(ttl, txt.getTTL());
            }
        }
        if (records.isEmpty()) {
            return new RawAnswer(Status.NOT_FOUND, List.of(), negativeTtl(response));
        }
        return new RawAnswer(Status.FOUND, records, Duration.ofSeconds(ttl));
    }

    /** RFC 2308: the SOA's minimum, bounded by the SOA's own TTL. */
    private static Duration negativeTtl(Message response) {
        for (Record record : response.getSection(Section.AUTHORITY)) {
            if (record instanceof SOARecord soa) {
                return Duration.ofSeconds(Math.min(soa.getMinimum(), soa.getTTL()));
            }
        }
        return null;
    }

    private Resolver resolver() throws UnknownHostException {
        Resolver current = resolver;
        if (current == null) {
            synchronized (this) {
                current = resolver;
                if (current == null) {
                    try {
                        current = create();
                    } catch (RuntimeException | UnknownHostException e) {
                        if (!creationFailureLogged) {
                            creationFailureLogged = true;
                            LOGGER.errorf("eddi.email.dns.servers is unusable (%s): every DKIM and DMARC lookup fails temporarily, "
                                    + "so no mail is authenticated until it is fixed", e.getMessage());
                        }
                        throw e;
                    }
                    resolver = current;
                }
            }
        }
        return current;
    }

    private Resolver create() throws UnknownHostException {
        ExtendedResolver created;
        if (servers.isEmpty()) {
            created = new ExtendedResolver();
        } else {
            List<Resolver> each = new ArrayList<>();
            for (String server : servers) {
                each.add(new SimpleResolver(address(server)));
            }
            created = new ExtendedResolver(each);
        }
        created.setTimeout(timeout);
        created.setRetries(1);
        return created;
    }

    /** {@code host}, {@code host:port}, {@code [v6]} or {@code [v6]:port}. */
    static InetSocketAddress address(String server) {
        String host = server;
        int port = 53;
        if (server.startsWith("[")) {
            int close = server.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException("Unclosed IPv6 bracket in a DNS server: " + server);
            }
            host = server.substring(1, close);
            String rest = server.substring(close + 1);
            if (rest.startsWith(":")) {
                port = Integer.parseInt(rest.substring(1));
            } else if (!rest.isEmpty()) {
                throw new IllegalArgumentException("Unexpected text after a DNS server address: " + server);
            }
        } else if (server.indexOf(':') >= 0 && server.indexOf(':') == server.lastIndexOf(':')) {
            int colon = server.indexOf(':');
            host = server.substring(0, colon);
            port = Integer.parseInt(server.substring(colon + 1));
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("DNS server port out of range: " + server);
        }
        return new InetSocketAddress(host, port);
    }
}
