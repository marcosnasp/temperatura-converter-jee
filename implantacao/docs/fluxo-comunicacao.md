# Fluxo de Comunicação — Diagramas de Sequência

> Complementa `componentes-monitoramento.md` (o que cada peça faz) com **como conversam** — ordem, protocolo, porta e payload.
> Todos os fluxos na rede `monitoring` (bridge) exceto o primeiro hop (Browser → Nginx).

---

## Índice
1. [Legenda e convenções](#1-legenda-e-convenções)
2. [Fluxo 1 — Requisição de conversão (usuário → Nginx → JEE)](#2-fluxo-1--requisição-de-conversão-usuário--nginx--jee)
3. [Fluxo 2 — Telemetria OTLP (JEE → Collector)](#3-fluxo-2--telemetria-otlp-jee--collector)
4. [Fluxo 3 — Scrape Prometheus (Prometheus → JEE e → Collector)](#4-fluxo-3--scrape-prometheus-prometheus--jee-e--collector)
5. [Fluxo 4 — Consulta Grafana (Browser → Grafana → Prometheus)](#5-fluxo-4--consulta-grafana-browser--grafana--prometheus)
6. [Fluxo 5 — Healthchecks (compose e Nginx)](#6-fluxo-5--healthchecks-compose-e-nginx)
7. [Fluxo 6 — Acesso direto sem Nginx (fallback localhost)](#7-fluxo-6--acesso-direto-sem-nginx-fallback-localhost)
8. [Fluxo 7 — Erro 401 (sem Basic Auth)](#8-fluxo-7--erro-401-sem-basic-auth)
9. [Diagrama geral (Mermaid)](#9-diagrama-geral-mermaid)
10. [Tabela de protocolos e payloads](#10-tabela-de-protocolos-e-payloads)

---

## 1. Legenda e convenções

```
[Browser]  cliente humano (curl, navegador)
[Nginx]    nginx-lb:80/443  (TLS termination)
[JEE]      temperatura-converter-jee:8080/9990  (WildFly 32)
[Collector|Otel]  otel-collector:4317/4318/8889/13133
[Prom]     prometheus:9090
[Graf]     grafana:3000  (via Nginx grafana.lab.dev:443)

→  requisição HTTP/gRPC
⇢  resposta
OTLP = OpenTelemetry Protocol (http/protobuf em :4318, gRPC em :4317)
PromQL = linguagem de consulta do Prometheus
```

---

## 2. Fluxo 1 — Requisição de conversão (usuário → Nginx → JEE)

**Caminho feliz:** `GET https://jee.lab.dev/temperatura/converter/ctof/25` com `Authorization: Basic YWRtaW46YWRtaW4xMjM=`

```mermaid
sequenceDiagram
    participant U as Browser/curl
    participant N as Nginx :443<br/>jee.lab.dev
    participant J as JEE :8080/9990<br/>WildFly 32
    participant C as Calculadora<br/>CalculadoraTemperaturaImpl

    U->>N: GET /temperatura/converter/ctof/25<br/>Host: jee.lab.dev<br/>Authorization: Basic admin:admin123<br/>TLS 1.3 (cert *.lab.dev)
    Note over N: server_name jee.lab.dev<br/>proxy_pass http://jee:8080<br/>X-Forwarded-Proto: https
    N->>J: GET /temperatura/converter/ctof/25<br/>Host: jee.lab.dev<br/>X-Real-IP: 172.19.0.1<br/>X-Forwarded-For: ...
    Note over J: BasicAuthFilter.java:21<br/>path=converter/ctof/25<br/>libera? não (exige auth)<br/>Base64 decode → admin:admin123<br/>APP_USERNAME/APP_PASSWORD OK
    J->>J: MP Metrics: incrementa<br/>http_server_requests_seconds_count<br/>+ start timer
    J->>J: Resteasy @Path("/converter")<br/>@GET @Path("/ctof/{tempCelsius}")<br/>@Inject CalculadoraTemperatura
    J->>C: celsiusToFarenheit(25.0)<br/>(25*9/5)+32 = 77.0
    C-->>J: 77.0
    J->>J: MP Metrics: observa histogram<br/>bucket le="0.1", sum, count
    J-->>N: 200 OK<br/>Content-Type: application/json<br/>Body: 77.0
    N-->>U: 200 OK<br/>77.0 (TLS)
```

**Detalhe das conversões de protocolo:**
- `U→N`: HTTPS (TCP 443, TLS 1.2/1.3, cert `lab.dev.crt` 365 dias)
- `N→J`: HTTP plain (TCP 8080, rede `monitoring`, header `Host` preservado)
- `J→C`: chamada Java in-process (CDI `@ApplicationScoped`)

**Latência típica:** <5ms (cálculo puro, sem IO).

**Validação:**
```bash
curl -k -u admin:admin123 https://jee.lab.dev/temperatura/converter/ctof/25  # 77.0
curl -k -u admin:admin123 https://jee.lab.dev/temperatura/converter/ctof/25 -v 2>&1 | grep "< HTTP"
docker logs temperatura-converter-jee --tail 20
```

---

## 3. Fluxo 2 — Telemetria OTLP (JEE → Collector)

**Condição:** `mp.telemetry.enabled=true` (hoje `false` por padrão para não quebrar sem Collector) e `otel.exporter.otlp.endpoint=http://otel-collector:4318`

```mermaid
sequenceDiagram
    participant J as JEE :8080<br/>SmallRye OTel
    participant O as Collector :4318<br/>receivers.otlp/http
    participant P as processors<br/>batch/memory_limiter
    participant E as exporters<br/>debug + prometheus:8889

    Note over J: Requisição anterior terminou<br/>span criado: traceId=abc123, spanId=xyz, parentId
    J->>O: POST /v1/traces HTTP/1.1<br/>Host: otel-collector:4318<br/>Content-Type: application/x-protobuf<br/>Body: ResourceSpans{service.name=temperatura-converter-jee, spans=[{name="GET /converter/ctof/{tempCelsius}", duration=2ms}]}
    Note over O: receivers.otlp.http 0.0.0.0:4318
    O->>P: memory_limiter (512 MiB check)<br/>resourcedetection (env, system → host.name)
    P->>P: batch timeout 5s (acumula)
    P->>E: export
    E->>E: debug verbosity=basic<br/>logs: "TraceID=abc123 SpanID=xyz Duration=2ms"
    E->>E: prometheus endpoint 0.0.0.0:8889<br/>converte OTLP metrics → Prometheus text
    Note over E: docker logs otel-collector<br/>mostra trace
```

**Para métricas OTLP (se JEE enviar metrics via OTLP):**
```
J --POST /v1/metrics--> O --batch--> E (prometheus:8889)
                                         ↑
Prometheus depois scrapeia GET http://otel-collector:8889/metrics
```

**Validação:**
```bash
# com telemetry on (requer rebuild com mp.telemetry.enabled=true)
docker logs otel-collector --tail 20 | grep -A2 TraceID
curl -s http://localhost:8889/metrics | head
```

---

## 4. Fluxo 3 — Scrape Prometheus (Prometheus → JEE e → Collector)

**A cada `scrape_interval` (10s JEE, 10s OTel, 15s global)**

```mermaid
sequenceDiagram
    participant Pr as Prometheus :9090<br/>TSDB
    participant J as JEE :8080<br/>/metrics
    participant O as Collector :8889<br/>/metrics
    participant L as Prometheus<br/>localhost:9090

    loop a cada 10s
        Pr->>J: GET /metrics HTTP/1.1<br/>Host: jee:8080<br/>Accept: text/plain
        Note over J: BasicAuthFilter libera /metrics sem auth<br/>MP Metrics serializa texto Prometheus
        J-->>Pr: 200 text/plain<br/>http_server_requests_seconds_count{app="temperatura-converter-jee"} 42<br/>jvm_memory_used_bytes 1.2e8<br/>...
        Pr->>Pr: parse + adiciona labels<br/>job="temperatura-converter-jee"<br/>instance="jee:8080"<br/>guarda em /prometheus (TSDB)
    end
    loop a cada 10s
        Pr->>O: GET /metrics HTTP/1.1<br/>Host: otel-collector:8889
        O-->>Pr: 200 text/plain<br/>otelcol_process_memory_rss 5e7<br/>...
        Pr->>Pr: guarda com job="otel-collector"
    end
    loop a cada 15s
        Pr->>J: GET /health HTTP/1.1<br/>Host: jee:8080 (e jee:9990)
        J-->>Pr: 200 application/json<br/>{"status":"UP"}
    end
    Pr->>L: GET /metrics (self)
    L-->>Pr: prometheus_build_info 1
```

**Arquivo que define:** `implantacao/prometheus/prometheus.yml:8` (ver `componentes-monitoramento.md:4`).

**Validação:**
```bash
curl -s http://localhost:9090/api/v1/targets | jq '.data.activeTargets[] | {job: .labels.job, health: .health, lastScrape: .lastScrape}'
curl -s "http://localhost:9090/api/v1/query?query=up" | jq
curl -s "http://localhost:9090/api/v1/query?query=http_server_requests_seconds_count" | jq
```

---

## 5. Fluxo 4 — Consulta Grafana (Browser → Grafana → Prometheus)

**Usuário abre `https://grafana.lab.dev` e vê dashboard `Temperatura Converter Service`**

```mermaid
sequenceDiagram
    participant U as Browser
    participant N as Nginx :443<br/>grafana.lab.dev
    participant G as Grafana :3000<br/>http internal
    participant P as Prometheus :9090<br/>PromQL

    U->>N: GET / HTTP/1.1<br/>Host: grafana.lab.dev<br/>TLS
    Note over N: server_name grafana.lab.dev<br/>proxy_pass http://grafana:3000<br/>Upgrade websocket
    N->>G: GET / HTTP/1.1<br/>Host: grafana.lab.dev<br/>X-Forwarded-Proto: https
    G-->>N: 302 /login ou 200 HTML
    N-->>U: HTML Grafana (TLS)

    Note over U: Usuário faz login admin/admin<br/>Dashboard auto-provisionado<br/>Refresh 10s → Grafana dispara queries

    loop a cada 10s (auto-refresh)
        G->>P: POST /api/v1/query_range HTTP/1.1<br/>Host: prometheus:9090<br/>Body: query=sum by(uri)(rate(http_server_requests_seconds_count{app="temperatura-converter-jee"}[1m]))
        Note over P: datasource.yml url=http://prometheus:9090<br/>access=proxy
        P->>P: avalia PromQL sobre TSDB<br/>rate([1m]) = (count_now - count_1m_ago)/60
        P-->>G: 200 JSON {data: {result: [{metric:{uri="/converter/ctof/{tempCelsius}"}, value:[ts, 0.7]}]}}
        G->>G: renderiza timeseries<br/>Requisições/s por endpoint
        G-->>N: JSON → HTML + JS
        N-->>U: gráfico atualizado
    end
```

**Outras queries do dashboard** (ver `componentes-monitoramento.md:5.3`):
- `histogram_quantile(0.95, sum by(le)(rate(http_server_requests_seconds_bucket[2m])))` → p95
- `sum by(status)(rate(http_server_requests_seconds_count{status=~"4..|5.."}[1m]))` → erros
- `up{job="temperatura-converter-jee"}` → up
- `jvm_memory_used_bytes{area="heap"}` → heap

**Validação:**
```bash
# manual via Prometheus (sem Grafana)
curl -s "http://localhost:9090/api/v1/query?query=sum%20by%20(uri)%20(rate(http_server_requests_seconds_count[1m]))" | jq

# via Grafana API
curl -k https://grafana.lab.dev/api/health --resolve grafana.lab.dev:443:127.0.0.1 | jq
curl -s http://localhost:3000/api/health | jq
```

---

## 6. Fluxo 5 — Healthchecks (compose e Nginx)

```mermaid
sequenceDiagram
    participant D as Docker Engine<br/>healthcheck
    participant J as JEE :8080
    participant N as Nginx :443<br/>/nginx-health
    participant O as Collector :13133

    loop a cada 15s (jee)
        D->>J: CMD-SHELL wget -qO- http://localhost:8080/temperatura/health<br/>|| wget -qO- http://localhost:9990/health
        J-->>D: {"status":"UP"} → healthy (retries 5)
    end
    loop a cada 15s (nginx)
        D->>N: CMD-SHELL wget --no-check-certificate -qO- https://127.0.0.1:443/nginx-health<br/>-H "Host: jee.lab.dev" | grep -q ok
        N-->>D: ok jee → healthy (retries 3)
    end
    Note over O: sem healthcheck no compose,<br/>mas expõe GET http://otel-collector:13133
    D->>O: curl http://localhost:13133 (manual)
    O-->>D: {"status":"Server available"}
```

**Config:**
- `implantacao/docker-compose.yml:24` (jee healthcheck)
- `implantacao/docker-compose.yml:106` (nginx healthcheck)
- `implantacao/nginx/nginx.conf:80` (`location /nginx-health`)

---

## 7. Fluxo 6 — Acesso direto sem Nginx (fallback localhost)

**Útil para debug sem TLS/`/etc/hosts`**

```mermaid
sequenceDiagram
    participant U as curl localhost
    participant J as JEE :8080
    participant P as Prometheus :9090
    participant G as Grafana :3000

    U->>J: GET http://localhost:8080/temperatura/converter/ctof/100<br/>Authorization: Basic ...
    J-->>U: 212.0
    U->>J: GET http://localhost:8080/metrics
    J-->>U: texto Prometheus
    U->>P: GET http://localhost:9090/graph
    P-->>U: UI Prometheus
    U->>G: GET http://localhost:3000
    G-->>U: UI Grafana
```

> Em produção, feche `ports` diretos e deixe só `80/443` do Nginx. Para lab mantivemos ambos.

---

## 8. Fluxo 7 — Erro 401 (sem Basic Auth)

```mermaid
sequenceDiagram
    participant U as curl sem auth
    participant N as Nginx :443
    participant J as JEE :8080<br/>BasicAuthFilter

    U->>N: GET /temperatura/converter/ctof/10<br/>Host: jee.lab.dev<br/>(sem Authorization)
    N->>J: GET /temperatura/converter/ctof/10
    Note over J: BasicAuthFilter.java:29<br/>auth == null → abort(401)
    J-->>N: 401 Unauthorized<br/>WWW-Authenticate: Basic realm="temperatura"<br/>Body: Unauthorized
    N-->>U: 401 Unauthorized
    Note over J: MP Metrics incrementa<br/>http_server_requests_seconds_count{status="401"}<br/>→ aparece em Taxa de erro 4xx no Grafana
```

**Fluxo de erro de métrica sem auth não ocorre:** `/metrics` é liberado no filter, então Prometheus nunca recebe 401 (se receber, dashboard fica `No data`).

---

## 9. Diagrama geral (Mermaid)

```mermaid
graph TD
    U[Browser / curl] -- "HTTPS 443<br/>Host: jee.lab.dev<br/>Basic Auth" --> N[Nginx :80→301<br/>:443 TLS *.lab.dev]
    N -- "HTTP 8080<br/>proxy_pass jee_backend" --> J[JEE WildFly :8080<br/>/temperatura<br/>/metrics /health]
    J -- "OTLP HTTP 4318<br/>http/protobuf" --> O[OTel Collector<br/>:4317 gRPC :4318 HTTP<br/>:8889 metrics :13133 health]
    J -- "HTTP 8080 /metrics<br/>scrape 10s" --> Pr[Prometheus :9090<br/>TSDB]
    O -- "HTTP 8889 /metrics<br/>scrape 10s" --> Pr
    J -- "HTTP 8080/9990 /health<br/>scrape 15s" --> Pr
    Pr -- "HTTP 9090 PromQL<br/>POST /api/v1/query_range" --> G[Grafana :3000<br/>datasource prometheus:9090<br/>dashboard temperatura-dashboard.json]
    N -- "HTTP 3000<br/>grafana.lab.dev" --> G
    N -- "HTTP 9090<br/>prometheus.lab.dev" --> Pr
    N -- "HTTP 13133<br/>otel.lab.dev" --> O
    U -- "HTTPS 443" --> N
```

---

## 10. Tabela de protocolos e payloads

| Hop | Direção | Porta host | Protocolo | Payload exemplo | Quem configura |
|-----|---------|------------|-----------|-----------------|----------------|
| Browser → Nginx | → | 443 | HTTPS/TLS 1.3 | `GET /temperatura/converter/ctof/25` + `Authorization: Basic ...` | `nginx.conf:51` + `certs/lab.dev.crt` |
| Nginx → JEE | → | 8080 (rede) | HTTP/1.1 | mesmo + `X-Real-IP`, `X-Forwarded-Proto: https` | `nginx.conf:67` `proxy_set_header` |
| JEE → Collector | → | 4318 (rede) | OTLP HTTP/protobuf | `POST /v1/traces` + `ResourceSpans{service.name=...}` | `microprofile-config.properties:3` + `compose:14` |
| Prometheus → JEE | → | 8080 | HTTP (scrape) | `GET /metrics` → `text/plain; version=0.0.4` | `prometheus.yml:9` |
| Prometheus → Collector | → | 8889 | HTTP (scrape) | `GET /metrics` → `otelcol_*` | `prometheus.yml:26` |
| Grafana → Prometheus | → | 9090 (rede) | HTTP PromQL | `POST /api/v1/query_range?query=rate(...)` → JSON | `datasource.yml:7` `url: http://prometheus:9090` |
| Browser → Grafana (via Nginx) | → | 443 | HTTPS | `GET /` → HTML + JS, `GET /api/datasources/proxy` → PromQL | `compose:71` `GF_SERVER_ROOT_URL` |
| Docker → JEE | → | — | exec wget | `wget http://localhost:8080/temperatura/health` | `compose:25` healthcheck |
| Docker → Nginx | → | — | exec wget | `wget --no-check-certificate https://127.0.0.1:443/nginx-health` | `compose:107` |

---

**Gerar tráfego e ver fluxos ao vivo:**
```bash
# terminal 1: logs
docker compose --project-directory implantacao logs -f otel-collector &
docker compose --project-directory implantacao logs -f prometheus &

# terminal 2: tráfego
for i in {1..20}; do curl -k -u admin:admin123 https://jee.lab.dev/temperatura/converter/ctof/$i --resolve jee.lab.dev:443:127.0.0.1 -s | xargs echo "→"; sleep 0.5; done

# terminal 3: ver métricas subirem
watch -n2 'curl -s http://localhost:9090/api/v1/query?query=sum(rate(http_server_requests_seconds_count[1m])) | jq .data.result[0].value[1]'
```

Ver também `componentes-monitoramento.md` (detalhe de cada bloco) e `api-referencia.md` (contrato).
