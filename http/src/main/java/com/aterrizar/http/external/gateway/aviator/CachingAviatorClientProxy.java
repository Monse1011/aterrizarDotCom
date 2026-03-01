package com.aterrizar.http.external.gateway.aviator;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.aterrizar.http.external.gateway.aviator.model.v2.FlightDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
@Primary
public class CachingAviatorClientProxy implements AviatorV2HttpClient {
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final AviatorV2HttpClient realClient;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public CachingAviatorClientProxy(
            @Qualifier("aviatorV2HttpClient") AviatorV2HttpClient realClient,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper) {
        this.realClient = realClient;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public FlightDto getFlights(String flightId) {
        var key = cacheKey(flightId);
        var cachedFlight = redisTemplate.opsForValue().get(key);
        if (cachedFlight != null) {
            return deserialize(cachedFlight);
        }

        var flight = realClient.getFlights(flightId);
        redisTemplate.opsForValue().set(key, serialize(flight), CACHE_TTL);
        return flight;
    }

    private String cacheKey(String flightNumber) {
        return "flight:" + flightNumber;
    }

    private String serialize(FlightDto flightDto) {
        try {
            return objectMapper.writeValueAsString(flightDto);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize flight data", e);
        }
    }

    private FlightDto deserialize(String cachedData) {
        try {
            return objectMapper.readValue(cachedData, FlightDto.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize flight data", e);
        }
    }
}
