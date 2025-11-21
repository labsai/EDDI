package ai.labs.eddi.engine.httpclient.impl;

import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class VertxHttpClient {
    private Vertx vertx;
    private WebClient webClient;
}
