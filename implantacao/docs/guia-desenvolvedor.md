# Guia do Desenvolvedor — temperatura-converter-jee (WildFly 32)

> Para quem vai **codar, testar e evoluir** a aplicação. Explica cada componente, requisito e fluxo, do `git clone` ao `docker compose up`. Stack: **Jakarta EE 10 / WildFly 32 + JAX-RS + CDI + MicroProfile**.

---

## Índice

1. [Visão geral em 30s](#1-visão-geral-em-30s)
2. [Requisitos](#2-requisitos)
3. [Stack e versões](#3-stack-e-versões)
4. [Estrutura de pastas](#4-estrutura-de-pastas)
5. [Componentes — o que faz cada arquivo](#5-componentes--o-que-faz-cada-arquivo)
6. [Fluxo de uma requisição](#6-fluxo-de-uma-requisição)
7. [Configuração (microprofile-config.properties + env vars)](#7-configuração-microprofile-configproperties--env-vars)
8. [Segurança](#8-segurança)
9. [Como rodar local (sem Docker)](#9-como-rodar-local-sem-docker)
10. [Como rodar com Docker / Compose](#10-como-rodar-com-docker--compose)
11. [API — referência de endpoints](#11-api--referência-de-endpoints)
12. [Testes](#12-testes)
13. [Monitoramento para devs](#13-monitoramento-para-devs)
14. [Como adicionar um novo endpoint](#14-como-adicionar-um-novo-endpoint)
15. [Boas práticas e contribuição](#15-boas-práticas-e-contribuição)
16. [Troubleshooting (dev)](#16-troubleshooting-dev)
17. [Referência de arquivos e comandos](#17-referência-de-arquivos-e-comandos)

---

## 1. Visão geral em 30s

Microserviço REST que converte temperatura entre **Celsius (C), Fahrenheit (F) e Kelvin (K)**. Stateless, sem banco. Recebe `GET /converter/{origem}to{destino}/{valor}` e devolve `Double` em JSON.

```
GET /temperatura/converter/ctof/100     → 212.0
GET /temperatura/converter/ktoc/273.15  → 0.0
```

Autenticado com **HTTP Basic** (`admin/admin123` por padrão, via `BasicAuthFilter` + `APP_USERNAME/PASSWORD`). Métricas ` /metrics` e health `/health` (MicroProfile) visualizados em **Prometheus + Grafana** atrás de **Nginx HTTPS** (`https://jee.lab.dev`).

Sem Spring Boot — deploy `ROOT.war` no WildFly 32.

---

## 2. Requisitos

### Obrigatórios

| Ferramenta | Versão mínima | Como verificar | Observação |
|------------|---------------|----------------|------------|
| **Java** | 21 (LTS) | `java -version` | `maven.compiler.source=21` em `pom.xml:16` |
| **Maven** | 3.9+ | `mvn -version` | Ou use wrapper se adicionar; hoje `mvn` direto |
| **Git** | 2.x | `git --version` | |
| **Docker** | 24+ | `docker --version` | Para build e stack completa |
| **Docker Compose** | v2 | `docker compose version` | |

### Opcionais mas recomendados

| Ferramenta | Para que |
|------------|----------|
| **curl / httpie / Postman / Bruno** | Testar endpoints autenticados |
| **jq** | Formatar JSON (`curl ... \| jq`) |
| **IDE** | IntelliJ IDEA ou VS Code com Extension Pack for Java |

### Hardware mínimo (dev)

- 2 CPU, 4 GB RAM, 2 GB disco livre (target/ + imagens ~1.5 GB)

---

## 3. Stack e versões

| Camada | Tecnologia | Versão | Onde está |
|--------|------------|--------|-----------|
| Linguagem | Java | 21 | `pom.xml:15` |
| Runtime | WildFly | 32.0.1.Final-jdk21 | `Dockerfile:8` `quay.io/wildfly/wildfly:32.0.1.Final-jdk21` |
| API | Jakarta EE | 10.0.0 | `pom.xml:17` `jakarta.jakartaee-api` (provided) |
| Web | JAX-RS 3.1 + CDI 4.0 | — | `RestApplication.java`, `controller/*`, `beans.xml` |
| Segurança | JAX-RS `ContainerRequestFilter` + Elytron `add-user.sh` | — | `config/BasicAuthFilter.java:13`, `scripts/add-user.sh` |
| Observabilidade | MicroProfile Metrics 5.1 + Health 4.0 + Telemetry 1.1 (SmallRye) | — | `microprofile-config.properties`, WildFly subsystems |
| Testes | JUnit 5.11.3 | — | `pom.xml:32`, `CalculadoraTemperaturaTest.java` |
| Build | Maven War plugin 3.4.0 | — | `pom.xml:53` `packaging war` `finalName ROOT` |
| Infra local | OTel Collector Contrib 0.128, Prometheus 3.3.1, Grafana 12.2, Nginx 1.27-alpine | — | `implantacao/docker-compose.yml` |

> **Por que WildFly 32 + Java 21?** WildFly 32 já traz MP Metrics/Health/Telemetry habilitados (via `standalone-microprofile.xml`). Java 21 é LTS e base da imagem `quay.io/wildfly`.

---

## 4. Estrutura de pastas

```
temperatura-converter-jee/
├── pom.xml                                         # jakartaee-api (provided), war ROOT
├── Dockerfile                                      # multi-stage Maven → WildFly
├── scripts/add-user.sh                             # cria usuário Elytron no boot
├── src/
│   ├── main/
│   │   ├── java/com/example/temperatura/converter/
│   │   │   ├── RestApplication.java                # @ApplicationPath("/")
│   │   │   ├── controller/
│   │   │   │   └── TemperaturaConverterController.java  # 6 @GET JAX-RS
│   │   │   ├── interfaces/
│   │   │   │   ├── CalculadoraTemperatura.java       # contrato
│   │   │   │   └── impl/CalculadoraTemperaturaImpl.java # fórmulas
│   │   │   ├── config/
│   │   │   │   └── BasicAuthFilter.java              # @Provider Basic Auth
│   │   │   └── health/
│   │   │       └── HealthResource.java               # @Path("/health") → {"status":"UP"}
│   │   ├── resources/META-INF/
│   │   │   └── microprofile-config.properties        # mp.metrics.tags, otel.*
│   │   └── webapp/WEB-INF/
│   │       ├── beans.xml                             # bean-discovery-mode all (CDI)
│   │       └── jboss-web.xml                         # context-root /temperatura
│   └── test/java/com/example/temperatura/converter/
│       └── CalculadoraTemperaturaTest.java           # 6 testes puros JUnit
├── target/                                         # gerado (ROOT.war)
├── implantacao/
│   ├── docker-compose.yml                          # jee:8080 + otel + prometheus + grafana + nginx
│   ├── nginx/nginx.conf + certs/                   # TLS *.lab.dev → jee.lab.dev
│   ├── otel-collector/otel-collector-config.yaml
│   ├── prometheus/prometheus.yml                   # job jee:8080/metrics
│   ├── grafana/{datasources,dashboards}/
│   ├── scripts/{add-hosts,generate-certs}.sh
│   ├── docs/{README,nginx-https,guia-desenvolvedor}.md
│   └── README.md                                   # quick start infra
└── README.md                                       # README raiz
```

---

## 5. Componentes — o que faz cada arquivo

### 5.1 `RestApplication.java:6`

```java
@ApplicationPath("/")
public class RestApplication extends Application {}
```

Ativa JAX-RS. Sem ele WildFly não escaneia `@Path`.

### 5.2 `interfaces/CalculadoraTemperatura.java:3`

```java
Double celsiusToFarenheit(Double c);
Double celsiusToKelvin(Double c);
Double farenheitToCelsius(Double f);
Double farenheitToKelvin(Double f);
Double kelvinToCelsius(Double k);
Double kelvinToFarenheit(Double k);
```

Contrato — facilita testar Controller mockando ou injetando impl CDI.

### 5.3 `interfaces/impl/CalculadoraTemperaturaImpl.java:5`

Fórmulas puras, sem dependência Jakarta:

| Método | Fórmula |
|--------|---------|
| `celsiusToFarenheit` | `(C × 9/5) + 32` |
| `celsiusToKelvin` | `C + 273.15` |
| `farenheitToCelsius` | `((F − 32) × 5)/9` |
| `farenheitToKelvin` | `((F − 32) × 5)/9 + 273.15` |
| `kelvinToCelsius` | `K − 273.15` |
| `kelvinToFarenheit` | `((K − 273.15) × 9)/5 + 32` |

> Nota: use `Double` (objeto) — permite `null` se um dia validar entrada. Hoje sem validação (Kelvin negativo passa) — ver seção 14.

### 5.4 `controller/TemperaturaConverterController.java:11`

```java
@Path("/converter") @Produces(APPLICATION_JSON)
public class TemperaturaConverterController {
  @Inject CalculadoraTemperatura calculadora; // CDI, sem @Autowired
  @GET @Path("/ctof/{tempCelsius}") public Double celsiusToFarenheit(@PathParam Double tempCelsius) { ... }
  // ... 5 outros
}
```

- Cada `GET` recebe `{valor}` como `Double` via `@PathParam` e devolve `Double` JSON.
- Sem DTO, sem tratamento de erro custom — JAX-RS devolve `400` se não for número e `401` via filter se sem Basic Auth.

### 5.5 `config/BasicAuthFilter.java:13`

```java
@Provider @Priority(AUTHENTICATION)
public class BasicAuthFilter implements ContainerRequestFilter {
  public void filter(ContainerRequestContext ctx) {
    if (path.equals("health") || path.equals("metrics") ...) return; // libera
    if (!path.startsWith("converter")) return;
    // valida Authorization: Basic base64(user:pass) contra APP_USERNAME/PASSWORD
  }
}
```

- **O que libera sem senha:** `/health` e `/metrics` (Prometheus + healthcheck).
- **CSRF:** não se aplica a JAX-RS (sem sessão).
- **Usuário:** criado no boot por `scripts/add-user.sh` via `add-user.sh -a -u $APP_USERNAME -p $APP_PASSWORD` (Elytron `ApplicationRealm`). Em dev fallback `admin/admin123`.

### 5.6 `health/HealthResource.java:10`

```java
@Path("/health") public class HealthResource {
  @GET public Response health() { return Response.ok(Map.of("status","UP")).build(); }
}
```

Health da aplicação (`/temperatura/health`). WildFly também expõe `/health` via MP Health no root (`:8080/health` e `:9990/health`).

### 5.7 `resources/META-INF/microprofile-config.properties:1`

```properties
mp.metrics.tags.app=temperatura-converter-jee
otel.service.name=temperatura-converter-jee
otel.exporter.otlp.endpoint=http://otel-collector:4318
otel.exporter.otlp.protocol=http/protobuf
mp.telemetry.enabled=false
```

### 5.8 `webapp/WEB-INF/jboss-web.xml:3` e `beans.xml`

```xml
<jboss-web><context-root>/temperatura</context-root></jboss-web>
<beans bean-discovery-mode="all"/>
```

Sem `jboss-web.xml` WildFly usaria `/ROOT.war` como context.

### 5.9 `pom.xml:9`

Apenas `jakarta.jakartaee-api:10.0.0` `provided` + `junit-jupiter`. Sem Spring.

### 5.10 `Dockerfile:1` + `scripts/add-user.sh`

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
COPY pom.xml .; RUN mvn dependency:go-offline -B
COPY src ./src; RUN mvn package -DskipTests -B
FROM quay.io/wildfly/wildfly:32.0.1.Final-jdk21
COPY --from=build /app/target/ROOT.war /opt/jboss/wildfly/standalone/deployments/temperatura.war
COPY scripts/add-user.sh /opt/jboss/wildfly/scripts/add-user.sh
ENTRYPOINT ["/opt/jboss/wildfly/scripts/add-user.sh"] # cria usuário + standalone.sh -c standalone-microprofile.xml -b 0.0.0.0
```

---

## 6. Fluxo de uma requisição

```
curl -u admin:admin123 https://jee.lab.dev/temperatura/converter/ctof/25
  │
  ├─► Nginx :443 (TLS termination, Host=jee.lab.dev)
  │     proxy_pass http://jee:8080
  │
  ├─► WildFly :8080 (Undertow, context /temperatura)
  │     ContainerRequestFilter: BasicAuthFilter
  │       → valida admin/admin123 (env)
  │       → libera /health|/metrics sem auth
  │
  ├─► Resteasy → @Path("/converter") @GET /ctof/{tempCelsius}
  │     Timer MP Metrics (http_server_requests_seconds)
  │     TraceId via MP Telemetry (se habilitado)
  │
  ├─► CalculadoraTemperatura.celsiusToFarenheit(25.0) → 77.0
  │
  ├─► Retorna 77.0 (JSON)
  │
  ├─► (se telemetry on) OTel exporta span → otel-collector:4318 → debug
  │
  └─► Prometheus em 10s scrapea /metrics → Grafana mostra req/s
```

---

## 7. Configuração (microprofile-config.properties + env vars)

| Variável | Onde | Padrão | Uso |
|----------|------|--------|-----|
| `APP_USERNAME` | `BasicAuthFilter.java:49` `scripts/add-user.sh:4` `compose:12` | `admin` | Usuário Basic Auth (Elytron + Filter) |
| `APP_PASSWORD` | `BasicAuthFilter.java:51` | `admin123` | Senha Basic Auth |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `microprofile-config.properties:3` `compose:14` | `http://otel-collector:4318` no Docker, `http://localhost:4318` local | Onde enviar traces |
| `OTEL_SERVICE_NAME` | `compose:15` | `temperatura-converter-jee` | Nome no trace |
| `GRAFANA_USER` / `GRAFANA_PASSWORD` | `compose:68` | `admin/admin` | Login Grafana |
| `mp.telemetry.enabled` | `microprofile-config.properties:6` | `false` | Ligar `true` só com Collector up |

Trocar em dev:

```bash
APP_USERNAME=dev APP_PASSWORD=dev123 mvn package
APP_USERNAME=dev docker compose --project-directory implantacao up --build -d
```

---

## 8. Segurança

- **Protegido:** todos `/converter/**` exigem `Authorization: Basic base64(user:pass)`
- **Público:** `/health`, `/metrics` (e `:9990/health` management) — para Prometheus/K8s sem credencial
- **Limitações atuais:**
  - Um único usuário em memória (Elytron `ApplicationRealm`) — evoluir para DB/LDAP/OAuth2 se precisar
  - Senha em env plain — em prod use Swarm/K8s secrets
  - Sem rate limiting, sem CORS configurado
  - Sem validação Kelvin < 0

Testar 401:

```bash
curl -i http://localhost:8080/temperatura/converter/ctof/10              # 401
curl -i -u admin:wrong http://localhost:8080/temperatura/converter/ctof/10 # 401
curl -i -u admin:admin123 http://localhost:8080/temperatura/converter/ctof/10 # 200
```

---

## 9. Como rodar local (sem Docker)

WildFly precisa estar instalado, ou use Docker como runtime. Para teste rápido sem WildFly:

```bash
git clone <repo> && cd temperatura-converter-jee

# 1. Build (gera ROOT.war)
mvn clean package -DskipTests

# 2. Rodar via Docker (recomendado — sem instalar WildFly local)
docker build -t temperatura-converter-jee .
docker run -p 8080:8080 -p 9990:9990 -e APP_USERNAME=admin -e APP_PASSWORD=admin123 temperatura-converter-jee

# 3. Testar
curl http://localhost:8080/health | jq
curl -u admin:admin123 http://localhost:8080/temperatura/converter/ctof/100  # 212.0
curl -u admin:admin123 http://localhost:8080/temperatura/converter/ctok/0    # 273.15

# 4. Ver métricas
curl http://localhost:8080/metrics | head -20
curl http://localhost:8080/metrics | grep http_server
```

> Rodar `mvn` puro sem WildFly só compila/testa — não sobe servidor. Para dev local sem Docker, instale WildFly 32 e deploye `target/ROOT.war` em `standalone/deployments/`.

---

## 10. Como rodar com Docker / Compose

### Só a app (sem monitoramento)

```bash
docker build -t temperatura-converter-jee .
docker run -p 8080:8080 -p 9990:9990 -e APP_USERNAME=admin -e APP_PASSWORD=admin123 temperatura-converter-jee
```

### Stack completa (jee + otel + prometheus + grafana + nginx)

```bash
cd implantacao
./scripts/add-hosts.sh              # 127.0.0.1 jee.lab.dev grafana.lab.dev prometheus.lab.dev otel.lab.dev
./scripts/generate-certs.sh         # só se cert expirou
docker compose up --build -d
docker compose ps
docker compose logs -f jee

# Via Nginx HTTPS
curl -k https://jee.lab.dev/health
curl -k https://jee.lab.dev/metrics | head
curl -k -u admin:admin123 https://jee.lab.dev/temperatura/converter/ctof/100

# Via localhost direto
curl http://localhost:8080/health
curl -u admin:admin123 http://localhost:8080/temperatura/converter/ctof/100
```

Parar: `docker compose down` (`-v` apaga volumes)

Ver `implantacao/README.md` e `implantacao/docs/nginx-https.md` para detalhes.

---

## 11. API — referência de endpoints

Base local: `http://localhost:8080/temperatura`  
Base via Nginx: `https://jee.lab.dev/temperatura`  
Auth: `Authorization: Basic base64(admin:admin123)` ou `curl -u admin:admin123`

### Conversão (GET, autenticado)

| Método | Path | Exemplo request | Resposta | Fórmula |
|--------|------|-----------------|----------|---------|
| GET | `/converter/ctof/{c}` | `/ctof/0` | `32.0` | `C×9/5+32` |
| GET | `/converter/ctok/{c}` | `/ctok/0` | `273.15` | `C+273.15` |
| GET | `/converter/ftoc/{f}` | `/ftoc/32` | `0.0` | `(F-32)×5/9` |
| GET | `/converter/ftok/{f}` | `/ftok/32` | `273.15` | `(F-32)×5/9+273.15` |
| GET | `/converter/ktoc/{k}` | `/ktoc/273.15` | `0.0` | `K-273.15` |
| GET | `/converter/ktof/{k}` | `/ktof/273.15` | `32.0` | `(K-273.15)×9/5+32` |

Retorno: `Content-Type: application/json` com número (`Double`). Erros: `401` sem auth, `400` se valor não é número, `404` se path errado.

```bash
curl -u admin:admin123 http://localhost:8080/temperatura/converter/ctof/25      # 77.0
curl -u admin:admin123 http://localhost:8080/temperatura/converter/ftoc/77     # 25.0
curl -u admin:admin123 "http://localhost:8080/temperatura/converter/ktoc/0"    # -273.15
curl -k -u admin:admin123 https://jee.lab.dev/temperatura/converter/ctof/100  # 212.0
```

### Monitoramento (sem auth)

| Path | Descrição | Via Nginx |
|------|-----------|-----------|
| `GET /health` | UP/DOWN (MP Health) | `https://jee.lab.dev/health` |
| `GET /metrics` | métricas Prometheus (MP Metrics) | `https://jee.lab.dev/metrics` |
| `GET /health` (management) | `:9990/health` WildFly | — |
| `GET /temperatura/health` | Health da app (HealthResource) | `https://jee.lab.dev/temperatura/health` |

---

## 12. Testes

### Rodar

```bash
mvn test              # 6 testes (CalculadoraTemperaturaTest)
mvn clean verify
```

### Tipos

| Teste | O que faz | Precisa Docker? |
|-------|-----------|-----------------|
| **Unit** (`CalculadoraTemperaturaTest:7`) | Testa `CalculadoraTemperaturaImpl` pura, sem container, sem CDI. Rápido (<1s) | Não |

Não há `@WebMvcTest` nem Testcontainers nesta versão JEE — testes são puros de fórmula. Para testar Controller com WildFly, use Arquillian ou RestAssured contra container real (ver Próximos passos).

### Escrever novo teste

```java
@Test void ctof25() {
  CalculadoraTemperaturaImpl c = new CalculadoraTemperaturaImpl();
  assertEquals(77.0, c.celsiusToFarenheit(25.0), 0.001);
}
```

Para teste de integração com BasicAuth, suba WildFly e use `java.net.http.HttpClient` com header `Authorization: Basic ...`.

### Cobertura (opcional)

Adicione `jacoco-maven-plugin` no `pom.xml` se precisar relatório.

---

## 13. Monitoramento para devs

- **Durante dev:** `curl http://localhost:8080/metrics | grep http_server` para ver contadores subirem a cada request.
- **Labels:** `mp.metrics.tags.app=temperatura-converter-jee` permite `app="temperatura-converter-jee"` no PromQL.
- **Logs com traceId:** quando `mp.telemetry.enabled=true`, logs incluem traceId — `docker logs jee | grep traceId`.
- **Stack completa:** ver `implantacao/docs/README.md` (métricas) e `implantacao/docs/nginx-https.md` (Nginx/TLS).

---

## 14. Como adicionar um novo endpoint

Exemplo: `GET /converter/ctof-round/{c}` que devolve inteiro arredondado.

1. **Interface** `CalculadoraTemperatura.java:3`:
   ```java
   Double celsiusToFarenheitRounded(Double c);
   ```

2. **Impl** `CalculadoraTemperaturaImpl.java:5`:
   ```java
   public Double celsiusToFarenheitRounded(Double c) { return (double) Math.round((c*9/5)+32); }
   ```

3. **Controller** `TemperaturaConverterController.java:18`:
   ```java
   @GET @Path("/ctof-round/{tempCelsius}") public Double ctofRound(@PathParam Double tempCelsius) { return calculadora.celsiusToFarenheitRounded(tempCelsius); }
   ```

4. **Teste** + `curl -u admin:admin123 http://localhost:8080/temperatura/converter/ctof-round/10.4` → `51.0`

5. Métrica aparece automaticamente como `uri="/converter/ctof-round/{tempCelsius}"` — sem config extra.

> Se precisar validação (ex: rejeitar Kelvin <0), adicione no Controller: `if (tempKelvin < 0) throw new WebApplicationException(Response.status(400).entity("Kelvin cannot be negative").build());`

---

## 15. Boas práticas e contribuição

- **Branch:** `feature/nome` ou `fix/nome` a partir de `main`
- **Commit:** `feat: add ctof-round endpoint` / `fix: correct kelvin formula`
- **Antes do PR:** `mvn clean test` (6/6 verde) + `docker compose --project-directory implantacao config` (valida yaml)
- **Não commit:** `target/`, `.idea/`, `*.log` (já no `.gitignore`)
- **Env vars:** nunca hardcode senha — use `APP_USERNAME`/`APP_PASSWORD`
- **Segurança:** se mudar `BasicAuthFilter.java:21`, mantenha `/metrics` e `/health` públicos ou Prometheus/healthcheck quebra
- **Observabilidade:** todo novo endpoint já é medido — adicione painel no `grafana/dashboards/temperatura-dashboard.json` se for crítico

---

## 16. Troubleshooting (dev)

| Problema | Causa | Solução |
|----------|-------|---------|
| `java: error: release version 21 not supported` | JDK 17/8 ativo | `java -version` → instale Temurin 21, `export JAVA_HOME=/usr/lib/jvm/temurin-21` |
| `Port 8080 already in use` | app já rodando | `lsof -i :8080` → `kill` ou `docker compose down` |
| `401 Unauthorized` | sem Basic Auth | `curl -u admin:admin123` ou check `APP_USERNAME` |
| `curl /metrics → 404` | WildFly sem MP Metrics subsystem | Recriar imagem `docker build` — WildFly 32 (`standalone-microprofile.xml`) já tem |
| `host not found in upstream` | nginx subiu antes | `docker compose restart nginx` |
| `ERR_CERT_AUTHORITY_INVALID` | cert autoassinado | `curl -k` ou `--cacert nginx/certs/lab.dev.crt` |
| `jee.lab.dev: Name not resolved` | /etc/hosts | `cat /etc/hosts \| grep lab.dev` → rode `./scripts/add-hosts.sh` |

```bash
mvn dependency:tree | grep jakarta
curl -v -u admin:admin123 http://localhost:8080/temperatura/converter/ctof/10
docker compose --project-directory implantacao logs -f jee
docker compose --project-directory implantacao down -v && docker compose --project-directory implantacao up --build -d
```

---

## 17. Referência de arquivos e comandos

```
# Raiz
pom.xml                                         # java 21, jakartaee-api 10.0.0, war ROOT
src/main/resources/META-INF/microprofile-config.properties
src/main/java/.../RestApplication.java           # @ApplicationPath("/")
src/main/java/.../controller/TemperaturaConverterController.java # 6 GETs JAX-RS
src/main/java/.../interfaces/CalculadoraTemperatura.java
src/main/java/.../interfaces/impl/CalculadoraTemperaturaImpl.java
src/main/java/.../config/BasicAuthFilter.java    # Basic Auth + libera /metrics /health
src/main/java/.../health/HealthResource.java     # /health → {"status":"UP"}
src/main/webapp/WEB-INF/jboss-web.xml            # context-root /temperatura
src/main/webapp/WEB-INF/beans.xml

# Infra
implantacao/docker-compose.yml                  # jee:8080 + otel + prometheus + grafana + nginx
implantacao/nginx/nginx.conf                    # jee.lab.dev → jee:8080
implantacao/nginx/certs/lab.dev.crt/.key        # cert autoassinado
implantacao/otel-collector/otel-collector-config.yaml
implantacao/prometheus/prometheus.yml           # job jee:8080/metrics
implantacao/grafana/datasources/datasource.yml
implantacao/grafana/dashboards/temperatura-dashboard.json
implantacao/scripts/{add-hosts,generate-certs}.sh
implantacao/docs/{README.md,nginx-https.md,guia-desenvolvedor.md}
```

**Comandos que todo dev usa:**

```bash
mvn clean test                         # testes
mvn package                            # gera ROOT.war
docker build -t temperatura-converter-jee .  # build imagem WildFly
docker compose --project-directory implantacao up --build -d  # infra completa
curl -u admin:admin123 http://localhost:8080/temperatura/converter/ctof/100
curl http://localhost:8080/metrics | head
```

---

*Próximo passo: leia `implantacao/docs/README.md` para entender as métricas que seus endpoints já geram, e `implantacao/docs/nginx-https.md` se for mexer no roteamento TLS.*
