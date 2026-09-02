package com.example.temperatura.converter.config;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Provider
@Priority(Priorities.AUTHENTICATION)
public class BasicAuthFilter implements ContainerRequestFilter {

    @Override
    public void filter(ContainerRequestContext ctx) throws IOException {
        String path = ctx.getUriInfo().getPath();
        // libera health/metrics sem auth (Prometheus + compose healthcheck)
        if (path.equals("health") || path.startsWith("health/") || path.equals("metrics") || path.startsWith("metrics/") || path.equals("metrics-per-endpoint") || path.equals("openapi") || path.equals("openapi-ui") || path.equals("openapi.yaml")) {
            return;
        }
        // ponytail: autentica apenas /converter, resto passa (404 do JAX-RS depois)
        if (!path.startsWith("converter")) {
            return;
        }

        String auth = ctx.getHeaderString("Authorization");
        if (auth == null || !auth.startsWith("Basic ")) {
            abort(ctx);
            return;
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(auth.substring(6)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            abort(ctx);
            return;
        }
        int colon = decoded.indexOf(':');
        if (colon < 0) {
            abort(ctx);
            return;
        }
        String user = decoded.substring(0, colon);
        String pass = decoded.substring(colon + 1);

        String expectedUser = System.getenv("APP_USERNAME");
        if (expectedUser == null) expectedUser = "admin";
        String expectedPass = System.getenv("APP_PASSWORD");
        if (expectedPass == null) expectedPass = "admin123";

        if (!user.equals(expectedUser) || !pass.equals(expectedPass)) {
            abort(ctx);
        }
    }

    private void abort(ContainerRequestContext ctx) {
        ctx.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                .header("WWW-Authenticate", "Basic realm=\"temperatura\"")
                .entity("Unauthorized")
                .build());
    }
}
