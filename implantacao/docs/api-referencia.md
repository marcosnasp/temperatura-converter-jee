# API — Referência Completa

> Base: `http://localhost:8080/temperatura` (direto) ou `https://jee.lab.dev/temperatura` (via Nginx TLS).
> Stack: **Jakarta EE 10 / WildFly 32 + JAX-RS 3.1 + CDI 4.0**. Sem Spring Boot (standalone-microprofile.xml).
> Arquivos: `src/main/java/com/example/temperatura/converter/controller/TemperaturaConverterController.java:11`, `interfaces/CalculadoraTemperatura.java:3`, `config/BasicAuthFilter.java:13`.

---

## Índice
1. [Autenticação](#1-autenticação)
2. [Headers e content negotiation](#2-headers-e-content-negotiation)
3. [Endpoints de conversão (6)](#3-endpoints-de-conversão-6)
4. [Endpoints de monitoramento (sem auth)](#4-endpoints-de-monitoramento-sem-auth)
5. [Códigos de status e erros](#5-códigos-de-status-e-erros)
6. [Exemplos curl (direto e via Nginx)](#6-exemplos-curl-direto-e-via-nginx)
7. [Fórmulas e precisão](#7-fórmulas-e-precisão)
8. [OpenAPI implícito (tabela)](#8-openapi-implícito-tabela)
9. [Como adicionar novo endpoint](#9-como-adicionar-novo-endpoint)
10. [Testes da API](#10-testes-da-api)

---

## 1. Autenticação

**Tipo:** HTTP Basic (`Authorization: Basic base64(user:pass)`)

| Variável | Padrão | Onde definido |
|----------|--------|---------------|
| `APP_USERNAME` | `admin` | `BasicAuthFilter.java:49` + `scripts/add-user.sh:4` + `compose:12` |
| `APP_PASSWORD` | `admin123` | `BasicAuthFilter.java:51` |

- Criado no boot via Elytron `ApplicationRealm` (`add-user.sh -a -u $USER -p $PASS -g guest`).
- **Protegido:** `path.startsWith("converter")` → exige Basic (`BasicAuthFilter.java:25`).
- **Público:** `health`, `metrics` → sem senha (para Prometheus/healthcheck).
- **Outros paths:** passam sem auth e caem em `404` do JAX-RS.

```bash
# header manual
echo -n "admin:admin123" | base64  # YWRtaW46YWRtaW4xMjM=
curl -H "Authorization: Basic YWRtaW46YWRtaW4xMjM=" http://localhost:8080/temperatura/converter/ctof/100

# atalho curl
curl -u admin:admin123 http://localhost:8080/temperatura/converter/ctof/100
```

Sem header ou senha errada → `401 Unauthorized` + `WWW-Authenticate: Basic realm="temperatura"` (`BasicAuthFilter.java:60`).

---

## 2. Headers e content negotiation

| Header | Valor | Obrigatório |
|--------|-------|-------------|
| `Authorization` | `Basic ...` | sim para `/converter/*` |
| `Accept` | `application/json` (ou `*/*`) | não — `@Produces(APPLICATION_JSON)` já devolve JSON |
| `Host` (via Nginx) | `jee.lab.dev` | sim quando via `https://jee.lab.dev` — Nginx roteia por `server_name` |
| `X-Forwarded-Proto` | `https` | adicionado pelo Nginx, não pelo cliente |

**Resposta:** `200 OK` + `Content-Type: application/json` + body `Double` (ex: `212.0`).

---

## 3. Endpoints de conversão (6)

Todos `GET`, autenticados, `Double` via `@PathParam` → `Double` JSON.

### 3.1 `GET /converter/ctof/{tempCelsius}` — Celsius → Fahrenheit

`controller/TemperaturaConverterController.java:19`:
```java
@GET @Path("/ctof/{tempCelsius}")
public Double celsiusToFarenheit(@PathParam("tempCelsius") Double tempCelsius) {
  return calculadora.celsiusToFarenheit(tempCelsius); // (C*9/5)+32
}
```

| Campo | Valor |
|-------|-------|
| Fórmula | `(C × 9/5) + 32` |
| Exemplo | `GET /converter/ctof/0` → `32.0` |
| Exemplo | `GET /converter/ctof/100` → `212.0` |
| Exemplo | `GET /converter/ctof/-40` → `-40.0` |

```bash
curl -u admin:admin123 http://localhost:8080/temperatura/converter/ctof/100      # 212.0
curl -k -u admin:admin123 https://jee.lab.dev/temperatura/converter/ctof/100    # 212.0
```

### 3.2 `GET /converter/ctok/{tempCelsius}` — Celsius → Kelvin

```java
@GET @Path("/ctok/{tempCelsius}")
public Double celsiusToKelvin(@PathParam Double tempCelsius) { return c + 273.15; }
```

| Exemplo | `GET /converter/ctok/0` → `273.15` |
| Exemplo | `GET /converter/ctok/-273.15` → `0.0` |
| Exemplo | `GET /converter/ctok/25` → `298.15` |

### 3.3 `GET /converter/ftoc/{tempFarenheit}` — Fahrenheit → Celsius

```java
@GET @Path("/ftoc/{tempFarenheit}")
public Double farenheitToCelsius(@PathParam Double tempFarenheit) { return ((F-32)*5)/9; }
```

| Exemplo | `GET /converter/ftoc/32` → `0.0` |
| Exemplo | `GET /converter/ftoc/212` → `100.0` |

### 3.4 `GET /converter/ftok/{tempFarenheit}` — Fahrenheit → Kelvin

```java
@GET @Path("/ftok/{tempFarenheit}")
public Double farenheitToKelvin(@PathParam Double tempFarenheit) { return ((F-32)*5/9)+273.15; }
```

| Exemplo | `GET /converter/ftok/32` → `273.15` |

### 3.5 `GET /converter/ktoc/{tempKelvin}` — Kelvin → Celsius

```java
@GET @Path("/ktoc/{tempKelvin}")
public Double kelvinToCelsius(@PathParam Double tempKelvin) { return K - 273.15; }
```

| Exemplo | `GET /converter/ktoc/273.15` → `0.0` |
| Nota | Hoje aceita `K < 0` (fisicamente impossível) — sem validação (ver seção 9) |

### 3.6 `GET /converter/ktof/{tempKelvin}` — Kelvin → Fahrenheit

```java
@GET @Path("/ktof/{tempKelvin}")
public Double kelvinToFarenheit(@PathParam Double tempKelvin) { return ((K-273.15)*9/5)+32; }
```

| Exemplo | `GET /converter/ktof/273.15` → `32.0` |

---

## 4. Endpoints de monitoramento (sem auth)

| Método | Path | Porta | Via Nginx | Descrição | Arquivo |
|--------|------|-------|-----------|-----------|---------|
| GET | `/metrics` | 8080 | `https://jee.lab.dev/metrics` | Métricas Prometheus (texto) | MP Metrics |
| GET | `/health` | 8080 | `https://jee.lab.dev/health` | Health JSON (MP Health) | MP Health |
| GET | `/health` | 9990 | — | Management health (WildFly) | `compose:25` healthcheck |
| GET | `/temperatura/health` | 8080 | `https://jee.lab.dev/temperatura/health` | Health da app | `health/HealthResource.java:10` |
| GET | `/metrics` (OTel) | 8889 | — | Métricas do Collector | `otel-collector-config.yaml:26` |
| GET | `/` (OTel health) | 13133 | `https://otel.lab.dev` | `{"status":"Server available"}` | `otel-collector-config.yaml:36` |

```bash
curl http://localhost:8080/metrics | head -20
curl http://localhost:8080/health | jq
curl http://localhost:9990/health | jq
curl http://localhost:8080/temperatura/health  # {"status":"UP"}
curl http://localhost:13133 | jq
curl -k https://jee.lab.dev/metrics | head -5
```

---

## 5. Códigos de status e erros

| Status | Quando | Body | Origem |
|--------|--------|------|--------|
| `200` | sucesso | `Double` JSON (`212.0`) | Controller |
| `401` | sem `Authorization` ou `user ≠ APP_USERNAME` ou `pass ≠ APP_PASSWORD` ou Base64 inválido | `Unauthorized` | `BasicAuthFilter.java:60` |
| `400` | `{valor}` não é número (`/converter/ctof/abc`) | HTML/texto JAX-RS `Bad Request` | Resteasy |
| `404` | path inexistente (`/converter/xyz/10`) | `404 Not Found` | JAX-RS |
| `405` | método errado (`POST /converter/ctof/10`) | `405 Method Not Allowed` | JAX-RS |
| `500` | exceção não tratada na conversão (hoje só `null` via `Double` objeto) | stacktrace | WildFly |

**Exemplos de erro:**
```bash
curl -i http://localhost:8080/temperatura/converter/ctof/10
# HTTP/1.1 401 Unauthorized
# WWW-Authenticate: Basic realm="temperatura"

curl -i -u admin:admin123 http://localhost:8080/temperatura/converter/ctof/abc
# HTTP/1.1 400 Bad Request

curl -i -u admin:admin123 http://localhost:8080/temperatura/converter/xx/10
# HTTP/1.1 404 Not Found
```

---

## 6. Exemplos curl (direto e via Nginx)

### Direto (sem TLS, sem /etc/hosts)

```bash
# conversão
curl -u admin:admin123 http://localhost:8080/temperatura/converter/ctof/0     # 32.0
curl -u admin:admin123 http://localhost:8080/temperatura/converter/ctok/0     # 273.15
curl -u admin:admin123 http://localhost:8080/temperatura/converter/ftoc/32    # 0.0
curl -u admin:admin123 http://localhost:8080/temperatura/converter/ftok/32    # 273.15
curl -u admin:admin123 http://localhost:8080/temperatura/converter/ktoc/273.15 # 0.0
curl -u admin:admin123 http://localhost:8080/temperatura/converter/ktof/273.15 # 32.0

# lote: Celsius → Fahrenheit de 0 a 100 passo 10
for c in 0 10 20 30 40 50 60 70 80 90 100; do
  echo -n "$c C → "; curl -s -u admin:admin123 http://localhost:8080/temperatura/converter/ctof/$c; echo " F"
done

# health/metrics sem auth
curl http://localhost:8080/temperatura/health
curl http://localhost:8080/health | jq
curl http://localhost:8080/metrics | grep http_server_requests
```

### Via Nginx (HTTPS, requer /etc/hosts ou --resolve)

```bash
# exige: ./scripts/add-hosts.sh  (127.0.0.1 jee.lab.dev)
# cert autoassinado → -k ou --cacert

# com -k (ignora cert)
curl -k -u admin:admin123 https://jee.lab.dev/temperatura/converter/ctof/100   # 212.0
curl -k https://jee.lab.dev/temperatura/health
curl -k https://jee.lab.dev/metrics | head -5
curl -k https://jee.lab.dev/health | jq

# com cert confiável (sem -k)
curl --cacert implantacao/nginx/certs/lab.dev.crt -u admin:admin123 https://jee.lab.dev/temperatura/converter/ctof/100

# sem /etc/hosts (CI)
curl -k --resolve jee.lab.dev:443:127.0.0.1 -u admin:admin123 https://jee.lab.dev/temperatura/converter/ctof/100

# redirect 80→443
curl -i http://jee.lab.dev/temperatura/health  # 301 Location: https://jee.lab.dev/...

# Grafana/Prometheus via Nginx
curl -k https://grafana.lab.dev/ | head -5          # <title>Grafana</title>
curl -k https://prometheus.lab.dev/-/healthy        # Prometheus Server is Healthy.
curl -k https://otel.lab.dev/ | jq                  # {"status":"Server available"}
```

### Com httpie / Postman

```bash
# httpie
http -a admin:admin123 GET http://localhost:8080/temperatura/converter/ctof/25
http --verify=no -a admin:admin123 GET https://jee.lab.dev/temperatura/converter/ctof/25

# Postman: Authorization → Basic Auth → admin/admin123 → GET https://jee.lab.dev/temperatura/converter/ctof/25
```

---

## 7. Fórmulas e precisão

`interfaces/impl/CalculadoraTemperaturaImpl.java:5` (`@ApplicationScoped` CDI):

| Método | Código | Fórmula matemática |
|--------|--------|--------------------|
| `celsiusToFarenheit` | `(c * 9 / 5) + 32` | `F = C × 9/5 + 32` |
| `celsiusToKelvin` | `c + 273.15` | `K = C + 273.15` |
| `farenheitToCelsius` | `((f - 32) * 5) / 9` | `C = (F − 32) × 5/9` |
| `farenheitToKelvin` | `((f - 32) * 5) / 9 + 273.15` | `K = (F − 32) × 5/9 + 273.15` |
| `kelvinToCelsius` | `k - 273.15` | `C = K − 273.15` |
| `kelvinToFarenheit` | `((k - 273.15) * 9) / 5 + 32` | `F = (K − 273.15) × 9/5 + 32` |

- Tipo `Double` (objeto) — permite `null` se um dia validar, mas hoje sem validação (Kelvin negativo passa).
- Precisão: `double` IEEE 754, erro < `1e-10` para valores usuais. Testes em `CalculadoraTemperaturaTest.java` usam `delta 0.001`.

---

## 8. OpenAPI implícito (tabela)

Sem `microprofile-openapi` instalado, mas contrato é:

```
openapi: 3.0.0
info: {title: temperatura-converter-jee, version: 1.0.0}
servers: [{url: http://localhost:8080/temperatura}, {url: https://jee.lab.dev/temperatura}]
paths:
  /converter/ctof/{tempCelsius}: {get: {parameters: [{name: tempCelsius, in: path, required: true, schema: {type: number, format: double}}], responses: {200: {content: {application/json: {schema: {type: number}}}}, 401: {}, 400: {}}}}
  /converter/ctok/{tempCelsius}: {get: {...}}
  /converter/ftoc/{tempFarenheit}: {get: {...}}
  /converter/ftok/{tempFarenheit}: {get: {...}}
  /converter/ktoc/{tempKelvin}: {get: {...}}
  /converter/ktof/{tempKelvin}: {get: {...}}
  /health: {get: {responses: {200: {content: {application/json: {schema: {type: object, properties: {status: {type: string}}}}}}}}}
  /metrics: {get: {responses: {200: {content: {text/plain: {}}}}}}
security: [{basicAuth: []}]
components: {securitySchemes: {basicAuth: {type: http, scheme: basic}}}
```

Para gerar OpenAPI real, adicione em `pom.xml`:
```xml
<dependency><groupId>org.microprofile</groupId><artifactId>microprofile-openapi-api</artifactId></dependency>
```

---

## 9. Como adicionar novo endpoint

Exemplo: `GET /converter/ctof-round/{c}` → Fahrenheit arredondado.

1. **Contrato** `interfaces/CalculadoraTemperatura.java:3`:
   ```java
   Double celsiusToFarenheitRounded(Double c);
   ```
2. **Impl** `interfaces/impl/CalculadoraTemperaturaImpl.java:5`:
   ```java
   public Double celsiusToFarenheitRounded(Double c) { return (double) Math.round((c*9/5)+32); }
   ```
3. **Controller** `controller/TemperaturaConverterController.java:18`:
   ```java
   @GET @Path("/ctof-round/{tempCelsius}")
   public Double ctofRound(@PathParam("tempCelsius") Double tempCelsius) {
     return calculadora.celsiusToFarenheitRounded(tempCelsius);
   }
   ```
4. **Teste** `CalculadoraTemperaturaTest.java`:
   ```java
   assertEquals(51.0, impl.celsiusToFarenheitRounded(10.4), 0.001);
   ```
5. Métrica aparece automaticamente como `uri="/converter/ctof-round/{tempCelsius}"` — sem config extra (MP Metrics instrumenta todo `@Path`).

**Validação (ex: rejeitar Kelvin <0):**
```java
if (tempKelvin < 0) throw new WebApplicationException(
  Response.status(400).entity("Kelvin cannot be negative: " + tempKelvin).build());
```
→ Prometheus verá `status="400"` em `Taxa de erro` no Grafana.

---

## 10. Testes da API

### Unit (sem container)

```bash
mvn test  # CalculadoraTemperaturaTest: 6 testes puros (<1s)
```

### Integração manual (com WildFly up)

```bash
# 1. subir
docker compose --project-directory implantacao up --build -d
# 2. testar
./implantacao/scripts/../test-api.sh  # se existir, ou:
curl -u admin:admin123 http://localhost:8080/temperatura/converter/ctof/25 | grep 77.0 && echo "OK"
curl -u admin:admin123 http://localhost:8080/temperatura/converter/ktoc/273.15 | grep 0.0 && echo "OK"
# 3. testar auth
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/temperatura/converter/ctof/10  # 401
curl -s -o /dev/null -w "%{http_code}" -u admin:wrong http://localhost:8080/temperatura/converter/ctof/10  # 401
curl -s -o /dev/null -w "%{http_code}" -u admin:admin123 http://localhost:8080/temperatura/converter/ctof/10  # 200
```

### Com RestAssured (futuro)

Para teste automatizado com container real, adicione Testcontainers + RestAssured e aponte para `http://localhost:8080/temperatura`.

---

**Próximos:** `componentes-monitoramento.md` (onde cada métrica é armazenada) e `fluxo-comunicacao.md` (diagramas de sequência).
