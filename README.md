# temperatura-converter-jee

> **Laboratório de estudos para monitoramento de aplicações** — conversor de temperatura `Celsius ↔ Fahrenheit ↔ Kelvin` em **Jakarta EE 10 (WildFly 32)** com stack de observabilidade completa. Propósito é didático: aprender na prática como instrumentar, coletar, armazenar e visualizar métricas/traces de uma aplicação JEE sem Spring Boot.

Este repo é a versão **JEE** do [`temperatura-converter-service`](https://github.com/marcosnasp/temperatura-converter-service) (Spring Boot). Mesma API, mesma dashboard Grafana, mas runtime WildFly + MicroProfile.

---

## Por que este lab existe?

- **Comparar stacks:** Spring Boot Actuator vs MicroProfile Metrics/Health/Telemetry no WildFly
- **Praticar observabilidade:** Prometheus scrape, OTel Collector, Grafana, Nginx TLS — tudo via `docker compose`
- **Entender trade-offs:** onde o WildFly expõe métricas (`:9990` vs `:8080`), como habilitar `statistics-enabled`, como adaptar dashboard Spring para WildFly (`base_*`/`wildfly_undertow_*`)
- **Base para evoluir:** adicionar tracing, alertas, balanceamento (`upstream jee_backend`) e testes de carga (`k6`)

> Não é produção. Cert autoassinado `*.lab.dev`, usuário `admin/admin123`, sem persistência externa. Ideal para estudar, quebrar e consertar — veja `implantacao/troubleshoot/`.

---

## Stack

| Camada | Tech | Porta |
|---|---|---|
| **App** | Jakarta EE 10, JAX-RS, CDI, WildFly 32, `ROOT.war` → `/temperatura` | 8080 / 9990 (management) |
| **Métricas/Health** | MicroProfile Metrics/Health (SmallRye) + Telemetry (OTel) | `/metrics`, `/health`, `/temperatura/health` |
| **Coleta** | OTel Collector Contrib 0.128 | 4317/4318 (OTLP), 8889, 13133 |
| **Armazenamento** | Prometheus 3.3 (`jee:9990/metrics` a cada 10s) | 9090 |
| **Visualização** | Grafana 12.2 (datasource Prometheus) | 3000 |
| **Proxy/TLS** | Nginx 1.27 Alpine (`*.lab.dev`, SAN `jee|grafana|prometheus|otel.lab.dev`) | 80→443 |
| **Carga** | k6 (`k6/temperatura-load.js` cobre 6 endpoints) | — |
| **Build** | Maven 3.9 + Java 21, multi-stage `Dockerfile` → `quay.io/wildfly/wildfly:32.0.1.Final-jdk21` | — |

```
                     OTLP 4318
[ JEE :8080 /temperatura ] ──► [ OTel :4317/4318 :8889 ]
         │  :9990/metrics ───────┼──► [ Prometheus :9090 ] ──► [ Grafana :3000 ]
         │                       │                              ▲
         └───────────────────────┴──────────► [ Nginx :80/443 ] ─┘
                                             jee.lab.dev → jee:8080
                                             /metrics|/health → jee:9990
                                             grafana|prometheus|otel → respectivos
```

---

## Quick start (3 passos)

```bash
# 1. hosts (senha sudo)
cd implantacao && ./scripts/add-hosts.sh
# 127.0.0.1 jee.lab.dev grafana.lab.dev prometheus.lab.dev otel.lab.dev

# 2. sobe tudo
docker compose up --build -d
docker compose ps   # jee, nginx-lb, grafana, prometheus, otel-collector = Up

# 3. teste
curl -u admin:admin123 http://localhost:8080/temperatura/converter/ctof/100  # 212.0
curl -k https://jee.lab.dev/temperatura/health                          # {"status":"UP"}
curl -k https://jee.lab.dev/metrics | head -20                         # base_*, wildfly_*
open https://grafana.lab.dev  # admin/admin → aviso cert → Avançado → Continuar
open https://prometheus.lab.dev/-/healthy
```

**Via Nginx (TLS autoassinado):**

```bash
curl -k -u admin:admin123 https://jee.lab.dev/temperatura/converter/ctok/0   # 273.15
curl --cacert implantacao/nginx/certs/lab.dev.crt -u admin:admin123 https://jee.lab.dev/temperatura/converter/ctof/100
```

Importar cert para sumir aviso: `sudo cp implantacao/nginx/certs/lab.dev.crt /usr/local/share/ca-certificates/lab.dev.crt && sudo update-ca-certificates` + reiniciar browser. Ver `implantacao/docs/nginx-https.md`.

---

## API

Base `http://localhost:8080/temperatura` ou `https://jee.lab.dev/temperatura` (Basic `admin/admin123` para `/converter/*`, `health|metrics` livre).

| Método | Path | Ex | Arquivo |
|---|---|---|---|
| GET | `/converter/ctof/{c}` | `100` → `212.0` | `TemperaturaConverterController.java:19` |
| GET | `/converter/ctok/{c}` | `0` → `273.15` | `:24` |
| GET | `/converter/ftoc/{f}` | `32` → `0.0` | `:30` |
| GET | `/converter/ftok/{f}` | `32` → `273.15` | `:37` |
| GET | `/converter/ktoc/{k}` | `273.15` → `0.0` | `:42` |
| GET | `/converter/ktof/{k}` | `273.15` → `32.0` | `:49` |
| GET | `/temperatura/health` | `{"status":"UP"}` | `HealthResource.java:10` |
| GET | `/metrics` (:9990) | Prometheus text | MP Metrics |
| GET | `/health` (:9990) | JSON | MP Health |

Detalhes: `implantacao/docs/api-referencia.md`.

---

## Observabilidade no lab

- **Métricas WildFly:** `base_memory_usedHeap_bytes`, `base_gc_time_total_seconds`, `base_cpu_processCpuLoad`, `wildfly_undertow_request_count_total` (`statistics-enabled=true` via `JAVA_OPTS` em `docker-compose.yml:18`). Dashboard original Spring (`http_server_requests_seconds_count`) adaptado em `implantacao/grafana/dashboards/temperatura-dashboard.json`.

- **Prometheus:** `implantacao/prometheus/prometheus.yml` scapeia `jee:9990`, `otel:8889`. Checar: `curl http://localhost:9090/api/v1/targets | jq`.

- **Grafana:** `https://grafana.lab.dev` — painéis Requisições/s, Latência, Erros, Up, Heap, GC, CPU/Threads. Ver `implantacao/grafana/`.

- **Traces:** `OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318`, `smallrye-opentelemetry` envia OTLP → Collector `debug` + `prometheus`.

- **Nginx:** `implantacao/nginx/nginx.conf` — `80→301 https`, `443 ssl` 4 vhosts, `upstream` para balanceamento futuro, `/metrics|/health → jee:9990`.

---

## Carga com k6

`k6/` dispara os 6 endpoints para aquecer os gráficos:

```bash
k6 run k6/temperatura-load.js
# ou docker (sem instalar)
docker run --rm -i --network implantacao_monitoring -v $PWD/k6:/scripts -e BASE_URL=http://jee:8080/temperatura grafana/k6 run /scripts/temperatura-load.js
# smoke
k6 run --vus 1 --duration 5s k6/temperatura-load.js
```

Ver `k6/README.md`. Métricas `wildfly_undertow_request_count_total` só sobem após habilitar `statistics-enabled` (já no compose).

---

## Estrutura

```
.
├── Dockerfile                         # Maven → WildFly 32
├── pom.xml                            # Jakarta EE 10, JUnit 5, war ROOT.war
├── src/main/java/.../converter/       # RestApplication, controller, config/BasicAuthFilter, health, interfaces
├── src/main/resources/META-INF/microprofile-config.properties
├── src/main/webapp/WEB-INF/beans.xml + jboss-web.xml (context-root /temperatura)
├── implantacao/
│   ├── docker-compose.yml             # jee + otel + prometheus + grafana + nginx
│   ├── nginx/nginx.conf + certs/      # TLS *.lab.dev
│   ├── otel-collector/otel-collector-config.yaml
│   ├── prometheus/prometheus.yml
│   ├── grafana/{datasources,dashboards}/
│   ├── docs/{api-referencia,arquitetura-detalhada,componentes-monitoramento,fluxo-comunicacao,guia-desenvolvedor,nginx-https}.md
│   └── troubleshoot/{README.md,04-ajustes-set-2026.md} + docs/troubleshoot/
├── k6/temperatura-load.js + README.md
└── scripts/add-user.sh                # ENTRYPOINT cria usuário Elytron
```

---

## OpenAPI + Swagger UI

Spec via MicroProfile OpenAPI 3.1.1 em `GET /openapi` (yaml) e UI em `GET /temperatura/openapi-ui` (Swagger). Habilitação: `pom.xml` + `standalone-microprofile.xml` + `@OpenAPIDefinition` + `SwaggerUIResource`.

- Spec: `curl -k https://jee.lab.dev/openapi | head`
- UI: `https://jee.lab.dev/temperatura/openapi-ui` → Try it out (Basic admin/admin123)
- Docs isolado: `implantacao/docs/openapi-swagger.md` | Geral: `implantacao/docs/README.md:18`

> Acesso direto `/openapi` baixa `yaml` (Content-Type application/yaml) — correto, use Swagger UI para ver bonito.

## Troubleshooting

Tudo catalogado em `implantacao/troubleshoot/README.md` e `implantacao/troubleshoot/04-ajustes-set-2026.md` (casos 4-6: Grafana No data, WildFly stats 0, Nginx 404) + `implantacao/docs/troubleshoot/` (01 WildFly image, 02 TLS unrecognized_name, 03 contexto 404).

Checklist:

```bash
docker compose --project-directory implantacao ps
docker compose --project-directory implantacao logs --tail=30 | grep -E "Restarting|has invalid keys|TLS handshake"
curl -s http://localhost:9990/metrics | head
curl -k https://jee.lab.dev/metrics | head
curl -s http://localhost:9090/api/v1/targets | jq '.data.activeTargets[] | {job:.labels.job,health:.health}'
```

Cert: `openssl x509 -in implantacao/nginx/certs/lab.dev.crt -noout -dates -ext subjectAltName`, gerar novo: `./implantacao/scripts/generate-certs.sh`.

---

## Build isolado

```bash
mvn clean package
docker build -t temperatura-converter-jee .
docker run -p 8080:8080 -p 9990:9990 -e APP_USERNAME=admin -e APP_PASSWORD=admin123 temperatura-converter-jee
```

---

## Licença / Créditos

Lab didático — sem licença de produção. Baseado em WildFly 32, SmallRye, OTel, Prometheus, Grafana, Nginx.
Para evolução, veja `implantacao/docs/` e `temperatura-converter-service` (variante Spring Boot).
