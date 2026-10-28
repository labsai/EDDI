/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import ai.labs.eddi.engine.mail.MailDnsResolver.RawAnswer;
import ai.labs.eddi.engine.mail.MailDnsResolver.Status;
import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An offline DNS: TXT records from a map, failures on request, queries counted.
 */
final class FakeDns implements MailDnsResolver.TxtSource {

    private final Map<String, List<String>> records = new HashMap<>();
    private final Set<String> timeouts = new HashSet<>();
    private final Set<String> servfails = new HashSet<>();
    final AtomicInteger queries = new AtomicInteger();
    final List<String> asked = new ArrayList<>();

    /** Adds a record; the name is given without the trailing dot. */
    FakeDns txt(String name, String record) {
        records.computeIfAbsent(normalise(name), k -> new ArrayList<>()).add(record);
        return this;
    }

    FakeDns all(Map<String, String> byName) {
        byName.forEach(this::txt);
        return this;
    }

    FakeDns timeout(String name) {
        timeouts.add(normalise(name));
        return this;
    }

    FakeDns servfail(String name) {
        servfails.add(normalise(name));
        return this;
    }

    /**
     * Loads records from a fixture: one per line, {@code name TXT "string"
     * "string" ...}, the strings joined as DNS would; {@code #} comments.
     */
    FakeDns load(String resource) {
        try (InputStream in = FakeDns.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + resource);
            }
            for (String line : new String(in.readAllBytes(), StandardCharsets.US_ASCII).split("\r?\n")) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int txt = trimmed.indexOf(" TXT ");
                String name = trimmed.substring(0, txt).strip();
                StringBuilder value = new StringBuilder();
                String rest = trimmed.substring(txt + 5);
                int open = rest.indexOf('"');
                while (open >= 0) {
                    int close = rest.indexOf('"', open + 1);
                    value.append(rest, open + 1, close);
                    open = rest.indexOf('"', close + 1);
                }
                txt(name, value.toString());
            }
            return this;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String normalise(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".")) {
            n = n.substring(0, n.length() - 1);
        }
        return n + ".";
    }

    @Override
    public RawAnswer query(String name) throws Exception {
        queries.incrementAndGet();
        asked.add(name);
        if (timeouts.contains(name)) {
            throw new SocketTimeoutException("timed out");
        }
        if (servfails.contains(name)) {
            return new RawAnswer(Status.TEMPFAIL, List.of(), null);
        }
        List<String> found = records.get(name);
        if (found == null) {
            return new RawAnswer(Status.NOT_FOUND, List.of(), Duration.ofMinutes(5));
        }
        return new RawAnswer(Status.FOUND, found, Duration.ofMinutes(10));
    }

    /** A resolver over this source with the default limits and a fresh registry. */
    MailDnsResolver resolver() {
        return resolver(new SimpleMeterRegistry());
    }

    MailDnsResolver resolver(MeterRegistry registry) {
        return new MailDnsResolver(this, 1000, Duration.ofHours(1), 10, registry, Ticker.systemTicker());
    }
}
