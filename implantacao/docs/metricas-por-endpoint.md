# Métricas por Endpoint — `converter_requests_total` (WildFly)

> Estudo isolado. Por que o gráfico `Requisições/s por endpoint` mostrava só 1 linha e como fizemos para mostrar 7 (6 endpoints + total) no WildFly 32.

---

## 1. Problema

Grafana `temperatura-dashboard.json:15` original (Spring) usava:

```
sum by (uri) (rate(http_server_requests_seconds_count{application="..."}[1m]))
```

WildFly 32 com `standalone-microprofile.xml` expõe `wildfly_undertow_request_count_total` mas **sem label `uri`**:

```
wildfly_undertow_request_count_total{deployment="temperatura.war",servlet="RestApplication"} 319
# sem uri, sem endpoint
```

Resultado: `sum(rate(wildfly_undertow...[1m]))` dava 1 linha `req/s` total, não 6 por `/ctof`, `/ctok` etc. Mesmo habilitando `statistics-enabled=true`, continua agregado.

MP Metrics (`@Counted`) não está disponível no WildFly 32 `metrics` (usa `org.wildfly.extension.metrics`, não `smallrye-metrics`), então `@Counted` não gera métrica.

---

## 2. Solução ponytail — contador próprio sem dependência

Criamos contador em memória com `ConcurrentHashMap<String,AtomicLong>` e expomos em formato Prometheus puro.

### 2.1 `src/main/java/.../metrics/EndpointMetrics.java:1`

```java
@ApplicationScoped
public class EndpointMetrics {
  private final ConcurrentHashMap<String, AtomicLong> counts = new ConcurrentHashMap<>();
  public EndpointMetrics(){ for(String ep: new String[]{"ctof","ctok","ftoc","ftok","ktoc","ktof"}) counts.put(ep, new AtomicLong(0)); }
  public void inc(String ep){ counts.computeIfAbsent(ep, k-> new AtomicLong()).incrementAndGet(); }
  public ConcurrentHashMap<String,AtomicLong> all(){ return counts; }
}
```

Sem lib externa, sem config.

### 2.2 `.../controller/TemperaturaConverterController.java:14,22`

```java
@Inject EndpointMetrics metrics;

@GET @Path("/ctof/{tempCelsius}")
public Double celsiusToFarenheit(...) { metrics.inc("ctof"); return calculadora...; }
// repete para ctok, ftoc, ftok, ktoc, ktof
```

Cada requisição incrementa 1.

### 2.3 `.../metrics/MetricsResource.java:9`

```java
@Path("/metrics-per-endpoint")
public class MetricsResource {
  @Inject EndpointMetrics metrics;
  @GET @Produces(TEXT_PLAIN)
  public Response prometheus(){
    StringBuilder sb = new StringBuilder();
    sb.append("# HELP converter_requests_total Total requests per endpoint\n");
    sb.append("# TYPE converter_requests_total counter\n");
    metrics.all().forEach((ep,cnt) -> sb.append(String.format("converter_requests_total{endpoint=\"%s\"} %d\n", ep, cnt.get())));
    return Response.ok(sb.toString()).build();
  }
}
```

- Path: `@ApplicationPath("/")` + `@Path("/metrics-per-endpoint")` + `context-root /temperatura` → `GET /temperatura/metrics-per-endpoint`
- `BasicAuthFilter.java:21` libera `path.equals("metrics-per-endpoint")` → sem auth, Prometheus scapeia.

Exemplo de saída:

```
# HELP converter_requests_total Total requests per endpoint
# TYPE converter_requests_total counter
converter_requests_total{endpoint="ctof"} 36
converter_requests_total{endpoint="ctok"} 18
converter_requests_total{endpoint="ftoc"} 18
converter_requests_total{endpoint="ftok"} 18
converter_requests_total{endpoint="ktoc"} 18
converter_requests_total{endpoint="ktof"} 18
```

`ctof` dobra porque `k6/temperatura-load.js:44` faz `+1` request extra aleatório em `ctof`.

---

## 3. Prometheus `implantacao/prometheus/prometheus.yml:9`

Adicionado job separado (scrape 5s, mais rápido que 10s do `jee:9990`):

```yaml
- job_name: "temperatura-converter-jee-per-endpoint"
  metrics_path: /temperatura/metrics-per-endpoint
  scrape_interval: 5s
  static_configs:
    - targets: ["jee:8080"]
      labels: {service: "temperatura-converter-jee"}
```

Prometheus na rede `monitoring` resolve `jee:8080` direto (não via Nginx). Checar:

```bash
curl -s http://localhost:9090/api/v1/targets | jq '.data.activeTargets[] | select(.labels.job=="temperatura-converter-jee-per-endpoint") | .health'
# "up"
curl -s 'http://localhost:9090/api/v1/query?query=converter_requests_total' | jq
```

---

## 4. Grafana `implantacao/grafana/dashboards/temperatura-dashboard.json:15`

Antes: 1 target `sum(rate(wildfly_undertow_request_count_total[1m]))` → 1 linha.

Depois: 7 targets por endpoint:

```json
"targets": [
  {"expr": "rate(converter_requests_total{endpoint=\"ctof\"}[1m])", "legendFormat": "ctof"},
  {"expr": "rate(converter_requests_total{endpoint=\"ctok\"}[1m])", "legendFormat": "ctok"},
  {"expr": "rate(converter_requests_total{endpoint=\"ftoc\"}[1m])", "legendFormat": "ftoc"},
  {"expr": "rate(converter_requests_total{endpoint=\"ftok\"}[1m])", "legendFormat": "ftok"},
  {"expr": "rate(converter_requests_total{endpoint=\"ktoc\"}[1m])", "legendFormat": "ktoc"},
  {"expr": "rate(converter_requests_total{endpoint=\"ktof\"}[1m])", "legendFormat": "ktof"},
  {"expr": "sum(rate(converter_requests_total[1m]))", "legendFormat": "total"}
]
```

`rate([1m])` → requisições/s nos últimos 1m, por endpoint. `total` soma os 6.

---

## 5. Como validar

```bash
# 0. métricas zeradas
curl http://localhost:8080/temperatura/metrics-per-endpoint
curl -k https://jee.lab.dev/temperatura/metrics-per-endpoint

# 1. gera carga (todos 6 endpoints)
k6 run k6/temperatura-load.js
# ou
docker run --rm -i --network implantacao_monitoring -v $PWD/k6:/scripts -e BASE_URL=http://jee:8080/temperatura grafana/k6 run --vus 3 --duration 10s /scripts/temperatura-load.js

# 2. confere contadores
docker exec temperatura-converter-jee wget -qO- http://localhost:8080/temperatura/metrics-per-endpoint
# ctof 36, outros 18

# 3. Prometheus
curl -s 'http://localhost:9090/api/v1/query?query=rate(converter_requests_total[1m])' | jq

# 4. Grafana
open https://grafana.lab.dev/d/temperatura-converter
# painel Requisições/s por endpoint → 7 linhas, cada endpoint com cor, total em destaque
```

Se um endpoint não subir, seu `inc("...")` não foi chamado — cheque `TemperaturaConverterController.java:22`.

---

## 6. Trade-offs e próximos passos

| Decisão | Por que ponytail | Quando trocar |
|---|---|---|
| Contador em memória (reinicia com redeploy) | Sem DB, sem lib, 20 linhas | Se precisar persistir/history → push para Prometheus já persiste, ou usar MP Metrics + DB |
| `metrics-per-endpoint` separado do `/metrics` oficial | Evita misturar `wildfly_*` + `base_*` | Se WildFly passar a expor `http_server_requests_seconds_count` com `uri`, apagar custom e voltar a `sum by (uri)` |
| 5s scrape para este job | Ver gráfico subir rápido no lab | Em prod, voltar para 10-15s |

Próximos estudos:
- `@Timed` por endpoint para latência p95 por endpoint (hoje só `wildfly_undertow_max_request_time_seconds` total)
- `@Counted` com MP Metrics se migrar para WildFly que tenha `smallrye-metrics`
- Adicionar `status` label (2xx/4xx/5xx) incrementando `converter_requests_total{endpoint,status}`

Referências: `src/main/java/.../metrics/`, `implantacao/prometheus/prometheus.yml:9`, `implantacao/grafana/dashboards/temperatura-dashboard.json:15`, `k6/temperatura-load.js:24`.

*Última atualização: 02/09/2026 — v1.2.0.*
