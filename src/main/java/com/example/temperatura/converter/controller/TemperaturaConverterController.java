package com.example.temperatura.converter.controller;

import com.example.temperatura.converter.interfaces.CalculadoraTemperatura;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/converter")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Conversão", description = "Endpoints de conversão de temperatura")
public class TemperaturaConverterController {

    @Inject
    CalculadoraTemperatura calculadora;

    @GET
    @Path("/ctof/{tempCelsius}")
    public Double celsiusToFarenheit(@PathParam("tempCelsius") Double tempCelsius) {
        return calculadora.celsiusToFarenheit(tempCelsius);
    }

    @GET
    @Path("/ctok/{tempCelsius}")
    public Double celsiusToKelvin(@PathParam("tempCelsius") Double tempCelsius) {
        return calculadora.celsiusToKelvin(tempCelsius);
    }

    @GET
    @Path("/ftoc/{tempFarenheit}")
    public Double farenheitToCelsius(@PathParam("tempFarenheit") Double tempFarenheit) {
        return calculadora.farenheitToCelsius(tempFarenheit);
    }

    @GET
    @Path("/ftok/{tempFarenheit}")
    public Double farenheitToKelvin(@PathParam("tempFarenheit") Double tempFarenheit) {
        return calculadora.farenheitToKelvin(tempFarenheit);
    }

    @GET
    @Path("/ktoc/{tempKelvin}")
    public Double kelvinToCelsius(@PathParam("tempKelvin") Double tempKelvin) {
        return calculadora.kelvinToCelsius(tempKelvin);
    }

    @GET
    @Path("/ktof/{tempKelvin}")
    public Double kelvinToFarenheit(@PathParam("tempKelvin") Double tempKelvin) {
        return calculadora.kelvinToFarenheit(tempKelvin);
    }
}
