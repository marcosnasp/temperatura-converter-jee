package com.example.temperatura.converter;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.info.Info;
import org.eclipse.microprofile.openapi.annotations.servers.Server;

@ApplicationPath("/")
@OpenAPIDefinition(
    info = @Info(title = "temperatura-converter-jee", version = "1.0.0", description = "Conversor Celsius/Fahrenheit/Kelvin - Jakarta EE 10 / WildFly 32 - Lab monitoramento"),
    servers = {@Server(url = "/temperatura"), @Server(url = "https://jee.lab.dev/temperatura")}
)
public class RestApplication extends Application {
}
