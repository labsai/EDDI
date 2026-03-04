package ai.labs.eddi.engine.httpclient.impl;

import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientSession;
import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public class VertxHttpClient {
    private final Vertx vertx;
    private final WebClientSession webClient;
    private final WebClient underlyingClient;
}
