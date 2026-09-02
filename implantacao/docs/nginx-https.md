# Guia Completo — Nginx como Balanceador + HTTPS com Certificado Autoassinado

> Para iniciantes. Explica **o que** é cada parte, **por que** existe e **como** funciona, linha a linha. Após ler, você saberá acessar `https://grafana.lab.dev` em vez de `http://localhost:3000`.

---

## Índice

1. [O que vamos construir](#1-o-que-vamos-construir)
2. [Por que Nginx? Por que HTTPS? Por que domínios?](#2-por-que-nginx-por-que-https-por-que-domínios)
3. [Os domínios e o /etc/hosts](#3-os-domínios-e-o-etchosts)
4. [Certificado autoassinado — o que é e como gerar](#4-certificado-autoassinado--o-que-é-e-como-gerar)
5. [Nginx.conf explicado linha a linha](#5-nginxconf-explicado-linha-a-linha)
6. [Docker Compose — serviço nginx](#6-docker-compose--serviço-nginx)
7. [Passo a passo para subir e validar](#7-passo-a-passo-para-subir-e-validar)
8. [Como usar no dia a dia (navegador, curl, confiar no cert)](#8-como-usar-no-dia-a-dia-navegador-curl-confiar-no-cert)
9. [Balanceamento — como escalar depois](#9-balanceamento--como-escalar-depois)
10. [Troubleshooting](#10-troubleshooting)
11. [Referência de arquivos e portas](#11-referência-de-arquivos-e-portas)

---

## 1. O que vamos construir

Antes:

```
http://localhost:3000  → Grafana
http://localhost:9090  → Prometheus
http://localhost:8080/temperatura → App
```

Depois (com Nginx + TLS):

```
https://grafana.lab.dev    → nginx:443 → grafana:3000
https://prometheus.lab.dev → nginx:443 → prometheus:9090
https://jee.lab.dev        → nginx:443 → jee:8080
https://otel.lab.dev       → nginx:443 → otel-collector:13133
http://*.lab.dev:80        → 301 redirect para https://
```

```
Browser ──HTTPS:443──► [ Nginx ] ──HTTP──► [ jee / grafana / prometheus / otel ]
                           │
                     cert *.lab.dev (autoassinado)
                     /etc/hosts 127.0.0.1
```

---

## 2. Por que Nginx? Por que HTTPS? Por que domínios?

| Pergunta | Resposta para iniciante |
|----------|-------------------------|
| **O que é Nginx?** | Servidor web que aqui funciona como **porteiro**: recebe na porta 443 e encaminha para o serviço certo baseado no nome do domínio (`grafana.lab.dev` vs `prometheus.lab.dev`). Chama-se **reverse proxy** ou **balanceador**. |
| **Por que balanceador?** | Um único ponto de entrada. Depois você pode colocar 2 apps atrás de `jee.lab.dev` e o Nginx distribui. Também centraliza TLS, logs e controle. |
| **Por que HTTPS?** | Mesmo em lab, HTTPS evita senha em texto puro (Grafana `admin/admin` e Basic Auth da app). Com TLS, a senha viaja cifrada. |
| **Por que autoassinado?** | Sem pagar autoridade (Let's Encrypt). O navegador reclama ("não confiável"), mas cifragem funciona. Para lab é suficiente. Em produção troque por cert válido. |
| **Por que domínios `*.lab.dev` em vez de `localhost:3000`?** | Domínios são mais próximos de produção, permitem cookies separados, HSTS e facilitam entender roteamento por host. Também permitem um único IP/porta (443) para tudo. |

> Analogia: `localhost:3000`, `localhost:9090` são como "rua 3000, rua 9090". `grafana.lab.dev`, `prometheus.lab.dev` são como "grafana.rua.lab, prometheus.rua.lab" — mesma rua (IP 127.0.0.1), casas diferentes (Host header).

---

## 3. Os domínios e o /etc/hosts

### O que é /etc/hosts?

Arquivo que o sistema consulta **antes** do DNS. Se você coloca `127.0.0.1 grafana.lab.dev`, o sistema não pergunta na internet — já sabe que é seu próprio PC.

Arquivo: `/etc/hosts` (Linux/macOS) ou `C:\Windows\System32\drivers\etc\hosts` (Windows).

Antes:

```
127.0.0.1 localhost
```

Depois:

```
127.0.0.1 localhost
127.0.0.1 jee.lab.dev grafana.lab.dev prometheus.lab.dev otel.lab.dev
```

### Como registrar (requer sudo)

```bash
# Opção 1: script pronto
cd implantacao
./scripts/add-hosts.sh

# Opção 2: manual
echo "127.0.0.1 jee.lab.dev grafana.lab.dev prometheus.lab.dev otel.lab.dev" | sudo tee -a /etc/hosts

# Verificar
cat /etc/hosts | grep lab.dev
getent hosts grafana.lab.dev   # deve mostrar 127.0.0.1
ping -c1 grafana.lab.dev       # deve responder
```

> Se não registrar, `https://grafana.lab.dev` dá `ERR_NAME_NOT_RESOLVED`. Alternativa sem hosts: use `curl --resolve grafana.lab.dev:443:127.0.0.1 https://grafana.lab.dev`.

---

## 4. Certificado autoassinado — o que é e como gerar

### Conceitos

- **Certificado** = arquivo `.crt` que diz "eu sou `*.lab.dev`". Contém chave pública.
- **Chave privada** = `.key` que só o Nginx tem. Prova que ele é dono do cert.
- **Autoassinado** = você mesmo assina, não há autoridade. Navegador avisa, mas tráfego continua cifrado.
- **SAN (Subject Alternative Name)** = lista de domínios que o cert vale. Nosso cert vale para 6 nomes: `grafana.lab.dev`, `prometheus.lab.dev`, `jee.lab.dev`, `otel.lab.dev`, `*.lab.dev`, `lab.dev`.

### Arquivos

```
implantacao/nginx/certs/
├── lab.dev.crt  # certificado (1.4K, válido 365 dias)
└── lab.dev.key  # chave privada (1.7K, permissão 600)
```

### Como foi gerado

```bash
openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
  -keyout implantacao/nginx/certs/lab.dev.key \
  -out implantacao/nginx/certs/lab.dev.crt \
  -subj "/CN=*.lab.dev/O=Lab Dev/C=BR" \
  -addext "subjectAltName=DNS:grafana.lab.dev,DNS:prometheus.lab.dev,DNS:jee.lab.dev,DNS:otel.lab.dev,DNS:*.lab.dev,DNS:lab.dev,IP:127.0.0.1"
```

Explicação parâmetro a parâmetro:

| Parâmetro | Significado |
|-----------|-------------|
| `-x509` | Gera cert autoassinado (não CSR) |
| `-nodes` | Não cifra a chave com senha (Nginx precisa ler sem senha) |
| `-days 365` | Validade 1 ano |
| `-newkey rsa:2048` | Cria chave RSA 2048 bits |
| `-subj "/CN=*.lab.dev/..."` | Subject: Common Name = `*.lab.dev` |
| `-addext "subjectAltName=..."` | SAN — sem isso, Chrome rejeita mesmo com CN |

### Verificar

```bash
openssl x509 -in implantacao/nginx/certs/lab.dev.crt -noout -subject -dates -ext subjectAltName
# Subject: CN = *.lab.dev
# Not Before: Aug 27 11:47:00 2026 GMT
# Not After : Aug 27 11:47:00 2027 GMT
# DNS:grafana.lab.dev, DNS:prometheus.lab.dev, DNS:jee.lab.dev, ...

# Ver fingerprint
openssl x509 -in implantacao/nginx/certs/lab.dev.crt -noout -fingerprint -sha256
```

### Regenerar ou adicionar domínio

```bash
./scripts/generate-certs.sh
# ou edite o comando e inclua DNS:novo.lab.dev no SAN, depois:
docker compose up -d --force-recreate nginx
```

---

## 5. Nginx.conf explicado linha a linha

Arquivo: `implantacao/nginx/nginx.conf`

```nginx
worker_processes auto;           # usa 1 worker por CPU
error_log /var/log/nginx/error.log warn;
pid /var/run/nginx.pid;

events { worker_connections 1024; }  # cada worker aguenta 1024 conexões simultâneas

http {
    include /etc/nginx/mime.types;  # tipos de arquivo
    access_log /var/log/nginx/access.log main;  # log com host, upstream, tempo
    sendfile on;
    keepalive_timeout 65;
    client_max_body_size 10m;

    # Upstreams — onde o balanceamento acontece. Hoje 1 servidor cada, amanhã N
    upstream jee_backend { server jee:8080; }
    upstream grafana_backend { server grafana:3000; }
    upstream prometheus_backend { server prometheus:9090; }
    upstream otel_backend { server otel-collector:13133; }

    # HTTP na porta 80 só redireciona para HTTPS
    server {
        listen 80;
        server_name jee.lab.dev grafana.lab.dev prometheus.lab.dev otel.lab.dev *.lab.dev;
        return 301 https://$host$request_uri;  # 301 = redirect permanente
    }

    # APP: https://jee.lab.dev → jee:8080
    server {
        listen 443 ssl;
        server_name jee.lab.dev;
        ssl_certificate /etc/nginx/certs/lab.dev.crt;      # cert
        ssl_certificate_key /etc/nginx/certs/lab.dev.key;  # chave
        ssl_protocols TLSv1.2 TLSv1.3;                      # só TLS moderno
        ssl_ciphers HIGH:!aNULL:!MD5;
        ssl_session_cache shared:SSL:10m;                   # cache de sessão TLS

        location / {
            proxy_pass http://jee_backend;                  # encaminha
            proxy_set_header Host $host;                    # preserva domínio original
            proxy_set_header X-Real-IP $remote_addr;        # IP do cliente
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;     # https
        }
        location /nginx-health { return 200 "ok jee\n"; }   # para healthcheck
    }

    # GRAFANA: https://grafana.lab.dev → grafana:3000
    server {
        listen 443 ssl;
        server_name grafana.lab.dev;
        ssl_certificate ...; ssl_certificate_key ...;

        location / {
            proxy_pass http://grafana_backend;
            proxy_set_header Host $host;
            # Grafana usa websocket → precisa Upgrade
            proxy_http_version 1.1;
            proxy_set_header Upgrade $http_upgrade;
            proxy_set_header Connection "upgrade";
        }
    }

    # PROMETHEUS: https://prometheus.lab.dev → prometheus:9090
    server { ... }  # igual

    # OTEL: https://otel.lab.dev → otel-collector:13133 (health/zpages)
    server { ... }

    # Fallback: host desconhecido → 404
    server {
        listen 443 ssl default_server;
        server_name _;
        return 404 "Unknown host - use jee.lab.dev, grafana.lab.dev, prometheus.lab.dev\n";
    }
}
```

**Ponto chave para iniciante:** `server_name` é o critério. Nginx lê o header `Host: grafana.lab.dev` e escolhe o bloco `server_name grafana.lab.dev`.

---

## 6. Docker Compose — serviço nginx

Trecho adicionado em `implantacao/docker-compose.yml:79-103`:

```yaml
nginx:
  image: nginx:1.27-alpine
  container_name: nginx-lb
  volumes:
    - ./nginx/nginx.conf:/etc/nginx/nginx.conf:ro
    - ./nginx/certs/lab.dev.crt:/etc/nginx/certs/lab.dev.crt:ro
    - ./nginx/certs/lab.dev.key:/etc/nginx/certs/lab.dev.key:ro
  ports:
    - "80:80"
    - "443:443"
  depends_on: [jee, grafana, prometheus, otel-collector]
  networks: [monitoring]
  healthcheck:
    test: ["CMD-SHELL", "wget --no-check-certificate -qO- https://127.0.0.1:443/nginx-health --header='Host: jee.lab.dev' | grep -q ok || exit 1"]
```

Além disso, no serviço `grafana` foram adicionadas variáveis para ele saber que está atrás de HTTPS:

```yaml
GF_SERVER_DOMAIN: grafana.lab.dev
GF_SERVER_PROTOCOL: http
GF_SERVER_ROOT_URL: https://grafana.lab.dev/
```

Sem isso, Grafana geraria links `http://` e redirecionamentos quebrados.

**Rede:** todos os 5 serviços na mesma rede `monitoring` — Nginx resolve `jee`, `grafana`, etc. via DNS interno do Docker (`127.0.0.11`).

---

## 7. Passo a passo para subir e validar

```bash
# 0. Registrar domínios (uma vez)
cd implantacao
./scripts/add-hosts.sh
cat /etc/hosts | grep lab.dev

# 1. (Opcional) Regenerar cert se expirou
./scripts/generate-certs.sh

# 2. Subir tudo
docker compose up --build -d
docker compose ps
# nginx-lb deve estar healthy

# 3. Ver logs do nginx
docker logs nginx-lb --tail 50
# erro comum: "host not found in upstream" → app ainda não subiu, aguarde 5s e docker logs de novo

# 4. Validar via curl (sem confiar no cert → -k)
curl -k https://jee.lab.dev/temperatura/health
# {"status":"UP"}

curl -k -u admin:admin123 https://jee.lab.dev/temperatura/converter/ctof/100
# 212.0

curl -k https://grafana.lab.dev/ | head
# <title>Grafana</title>

curl -k https://prometheus.lab.dev/-/healthy
# Prometheus Server is Healthy.

curl -k https://otel.lab.dev/  # health do collector
# {"status":"Server available"}

# 5. Validar redirect HTTP→HTTPS
curl -i http://jee.lab.dev/temperatura/health
# HTTP/1.1 301 Moved Permanently
# Location: https://jee.lab.dev/temperatura/health

# 6. Validar direto ainda funciona (localhost)
curl http://localhost:8080/temperatura/health
curl http://localhost:3000 | head
curl http://localhost:9090/-/healthy

# 7. Parar
docker compose down
```

---

## 8. Como usar no dia a dia (navegador, curl, confiar no cert)

### Navegador

1. Abra `https://grafana.lab.dev` → verá aviso "Sua conexão não é particular" (NET::ERR_CERT_AUTHORITY_INVALID). É esperado para autoassinado.
2. Clique em **Avançado → Continuar para grafana.lab.dev (não seguro)**.
3. Login `admin/admin`.

Para remover o aviso, importe o cert:

```bash
# Ubuntu/Debian: confiar no sistema
sudo cp implantacao/nginx/certs/lab.dev.crt /usr/local/share/ca-certificates/lab.dev.crt
sudo update-ca-certificates
# Reinicie o navegador

# Firefox: Configurações → Privacidade → Certificados → Ver Certificados → Autoridades → Importar → lab.dev.crt

# Windows: duplo clique no .crt → Instalar Certificado → Autoridades de Certificação Raiz Confiáveis
```

### curl

```bash
# sem confiar (ignora cert)
curl -k https://grafana.lab.dev
# ou
curl --insecure https://grafana.lab.dev

# confiando (passa cert)
curl --cacert implantacao/nginx/certs/lab.dev.crt https://grafana.lab.dev

# via IP sem hosts (útil em CI)
curl -k --resolve grafana.lab.dev:443:127.0.0.1 https://grafana.lab.dev
```

### Grafana e Prometheus via HTTPS

- Grafana datasource já usa `http://prometheus:9090` interno (não passa pelo Nginx) — não muda.
- Se quiser que Grafana use `https://prometheus.lab.dev`, troque `datasource.yml` para `url: https://prometheus.lab.dev` e adicione cert ao Grafana.

---

## 9. Balanceamento — como escalar depois

Hoje cada upstream tem 1 servidor. Para 2 réplicas da app:

```yaml
# docker-compose.yml
app2:
  build: { context: .., dockerfile: Dockerfile }
  networks: [monitoring]

# nginx.conf
upstream jee_backend {
    server jee:8080 weight=1;
    server jee2:8080 weight=1;
    # least_conn; # descomente para enviar para o menos carregado
}
```

Nginx distribui round-robin. Para sticky session, adicione `ip_hash;`.

Também pode adicionar `health_check` via `nginx-plus` ou externo.

---

## 10. Troubleshooting

| Sintoma | Causa | Solução |
|---------|-------|---------|
| `curl: (6) Could not resolve host grafana.lab.dev` | `/etc/hosts` não registrado | `cat /etc/hosts \| grep lab.dev` → se vazio, `sudo sh -c 'echo "127.0.0.1 jee.lab.dev grafana.lab.dev prometheus.lab.dev otel.lab.dev" >> /etc/hosts'` |
| `ERR_CERT_AUTHORITY_INVALID` / `curl: (60) SSL certificate problem` | Cert autoassinado não confiado | Use `curl -k` ou `--cacert` ou importe cert no SO/navegador |
| `400 Bad Request` ou `404 Unknown host` | Host header errado | `curl -k https://127.0.0.1:443/ -H "Host: grafana.lab.dev"` — use domínio, não IP |
| `502 Bad Gateway` no Nginx | Backend fora do ar | `docker ps`, `docker logs jee`, `docker logs nginx-lb` → veja `connect() failed` |
| `host not found in upstream "jee:8080"` no log do Nginx | Nginx subiu antes do app (DNS ainda não existe) | `docker compose restart nginx` ou `docker compose up -d` de novo; em produção use `resolver 127.0.0.11` com variável |
| `301` infinito | Tentando `http://localhost:3000` atrás do Nginx com `X-Forwarded-Proto` errado | Use `https://grafana.lab.dev` direto |
| Porta 443 já em uso | Outro serviço | `sudo lsof -i :443`, `sudo netstat -tulpn \| grep 443`, pare o serviço ou mude `ports: ["8443:443"]` |

**Comandos úteis:**

```bash
docker logs nginx-lb --tail 100
docker exec nginx-lb nginx -t
docker exec nginx-lb cat /etc/nginx/nginx.conf
cat /etc/hosts
openssl x509 -in implantacao/nginx/certs/lab.dev.crt -noout -dates
curl -vk https://grafana.lab.dev 2>&1 | head -20  # -v mostra handshake TLS
curl -k https://jee.lab.dev/nginx-health -H "Host: jee.lab.dev"
```

---

## 11. Referência de arquivos e portas

```
implantacao/
├── docker-compose.yml              # + serviço nginx:80/443
├── nginx/
│   ├── nginx.conf                  # 4 vhosts TLS + redirect 80→443
│   ├── certs/lab.dev.crt/.key      # cert autoassinado
│   └── README.md
├── scripts/
│   ├── add-hosts.sh                # registra 127.0.0.1 *.lab.dev
│   └── generate-certs.sh           # gera cert
├── docs/
│   ├── README.md                   # guia geral de monitoramento
│   └── nginx-https.md              # este guia
└── grafana/datasources/datasource.yml  # continua http interno
```

**Portas finais:**

| Acesso | URL | Porta host |
|--------|-----|------------|
| Via Nginx (recomendado) | `https://jee.lab.dev/temperatura/...` | 443 |
| Via Nginx | `https://grafana.lab.dev` | 443 |
| Via Nginx | `https://prometheus.lab.dev` | 443 |
| Via Nginx | `https://otel.lab.dev` | 443 |
| Direto (fallback) | `http://localhost:8080` | 8080 |
| Direto | `http://localhost:3000` | 3000 |
| Direto | `http://localhost:9090` | 9090 |

*Produção: feche portas diretas (remova `ports` de app/grafana/prometheus) e deixe só `80/443` do Nginx. Para Lab, mantivemos ambos para facilitar `curl localhost` sem TLS.*

