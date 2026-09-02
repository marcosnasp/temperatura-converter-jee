package com.example.temperatura.converter;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Path("/openapi-ui")
public class SwaggerUIResource {

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response ui() throws Exception {
        // ponytail: serve swagger-ui.html do webapp sem depender do default servlet (ApplicationPath "/" bloqueia estático)
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("swagger-ui.html")) {
            if (is != null) {
                String html = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                return Response.ok(html).build();
            }
        }
        // fallback inline (se não achar no classpath, usa CDN)
        String html = """
            <!DOCTYPE html><html lang="pt-BR"><head><meta charset="UTF-8"><title>Swagger UI - temperatura-converter-jee</title>
            <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5.11.0/swagger-ui.css"/></head>
            <body><div id="swagger-ui"></div>
            <script src="https://unpkg.com/swagger-ui-dist@5.11.0/swagger-ui-bundle.js"></script>
            <script src="https://unpkg.com/swagger-ui-dist@5.11.0/swagger-ui-standalone-preset.js"></script>
            <script>window.onload=()=>{SwaggerUIBundle({url:"/openapi",dom_id:'#swagger-ui',presets:[SwaggerUIBundle.presets.apis,SwaggerUIStandalonePreset],layout:"StandaloneLayout"})}</script>
            </body></html>
            """;
        return Response.ok(html).build();
    }
}
