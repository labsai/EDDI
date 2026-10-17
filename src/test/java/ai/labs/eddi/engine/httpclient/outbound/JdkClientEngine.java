/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.outbound;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * An {@link OutboundEngine} that hands every hop to a {@link HttpClient} —
 * usually a Mockito mock — as a JDK {@link HttpRequest}.
 * <p>
 * For tests written against a mocked JDK client before their code moved onto
 * {@link OutboundHttp}: they keep stubbing {@code client.send(...)} and
 * matching on the {@code HttpRequest}, while the code under test runs through
 * the real facade. A stubbed response's body may be a {@code String}, a
 * {@code byte[]} or an {@code InputStream}, whatever the stub's handler type
 * was.
 */
public final class JdkClientEngine implements OutboundEngine {

    /** Headers the JDK request builder refuses to be given. */
    private static final Set<String> RESTRICTED = Set.of("host", "content-length", "connection", "expect", "upgrade");

    private final HttpClient client;

    public JdkClientEngine(HttpClient client) {
        this.client = client;
    }

    /** A real facade sending through {@code client}. */
    public static OutboundHttp facade(HttpClient client) {
        return TestOutbound.facade(new JdkClientEngine(client));
    }

    @Override
    public Received send(Hop hop) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(hop.uri())
                .timeout(hop.hopTimeout())
                .method(hop.method(), hop.body().length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(hop.body()));
        hop.headers().forEach((name, values) -> {
            if (!RESTRICTED.contains(name.toLowerCase(Locale.ROOT))) {
                values.forEach(value -> builder.header(name, value));
            }
        });
        HttpResponse<?> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response == null) {
            throw new IOException("the stubbed client returned no response");
        }
        HttpHeaders headers = response.headers();
        Map<String, List<String>> headerMap = headers == null ? Map.of() : headers.map();
        return new Received(response.statusCode(), null, OutboundRequest.caseInsensitiveCopy(headerMap), bodyOf(response.body()));
    }

    private static InputStream bodyOf(Object body) {
        if (body instanceof InputStream stream) {
            return stream;
        }
        if (body instanceof byte[] bytes) {
            return new ByteArrayInputStream(bytes);
        }
        if (body instanceof String text) {
            return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        }
        return InputStream.nullInputStream();
    }
}
