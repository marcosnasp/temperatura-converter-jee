# Guia Completo de Monitoramento — temperatura-converter-jee (WildFly 31)

> Público-alvo: pessoa iniciante em monitoramento. Este documento explica **o que** está sendo monitorado, **por que** importa e **como** cada configuração funciona, linha a linha. Stack: **Jakarta EE 10 / WildFly 31 + MicroProfile Metrics/Health/Telemetry**.

---

## Índice

1. [O que é monitoramento? Os 3 pilares](#1-o-que-é-monitoramento-os-3-pilares)
2. [Visão geral da arquitetura](#2-visão-geral-da-arquitetura)
3. [Ferramentas usadas e papel de cada uma](#3-ferramentas-usadas-e-papel-de-cada-uma)
4. [Configuração da aplicação (JEE / WildFly)](#4-configuração-da-aplicação-jee--wildfly)
5. [OpenTelemetry Collector — o "roteador" da telemetria](#5-opentelemetry-collector--o-roteador-da-telemetria)
6. [Prometheus — o banco de métricas](#6-prometheus--o-banco-de-métricas)
7. [Grafana — os painéis visuais](#7-grafana--os-painéis-visuais)
8. [O que exatamente está sendo monitorado? Tabela de métricas](#8-o-que-exatamente-está-sendo-monitorado-tabela-de-métricas)
9. [Dashboard explicado painel a painel (com PromQL para iniciantes)](#9-dashboard-explicado-painel-a-painel-com-promql-para-iniciantes)
10. [Tracing e correlação de logs](#10-tracing-e-correlação-de-logs)
11. [Como subir e validar tudo (passo a passo)](#11-como-subir-e-validar-tudo-passo-a-passo)
12. [Como ler os gráficos e agir](#12-como-ler-os-gráficos-e-agir)
13. [Alertas — quando ser acordado](#13-alertas--quando-ser-acordado)
14. [Troubleshooting (problemas comuns)](#14-troubleshooting-problemas-comuns)
15. [Próximos passos](#15-próximos-passos)
16. [Referência de arquivos](#16-referência-de-arquivos)
17. [Nginx + HTTPS — próximo guia](#17-nginx--https--próximo-guia)

---

## 1. O que é monitoramento? Os 3 pilares

Monitorar não é só "ver se está no ar". É responder 3 perguntas:

| Pilar | Pergunta que responde | Exemplo no projeto | Ferramenta |
|-------|-----------------------|--------------------|------------|
| **Métricas** (números ao longo do tempo) | "Está rápido/lento? Quantos erros?" | `http_server_requests_seconds_count` / `base_cpu_processCpuLoad` | MicroProfile Metrics → Prometheus → Grafana |
| **Traces** (rastro de uma requisição) | "Por onde passou a requisição? Onde demorou?" | `traceId=abc123, spanId=xyz` | MicroProfile Telemetry (SmallRye OTel) → OTel Collector |
| **Logs** (texto do que aconteceu) | "O que o código escreveu quando deu erro?" | `INFO [jee,traceId,spanId] ...` | WildFly logging + OTel Collector |

> Analogia: métricas = painel do carro (velocidade, temperatura), traces = GPS do trajeto, logs = caixa-preta que conta a história.

Este projeto implementa os 3, mesmo que traces/logs hoje vão para `debug` (console do Collector) — estrutura já pronta para plugar um backend como Tempo/Jaeger depois.

---

## 2. Visão geral da arquitetura

```
                          OTLP (4318 HTTP / 4317 gRPC)
   ┌─────────────────────┐  traces/metrics/logs   ┌──────────────────┐
   │  JEE WildFly 31     │ ──────────────────────► │  OTel Collector  │
   │  jee:8080           │                         │  :4317/:4318     │
   │  /temperatura       │                         │  :8889 /metrics  │
   │  /metrics (MP)      │                         │  :13133 health   │
   │  /health (MP)       │                         └────────┬─────────┘
   │  ROOT.war           │                                  │
   └─────────┬───────────┘                         ┌────────▼─────────┐
             │ scrape :8080                        │   Prometheus     │
             │  /metrics + /health                 │   :9090          │
             └────────────────────────────────────►│  (coleta a cada │
                                                   │   10-15s, guarda │
                                                   │   por 15 dias)   │
                                                   └────────┬─────────┘
                                                            │ PromQL
                                                   ┌────────▼─────────┐
                                                   │    Grafana       │
                                                   │    :3000         │
                                                   │  dashboards +    │
                                                   │  datasource      │
                                                   └──────────────────┘
                              ▲
                              │  TLS *.lab.dev
                     ┌────────┴─────────┐
                     │  Nginx :80/:443  │
                     │  jee.lab.dev → jee:8080
                     │  grafana → :3000
                     └──────────────────┘
```

**Fluxo em palavras:**
1. App recebe `GET /temperatura/converter/ctof/100` → MicroProfile Metrics conta + mede tempo + Telemetry cria traceId.
2. App expõe tudo em `/metrics` (formato Prometheus) e `/health`, e envia trace via OTLP para Collector (`otel-collector:4318`).
3. Collector recebe OTLP, faz `batch`, expõe métricas em `:8889`, imprime traces no log (`debug`).
4. Prometheus a cada 10s busca (`scrape`) `/metrics` do JEE e `/metrics` do Collector e guarda.
5. Grafana pergunta ao Prometheus com PromQL e desenha gráficos.
6. Nginx encerra TLS `*.lab.dev` e roteia por `Host` — único ponto HTTPS.

---

## 3. Ferramentas usadas e papel de cada uma

### WildFly 31 + Jakarta EE 10
Servidor de aplicação. Roda `ROOT.war` (sem Spring Boot). Subsystems `microprofile-metrics-smallrye`, `microprofile-health-smallrye` e `microprofile-telemetry-smallrye` já habilitados no `standalone.xml`.

### MicroProfile Metrics
Expõe `/metrics` no formato Prometheus (sem precisar `micrometer-registry-prometheus`). Labels automáticos incluem `mp.metrics.tags.app=temperatura-converter-jee`.

### MicroProfile Health
Expõe `/health` (e `/health/ready`/`live` via management `:9990/health`). Usado pelo `healthcheck` do compose.

### MicroProfile Telemetry (SmallRye OpenTelemetry)
Cria `traceId/spanId` por requisição e exporta via OTLP para `http://otel-collector:4318`. Config em `microprofile-config.properties`.

### OpenTelemetry (OTel)
Padrão aberto para traces/metrics/logs. App só envia para o Collector via protocolo **OTLP**.

### OTel Collector
Processo `otel/opentelemetry-collector-contrib:0.128.0` — "correio": `receivers` → `processors` → `exporters`. Hoje exporta para `debug` e `prometheus`.

### Prometheus
Banco de séries temporais. Faz **pull** via HTTP. Guarda `http_server_requests_seconds_count{uri="/converter/ctof/{tempCelsius}", status="200"}` etc.

### Grafana
Visualizador. Não guarda dados — só PromQL em cima do Prometheus.

---

## 4. Configuração da aplicação (JEE / WildFly)

### 4.1 `src/main/webapp/WEB-INF/jboss-web.xml`

```xml
<jboss-web>
    <context-root>/temperatura</context-root>
</jboss-web>
```

Base URL = `http://jee:8080/temperatura`. Sem isso WildFly usaria nome do war.

### 4.2 `src/main/resources/META-INF/microprofile-config.properties`

```properties
mp.metrics.tags.app=temperatura-converter-jee
otel.service.name=temperatura-converter-jee
otel.exporter.otlp.endpoint=http://otel-collector:4318
otel.exporter.otlp.protocol=http/protobuf
mp.telemetry.enabled=false   # ponytail: desativa tracing se collector off, evita timeout no boot
```

- `mp.metrics.tags.app` → label `app="temperatura-converter-jee"` em toda métrica — essencial para filtrar no Grafana (`job=temperatura-converter-jee`).
- `otel.*` → para onde enviar traces. No Docker `otel-collector:4318`, local `localhost:4318`.
- `mp.telemetry.enabled=false` evita que WildFly tente conectar no Collector durante testes/build sem Docker.

No `docker-compose.yml` o env `OTEL_EXPORTER_OTLP_ENDPOINT` sobrescreve o properties em runtime.

### 4.3 `src/main/java/com/example/temperatura/converter/RestApplication.java`

```java
@ApplicationPath("/")
public class RestApplication extends Application {}
```

Ativa JAX-RS. Sem ele, `@Path("/converter")` não é descoberto.

### 4.4 `src/main/java/com/example/temperatura/converter/config/BasicAuthFilter.java:13`

```java
@Provider @Priority(Priorities.AUTHENTICATION)
public class BasicAuthFilter implements ContainerRequestFilter {
  // libera /health e /metrics sem auth (Prometheus + healthcheck)
  if (path.equals("health") || path.equals("metrics") ...) return;
  if (!path.startsWith("converter")) return;
  // valida Authorization: Basic base64(APP_USERNAME:APP_PASSWORD)
}
```

- `/metrics` e `/health` ficam sem senha para Prometheus scrapear sem credencial.
- `/converter/**` exige Basic Auth (`admin/admin123` via `APP_USERNAME`/`APP_PASSWORD` criados em `scripts/add-user.sh` via Elytron).
- Se fechar `/metrics`, Prometheus recebe 401 e gráfico fica vazio.

### 4.5 `src/main/java/com/example/temperatura/converter/controller/TemperaturaConverterController.java:11`

JAX-RS, não Spring MVC:

```java
@Path("/converter") @Produces(APPLICATION_JSON)
public class TemperaturaConverterController {
  @Inject CalculadoraTemperatura calculadora;
  @GET @Path("/ctof/{tempCelsius}") public Double celsiusToFarenheit(@PathParam Double tempCelsius) { ... }
  // + 5 outros
}
```

Cada `GET` recebe `Double` via `@PathParam` e devolve `Double` serializado como JSON.

### 4.6 `pom.xml:22`

Apenas `jakarta.jakartaee-api:10.0.0` (`provided` — WildFly já tem) + `junit-jupiter` para testes. Sem `spring-boot-starter-*`.

### 4.7 `Dockerfile:1`

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .; RUN mvn dependency:go-offline -B
COPY src ./src; RUN mvn package -DskipTests -B
FROM quay.io/wildfly/wildfly:31.0.0.Final-jdk21
COPY --from=build /app/target/ROOT.war /opt/jboss/wildfly/standalone/deployments/
EXPOSE 8080 9990
ENTRYPOINT ["/opt/jboss/wildfly/scripts/add-user.sh"] # cria usuário Elytron + standalone.sh
```

Multi-stage: 1º compila, 2º só WildFly (~400MB). Roda como `jboss`.

---

## 5. OpenTelemetry Collector — o "roteador" da telemetria

Arquivo: `implantacao/otel-collector/otel-collector-config.yaml`

### Receivers (entradas)

```yaml
receivers:
  otlp:
    protocols:
      grpc: { endpoint: 0.0.0.0:4317 }
      http: { endpoint: 0.0.0.0:4318 }
```

gRPC mais eficiente, HTTP mais compatível. Deixamos os dois.

### Processors (tratamento)

```yaml
processors:
  batch: { timeout: 5s }
  memory_limiter: { check_interval: 1s, limit_mib: 512 }
  resourcedetection: { detectors: [env, system], system: { hostname_sources: ["os"] } }
```

`batch` junta spans a cada 5s; `memory_limiter` protege contra OOM.

### Exporters (saídas)

```yaml
exporters:
  debug: { verbosity: basic }
  prometheus: { endpoint: "0.0.0.0:8889", send_timestamps: true }
```

> Para produção: trocar `debug` por `otlp/tempo` e adicionar serviço `tempo` no compose.

### Extensions

```yaml
extensions:
  health_check: { endpoint: 0.0.0.0:13133 } # GET http://localhost:13133 → {"status":"Server available"}
  zpages: { endpoint: 0.0.0.0:55679 }
```

### Pipelines

```yaml
service:
  pipelines:
    traces:  { receivers: [otlp], processors: [memory_limiter, resourcedetection, batch], exporters: [debug] }
    metrics: { receivers: [otlp], processors: [memory_limiter, batch], exporters: [prometheus, debug] }
    logs:    { receivers: [otlp], processors: [memory_limiter, batch], exporters: [debug] }
  telemetry: { logs: { level: info } }
```

---

## 6. Prometheus — o banco de métricas

Arquivo: `implantacao/prometheus/prometheus.yml`

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s
  scrape_timeout: 10s
  external_labels: { monitor: "temperatura-converter" }

scrape_configs:
  - job_name: "temperatura-converter-jee"
    metrics_path: /metrics
    scrape_interval: 10s
    static_configs:
      - targets: ["jee:8080"]
        labels: { service: "temperatura-converter-jee", app: "jee-wildfly31" }

  - job_name: "temperatura-converter-jee-health"
    metrics_path: /health
    scrape_interval: 15s
    static_configs:
      - targets: ["jee:9990", "jee:8080"]

  - job_name: "otel-collector"
    metrics_path: /metrics
    scrape_interval: 10s
    static_configs:
      - targets: ["otel-collector:8889"]

  - job_name: "prometheus"
    static_configs:
      - targets: ["localhost:9090"]
```

**Conceitos para iniciante:**
- **Job** = grupo de alvos.
- **Target** = `host:porta` que responde texto Prometheus.
- **Scrape** = `GET http://jee:8080/metrics`.
- **Labels** = `status="200"`, `uri="/converter/ctof/{tempCelsius}"`.
- **Retenção**: 15 dias em `/prometheus` (`prometheus-data`).

> Diferença JEE vs Spring: no Spring era `/temperatura/actuator/prometheus` em `app:9001`; no JEE é `/metrics` em `jee:8080`.

---

## 7. Grafana — os painéis visuais

### Datasource — `implantacao/grafana/datasources/datasource.yml`

```yaml
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
    jsonData: { httpMethod: POST, timeInterval: 10s, queryTimeout: 60s }
```

### Dashboard provider — `implantacao/grafana/dashboards/dashboard.yml`

```yaml
providers:
  - name: "temperatura-converter"
    type: file
    options: { path: /var/lib/grafana/dashboards }
```

### Compose — serviço Grafana em `implantacao/docker-compose.yml:64`

```yaml
grafana:
  image: grafana/grafana:12.2.0
  environment:
    GF_SECURITY_ADMIN_USER: ${GRAFANA_USER:-admin}
    GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_PASSWORD:-admin}
    GF_SERVER_DOMAIN: grafana.lab.dev
    GF_SERVER_ROOT_URL: https://grafana.lab.dev/
    GF_SERVER_PROTOCOL: http   # TLS termina no Nginx
  volumes:
    - ./grafana/datasources:/etc/grafana/provisioning/datasources:ro
    - ./grafana/dashboards:/etc/grafana/provisioning/dashboards:ro
    - ./grafana/dashboards:/var/lib/grafana/dashboards:ro
```

---

## 8. O que exatamente está sendo monitorado? Tabela de métricas

### 8.1 Métricas HTTP

| Métrica | O que mede | Labels | Por que olhar? |
|---------|------------|--------|----------------|
| `http_server_requests_seconds_count` | Quantos requests (contador) | `uri`, `method`, `status`, `app` | Volume e taxa de erro |
| `http_server_requests_seconds_sum` | Soma do tempo | mesmas | `sum/count` = média |
| `http_server_requests_seconds_bucket{le="0.1"}` | Histograma ≤0.1s etc. | `le` | p95/p99 |
| `rest_request_seconds_*` / `base_cpu_processCpuLoad` | Alternativas MP/WildFly | — | WildFly pode expor `base:` prefix |

> Média = `sum / count`. Mas média engana — use **percentis**.

### 8.2 Métricas JVM / Sistema (WildFly)

| Métrica | O que mede |
|---------|------------|
| `jvm_memory_used_bytes{area="heap"}` | Heap usado |
| `jvm_memory_max_bytes` | Máximo heap |
| `jvm_gc_pause_seconds_*` | Pausas GC |
| `jvm_threads_live_threads` | Threads vivas |
| `base_cpu_processCpuLoad` / `system_cpu_usage` | CPU 0..1 |
| `process_uptime_seconds` | Uptime |

### 8.3 Infra

| Métrica | Fonte | Indica |
|---------|-------|--------|
| `up{job="temperatura-converter-jee"}` | Prometheus | 1 = scrape OK, 0 = fora do ar |
| `scrape_duration_seconds` | Prometheus | Tempo de scrape |
| `otelcol_process_memory_rss` | OTel :8888 | Memória do Collector |

### Exemplo real (`/metrics`)

```
# HELP http_server_requests_seconds_count
http_server_requests_seconds_count{app="temperatura-converter-jee",method="GET",uri="/converter/ctof/{tempCelsius}",status="200"} 42.0
http_server_requests_seconds_sum{app="temperatura-converter-jee",method="GET",uri="/converter/ctof/{tempCelsius}",status="200"} 1.23
```

---

## 9. Dashboard explicado painel a painel (com PromQL para iniciantes)

Arquivo: `implantacao/grafana/dashboards/temperatura-dashboard.json` — `job=temperatura-converter-jee`.

### Painel 1 — Requisições/s por endpoint
```
sum by (uri) (rate(http_server_requests_seconds_count{app="temperatura-converter-jee"}[1m]))
```
`rate([1m])` = por segundo; `sum by (uri)` separa por endpoint.

### Painel 2 — Latência p95 / p99
```
histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket[2m])))
```

### Painel 3 — Taxa de erro (4xx/5xx)
```
sum by (status) (rate(http_server_requests_seconds_count{status=~"4..|5.."}[1m]))
```

### Painel 4 — Up
```
up{job="temperatura-converter-jee"}
```

### Painel 5 — JVM Heap
```
jvm_memory_used_bytes{area="heap"}
```

### Painel 6 — GC pausas
```
rate(jvm_gc_pause_seconds_sum[1m])
```

### Painel 7 — CPU / Threads
```
base_cpu_processCpuLoad
jvm_threads_live_threads
```

---

## 10. Tracing e correlação de logs

1. Requisição entra → SmallRye Telemetry cria `traceId`/`spanId`.
2. Log carrega IDs (MDC).
3. WildFly envia span via OTLP para `http://otel-collector:4318/v1/traces`.
4. Collector imprime em `docker logs otel-collector` (debug).

```bash
docker logs temperatura-converter-jee | grep traceId
docker logs otel-collector | grep -A2 "TraceID"
curl -u admin:admin123 http://localhost:8080/temperatura/converter/ctof/100
docker logs temperatura-converter-jee --since 10s
```

Para persistir, troque `debug` por `otlp/tempo` e adicione `tempo` no compose.

---

## 11. Como subir e validar tudo (passo a passo)

### Pré-requisitos
- Docker + Compose v2
- Java 21 + Maven 3.9+ (para `mvn package` gerar `ROOT.war`)
- Portas livres: 80, 443, 8080, 9990, 9090, 3000, 4317, 4318

### Subir
```bash
cd implantacao
./scripts/add-hosts.sh            # 127.0.0.1 jee.lab.dev grafana.lab.dev prometheus.lab.dev otel.lab.dev
docker compose up --build -d
docker compose ps                 # nginx-lb + jee healthy
docker compose logs -f jee
```

### Validar em 30 segundos
```bash
# 1. Health (sem senha)
curl http://localhost:8080/health | jq
curl http://localhost:9990/health | jq
# esperado: {"status":"UP"}

# 2. Métricas Prometheus (sem senha)
curl -s http://localhost:8080/metrics | head -20

# 3. Tráfego real (com senha)
curl -u admin:admin123 http://localhost:8080/temperatura/converter/ctof/100  # 212.0
curl -u admin:admin123 http://localhost:8080/temperatura/converter/ctok/0    # 273.15

# 4. Métricas mudarem
curl -s http://localhost:8080/metrics | grep http_server_requests

# 5. Prometheus targets
curl -s http://localhost:9090/api/v1/targets | jq '.data.activeTargets[] | {job: .labels.job, health: .health}'

# 6. Grafana
open http://localhost:3000  # admin/admin → Dashboards → Temperatura Converter Service
open https://grafana.lab.dev  # via Nginx

# 7. OTel health
curl http://localhost:13133 | jq

# 8. Via Nginx + HTTPS
curl -k https://jee.lab.dev/health
curl -k -u admin:admin123 https://jee.lab.dev/temperatura/converter/ctof/100

# 9. Traces
docker logs otel-collector --tail 20
```

### Parar / limpar
```bash
docker compose down
docker compose down -v  # apaga volumes prometheus/grafana
```

---

## 12. Como ler os gráficos e agir

| Sintoma | Significado | O que fazer |
|---------|-------------|-------------|
| `up == 0` | App não responde | `docker ps`, `docker logs jee`, `curl /health` |
| `rate(5xx) > 0` | Erros internos | `docker logs jee`, ver `traceId` |
| `p95 > 500ms` | Lentidão | Ver GC/CPU |
| `heap` só sobe | Vazamento | Heap dump (`jmap`), revisar cache |
| `threads` sobe | Thread leak | `jstack` |
| `cpu > 0.8` | Saturado | Escalar réplicas |

**Exercício:**
```bash
for i in {1..100}; do curl -s -u admin:admin123 http://localhost:8080/temperatura/converter/ctof/$i > /dev/null; done
# veja Requisições/s subir
curl -u admin:wrong http://localhost:8080/temperatura/converter/ctof/10  # veja Taxa de erro
docker stop temperatura-converter-jee  # veja Up cair
```

---

## 13. Alertas — quando ser acordado

Exemplo para `implantacao/prometheus/prometheus.yml`:

```yaml
groups:
  - name: temperatura-jee
    rules:
      - alert: AppForaDoAr
        expr: up{job="temperatura-converter-jee"} == 0
        for: 1m
        labels: { severity: critical }
        annotations: { summary: "JEE não responde há 1min" }

      - alert: MuitosErros5xx
        expr: sum(rate(http_server_requests_seconds_count{status=~"5.."}[2m])) > 0.05
        for: 2m
        labels: { severity: critical }

      - alert: LatenciaAlta
        expr: histogram_quantile(0.95, sum by(le)(rate(http_server_requests_seconds_bucket[2m]))) > 0.5
        for: 5m
        labels: { severity: warning }

      - alert: HeapCheio
        expr: (jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}) > 0.9
        for: 5m
        labels: { severity: warning }
```

Adicione `alertmanager` no compose para enviar email/Slack.

---

## 14. Troubleshooting (problemas comuns)

| Problema | Causa | Solução |
|----------|-------|---------|
| `curl /metrics → 401` | Filter bloqueou | `BasicAuthFilter.java:21` deve liberar `path.equals("metrics")` |
| Prometheus `DOWN` `jee:8080` | Rede `monitoring` | `docker compose ps`, `docker network inspect implantacao_monitoring` |
| Grafana datasource vermelho | URL errada | Dentro do Docker é `http://prometheus:9090`, não `localhost` |
| Dashboard `No data` | Sem tráfego ou label | Gere tráfego e verifique `app="temperatura-converter-jee"` |
| `p95` vazio | Histograma desabilitado | Ver se WildFly expõe buckets — cheque `/metrics` |
| Traces não aparecem | `OTEL_EXPORTER_OTLP_ENDPOINT` errado | Deve ser `http://otel-collector:4318` no compose |
| Porta 8080 em uso | Outra app | `lsof -i :8080` |
| `ERR_NAME_NOT_RESOLVED` `jee.lab.dev` | /etc/hosts | `./scripts/add-hosts.sh` |

```bash
docker compose logs jee --tail 50
docker compose logs prometheus --tail 50
curl http://localhost:9090/api/v1/query?query=up
```

---

## 15. Próximos passos

1. **Persistir traces**: `tempo` + `otlp/tempo`
2. **Logs centralizados**: `logs → loki` + datasource Loki
3. **Alertmanager**: `prom/alertmanager` + Slack/email
4. **Segurança**: trocar `admin/admin123` via `APP_USERNAME`/`APP_PASSWORD` + `GRAFANA_PASSWORD`
5. **Retenção**: `--storage.tsdb.retention.time=30d`

---

## 16. Referência de arquivos

```
implantacao/
├── docker-compose.yml                          # jee:8080 (WildFly 31) + otel + prometheus + grafana + nginx
├── Dockerfile (na raiz)                        # multi-stage Maven → WildFly
├── nginx/nginx.conf                            # jee.lab.dev → jee:8080
├── nginx/certs/lab.dev.crt/.key                # cert autoassinado *.lab.dev
├── scripts/{add-hosts,generate-certs}.sh
├── otel-collector/otel-collector-config.yaml
├── prometheus/prometheus.yml                   # job jee:8080/metrics
├── grafana/{datasources,dashboards}/
├── docs/
│   ├── README.md                               # este guia (você está aqui)
│   ├── nginx-https.md
│   └── guia-desenvolvedor.md                   # JEE: JAX-RS, CDI, Elytron
├── troubleshoot/README.md
└── README.md                                   # quick start
src/main/resources/META-INF/microprofile-config.properties
src/main/java/.../config/BasicAuthFilter.java    # libera /metrics
pom.xml                                         # jakartaee-api 10.0.0
```

**Serviços e portas:**

| Serviço | Container | Porta host→container | URL direta | URL via Nginx (HTTPS) |
|---------|-----------|----------------------|------------|------------------------|
| JEE | temperatura-converter-jee | 8080→8080 | http://localhost:8080/temperatura | https://jee.lab.dev/temperatura |
| Prometheus | prometheus | 9090→9090 | http://localhost:9090 | https://prometheus.lab.dev |
| Grafana | grafana | 3000→3000 | http://localhost:3000 | https://grafana.lab.dev |
| OTel health | otel-collector | 13133→13133 | http://localhost:13133 | https://otel.lab.dev |
| Nginx | nginx-lb | 80→80, 443→443 | http://localhost | https://*.lab.dev |

---

## 17. Nginx + HTTPS — próximo guia

Toda a camada de balanceador, domínios `*.lab.dev`, certificado e `/etc/hosts` detalhada em:

**→ [`docs/nginx-https.md`](./nginx-https.md)**

---

*Dúvidas? Comece pelo Prometheus UI (`http://localhost:9090/graph` ou `https://prometheus.lab.dev/graph`) digitando `up` e vendo o valor ao vivo.*
