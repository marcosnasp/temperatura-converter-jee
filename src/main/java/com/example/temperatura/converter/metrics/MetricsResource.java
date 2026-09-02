package com.example.temperatura.converter.metrics;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/metrics-per-endpoint")
public class MetricsResource {

    @Inject
    EndpointMetrics metrics;

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public Response prometheus() {
        StringBuilder sb = new StringBuilder();
        sb.append("# HELP converter_requests_total Total requests per endpoint\n");
        sb.append("# TYPE converter_requests_total counter\n");
        metrics.all().forEach((ep, cnt) ->
            sb.append(String.format("converter_requests_total{endpoint=\"%s\"} %d\n", ep, cnt.get()))
        );
        // ponytail: expõe formato Prometheus puro, sem dependência extra
        return Response.ok(sb.toString()).build();
    }
}
