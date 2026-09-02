package com.example.temperatura.converter.metrics;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class EndpointMetrics {
    private final ConcurrentHashMap<String, AtomicLong> counts = new ConcurrentHashMap<>();

    public EndpointMetrics() {
        for (String ep : new String[]{"ctof","ctok","ftoc","ftok","ktoc","ktof"}) {
            counts.put(ep, new AtomicLong(0));
        }
    }

    public void inc(String endpoint) {
        counts.computeIfAbsent(endpoint, k -> new AtomicLong()).incrementAndGet();
    }

    public long get(String endpoint) {
        return counts.getOrDefault(endpoint, new AtomicLong(0)).get();
    }

    public ConcurrentHashMap<String, AtomicLong> all() {
        return counts;
    }
}
