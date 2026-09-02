# Arquitetura Detalhada — Sistema Completo

> Visão 360° do projeto **temperatura-converter-jee** (WildFly 32) + stack de observabilidade.
> Este doc é o **índice mestre** — cada seção aprofunda em um doc filho.

---

## Índice
1. [Resumo executivo](#1-resumo-executivo)
2. [Mapa de documentos](#2-mapa-de-documentos)
3. [Arquitetura em camadas](#3-arquitetura-em-camadas)
4. [Componentes em 30s](#4-componentes-em-30s)
5. [Fluxo de uma requisição (resumo)](#5-fluxo-de-uma-requisição-resumo)
6. [API em 30s](#6-api-em-30s)
7. [Observabilidade em 30s](#7-observabilidade-em-30s)
8. [Infra e deploy](#8-infra-e-deploy)
9. [Decisões arquiteturais (ADRs resumidos)](#9-decisões-arquiteturais-adrs-resumidos)
10. [Glossário para iniciantes](#10-glossário-para-iniciantes)
11. [Referência de arquivos e portas](#11-referência-de-arquivos-e-portas)

---

## 1. Resumo executivo

Microserviço **stateless, sem banco**, que converte temperatura entre **Celsius, Fahrenheit, Kelvin** via `GET /converter/{origem}to{destino}/{valor}` → `Double` JSON. Autenticado com Basic Auth, observado por métricas/traces/logs, exposto via HTTPS com domínios `*.lab.dev` atrás de Nginx.

**Stack:** Java 21 + Jakarta EE 10 (WildFly 32, JAX-RS/CDI) + MicroProfile Metrics/Health/Telemetry/OpenAPI (SmallRye) + OTel Collector → Prometheus → Grafana + Nginx TLS.

**Requisito não funcional:** rodar com `docker compose up --build -d` e estar monitorado em <60s.

---

## 2. Mapa de documentos

| Doc | O que cobre | Para quem |
|-----|-------------|-----------|
| **`arquitetura-detalhada.md`** (você está aqui) | visão geral, camadas, ADRs, glossário | todos — comece aqui |
| **`componentes-monitoramento.md`** | cada componente (JEE, OTel, Prometheus, Grafana, Nginx) linha a linha, portas, configs | quem vai operar/debugar |
| **`fluxo-comunicacao.md`** | 7 fluxos com diagramas Mermaid (requisição, OTLP, scrape, PromQL, healthcheck, 401) | quem quer entender ordem/protocolo |
| **`api-referencia.md`** | 6 endpoints de conversão + 2 de monitoramento, auth, curl, fórmulas, erros | quem vai integrar/consumir |
| **`README.md`** | guia de monitoramento para iniciantes (3 pilares) | iniciante em observabilidade |
| **`nginx-https.md`** | Nginx, domínios, cert autoassinado, /etc/hosts | quem vai mexer em TLS/roteamento |
| **`guia-desenvolvedor.md`** | como codar, testar, adicionar endpoint, stack | dev que vai evoluir código |

**Ordem de leitura recomendada:** `arquitetura-detalhada.md` → `api-referencia.md` → `componentes-monitoramento.md` → `fluxo-comunicacao.md` → `guia-desenvolvedor.md`.

---

## 3. Arquitetura em camadas

```
┌─────────────────────────────────────────────────────────────┐
│  Cliente (Browser / curl / App)                             │
│  https://jee.lab.dev/temperatura/converter/ctof/25          │
└──────────────────────┬──────────────────────────────────────┘
                       │ HTTPS 443 (TLS 1.3, cert *.lab.dev)
┌──────────────────────▼──────────────────────────────────────┐
│  Edge — Nginx 1.27 (nginx-lb)                               │
│  80→301  443→ jee:8080 | grafana:3000 | prometheus:9090     │
│  4 vhosts por Host, upstream jee_backend (balanceável)     │
└──────────────────────┬──────────────────────────────────────┘
                       │ HTTP 8080/3000/9090/13133 (rede monitoring)
┌──────────────────────▼──────────────────────────────────────┐
│  App — JEE WildFly 32 (temperatura-converter-jee:8080)     │
│  JAX-RS /converter/* (BasicAuthFilter) + /metrics + /health│
│  CDI CalculadoraTemperaturaImpl (6 fórmulas puras)         │
│  MP Metrics (histogram) + MP Health + MP Telemetry (OTLP)  │
└──────────┬───────────────────────┬──────────────────────────┘
           │ OTLP 4318             │ scrape 10s
┌──────────▼──────────┐  ┌─────────▼──────────┐
│ OTel Collector      │  │ Prometheus :9090   │
│ receivers.otlp      │  │ scrape jee:8080    │
│ processors batch    │◄─┤  otel:8889         │
│ exporters debug/    │  │ TSDB 15d           │
│ prometheus:8889     │  └─────────┬──────────┘
│ health :13133       │            │ PromQL
└─────────────────────┘  ┌─────────▼──────────┐
                         │ Grafana :3000      │
                         │ datasource prom:9090│
                         │ dashboard 8 painéis │
                         └─────────────────────┘
```

**Camadas:**
1. **Edge** — Nginx (único ponto TLS, roteamento por Host, logs `main` com `rt`).
2. **App** — WildFly (stateless, sem sessão, sem DB).
3. **Telemetria** — OTel Collector (roteador, sem armazenamento).
4. **Armazenamento** — Prometheus (TSDB, pull).
5. **Visualização** — Grafana (sem dados próprios).

---

## 4. Componentes em 30s

| Componente | Imagem | Porta host | URL interna (rede) | URL externa (host) | Config principal |
|------------|--------|------------|--------------------|--------------------|------------------|
| **JEE** | `quay.io/wildfly/wildfly:32.0.1.Final-jdk21` | 8080, 9990 | `jee:8080` | `http://localhost:8080` / `https://jee.lab.dev` | `microprofile-config.properties`, `BasicAuthFilter.java:21`, `jboss-web.xml:3` |
| **OTel Collector** | `otel/opentelemetry-collector-contrib:0.128.0` | 4317, 4318, 8889, 13133 | `otel-collector:4317` | `http://localhost:4318` | `otel-collector-config.yaml:1` |
| **Prometheus** | `prom/prometheus:v3.3.1` | 9090 | `prometheus:9090` | `http://localhost:9090` / `https://prometheus.lab.dev` | `prometheus.yml:1` |
| **Grafana** | `grafana/grafana:12.2.0` | 3000 | `grafana:3000` | `http://localhost:3000` / `https://grafana.lab.dev` | `datasource.yml:1`, `temperatura-dashboard.json:1` |
| **Nginx** | `nginx:1.27-alpine` | 80, 443 | `nginx-lb:80/443` | `https://*.lab.dev` | `nginx.conf:1`, `certs/lab.dev.crt` |

Detalhe completo em `componentes-monitoramento.md`.

---

## 5. Fluxo de uma requisição (resumo)

```
curl -u admin:admin123 https://jee.lab.dev/temperatura/converter/ctof/25
  → Nginx 443 (TLS) → jee:8080 (HTTP, Host preservado)
    → BasicAuthFilter valida → Resteasy @Path("/ctof/{c}") → Calculadora (25*9/5+32=77.0)
      → MP Metrics histogram + (se on) OTLP trace → Collector:4318
    ← 200 77.0 JSON
  ← 200 77.0 (TLS)
    → (10s depois) Prometheus scrape GET /metrics → TSDB
      → Grafana PromQL rate(...[1m]) → gráfico Requisições/s sobe
```

Ver `fluxo-comunicacao.md` para 7 diagramas Mermaid (feliz, erro 401, OTLP, scrape, PromQL, healthcheck).

---

## 6. API em 30s

Base `http://localhost:8080/temperatura` ou `https://jee.lab.dev/temperatura`, auth `admin/admin123`.

| Método | Path | Exemplo | Resposta | Fórmula |
|--------|------|---------|----------|---------|
| GET | `/converter/ctof/{c}` | `/ctof/100` | `212.0` | `C×9/5+32` |
| GET | `/converter/ctok/{c}` | `/ctok/0` | `273.15` | `C+273.15` |
| GET | `/converter/ftoc/{f}` | `/ftoc/32` | `0.0` | `(F-32)×5/9` |
| GET | `/converter/ftok/{f}` | `/ftok/32` | `273.15` | `(F-32)×5/9+273.15` |
| GET | `/converter/ktoc/{k}` | `/ktoc/273.15` | `0.0` | `K-273.15` |
| GET | `/converter/ktof/{k}` | `/ktof/273.15` | `32.0` | `(K-273.15)×9/5+32` |
| GET | `/health` | `/health` | `{"status":"UP"}` | — (sem auth) |
| GET | `/metrics` | `/metrics` | texto Prometheus | — (sem auth) |

Erros: `401` sem Basic, `400` se valor não é número, `404` se path errado. Detalhe em `api-referencia.md`.

---

## 7. Observabilidade em 30s

| Pilar | Ferramenta | O que mostra | Onde ver |
|-------|------------|--------------|----------|
| Métricas | MP Metrics → Prometheus → Grafana | requisições/s, p95/p99, erros 4xx/5xx, up, heap, GC, CPU | `https://grafana.lab.dev` (admin/admin) |
| Traces | MP Telemetry → OTel Collector (debug) | traceId/spanId por requisição, duração | `docker logs otel-collector \| grep TraceID` |
| Logs | WildFly logging | `INFO [traceId] ...` | `docker logs temperatura-converter-jee` |

Dashboard `temperatura-dashboard.json` (8 painéis, refresh 10s, `job=temperatura-converter-jee`): requisições/s, latência p95/p99, erros, up, heap, GC, CPU/threads.

---

## 8. Infra e deploy

### 8.1 `implantacao/docker-compose.yml:1` (85 linhas)

5 serviços + 2 volumes + 1 rede. `depends_on: [otel-collector]` (jee), `depends_on: [jee]` (prometheus), `depends_on: [prometheus]` (grafana), `depends_on: [jee,grafana,prometheus,otel-collector]` (nginx). Healthchecks em `jee` (8080/9990) e `nginx` (443/nginx-health).

### 8.2 Rede e DNS

`monitoring` bridge → `jee`, `prometheus`, `grafana`, `otel-collector`, `nginx-lb` resolvem por nome. Fora do Docker, use `localhost:8080` ou `*.lab.dev` via `/etc/hosts`.

### 8.3 Domínios e TLS

`127.0.0.1 jee.lab.dev grafana.lab.dev prometheus.lab.dev otel.lab.dev` via `scripts/add-hosts.sh`. Cert autoassinado `nginx/certs/lab.dev.crt` (SAN `*.lab.dev`, 365d, `scripts/generate-certs.sh`). `curl -k` ou `--cacert` para ignorar/confiar.

### 8.4 Subir e validar (30s)

```bash
cd implantacao
./scripts/add-hosts.sh
docker compose up --build -d
docker compose ps  # 5 Up (2 healthy)
curl http://localhost:8080/health | jq  # UP
curl -u admin:admin123 http://localhost:8080/temperatura/converter/ctof/100  # 212.0
curl -s http://localhost:9090/api/v1/targets | jq '.data.activeTargets[] | .health'  # up up up
open https://grafana.lab.dev  # admin/admin → Temperatura Converter Service
```

---

## 9. Decisões arquiteturais (ADRs resumidos)

| Decisão | Alternativa descartada | Por quê |
|---------|------------------------|---------|
| **WildFly 32 + Jakarta EE 10** (sem Spring Boot) | Spring Boot 3.x + Actuator | Requisito JEE do projeto; WildFly 32 (`standalone-microprofile.xml`) já traz MP Metrics/Health/Telemetry/OpenAPI sem `micrometer-registry-prometheus` |
| **MicroProfile Metrics** (`/metrics` texto Prometheus) | Micrometer + Spring Actuator | Nativo no WildFly, sem lib extra; formato idêntico para Prometheus |
| **OTel Collector como sidecar** (push OTLP) | App expor `/metrics` só (pull) | Desacopla telemetria; mesmo pipeline para traces/metrics/logs; pronto para Tempo/Jaeger sem mudar app |
| **Prometheus pull** | Pushgateway | Modelo padrão Prometheus; simples para lab (sem auth no `/metrics`) |
| **Grafana provisionado via `datasources/` + `dashboards/`** | Import manual via UI | Infra como código; `docker compose up` já sobe com dashboard |
| **Nginx TLS termination** (backends http) | TLS em cada backend | Um único cert `*.lab.dev`; Grafana/Prometheus/JEE ficam simples (http) |
| **`mp.telemetry.enabled=false` por padrão** | sempre on | Evita timeout de 5s no boot quando Collector não existe (ex: `mvn test`) |
| **`BasicAuthFilter` em JAX-RS** (não Elytron + `web.xml`) | `security-constraint` no `web.xml` | Libera `/metrics`/`/health` sem auth com 3 linhas; sem `jboss-web.xml` complexo |

---

## 10. Glossário para iniciantes

| Termo | Definição em 1 frase |
|-------|----------------------|
| **Jakarta EE** | Conjunto de APIs Java para apps corporativos (ex-Java EE) — JAX-RS (REST), CDI (injeção), etc. |
| **WildFly** | Servidor que roda apps Jakarta EE (como Tomcat, mas com mais subsistemas) |
| **JAX-RS** | API Jakarta para REST (`@Path`, `@GET`, `@PathParam`) — equivalente a Spring MVC |
| **CDI** | Injeção de dependência Jakarta (`@Inject`, `@ApplicationScoped`) — equivalente a Spring `@Autowired` |
| **MicroProfile** | Extensões para microserviços (Metrics, Health, Config, Telemetry) em cima de Jakarta EE |
| **OTLP** | Protocolo OpenTelemetry para enviar traces/metrics/logs ao Collector |
| **Collector** | Processo que recebe OTLP, processa (batch) e exporta (debug, prometheus) |
| **Prometheus** | Banco que busca (`scrape`) `/metrics` periodicamente e guarda séries temporais |
| **PromQL** | Linguagem de consulta do Prometheus (`rate(http_server_requests_seconds_count[1m])`) |
| **Grafana** | UI que desenha gráficos a partir de PromQL sobre Prometheus |
| **Nginx** | Servidor que aqui faz reverse proxy + TLS (portaria) |
| **TLS** | Criptografia HTTPS (cert + chave) — sem ela senha viaja em texto puro |
| **SAN** | Subject Alternative Name — lista de domínios que um cert vale (`*.lab.dev`) |
| **/etc/hosts** | Arquivo que mapeia `127.0.0.1 → jee.lab.dev` antes do DNS |

---

## 11. Referência de arquivos e portas

```
temperatura-converter-jee/
├── pom.xml                                         # java 21, jakartaee-api 10.0.0 (provided), war ROOT
├── Dockerfile                                      # multi-stage Maven → WildFly 32
├── scripts/add-user.sh                             # Elytron add-user.sh -a
├── src/main/
│   ├── java/.../RestApplication.java               # @ApplicationPath("/")
│   ├── java/.../controller/TemperaturaConverterController.java # 6 @GET
│   ├── java/.../interfaces/CalculadoraTemperatura.java
│   ├── java/.../interfaces/impl/CalculadoraTemperaturaImpl.java
│   ├── java/.../config/BasicAuthFilter.java        # libera /metrics /health
│   ├── java/.../health/HealthResource.java         # /temperatura/health
│   ├── resources/META-INF/microprofile-config.properties
│   └── webapp/WEB-INF/{jboss-web.xml,beans.xml}   # context-root /temperatura, CDI all
├── implantacao/
│   ├── docker-compose.yml                          # 5 serviços, rede monitoring, volumes
│   ├── .env.example / env.example                  # APP_USERNAME, GRAFANA_USER, OTEL_*
│   ├── nginx/{nginx.conf,certs/lab.dev.crt/.key,README.md}
│   ├── otel-collector/otel-collector-config.yaml
│   ├── prometheus/prometheus.yml
│   ├── grafana/{datasources/datasource.yml,dashboards/{dashboard.yml,temperatura-dashboard.json}}
│   ├── scripts/{add-hosts.sh,generate-certs.sh}
│   ├── docs/
│   │   ├── arquitetura-detalhada.md                # este arquivo (índice mestre)
│   │   ├── componentes-monitoramento.md            # deep dive por componente
│   │   ├── fluxo-comunicacao.md                    # 7 fluxos com Mermaid
│   │   ├── api-referencia.md                       # contrato da API
│   │   ├── README.md                               # guia 3 pilares (iniciantes)
│   │   ├── nginx-https.md                          # guia Nginx/TLS
│   │   └── guia-desenvolvedor.md                   # guia dev (JAX-RS/CDI)
│   ├── troubleshoot/README.md                      # 3 casos reais + catálogo
│   └── README.md                                   # quick start (30s)
```

**Portas finais:**

| Via | URL | Porta host |
|-----|-----|------------|
| Nginx (recomendado) | `https://jee.lab.dev/temperatura/...` | 443 |
| Nginx | `https://grafana.lab.dev` | 443 |
| Nginx | `https://prometheus.lab.dev` | 443 |
| Nginx | `https://otel.lab.dev` | 443 |
| Direto | `http://localhost:8080` | 8080 |
| Direto | `http://localhost:3000` | 3000 |
| Direto | `http://localhost:9090` | 9090 |
| Direto | `http://localhost:4318` (OTLP) | 4318 |

---

*Próximo passo recomendado: abra `api-referencia.md` para testar a API, depois `componentes-monitoramento.md` para entender cada métrica e `fluxo-comunicacao.md` para ver a ordem das chamadas.*
