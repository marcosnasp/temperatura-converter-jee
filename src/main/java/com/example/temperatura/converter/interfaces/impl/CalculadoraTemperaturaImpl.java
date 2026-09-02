package com.example.temperatura.converter.interfaces.impl;

import com.example.temperatura.converter.interfaces.CalculadoraTemperatura;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class CalculadoraTemperaturaImpl implements CalculadoraTemperatura {

    @Override
    public Double celsiusToFarenheit(Double c) {
        return (c * 9 / 5) + 32;
    }

    @Override
    public Double celsiusToKelvin(Double c) {
        return c + 273.15;
    }

    @Override
    public Double farenheitToCelsius(Double f) {
        return ((f - 32) * 5) / 9;
    }

    @Override
    public Double farenheitToKelvin(Double f) {
        return ((f - 32) * 5) / 9 + 273.15;
    }

    @Override
    public Double kelvinToCelsius(Double k) {
        return k - 273.15;
    }

    @Override
    public Double kelvinToFarenheit(Double k) {
        return ((k - 273.15) * 9) / 5 + 32;
    }
}
