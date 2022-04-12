package ai.labs.eddi.engine.httpclient.impl;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.eclipse.jetty.client.HttpClient;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class JettyHttpClient {
    private HttpClient httpClient;
}
