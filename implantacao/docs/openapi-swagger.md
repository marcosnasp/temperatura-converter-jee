# OpenAPI + Swagger UI — temperatura-converter-jee

> Estudo isolado. Como habilitamos `MicroProfile OpenAPI 3.1.1` no WildFly 32 e como o Swagger UI consome o spec em `/openapi`.

---

## 1. O que é o quê

| Termo | O que é | Onde vive neste lab |
|---|---|---|
| **OpenAPI (spec)** | Arquivo `yaml/json` que descreve a API (paths, params, responses). Máquina lê, humano estuda. | `GET /openapi` → gerado pelo WildFly SmallRye |
| **MicroProfile OpenAPI** | Spec Java (`org.eclipse.microprofile.openapi`) + anotações (`@OpenAPIDefinition`, `@Operation`). WildFly implementa via `smallrye-open-api`. | `pom.xml:23` + `RestApplication.java:4` |
| **Swagger UI** | Página HTML/JS que lê o spec e vira doc interativa (botão Try it out). | `src/main/java/.../SwaggerUIResource.java:9` → `https://jee.lab.dev/temperatura/openapi-ui` |

Fluxo:

```
Browser https://jee.lab.dev/temperatura/openapi-ui
  → Nginx:443 → jee:8080/temperatura/openapi-ui (JAX-RS, contorna ApplicationPath "/" que bloqueia estático)
    → JS busca /openapi (Nginx → jee:8080/openapi)
      → renderiza 6 endpoints
```

---

## 2. Como foi habilitado (passo a passo)

### 2.1 Dependência `pom.xml:23`

```xml
<dependency>
  <groupId>org.eclipse.microprofile.openapi</groupId>
  <artifactId>microprofile-openapi-api</artifactId>
  <version>3.1.1</version>
  <scope>provided</scope> <!-- WildFly já tem o jar em modules/org/eclipse/microprofile/openapi -->
</dependency>
```

Versão bate com `modules/org/eclipse/microprofile/openapi/api/main/microprofile-openapi-api-3.1.1.jar` do WildFly 32.

### 2.2 WildFly config `scripts/add-user.sh:9`

`standalone.xml` padrão **não** tem o extension `microprofile-openapi-smallrye`. Já o `standalone-microprofile.xml` tem:

```xml
<extension module="org.wildfly.extension.microprofile.openapi-smallrye"/>
<subsystem xmlns="urn:wildfly:microprofile-openapi-smallrye:1.0"/>
```

Troca:

```bash
# antes
exec standalone.sh -b 0.0.0.0 -bmanagement 0.0.0.0
# depois
exec standalone.sh -c standalone-microprofile.xml -b 0.0.0.0 -bmanagement 0.0.0.0
```

Log confirma: `WFLYMPOAI0001: Activating MicroProfile OpenAPI Subsystem` + `WFLYMPOAI0004: Registered MicroProfile OpenAPI endpoint '/openapi'`.

### 2.3 Anotações `RestApplication.java:6` e `TemperaturaConverterController.java:10`

```java
@OpenAPIDefinition(
  info = @Info(title="temperatura-converter-jee", version="1.0.0",
               description="Conversor C/F/K - Lab monitoramento"),
  servers = {@Server(url="/temperatura"), @Server(url="https://jee.lab.dev/temperatura")}
)
public class RestApplication extends Application {}

@Tag(name="Conversão", description="Endpoints de conversão")
public class TemperaturaConverterController {
  @GET @Path("/ctof/{tempCelsius}") ...
}
```

Sem anotação o spec já nasce (SmallRye escaneia `@Path`), mas fica `title: temperatura.war / version 1.0`. Com anotação fica legível.

### 2.4 Swagger UI `src/main/java/.../SwaggerUIResource.java:9` + `src/main/webapp/swagger-ui.html`

`@ApplicationPath("/")` do JAX-RS bloqueia arquivos estáticos (`/temperatura/swagger-ui.html` dava 404). Solução ponytail: JAX-RS resource serve o HTML:

```java
@Path("/openapi-ui")
public class SwaggerUIResource {
  @GET @Produces(TEXT_HTML)
  public Response ui() { /* serve swagger-ui.html via CDN */ }
}
```

HTML carrega `swagger-ui-dist@5.11.0` via CDN `unpkg.com`:

```html
<link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5.11.0/swagger-ui.css"/>
<script src="https://unpkg.com/swagger-ui-dist@5.11.0/swagger-ui-bundle.js"></script>
SwaggerUIBundle({ url: "/openapi", dom_id: '#swagger-ui' })
```

- `url: "/openapi"` → root, fora de `/temperatura` (onde WildFly expõe). De `/temperatura/openapi-ui` o JS busca `https://jee.lab.dev/openapi` via Nginx (`location / → jee:8080`).
- `BasicAuthFilter.java:25` libera `!path.startsWith("converter")` → `/openapi` e `/openapi-ui` passam sem auth. `Try it out` em `/converter/*` vai pedir `admin/admin123`.
- Fallback: `src/main/webapp/swagger-ui.html` mantido como backup estático.

Alternativa offline: trocar CDN por `org.webjars:swagger-ui:5.11.0` (jar) + copiar para `webapp`.

---

## 3. Como usar no dia a dia

```bash
# spec crua
curl http://localhost:8080/openapi | head -30
curl -k https://jee.lab.dev/openapi | head -30
curl -H "Accept: application/json" http://localhost:8080/openapi | jq .info

# swagger ui (navegador) - via JAX-RS (funciona com ApplicationPath "/")
https://jee.lab.dev/temperatura/openapi-ui
# 6 endpoints → Expand → Try it out → Execute (preencha temp, Authorize com admin/admin123)
# backup estático (se JAX-RS liberado): https://jee.lab.dev/temperatura/swagger-ui.html

# importar no Postman/Insomnia
# Postman → Import → Link → https://jee.lab.dev/openapi → gera collection

# validar
docker logs temperatura-converter-jee --tail 20 | grep WFLYMPOAI
# WFLYMPOAI0004: Registered MicroProfile OpenAPI endpoint '/openapi'
```

Auth no Swagger UI: clique cadeado `Authorize` → `Basic` → `admin/admin123` → Try it out em `GET /converter/ctof/100` → `212.0`.

Sem auth: `curl -i http://localhost:8080/temperatura/converter/ctof/10` → `401`.

---

## 4. Por que o navegador pediu para baixar ao acessar `/openapi`?

`GET /openapi` retorna `Content-Type: application/yaml` + `Content-Disposition: attachment`. Browser entende como arquivo, não HTML → sugere salvar `openapi.yaml`. Correto. Use o Swagger UI para ver bonito, ou `curl`.

---

## 5. Troubleshooting

| Sintoma | Causa | Fix |
|---|---|---|
| `curl /openapi → 404` | `standalone.xml` sem extension | `add-user.sh` deve usar `-c standalone-microprofile.xml` |
| `openapi.yaml title temperatura.war` | Sem `@OpenAPIDefinition` | Adicionar em `RestApplication.java:6` |
| `Swagger UI vazio / Failed to fetch` | `url` errado (`/temperatura/openapi`) | Usar `/openapi` (root) |
| `Try it out → 401` | Sem Basic | Authorize `admin/admin123` no Swagger UI |
| `swagger-ui.html 404` / `openapi-ui 404` | `ApplicationPath "/"` bloqueia estático | Usar `SwaggerUIResource.java:9` (`/temperatura/openapi-ui`) → `mvn package` → redeploy |

Validar: `docker exec temperatura-converter-jee wget -qO- http://localhost:8080/openapi | grep title` → `temperatura-converter-jee`.

---

## 6. Próximos passos para estudo

- `@Operation(summary="Celsius para Fahrenheit", description="Fórmula (C*9/5)+32")` em cada método do controller para doc rica
- `@APIResponse(responseCode="401", description="Sem auth")` + `@SecurityScheme` para Basic aparecer no Swagger UI
- Gerar cliente: `npx @openapitools/openapi-generator-cli generate -i http://localhost:8080/openapi -g typescript-fetch -o /tmp/client`
- Comparar com Spring Boot: `springdoc-openapi` expõe `/v3/api-docs` + `/swagger-ui.html` — mesmo spec, outro runtime

Referências: `impl/docs/api-referencia.md:8`, `src/main/java/.../RestApplication.java:6`, `src/main/java/.../SwaggerUIResource.java:9`, `src/main/webapp/swagger-ui.html:10`.

*Última atualização: 02/09/2026 — habilitação OpenAPI 3.1.1 + Swagger UI via CDN.*
