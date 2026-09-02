# Componentes de Monitoramento — Deep Dive

> Stack: **JEE WildFly 32 (MicroProfile Metrics/Health/Telemetry) + OTel Collector 0.128 + Prometheus 3.3.1 + Grafana 12.2 + Nginx 1.27** — 5 containers na rede `monitoring`.
> Pré-requisito: ler `implantacao/README.md` (quick start) e `implantacao/docs/README.md` (guia geral).

---

## Índice
1. [Mapa geral](#1-mapa-geral)
2. [JEE / WildFly 32 — origem da telemetria](#2-jee--wildfly-32--origem-da-telemetria)
3. [OTel Collector — roteador](#3-otel-collector--roteador)
4. [Prometheus — banco de métricas](#4-prometheus--banco-de-métricas)
5. [Grafana — visualização](#5-grafana--visualização)
6. [Nginx — TLS e roteamento](#6-nginx--tls-e-roteamento)
7. [Rede, volumes e DNS interno](#7-rede-volumes-e-dns-interno)
8. [Tabela de portas e URLs](#8-tabela-de-portas-e-urls)
9. [Como cada métrica nasce e morre](#9-como-cada-métrica-nasce-e-morre)
10. [Checklist de validação por componente](#10-checklist-de-validação-por-componente)

---

## 1. Mapa geral

```
                    OTLP 4318 (HTTP/protobuf)  traces/metrics/logs
  [ JEE  jee:8080 ] ──────────────────────────────────► [ OTel Collector ]
    │  /temperatura (JAX-RS)                          │  :4317 gRPC  :4318 HTTP
    │  /metrics  (MP Metrics) ──┐                      │  :8889 /metrics (prometheus exporter)
    │  /health   (MP Health)  │  scrape 10-15s         │  :13133 health_check
    │  :9990/health (mgmt)   │                        └────────┬──────────────┘
    │                         │                                 │ :8889 scrape
    │                         └───────────────┐        ┌────────▼─────────┐
    │                                         └───────►│   Prometheus     │  :9090
    │                                                  │  TSDB 15 dias    │
    │                                                  └────────┬─────────┘
    │                                                           │ PromQL HTTP
    │                                                  ┌────────▼─────────┐
    │                                                  │    Grafana       │  :3000
    │                                                  │ dashboards +     │
    │                                                  │ datasource       │
    │                                                  └────────▲─────────┘
    │                                                           │
                    TLS *.lab.dev  :443                        │ http://prometheus:9090
  [ Usuário / curl / Browser ] ────────────────► [ Nginx ] ────┘
                                                 :80→301
                                                 jee.lab.dev → jee:8080
                                                 grafana.lab.dev → grafana:3000
                                                 prometheus.lab.dev → prometheus:9090
                                                 otel.lab.dev → otel-collector:13133
```

**Analogia:**
- JEE = fábrica (produz peças + conta quantas fez)
- OTel Collector = correio (recebe cartas OTLP e redistribui)
- Prometheus = arquivo (fotocopia o quadro de produção a cada 10s e arquiva)
- Grafana = vitrine (desenha gráficos a partir do arquivo)
- Nginx = portaria (um único portão com crachá TLS que encaminha para o prédio certo)

---

## 2. JEE / WildFly 32 — origem da telemetria

### 2.1 O que é

Imagem `quay.io/wildfly/wildfly:32.0.1.Final-jdk21`. Runtime Jakarta EE 10 que roda `ROOT.war` (gerado via `mvn package`). Sem Spring Boot.

`Dockerfile:1`:
```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .; RUN mvn dependency:go-offline -B
COPY src ./src; RUN mvn package -DskipTests -B
FROM quay.io/wildfly/wildfly:32.0.1.Final-jdk21
COPY --from=build /app/target/ROOT.war /opt/jboss/wildfly/standalone/deployments/temperatura.war
COPY --chown=jboss:jboss scripts/add-user.sh /opt/jboss/wildfly/scripts/add-user.sh
EXPOSE 8080 9990
ENTRYPOINT ["/opt/jboss/wildfly/scripts/add-user.sh"] # standalone-microprofile.xml + statistics-enabled
```

`scripts/add-user.sh:4`:
```bash
USER_NAME=${APP_USERNAME:-admin}
USER_PASS=${APP_PASSWORD:-admin123}
jboss@wildfly:~> /opt/jboss/wildfly/bin/add-user.sh -a -u "$USER_NAME" -p "$USER_PASS" -g guest
exec /opt/jboss/wildfly/bin/standalone.sh -c standalone-microprofile.xml -b 0.0.0.0 -bmanagement 0.0.0.0 # + -Dwildfly.statistics-enabled=true via JAVA_OPTS
```

### 2.2 Subsystems habilitados

WildFly 32 já traz no `standalone-microprofile.xml`:
- `microprofile-metrics-smallrye` → expõe `/metrics` (`:8080` e `:9990`)
- `microprofile-health-smallrye` → expõe `/health` (e `:9990/health`)
- `microprofile-telemetry-smallrye` (SmallRye OpenTelemetry) → intercepta JAX-RS e cria spans
- `microprofile-openapi-smallrye` → expõe `/openapi` e `/openapi-ui` via `SwaggerUIResource`

### 2.3 Config — `src/main/resources/META-INF/microprofile-config.properties:1`

```properties
mp.metrics.tags.app=temperatura-converter-jee
otel.service.name=temperatura-converter-jee
otel.exporter.otlp.endpoint=http://otel-collector:4318
otel.exporter.otlp.protocol=http/protobuf
mp.telemetry.enabled=false
```

| Chave | Efeito | Override no compose |
|-------|--------|---------------------|
| `mp.metrics.tags.app` | Adiciona label `app="temperatura-converter-jee"` em **toda** métrica — usado nos dashboards (`{app="temperatura-converter-jee"}`) | — |
| `otel.service.name` | `service.name` no `Resource` OTLP — aparece como `service.name=temperatura-converter-jee` nos traces | `OTEL_SERVICE_NAME` |
| `otel.exporter.otlp.endpoint` | Para onde enviar OTLP | `OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318` (DNS da rede `monitoring`) |
| `mp.telemetry.enabled=false` | Desativa envio OTLP quando collector não existe (evita timeout de 5s no boot durante `mvn test`) | Ative com `-Dmp.telemetry.enabled=true` se quiser traces locais |

### 2.4 Endpoints expostos pelo JEE

| Path | Porta | Auth | Subsystem | Formato |
|------|-------|------|-----------|---------|
| `/metrics` | 8080 | **não** (liberado em `BasicAuthFilter.java:21`) | MP Metrics | texto Prometheus |
| `/health` | 8080 | não | MP Health | JSON `{"status":"UP","checks":[...]}` |
| `/health` | 9990 | não | Management | mesmo JSON (usado no `healthcheck` do compose) |
| `/temperatura/health` | 8080 | não | `health/HealthResource.java:10` (`@Path("/health")`) | `{"status":"UP"}` |
| `/temperatura/converter/*` | 8080 | **sim** Basic | JAX-RS | `Double` JSON |

**Por que `/metrics` sem auth?** `implantacao/prometheus/prometheus.yml:9` faz `GET http://jee:8080/metrics` sem header `Authorization`. Se o filtro exigisse senha, Prometheus receberia `401` e métricas ficariam vazias (`up=0` mas sem dados).

`src/main/java/com/example/temperatura/converter/config/BasicAuthFilter.java:13`:
```java
@Provider @Priority(AUTHENTICATION)
public class BasicAuthFilter implements ContainerRequestFilter {
  public void filter(ContainerRequestContext ctx) {
    String path = ctx.getUriInfo().getPath();
    if (path.equals("health") || path.startsWith("health/") || path.equals("metrics") ...) return;
    if (!path.startsWith("converter")) return;
    // valida Basic base64(APP_USERNAME:APP_PASSWORD)
  }
}
```

### 2.5 Métricas que o JEE emite

Exemplo real (`curl -s http://localhost:8080/metrics | grep http_server_requests`):
```
# HELP http_server_requests_seconds Number of HTTP requests
# TYPE http_server_requests_seconds histogram
http_server_requests_seconds_count{method="GET",uri="/converter/ctof/{tempCelsius}",status="200",app="temperatura-converter-jee"} 42.0
http_server_requests_seconds_sum{method="GET",uri="/converter/ctof/{tempCelsius}",status="200",app="temperatura-converter-jee"} 0.84
http_server_requests_seconds_bucket{method="GET",uri="/converter/ctof/{tempCelsius}",status="200",le="0.005"} 30.0
...
# HELP jvm_memory_used_bytes Used memory
jvm_memory_used_bytes{area="heap",id="heap"} 1.2e8
jvm_threads_live_threads 42.0
base_cpu_processCpuLoad 0.03
```

### 2.6 Variáveis de ambiente (compose `jee:12`)

```yaml
APP_USERNAME: ${APP_USERNAME:-admin}
APP_PASSWORD: ${APP_PASSWORD:-admin123}
OTEL_EXPORTER_OTLP_ENDPOINT: http://otel-collector:4318
OTEL_SERVICE_NAME: temperatura-converter-jee
JAVA_OPTS: "-Djboss.bind.address=0.0.0.0 -Djboss.bind.address.management=0.0.0.0"
```

---

## 3. OTel Collector — roteador

Imagem `otel/opentelemetry-collector-contrib:0.128.0`.

### 3.1 Arquivo: `implantacao/otel-collector/otel-collector-config.yaml:1`

```yaml
receivers:
  otlp:
    protocols:
      grpc: { endpoint: 0.0.0.0:4317 }
      http: { endpoint: 0.0.0.0:4318 }

processors:
  batch: { timeout: 5s }
  memory_limiter: { check_interval: 1s, limit_mib: 512 }
  resourcedetection: { detectors: [env, system], system: { hostname_sources: ["os"] } }

exporters:
  debug: { verbosity: basic }
  prometheus: { endpoint: "0.0.0.0:8889", send_timestamps: true }

extensions:
  health_check: { endpoint: 0.0.0.0:13133 }
  zpages: { endpoint: 0.0.0.0:55679 }

service:
  extensions: [health_check, zpages]
  pipelines:
    traces:  { receivers: [otlp], processors: [memory_limiter, resourcedetection, batch], exporters: [debug] }
    metrics: { receivers: [otlp], processors: [memory_limiter, batch], exporters: [prometheus, debug] }
    logs:    { receivers: [otlp], processors: [memory_limiter, batch], exporters: [debug] }
  telemetry: { logs: { level: info } }
```

### 3.2 Papel de cada bloco

| Bloco | O que faz | Analogia |
|-------|-----------|----------|
| **receivers.otlp** | Abre 2 portas: `4317 gRPC` (binário, eficiente) e `4318 HTTP` (compatível, usado pelo JEE `http/protobuf`) | Caixa de entrada do correio |
| **processors.batch** | Junta spans/metrics por 5s antes de exportar — reduz chamadas | Agrupa cartas antes de despachar |
| **processors.memory_limiter 512 MiB** | Se memória >512 MiB, descarta/refusa temporariamente — evita OOM | Limitador de peso da mala |
| **processors.resourcedetection** | Adiciona `host.name`, `os.type` ao Resource — útil para distinguir réplicas | Carimbo de origem |
| **exporters.debug** | Imprime no stdout (`docker logs otel-collector`) — hoje onde traces/logs vão | Fotocopiadora para debug |
| **exporters.prometheus 8889** | Expõe `/metrics` com métricas OTLP convertidas para Prometheus — scrapeado pelo Prometheus | Prateleira para o arquivista buscar |
| **extensions.health_check 13133** | `GET http://otel-collector:13133` → `{"status":"Server available"}` | Batida de porta "estou vivo" |
| **extensions.zpages 55679** | UI debug interna `http://otel-collector:55679/debug/tracez` | Raio-X interno |

### 3.3 Pipelines — o fluxo interno

```
traces:  OTLP ──► memory_limiter ──► resourcedetection ──► batch (5s) ──► debug
metrics: OTLP ──► memory_limiter ──► batch ──► prometheus:8889 + debug
logs:    OTLP ──► memory_limiter ──► batch ──► debug
```

> Para produção, troque `debug` por `otlp/tempo` (`endpoint: tempo:4317`) e adicione serviço `tempo` no compose. Ver `docs/README.md:15`.

### 3.4 Portas e health

| Porta | Protocolo | Quem usa | Dentro da rede | Fora (host) |
|-------|-----------|----------|----------------|-------------|
| 4317 | gRPC OTLP | JEE (se `otel.exporter.otlp.protocol=grpc`) | `otel-collector:4317` | `localhost:4317` |
| 4318 | HTTP OTLP | JEE atual (`http/protobuf`) | `otel-collector:4318` | `localhost:4318` |
| 8889 | HTTP Prometheus | Prometheus scrape | `otel-collector:8889/metrics` | `localhost:8889/metrics` |
| 13133 | HTTP health | Nginx `otel.lab.dev`, compose health | `otel-collector:13133` | `localhost:13133` |
| 55679 | HTTP zpages | debug humano | — | — (não exposto) |

### 3.5 Erro comum já corrigido

`telemetry:` no root com `metrics.readers.pull` quebra na imagem 0.128.0 (`has invalid keys: telemetry`). Fix: manter apenas `service.telemetry.logs.level=info`. Ver `troubleshoot/README.md:2 Caso 1`.

---

## 4. Prometheus — banco de métricas

Imagem `prom/prometheus:v3.3.1`. Banco de séries temporais que faz **pull** HTTP.

### 4.1 Arquivo: `implantacao/prometheus/prometheus.yml:1`

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
    fallback_scrape_protocol: "PrometheusText0.0.4"
    static_configs:
      - targets: ["jee:9990"]
        labels: { service: "temperatura-converter-jee", app: "jee-wildfly32" }

  - job_name: "temperatura-converter-jee-per-endpoint"
    metrics_path: /temperatura/metrics-per-endpoint
    scrape_interval: 5s
    static_configs:
      - targets: ["jee:8080"]
        labels: { service: "temperatura-converter-jee" }

  - job_name: "otel-collector"
    metrics_path: /metrics
    scrape_interval: 10s
    static_configs:
      - targets: ["otel-collector:8889"]
        labels: { service: "otel-collector" }

  - job_name: "prometheus"
    static_configs:
      - targets: ["localhost:9090"]
```

### 4.2 Conceitos para iniciante

| Termo | Significado | Exemplo |
|-------|-------------|---------|
| **scrape** | `GET http://alvo:porta/metrics` que Prometheus faz periodicamente | `GET http://jee:8080/metrics` a cada 10s |
| **job** | Grupo de alvos com mesma finalidade | `temperatura-converter-jee` |
| **target** | `host:porta` individual | `jee:8080`, `otel-collector:8889` |
| **labels** | `key="value"` que identifica a série | `uri="/converter/ctof/{tempCelsius}", status="200", app="temperatura-converter-jee"` |
| **sample** | um ponto `(timestamp, value)` | `42.0 @ 2026-08-27T12:00:00Z` |
| **TSDB** | banco em `/prometheus` (volume `prometheus-data`), retenção 15 dias por padrão | `--storage.tsdb.retention.time=15d` |

### 4.3 Como funciona o scrape

1. Prometheus lê `prometheus.yml` no boot (`--config.file=/etc/prometheus/prometheus.yml`).
2. A cada `scrape_interval`, faz `GET http://jee:8080/metrics`.
3. Servidor responde texto Prometheus (ver seção 2.5).
4. Prometheus parseia, adiciona labels `job`, `instance`, `service`, e guarda no TSDB.
5. `up{job="temperatura-converter-jee"}` = 1 se scrape OK, 0 se falhou.

### 4.4 UI e API

| URL | Uso |
|-----|-----|
| `http://localhost:9090/graph` ou `https://prometheus.lab.dev/graph` | UI — digite `up` ou `http_server_requests_seconds_count` |
| `http://localhost:9090/api/v1/targets` | JSON de health de cada target |
| `http://localhost:9090/api/v1/query?query=up` | Query instantânea |
| `http://localhost:9090/-/healthy` | Health do próprio Prometheus |

---

## 5. Grafana — visualização

Imagem `grafana/grafana:12.2.0`. Não guarda dados — só pergunta ao Prometheus via PromQL e desenha.

### 5.1 Datasource — `implantacao/grafana/datasources/datasource.yml:1`

```yaml
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090   # DNS interno da rede monitoring, não localhost!
    isDefault: true
    editable: true
    jsonData: { httpMethod: POST, timeInterval: 10s, queryTimeout: 60s }
```

> Erro comum: usar `http://localhost:9090` — dentro do container `localhost` é o próprio Grafana, não o Prometheus.

### 5.2 Dashboard provider — `implantacao/grafana/dashboards/dashboard.yml:1`

```yaml
apiVersion: 1
providers:
  - name: "temperatura-converter"
    orgId: 1
    folder: ""
    type: file
    disableDeletion: false
    editable: true
    options: { path: /var/lib/grafana/dashboards }
```

### 5.3 Dashboard — `implantacao/grafana/dashboards/temperatura-dashboard.json:1`

8 painéis provisionados automaticamente (sem import manual):

| # | Tipo | Título | PromQL | O que mostra |
|---|------|--------|--------|--------------|
| 1 | timeseries | Requisições/s por endpoint | `rate(converter_requests_total{endpoint="ctof"}[1m])` + 5 outros + `sum(rate(converter_requests_total[1m]))` | Throughput por endpoint (ctof/ctok/ftoc/ftok/ktoc/ktof) |
| 2 | timeseries | Latência p95 / p99 | `wildfly_undertow_max_request_time_seconds` / `rate(wildfly_undertow_processing_time_total_seconds[1m])` | Cauda total (WildFly sem histograma por uri) |
| 3 | timeseries | Taxa de erro (4xx/5xx) | `sum(rate(wildfly_undertow_error_count_total[1m]))` | Erros/s total |
| 4 | stat | Up | `up{job="temperatura-converter-jee"}` | 1 = OK, 0 = down |
| 5 | stat | JVM Heap usado | `base_memory_usedHeap_bytes` | Bytes heap |
| 6 | timeseries | JVM GC pausas | `rate(base_gc_time_total_seconds[1m])` | Tempo GC/s |
| 7a | timeseries | CPU | `base_cpu_processCpuLoad` | 0..1 |
| 7b | timeseries | Threads | `base_thread_count` | contagem |

Refresh `10s`, janela `now-15m → now`.

### 5.4 Config no compose — `implantacao/docker-compose.yml:64`

```yaml
grafana:
  image: grafana/grafana:12.2.0
  environment:
    GF_SECURITY_ADMIN_USER: ${GRAFANA_USER:-admin}
    GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_PASSWORD:-admin}
    GF_USERS_ALLOW_SIGN_UP: "false"
    GF_SERVER_DOMAIN: grafana.lab.dev
    GF_SERVER_ROOT_URL: https://grafana.lab.dev/
    GF_SERVER_PROTOCOL: http  # ponytail: TLS termina no Nginx, Grafana fica http
  volumes:
    - ./grafana/datasources:/etc/grafana/provisioning/datasources:ro
    - ./grafana/dashboards:/etc/grafana/provisioning/dashboards:ro
    - ./grafana/dashboards:/var/lib/grafana/dashboards:ro
    - grafana-data:/var/lib/grafana
```

> `GF_SERVER_PROTOCOL=http` é crítico — `https` faz Grafana escutar TLS e quebra `proxy_pass http://grafana:3000` (ver `troubleshoot/README.md Caso 2`).

---

## 6. Nginx — TLS e roteamento

Imagem `nginx:1.27-alpine`. Único ponto HTTPS, 4 vhosts por `Host`.

### 6.1 Arquivo: `implantacao/nginx/nginx.conf:1`

```nginx
worker_processes auto;
error_log /var/log/nginx/error.log warn;

events { worker_connections 1024; }

http {
    include /etc/nginx/mime.types;
    log_format main '$remote_addr - $remote_user [$time_local] "$request" '
                    '$status $body_bytes_sent "$http_referer" '
                    '"$http_user_agent" "$http_x_forwarded_for" '
                    'host=$host upstream=$upstream_addr rt=$request_time';
    access_log /var/log/nginx/access.log main;
    sendfile on; keepalive_timeout 65; client_max_body_size 10m;

    upstream jee_backend { server jee:8080; }
    upstream grafana_backend { server grafana:3000; }
    upstream prometheus_backend { server prometheus:9090; }
    upstream otel_backend { server otel-collector:13133; }

    server { listen 80; server_name jee.lab.dev grafana.lab.dev prometheus.lab.dev otel.lab.dev *.lab.dev;
             return 301 https://$host$request_uri; }

    server { listen 443 ssl; server_name jee.lab.dev;
             ssl_certificate /etc/nginx/certs/lab.dev.crt;
             ssl_certificate_key /etc/nginx/certs/lab.dev.key;
             ssl_protocols TLSv1.2 TLSv1.3; ssl_ciphers HIGH:!aNULL:!MD5;
             location / { proxy_pass http://jee_backend;
                          proxy_set_header Host $host;
                          proxy_set_header X-Real-IP $remote_addr;
                          proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
                          proxy_set_header X-Forwarded-Proto $scheme; }
             location /nginx-health { return 200 "ok jee\n"; } }

    server { listen 443 ssl; server_name grafana.lab.dev; ... proxy_pass http://grafana_backend;
             proxy_http_version 1.1; proxy_set_header Upgrade $http_upgrade; proxy_set_header Connection "upgrade"; }

    server { listen 443 ssl; server_name prometheus.lab.dev; ... proxy_pass http://prometheus_backend; }
    server { listen 443 ssl; server_name otel.lab.dev; ... proxy_pass http://otel_backend; }
    server { listen 443 ssl default_server; server_name _; return 404 "Unknown host\n"; }
}
```

### 6.2 Conceitos

| Termo | Significado |
|-------|-------------|
| **reverse proxy** | Recebe na 443 e encaminha para backend interno — cliente nunca fala direto com `jee:8080` |
| **TLS termination** | Nginx decripta HTTPS (com `lab.dev.crt/.key`) e fala HTTP plain com backends — por isso Grafana fica `http` interno |
| **server_name** | Critério de roteamento — lê `Host: grafana.lab.dev` e escolhe o bloco `server_name grafana.lab.dev` |
| **upstream** | Pool de servidores — hoje 1 cada, amanhã `server jee2:8080` para balancear round-robin |
| **301 redirect** | `http://*.lab.dev:80` → `https://$host$request_uri` (força HTTPS) |

### 6.3 Certificado — `implantacao/nginx/certs/lab.dev.crt`

Autoassinado 365 dias, SAN: `grafana.lab.dev, prometheus.lab.dev, jee.lab.dev, otel.lab.dev, *.lab.dev, lab.dev, 127.0.0.1`.

Gerado por `implantacao/scripts/generate-certs.sh`:
```bash
openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
  -keyout nginx/certs/lab.dev.key -out nginx/certs/lab.dev.crt \
  -subj "/CN=*.lab.dev/O=Lab Dev/C=BR" \
  -addext "subjectAltName=DNS:grafana.lab.dev,DNS:prometheus.lab.dev,DNS:jee.lab.dev,DNS:otel.lab.dev,DNS:*.lab.dev,DNS:lab.dev,IP:127.0.0.1"
```

Verificar: `openssl x509 -in nginx/certs/lab.dev.crt -noout -dates -ext subjectAltName`

### 6.4 Domínios — `/etc/hosts`

```
127.0.0.1 jee.lab.dev grafana.lab.dev prometheus.lab.dev otel.lab.dev
```
Registrado via `implantacao/scripts/add-hosts.sh`. Sem isso `curl https://grafana.lab.dev` dá `Could not resolve host`.

---

## 7. Rede, volumes e DNS interno

`implantacao/docker-compose.yml:112`:
```yaml
networks:
  monitoring: { driver: bridge }
volumes:
  prometheus-data:   # /prometheus (TSDB 15 dias)
  grafana-data:      # /var/lib/grafana (dashboards, usuários)
```

- Todos os 5 serviços na mesma rede `monitoring` — DNS interno `127.0.0.11` resolve `jee`, `prometheus`, `otel-collector`, `grafana`, `nginx-lb`.
- `jee:8080` só existe dentro da rede — fora use `localhost:8080` (mapeado via `ports`).
- `prometheus:9090` para Grafana datasource — `localhost:9090` só funciona fora do Docker.

---

## 8. Tabela de portas e URLs

| Serviço | Container | Host:Container | URL direta (http) | URL via Nginx (https) | Auth |
|---------|-----------|----------------|-------------------|-----------------------|------|
| JEE | temperatura-converter-jee | 8080:8080 | http://localhost:8080/temperatura | https://jee.lab.dev/temperatura | Basic `admin/admin123` |
| Metrics | — | — | http://localhost:8080/metrics | https://jee.lab.dev/metrics | não |
| Health | — | — | http://localhost:8080/health | https://jee.lab.dev/health | não |
| Mgmt Health | — | 9990:9990 | http://localhost:9990/health | — | não |
| OTLP HTTP | otel-collector | 4318:4318 | http://localhost:4318 | — | não |
| OTLP gRPC | otel-collector | 4317:4317 | — | — | não |
| OTel metrics | otel-collector | 8889:8889 | http://localhost:8889/metrics | — | não |
| OTel health | otel-collector | 13133:13133 | http://localhost:13133 | https://otel.lab.dev | não |
| Prometheus | prometheus | 9090:9090 | http://localhost:9090 | https://prometheus.lab.dev | não |
| Grafana | grafana | 3000:3000 | http://localhost:3000 | https://grafana.lab.dev | `admin/admin` |
| Nginx | nginx-lb | 80:80, 443:443 | http://localhost:80 | https://*.lab.dev:443 | cert `*.lab.dev` |

---

## 9. Como cada métrica nasce e morre

```
1. Browser  GET /temperatura/converter/ctof/25
2.   → Nginx 443 (TLS) → jee:8080 (HTTP)
3.   → BasicAuthFilter valida, Resteasy roteia para @Path("/ctof/{tempCelsius}")
4.   → MP Metrics incrementa http_server_requests_seconds_count{uri="/converter/ctof/{tempCelsius}",status="200"} + observa histogram bucket
5.   → (se telemetry on) cria traceId/spanId, exporta OTLP → otel-collector:4318
6.   → Resposta 77.0 JSON volta pelo mesmo caminho
7.   → OTel Collector batch 5s → debug + prometheus:8889
8.   → Prometheus (a cada 10s) GET /metrics → guarda sample no TSDB
9.   → Grafana (a cada 10s) PromQL rate(...[1m]) → desenha ponto no gráfico
10.  → Usuário vê Requisições/s subir no dashboard https://grafana.lab.dev
```

Trace morre em `debug` (hoje) — para persistir, plugar Tempo/Jaeger.

---

## 10. Checklist de validação por componente

```bash
# JEE
curl -s http://localhost:8080/health | jq .status  # UP
curl -s http://localhost:8080/metrics | grep -c http_server_requests  # >0
curl -u admin:admin123 http://localhost:8080/temperatura/converter/ctof/100  # 212.0

# OTel
curl -s http://localhost:13133 | jq .status  # Server available
curl -s http://localhost:8889/metrics | head
docker logs otel-collector --tail 20 | grep "Everything is ready"

# Prometheus
curl -s http://localhost:9090/api/v1/targets | python3 -c "import json,sys; d=json.load(sys.stdin); print([t['health'] for t in d['data']['activeTargets']])"
curl -s "http://localhost:9090/api/v1/query?query=up" | jq

# Grafana
curl -s http://localhost:3000/api/health | jq .database  # ok
curl -k https://grafana.lab.dev/api/health --resolve grafana.lab.dev:443:127.0.0.1 | jq

# Nginx
curl -k https://jee.lab.dev/health --resolve jee.lab.dev:443:127.0.0.1 | jq
curl -i http://jee.lab.dev/health 2>&1 | grep "301"  # redirect 80→443
openssl x509 -in implantacao/nginx/certs/lab.dev.crt -noout -dates
cat /etc/hosts | grep lab.dev
```

Ver também `implantacao/docs/fluxo-comunicacao.md` (diagramas de sequência) e `implantacao/docs/api-referencia.md` (contrato da API).

