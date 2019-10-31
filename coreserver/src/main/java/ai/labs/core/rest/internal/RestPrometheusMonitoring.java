package ai.labs.core.rest.internal;

import ai.labs.rest.restinterfaces.IRestPrometheusMonitoring;
import io.micrometer.prometheus.PrometheusMeterRegistry;

import javax.inject.Inject;
import javax.ws.rs.core.Response;

public class RestPrometheusMonitoring implements IRestPrometheusMonitoring {
    private final PrometheusMeterRegistry prometheusMeterRegistry;

    @Inject
    public RestPrometheusMonitoring(PrometheusMeterRegistry prometheusMeterRegistry) {
        this.prometheusMeterRegistry = prometheusMeterRegistry;
    }

    @Override
    public Response readMonitoringStats() {
        return Response.ok(prometheusMeterRegistry.scrape()).build();
    }
}
