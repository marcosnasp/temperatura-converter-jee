# k6 — Temperatura Converter (6 endpoints)

> Um script dispara os 6 endpoints para aquecer Grafana (`base_*` + `wildfly_undertow_*` após correção em `implantacao/grafana/dashboards/temperatura-dashboard.json`).

## Endpoints cobertos

`ctof, ctok, ftoc, ftok, ktoc, ktof` — `src/main/java/com/example/temperatura/converter/controller/TemperaturaConverterController.java:19`

## Rodar

```bash
# nativo (requer k6: https://k6.io/docs/get-started/installation/)
k6 run k6/temperatura-load.js
k6 run --env BASE_URL=http://localhost:8080/temperatura k6/temperatura-load.js
k6 run --env BASE_URL=https://jee.lab.dev/temperatura k6/temperatura-load.js

# via Docker (sem instalar, usa rede do compose)
docker run --rm -i --network implantacao_monitoring \
  -v $PWD/k6:/scripts -e BASE_URL=http://jee:8080/temperatura \
  grafana/k6 run /scripts/temperatura-load.js

# via Nginx TLS (host precisa /etc/hosts ou --resolve)
k6 run --env BASE_URL=https://jee.lab.dev/temperatura k6/temperatura-load.js
# self-signed já bypassado via insecureSkipTLSVerify:true

# smoke (1 VU, 10s) — sem editar script
k6 run --vus 1 --duration 10s k6/temperatura-load.js

# credenciais diferentes
k6 run --env APP_USERNAME=admin --env APP_PASSWORD=admin123 k6/temperatura-load.js
```

## O que esperar no Grafana

- `https://grafana.lab.dev` → `JVM Heap usado` (`base_memory_usedHeap_bytes`) sobe, `CPU / Threads` oscila, `JVM GC pausas` mostra picos, `Requisições/s` só >0 se `statistics-enabled=true` no WildFly (ver `troubleshoot` anterior).
- Prometheus: `curl "http://localhost:9090/api/v1/query?query=rate(base_gc_time_total_seconds[1m])"` deve retornar >0 após 30s de carga.

## Ajuste rápido

`k6/temperatura-load.js:7` `stages` — hoje `30s→10VUs →1m→10VUs →30s→0`. Para spike: `stages: [{duration:'10s',target:50},{duration:'20s',target:50}]`.
