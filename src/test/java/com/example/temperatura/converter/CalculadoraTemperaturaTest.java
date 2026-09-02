package com.example.temperatura.converter;

import com.example.temperatura.converter.interfaces.impl.CalculadoraTemperaturaImpl;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CalculadoraTemperaturaTest {
    CalculadoraTemperaturaImpl c = new CalculadoraTemperaturaImpl();
    @Test void ctof() { assertEquals(32.0, c.celsiusToFarenheit(0.0), 0.001); assertEquals(212.0, c.celsiusToFarenheit(100.0), 0.001); }
    @Test void ctok() { assertEquals(273.15, c.celsiusToKelvin(0.0), 0.001); }
    @Test void ftoc() { assertEquals(0.0, c.farenheitToCelsius(32.0), 0.001); }
    @Test void ftok() { assertEquals(273.15, c.farenheitToKelvin(32.0), 0.001); }
    @Test void ktoc() { assertEquals(0.0, c.kelvinToCelsius(273.15), 0.001); }
    @Test void ktof() { assertEquals(32.0, c.kelvinToFarenheit(273.15), 0.001); }
}
