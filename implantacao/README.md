# Implantação — Monitoramento (JEE / WildFly 32)

Stack: **Jakarta EE 10 (WildFly 32) + JAX-RS + MicroProfile Metrics/Health/Telemetry/OpenAPI + OpenTelemetry + Prometheus + Grafana + Nginx (TLS)** via Docker Compose. **Sem Spring Boot** — deploy `ROOT.war` no WildFly (`standalone-microprofile.xml`).

## Arquitetura

```
                     OTLP 4318 (MP Telemetry)
[ JEE :8080 /temperatura ] ──► [ OTel Collector :4317/4318 :8889 ]
         │  /metrics (MP Metrics)      │
         │  /health (MP Health)        │
         └─ /metrics ──────────────────┼──► [ Prometheus :9090 ] ──► [ Grafana :3000 ]
                                      │                                ▲
                                      └───────────► [ Nginx :80/443 ] ──┘
                                                     TLS *.lab.dev
                                                     jee.lab.dev → jee:8080
                                                     grafana.lab.dev → grafana:3000
                                                     prometheus.lab.dev → prometheus:9090
                                                     otel.lab.dev → otel:13133
```

- JEE expõe `/metrics` (MicroProfile Metrics, formato Prometheus) + `/health` (MP Health) e envia traces OTLP via `smallrye-opentelemetry`.
- OTel Collector recebe OTLP, exporta métricas em `:8889` e debug.
- Prometheus scapeia `jee:8080/metrics` + collector a cada 10s.
- Grafana com datasource Prometheus e dashboard (mesmo da versão Spring, adaptado para `job=temperatura-converter-jee`).
- **Nginx** TLS termination `*.lab.dev`, roteia por `Host` — único ponto HTTPS.

## Pré-requisitos

- Docker + Docker Compose v2
- Java 21 + Maven 3.9+ (para build `mvn package` do `war`)
- Portas livres: 80, 443, 8080, 9990, 9090, 3000, 4317, 4318

## 1. Registrar domínios em /etc/hosts

```bash
cd implantacao
./scripts/add-hosts.sh
# ou manual:
echo "127.0.0.1 jee.lab.dev grafana.lab.dev prometheus.lab.dev otel.lab.dev" | sudo tee -a /etc/hosts

cat /etc/hosts | grep lab.dev
getent hosts jee.lab.dev
```

> Sem isso, `https://jee.lab.dev` dá `ERR_NAME_NOT_RESOLVED`. Ver `docs/nginx-https.md`.

## 2. Subir tudo

```bash
cd implantacao
docker compose up --build -d
docker compose ps   # nginx-lb + jee devem estar healthy
docker compose logs -f jee
```

Serviços:

| Serviço | URL direta (http) | URL via Nginx (https) | Credenciais |
|---------|-------------------|------------------------|-------------|
| JEE (WildFly) | http://localhost:8080/temperatura | **https://jee.lab.dev/temperatura** | Basic `admin/admin123` |
| Metrics (MP) | http://localhost:8080/metrics | https://jee.lab.dev/metrics | sem auth |
| Health (MP) | http://localhost:8080/health | https://jee.lab.dev/health | sem auth |
| Health (management) | http://localhost:9990/health | — | — |
| Prometheus | http://localhost:9090 | **https://prometheus.lab.dev** | - |
| Grafana | http://localhost:3000 | **https://grafana.lab.dev** | `admin/admin` |
| OTel health | http://localhost:13133 | https://otel.lab.dev | - |
| OTLP | http://localhost:4318 | — | - |
| Nginx | http://localhost:80 | https://localhost:443 | cert `*.lab.dev` |

## Teste rápido

```bash
# via localhost (sem TLS) — WildFly
curl http://localhost:8080/temperatura/health
curl -u admin:admin123 http://localhost:8080/temperatura/converter/ctof/100  # 212.0
curl http://localhost:8080/metrics | head -20
curl http://localhost:8080/health | jq

# via Nginx + HTTPS (com -k para cert autoassinado)
curl -k https://jee.lab.dev/temperatura/health
curl -k -u admin:admin123 https://jee.lab.dev/temperatura/converter/ctof/100
curl -k https://jee.lab.dev/metrics | head -5
curl -k https://grafana.lab.dev/ | head -5
curl -k https://prometheus.lab.dev/-/healthy
curl -k https://otel.lab.dev/ | head

# redirect 80 → 443
curl -i http://jee.lab.dev/temperatura/health  # 301 → https://

# Prometheus targets
curl http://localhost:9090/api/v1/targets | jq
curl -k https://prometheus.lab.dev/api/v1/targets | jq

# com cert confiável (sem -k)
curl --cacert nginx/certs/lab.dev.crt https://grafana.lab.dev
```

Navegador: `https://grafana.lab.dev` → aviso → Avançado → Continuar. Para remover, importe `nginx/certs/lab.dev.crt` (ver `docs/nginx-https.md:8`).

## Dashboard Grafana

Mesmo de `temperatura-converter-service`, adaptado para `job=temperatura-converter-jee`:
- Requisições/s por endpoint (MicroProfile Metrics `http_server_requests_seconds` ou `rest_request`)
- Latência p95/p99
- Taxa erro 4xx/5xx
- Up / Heap JVM / GC (WildFly `jvm_memory_used_bytes` + `base_cpu_processCpuLoad`)

Acesse via **https://grafana.lab.dev**.

## Certificado

- `nginx/certs/lab.dev.crt` + `.key` (365 dias, SAN `*.lab.dev` cobre `jee.lab.dev`)
- Gerar novo: `./scripts/generate-certs.sh`
- `openssl x509 -in nginx/certs/lab.dev.crt -noout -dates -ext subjectAltName`

## Alertas (exemplo MP Metrics)

```yaml
groups:
  - name: temperatura-jee
    rules:
      - alert: HighErrorRate
        expr: sum(rate(http_server_requests_seconds_count{job="temperatura-converter-jee",status=~"5.."}[2m])) > 0.05
        for: 2m
        labels: {severity: critical}
```

## Configuração JEE (WildFly)

- `src/main/webapp/WEB-INF/jboss-web.xml` → `context-root /temperatura`
- `src/main/resources/META-INF/microprofile-config.properties`:
```properties
mp.metrics.tags.app=temperatura-converter-jee
mp.telemetry.propagation=none
otel.service.name=temperatura-converter-jee
otel.exporter.otlp.endpoint=http://otel-collector:4318
otel.exporter.otlp.protocol=http/protobuf
```
- `standalone.xml` habilita `microprofile-metrics-smallrye` + `microprofile-health-smallrye` + `microprofile-telemetry` (SmallRye)
- Env no compose: `OTEL_EXPORTER_OTLP_ENDPOINT`, `APP_USERNAME/PASSWORD` (via `add-user.sh` Elytron)

## Nginx

Ver `nginx/README.md` e `docs/nginx-https.md`.

Resumo `nginx.conf`:
- `listen 80` → `301 https://$host$request_uri`
- `listen 443 ssl` para `jee|grafana|prometheus|otel.lab.dev`
- `upstream jee_backend { server jee:8080; }` (adicione `jee2:8080` para balancear)

## Traces (MP Telemetry)

JEE usa `smallrye-opentelemetry` (MicroProfile Telemetry 1.1). Spans enviados via OTLP para `otel-collector:4318` → `debug`. Logs incluem `traceId/spanId` via MDC.

## Build isolado (sem Compose)

```bash
mvn clean package  # gera target/ROOT.war
docker build -t temperatura-converter-jee .
docker run -p 8080:8080 -p 9990:9990 -e APP_USERNAME=admin -e APP_PASSWORD=admin123 temperatura-converter-jee
```

Dockerfile (WildFly 32):
```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .; RUN mvn dependency:go-offline -B
COPY src ./src; RUN mvn package -DskipTests -B
FROM quay.io/wildfly/wildfly:32.0.1.Final-jdk21
COPY --from=build /app/target/ROOT.war /opt/jboss/wildfly/standalone/deployments/temperatura.war
EXPOSE 8080 9990
```

## Parar / limpar

```bash
docker compose down
docker compose down -v  # apaga volumes prometheus/grafana
```

## Arquivos

```
implantacao/
├── docker-compose.yml              # jee:8080/9990 (WildFly 32) + otel + prometheus + grafana + nginx
├── Dockerfile (na raiz)            # multi-stage Maven → WildFly
├── nginx/
│   ├── nginx.conf                  # jee.lab.dev → jee:8080
│   ├── certs/lab.dev.crt/.key
│   └── README.md
├── scripts/add-hosts.sh            # 127.0.0.1 jee.lab.dev
├── otel-collector/otel-collector-config.yaml
├── prometheus/prometheus.yml       # job jee:9990/metrics + per-endpoint 8080/temperatura/metrics-per-endpoint
├── grafana/...
├── docs/
│   ├── README.md                   # guia monitoramento (MP Metrics/Health)
│   ├── nginx-https.md
│   └── guia-desenvolvedor.md       # JEE: JAX-RS, CDI, Elytron, Arquillian
├── troubleshoot/README.md
└── README.md                       # este arquivo
```

## Documentação detalhada

- **Monitoramento (MP):** `docs/README.md`
- **Nginx + HTTPS:** `docs/nginx-https.md`
- **Guia do Desenvolvedor JEE:** `docs/guia-desenvolvedor.md`
- **Troubleshooting JEE:** `troubleshoot/README.md`
