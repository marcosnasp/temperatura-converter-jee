package com.example.temperatura.converter.interfaces;

public interface CalculadoraTemperatura {
    Double celsiusToFarenheit(Double c);
    Double celsiusToKelvin(Double c);
    Double farenheitToCelsius(Double f);
    Double farenheitToKelvin(Double f);
    Double kelvinToCelsius(Double k);
    Double kelvinToFarenheit(Double k);
}
