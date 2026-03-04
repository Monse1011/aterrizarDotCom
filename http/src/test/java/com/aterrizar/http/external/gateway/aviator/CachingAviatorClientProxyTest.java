package com.aterrizar.http.external.gateway.aviator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.aterrizar.http.external.gateway.aviator.model.v2.AirportDto;
import com.aterrizar.http.external.gateway.aviator.model.v2.FlightDto;
import com.fasterxml.jackson.databind.ObjectMapper;

class CachingAviatorClientProxyTest {

    @Test
    void shouldReturnCachedFlightWithoutCallingRealClient() throws Exception {
        var realClient = mock(AviatorV2HttpClient.class);
        var redisTemplate = mock(StringRedisTemplate.class);

        var valueOperations = mock(ValueOperations.class);
        var objectMapper = new ObjectMapper();

        var cachedFlight = buildFlight("USJFKGBLHR");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("flight:USJFKGBLHR"))
                .thenReturn(objectMapper.writeValueAsString(cachedFlight));

        var proxy = new CachingAviatorClientProxy(realClient, redisTemplate, objectMapper);

        var response = proxy.getFlights("USJFKGBLHR");

        assertEquals(cachedFlight, response);
        verify(realClient, never()).getFlights(anyString());
    }

    @Test
    void shouldCacheMissThenServeHitUnderLatencyBudget() throws Exception {
        var realClient = mock(AviatorV2HttpClient.class);
        var redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        var valueOperations = mock(ValueOperations.class);
        var objectMapper = new ObjectMapper();

        Map<String, String> cache = new ConcurrentHashMap<>();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString()))
                .thenAnswer(invocation -> cache.get(invocation.getArgument(0, String.class)));
        doAnswer(
                invocation -> {
                    cache.put(
                            invocation.getArgument(0, String.class),
                            invocation.getArgument(1, String.class));
                    return null;
                })
                .when(valueOperations)
                .set(anyString(), anyString(), eq(Duration.ofMinutes(10)));

        var liveFlight = buildFlight("USJFKGBLHR");
        when(realClient.getFlights("USJFKGBLHR"))
                .thenAnswer(
                        invocation -> {
                            TimeUnit.MILLISECONDS.sleep(550);
                            return liveFlight;
                        });

        var proxy = new CachingAviatorClientProxy(realClient, redisTemplate, objectMapper);

        long firstStartNanos = System.nanoTime();
        var firstCall = proxy.getFlights("USJFKGBLHR");
        long firstElapsedMillis =
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - firstStartNanos);

        proxy.getFlights("USJFKGBLHR");
        long secondStartNanos = System.nanoTime();
        var secondCall = proxy.getFlights("USJFKGBLHR");
        long secondElapsedMillis =
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - secondStartNanos);

        assertEquals(liveFlight, firstCall);
        assertEquals(liveFlight, secondCall);
        assertTrue(firstElapsedMillis > 500, "First call should simulate slow external API");
        assertTrue(secondElapsedMillis < 20, "Second call should hit Redis cache");
        verify(realClient).getFlights("USJFKGBLHR");
        verify(valueOperations).set(anyString(), anyString(), eq(Duration.ofMinutes(10)));
    }

    private FlightDto buildFlight(String flightNumber) {
        return new FlightDto(
                flightNumber, "12345", new AirportDto("JFK", "US"), new AirportDto("LHR", "GB"));
    }
}
