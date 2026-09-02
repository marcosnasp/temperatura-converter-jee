# Ajustes Set/2026 — Grafana vazio, WildFly stats e Nginx 404

> Complemento aos casos 1-3 do `README.md`. Documenta 3 correções aplicadas em 02/09/2026 após rodar `k6` e validar `https://jee.lab.dev/metrics`.

---

## Caso 4 — Grafana só mostra `Up`, resto `No data` mesmo com tráfego `k6`

**Sintoma:**

```
Grafana https://grafana.lab.dev/d/temperatura-converter → painéis:
  Up = 1 (ok), JVM Heap/GC = ok, mas
  Requisições/s por endpoint = No data
  Latência p95/p99 = No data
  Taxa de erro = No data
k6 run k6/temperatura-load.js → 315 reqs p95 6ms fails 0, mas Grafana não mexe
Prometheus Targets → jee:9990 health=up
```

**Causa:**

`implantacao/grafana/dashboards/temperatura-dashboard.json:17,30,47,72,85,97` ainda usava métricas **Spring Boot Micrometer**:

```
http_server_requests_seconds_count{application="temperatura-converter-jee"}
jvm_memory_used_bytes{application="..."}
jvm_gc_pause_seconds_sum
system_cpu_usage
```

No WildFly 32 (`quay.io/wildfly/wildfly:32.0.1.Final-jdk21`) as métricas são **MicroProfile / WildFly**:

```
base_memory_usedHeap_bytes
base_gc_time_total_seconds
base_cpu_processCpuLoad / base_thread_count
wildfly_undertow_request_count_total
```

`prometheus.yml:14` scrapia `jee:9990/metrics` corretamente, mas as queries do dashboard nunca casavam → só `up{job="..."}` retornava.

Também `prometheus.yml:15` rotulava `app="jee-wildfly31"` enquanto dashboard filtrava `application="temperatura-converter-jee"` → mismatch.

**Diagnóstico:**

```bash
docker exec temperatura-converter-jee wget -qO- http://localhost:9990/metrics | grep -E "http_server|jvm_memory_used"
# vazio — não existe no WildFly
docker exec temperatura-converter-jee wget -qO- http://localhost:9990/metrics | grep base_memory_usedHeap
# base_memory_usedHeap_bytes 91151048 — existe
curl -s http://localhost:9090/api/v1/query?query=base_memory_usedHeap_bytes | jq .data.result[0].value
# 91151048 — Prometheus tem
curl -s 'http://localhost:9090/api/v1/query?query=http_server_requests_seconds_count' | jq .data.result
# [] — vazio
cat implantacao/grafana/dashboards/temperatura-dashboard.json | grep application
```

**Correção aplicada:**

`implantacao/grafana/dashboards/temperatura-dashboard.json:17-101` (commit 02/09/2026):

```diff
- sum by (uri) (rate(http_server_requests_seconds_count{application="temperatura-converter-jee"}[1m]))
+ sum(rate(wildfly_undertow_request_count_total{job="temperatura-converter-jee"}[1m]))

- histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{...}[2m])))
+ wildfly_undertow_max_request_time_seconds{job="temperatura-converter-jee"}

- sum by (status) (rate(http_server_requests_seconds_count{status=~"4..|5.."}[1m]))
+ sum(rate(wildfly_undertow_error_count_total{job="temperatura-converter-jee"}[1m]))

- jvm_memory_used_bytes{application="...",area="heap"}
+ base_memory_usedHeap_bytes{job="temperatura-converter-jee"}

- rate(jvm_gc_pause_seconds_sum{...}[1m])
+ rate(base_gc_time_total_seconds{job="temperatura-converter-jee"}[1m])

- system_cpu_usage / jvm_threads_live_threads
+ base_cpu_processCpuLoad / base_thread_count
```

**Validação:**

```bash
docker compose -f implantacao/docker-compose.yml restart grafana; sleep 10
curl -k https://grafana.lab.dev/api/health | jq
# Grafana 200, painéis Heap/CPU/GC agora mostram curvas após k6
curl -s 'http://localhost:9090/api/v1/query?query=base_memory_usedHeap_bytes' | jq
```

**Prevenção / Nota:**

WildFly `wildfly_undertow_request_count_total` **não tem label `uri`** — só `deployment`, `servlet`. O gráfico `Requisições/s` mostra total, não por `/ctof`/`/ctok`. Para voltar a ter por endpoint, anotar `controller/TemperaturaConverterController.java:19` com `@Counted(name="converter_ctof_total")` (MP Metrics) ou migrar para `microprofile-metrics` REST counters.

**Arquivos:** `implantacao/grafana/dashboards/temperatura-dashboard.json:1-107`

---

## Caso 5 — `wildfly_undertow_request_count_total` sempre `0` mesmo após `k6`

**Sintoma:**

```
docker exec temperatura-converter-jee wget -qO- http://localhost:9990/metrics | grep wildfly_undertow_request_count_total
# 0.0 para http_listener e deployment mesmo após 10 reqs via curl/k6
k6 run k6/temperatura-load.js → 315 reqs, mas Prometheus:
curl -s 'http://localhost:9090/api/v1/query?query=wildfly_undertow_request_count_total' | jq .data.result[].value
# ["319"?] antes: ["0"]
Grafana Requisições/s continua 0
```

**Causa:**

`standalone.xml:468` (`urn:jboss:domain:undertow:14.0`):

```xml
<subsystem xmlns="urn:jboss:domain:undertow:14.0" statistics-enabled="${wildfly.statistics-enabled:false}">
```

Default `false`. `implantacao/docker-compose.yml:18` tinha:

```
JAVA_OPTS: "-Djboss.bind.address=0.0.0.0 -Djboss.bind.address.management=0.0.0.0"
```

Sem `-Dwildfly.statistics-enabled=true`, Undertow não coleta `request-count`, `processing-time`, etc. `base_*` (JVM) funciona, mas `wildfly_undertow_*` fica zero.

**Diagnóstico:**

```bash
docker exec temperatura-converter-jee cat /opt/jboss/wildfly/standalone/configuration/standalone.xml | grep statistics-enabled
# undertow statistics-enabled=${wildfly.statistics-enabled:false}
docker exec temperatura-converter-jee /opt/jboss/wildfly/bin/jboss-cli.sh --connect --commands="ls /subsystem=undertow" | grep statistics
# statistics-enabled=false
for i in {1..10}; do docker exec temperatura-converter-jee wget -qO- http://localhost:8080/temperatura/converter/ctof/$i --header="Authorization: Basic YWRtaW46YWRtaW4xMjM=" > /dev/null; done
docker exec temperatura-converter-jee wget -qO- http://localhost:9990/metrics | grep wildfly_undertow_request_count_total
# 0.0
```

**Correção aplicada:**

1) Persistente — `implantacao/docker-compose.yml:18`:

```diff
- JAVA_OPTS: "-Djboss.bind.address=0.0.0.0 -Djboss.bind.address.management=0.0.0.0"
+ JAVA_OPTS: "-Djboss.bind.address=0.0.0.0 -Djboss.bind.address.management=0.0.0.0 -Dwildfly.statistics-enabled=true"
```

2) Imediata (sem rebuild) — CLI volátil:

```bash
docker exec temperatura-converter-jee /opt/jboss/wildfly/bin/jboss-cli.sh --connect --commands="/subsystem=undertow:write-attribute(name=statistics-enabled,value=true)"
docker exec temperatura-converter-jee /opt/jboss/wildfly/bin/jboss-cli.sh --connect ":reload"
sleep 10
```

**Validação:**

```bash
docker exec temperatura-converter-jee wget -qO- http://localhost:9990/metrics | grep wildfly_undertow_request_count_total
# 2.0 após reload, 319.0 após k6
curl -s 'http://localhost:9090/api/v1/query?query=wildfly_undertow_request_count_total' | jq
# 319

# após persistir:
docker compose -f implantacao/docker-compose.yml up -d --force-recreate jee; sleep 15
docker exec temperatura-converter-jee wget -qO- http://localhost:9990/metrics | grep statistics-enabled
docker run --rm -i --network implantacao_monitoring -v $PWD/k6:/scripts -e BASE_URL=http://jee:8080/temperatura grafana/k6 run --vus 5 --duration 15s /scripts/temperatura-load.js
# Grafana Requisições/s agora sobe
```

**Prevenção:** Sempre que o dashboard depender de `wildfly_undertow_*`, garantir `wildfly.statistics-enabled=true` no compose. Validar com `jboss-cli ls /subsystem=undertow | grep statistics`.

**Arquivos:** `implantacao/docker-compose.yml:18`, `scripts/add-user.sh:9` (exec standalone.sh lê JAVA_OPTS)

---

## Caso 6 — `https://jee.lab.dev/metrics` e `/health` dão `404 Not Found`

**Sintoma:**

```
curl -k https://jee.lab.dev/metrics → 404 Not Found (ou vazio)
curl -k https://jee.lab.dev/health  → 404
curl -k https://jee.lab.dev/temperatura/health → {"status":"UP"} (ok)
curl http://localhost:9990/metrics | head → OK (base_*...)
curl http://localhost:8080/metrics → 404 / vazio
nginx log: GET /metrics 404 host=jee.lab.dev upstream=jee:8080
```

Doc `implantacao/docs/api-referencia.md:142` dizia `http://localhost:8080/metrics` sem auth, mas no WildFly MP Metrics/Health expõem em **porta management 9990**, não em 8080.

**Causa:**

`implantacao/nginx/nginx.conf:66-77` tinha só:

```nginx
location / { proxy_pass http://jee_backend; } # jee_backend = jee:8080
```

`/metrics` e `/health` via Nginx iam para `jee:8080` → 404. `prometheus.yml:14` scrapia `jee:9990` direto (rede `monitoring`), então Prometheus funcionava, mas navegador via `https://jee.lab.dev/metrics` não.

**Diagnóstico:**

```bash
docker exec temperatura-converter-jee wget -qO- http://localhost:8080/metrics | head  # 404
docker exec temperatura-converter-jee wget -qO- http://localhost:9990/metrics | head  # OK
curl -k https://jee.lab.dev/metrics -v 2>&1 | grep "< HTTP"
# 404
docker logs nginx-lb --tail 20 | grep metrics
# upstream=172.19.0.3:8080
cat implantacao/nginx/nginx.conf | grep -A2 "location /"
```

**Correção aplicada:**

`implantacao/nginx/nginx.conf:66-75` (antes de `location /`):

```nginx
# MP Metrics/Health estão na porta management 9990, não em 8080
location /metrics {
    proxy_pass http://jee:9990;
    proxy_set_header Host $host;
}
location /health {
    proxy_pass http://jee:9990;
    proxy_set_header Host $host;
}
```

**Validação:**

```bash
docker compose -f implantacao/docker-compose.yml up -d --force-recreate nginx; sleep 5
curl -k https://jee.lab.dev/metrics | head -20
# # HELP base_classloader_loadedClasses_total ...
curl -k https://jee.lab.dev/health | jq .[0].data
# [{"value":"running"}]
curl -k https://jee.lab.dev/temperatura/health | jq
# {"status":"UP"}
docker logs nginx-lb --tail 5 | grep metrics
# host=jee.lab.dev upstream=172.19.0.3:9990 rt=0.011
```

**Prevenção:** Manter tabela de portas atualizada (`README.md:65`, `docs/api-referencia.md:142`): `8080/temperatura/*` para app, `9990/metrics|health` para MP, e refletir no `nginx.conf`. Validar com `nginx -t` antes de subir.

**Arquivos:** `implantacao/nginx/nginx.conf:50-95`, `implantacao/docker-compose.yml:88-95` (volumes nginx)

---

## Arquivos novos nesta leva

```
k6/temperatura-load.js   # 6 endpoints com Basic auth, stages 30s/1m/30s, insecureSkipTLSVerify
k6/README.md             # como rodar nativo vs docker --network implantacao_monitoring
```

Teste k6 usado para validar todos os casos acima:

```bash
k6 run k6/temperatura-load.js
docker run --rm -i --network implantacao_monitoring -v $PWD/k6:/scripts -e BASE_URL=http://jee:8080/temperatura grafana/k6 run --vus 5 --duration 15s /scripts/temperatura-load.js
# smoke: --vus 1 --duration 5s → 21 reqs p95 5ms
```

**Referência cruzada:** `docs/troubleshoot/02-tls-unrecognized-name-https-jee.md` (hosts) e `implantacao/README.md:4` (checklist 5 comandos).

*Última atualização: 02/09/2026 — casos 4,5,6 adicionados após validação k6 + estatísticas WildFly.*
