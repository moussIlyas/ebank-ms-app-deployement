# Ebank Microservices

A microservices banking demo built with Spring Boot, Spring Cloud and Spring AI
(Ollama Cloud + Tavily web search), with a central configuration repository and MariaDB.

## Modules

| Module | Port | Description |
|--------|------|-------------|
| `discovery-service` | 8761 | Eureka service registry |
| `gateway-service`   | 9999 | Spring Cloud Gateway entry point |
| `customer-service`  | 8056 | Customer management REST API (MariaDB `customer-db`) |
| `ebank-service`     | 8057 | Bank account management, calls `customer-service` via OpenFeign (MariaDB `accounts-db`) |
| `ebank-bot`         | 8058 | AI chatbot powered by Spring AI on Ollama Cloud with a Tavily web-search tool |

## Tech stack

- Java 21, Maven
- Spring Boot / Spring Cloud (Eureka, Gateway, OpenFeign, Resilience4j)
- Spring Data JPA + **MariaDB** (H2 removed)
- Spring AI + **Ollama Cloud** (`https://ollama.com`) + **Tavily** tool calling
- Docker / Docker Compose (whole stack, dedicated **dev** and **prod** modes)

---

## 1. Central configuration (`config-repo/`)

All application configuration for **every** service lives in one folder at the repo root:

```
config-repo/
  global.properties                 # shared defaults (Eureka URL, config client off)
  <service>-dev.properties          # dev profile config
  <service>-pro.properties          # production profile config
```

Each module only declares its identity, active profile and the imports:

```properties
spring.application.name=customer-service
spring.profiles.active=dev

spring.config.import=optional:file:../.env,\
  optional:file:../config-repo/global.properties,\
  optional:file:../config-repo/${spring.application.name}-${spring.profiles.active}.properties
```

### How a module finds its config file

The file name is **derived**, never hardcoded. Spring expands two variables while
building the import path:

| Variable | Comes from | Example value |
|---|---|---|
| `${spring.application.name}` | line 1 of the module's `application.properties` | `customer-service` |
| `${spring.profiles.active}` | line 2 (or the launch override, see below) | `dev` |

So the path resolves to `../config-repo/customer-service-dev.properties`.
Adding a new service only requires creating `config-repo/<name>-dev.properties`
and `config-repo/<name>-pro.properties` — the import line stays identical in
every module.

### Load order (first → last, later wins)

```
1. ../.env                                secrets (DB_HOST, DB_USERNAME, ...)
2. ../config-repo/global.properties       shared defaults (Eureka URL, ...)
3. ../config-repo/<name>-<profile>.properties   service + profile specific
4. OS env vars / --spring.*= CLI args     highest precedence, override anything
```

### dev vs pro: who decides?

`spring.profiles.active=dev` in the module is only the **default**. Whatever is
set at launch time always wins:

```bash
# dev (default)
./mvnw -f ebank-bot/pom.xml spring-boot:run

# production - any of these:
./mvnw -f ebank-bot/pom.xml spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=pro
SPRING_PROFILES_ACTIVE=pro java -jar ebank-bot.jar
# docker-compose later: environment: SPRING_PROFILES_ACTIVE: pro
```

To change ports, URLs, prompts, models or credentials for any service — edit `config-repo/` only.

> **Caveat:** `../config-repo/` and `../.env` are relative to the process working
> directory. This works from the IDE (CWD = module folder) and from
> `./mvnw -f <module>/pom.xml spring-boot:run` at the repo root; a packaged jar
> started from another directory silently skips them (`optional:` imports).

---

## 2. Secrets = environment variables (CI/CD friendly)

No secret is committed. Properties in `config-repo/` only reference placeholders
(`${...}`) that Spring resolves from the **`.env` file** at the repo root
(imported first via `spring.config.import=optional:file:../.env`) or, when
containerized, from real **OS environment variables**:

| Property (in `config-repo`) | Environment variable (in `.env`) | Dev default |
|---|---|---|
| `spring.datasource.url` host part | `DB_HOST` | `localhost` |
| `spring.datasource.username` | `DB_USERNAME` | `ebank` |
| `spring.datasource.password` | `DB_PASSWORD` | `ebank` |
| `spring.ai.ollama.api-key` | `OLLAMA_API_KEY` | *(none — required)* |
| `app.tavily.api-key`       | `TAVILY_API_KEY` | *(none — required)* |
| `MARIADB_ROOT_PASSWORD` (docker-compose only) | `MARIADB_ROOT_PASSWORD` | `root` |

Setup: copy `.env.example` → `.env` (gitignored) and fill in the values.
The same `.env` is also auto-read by `docker compose` for variable substitution.

Inside a container the `.env` file does not exist — the `optional:` import
no-ops and the injected OS env vars (`environment:` / `env_file:` in compose)
take over. Same `config-repo` files, both worlds.

Where to provide the values:

- **Local**: the root `.env` file (loaded automatically, no `export` needed)
- **GitHub Actions**: repo *Settings → Secrets → Actions*, expose with `env:` in the job
- **Deployment**: Docker `env_file:` / `-e VAR=...` / Kubernetes `Secret` → env var

---

## 3. AI agent architecture (`ebank-bot`)

Best-practice strategy pattern — the agent never knows which LLM backend it runs on:

```
EbankAIAgent (interface)                     ← used by the controller
  └── DefaultEbankAIAgent (@Service)         ← system prompt, chat memory, tools
        └── AgentProvider (interface)        ← backend SPI
              └── OllamaAgentProvider        ← selected by app.agent.provider=ollama
                    builds ChatClient on Ollama Cloud with Bearer auth
```

- **Swap backend later**: implement `AgentProvider` (e.g. `GrokAgentProvider`), set
  `app.agent.provider=grok` in `config-repo/ebank-bot-*.properties` — no other code changes.
- **System prompt** is a property: `app.agent.system-prompt` (edit in `config-repo`, no rebuild).
- **Tools**: `tools/WebSearchTool` — a single `@Component` with a `@Tool` method calling
  the Tavily search API; registered via `defaultTools(...)`.
- **Chat memory**: `MessageChatMemoryAdvisor` keyed by conversation id.

---

## 4. Database (MariaDB via Docker)

MariaDB runs as a container in both dev and prod (it is part of every compose
invocation below, so there is no separate "start the DB" step anymore).

- `docker/init-db.sh` runs once on an empty volume (`docker-entrypoint-initdb.d`):
  creates `customer-db`, `accounts-db` and the app user
- Credentials come from `.env` (`DB_USERNAME` / `DB_PASSWORD`, root password via
  `MARIADB_ROOT_PASSWORD`) — compose refuses to start without them (`:?` in
  `docker-compose.yml`), so a forgotten `.env` fails fast instead of silently
  falling back to `root`/`ebank`
- Inside containers `DB_HOST=mariadb` is injected by compose (DNS name); on the
  host `.env` keeps `DB_HOST=localhost` — no config file changes needed
- Schema is managed by Hibernate (`spring.jpa.hibernate.ddl-auto=update`);
  seed data is inserted only when tables are empty (restart-safe)
- Data lives in the named volume `ebank-ms_mariadb-data` (project name is fixed
  by `name: ebank-ms`, so the volume no longer depends on your checkout folder.
  Migrating data from an old run: `docker volume rename project-spring-ai_mariadb-data ebank-ms_mariadb-data`)

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml down     # stop (data kept)
docker compose -f docker-compose.yml -f docker-compose.dev.yml down -v  # stop and delete data
```

---

## 5. Run everything

Prerequisites: Docker (dev mode: no JDK needed on the host), the two API keys in
`.env` (see §2); no local Ollama install needed (the chatbot calls Ollama Cloud).

The whole stack is containerized. One shared base file (`docker-compose.yml`)
plus one overlay per mode — same images, same `config-repo/`, different profile:

| Mode | Command | What you get |
|------|---------|--------------|
| **dev** | `docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build --wait` | `dev` profile, every service + MariaDB published on `127.0.0.1` (8761/9999/8056/8057/8058/3306) |
| **prod** | `docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build --wait` | `pro` profile, only the gateway on `9999`, read-only rootfs, no capabilities, memory limits |

```bash
# once: secrets
cp .env.example .env   # fill in the real values

# DEV - everything on localhost
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build --wait

# PROD - hardened, gateway-only
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build --wait

docker compose -f docker-compose.yml -f docker-compose.dev.yml ps    # health at a glance
docker compose -f docker-compose.yml -f docker-compose.dev.yml logs -f gateway-service
docker compose -f docker-compose.yml -f docker-compose.dev.yml down
```

`--wait` blocks until every healthcheck (MariaDB + `/actuator/health` of each
service) reports healthy. Ordering is enforced by the compose file
(`depends_on: condition: service_healthy`): MariaDB/Eureka first, the rest after.

How the two control files map to the containers:

- `config-repo/` is bind-mounted **read-only** at `/app/config-repo` — the jars
  run in `/app/<module>` and import `../config-repo/...` exactly like the IDE.
  Change a property, `docker compose restart <service>` — no rebuild.
- `.env` is injected as real OS environment variables (`env_file`). In a
  container `../.env` doesn't exist, so the `optional:` import no-ops and the
  env vars win. Compose also reads the same `.env` for `${...}` substitution
  (DB bootstrap, `IMAGE_TAG`).

### Alternative: run services from the host (hot IDE loop)

```bash
# 1. database (+ ports for the host, from the dev overlay)
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d mariadb --wait

# 2. services, each in its own terminal (order matters: discovery first)
./mvnw -f discovery-service/pom.xml spring-boot:run
./mvnw -f gateway-service/pom.xml   spring-boot:run
./mvnw -f customer-service/pom.xml  spring-boot:run
./mvnw -f ebank-service/pom.xml     spring-boot:run
./mvnw -f ebank-bot/pom.xml         spring-boot:run
```

Note: host-run services talk to the containerized Eureka via
`http://localhost:8761/eureka` (`config-repo/global.properties`).

---

## 6. Endpoints

| Service | Endpoint |
|---------|----------|
| Eureka dashboard | http://localhost:8761 |
| Gateway | http://localhost:9999 |
| Customers | http://localhost:8056/customers |
| Accounts | http://localhost:8057/accounts |
| Chatbot | http://localhost:8058/chat?query=Bonjour |
| Bot health | http://localhost:8058/actuator/health |
| Swagger UI | http://localhost:8056/swagger-ui/index.html (per service) |

### Test the chatbot (LLM + tool + memory)

```bash
# plain chat
curl --get http://localhost:8058/chat --data-urlencode "query=Salut, qui es-tu ?"

# forces the webSearch tool (Tavily)
curl --get http://localhost:8058/chat \
  --data-urlencode "query=Use webSearch: current EUR/USD rate? Reply with rate and source URL."

# same conversation remembers previous answers (chat memory)
curl --get http://localhost:8058/chat --data-urlencode "query=What rate did you just give me?"
```

---

## 7. Build

```bash
./mvnw -f customer-service/pom.xml -DskipTests package
# or compile any module the same way:
./mvnw -f ebank-bot/pom.xml -DskipTests compile
```

## Project layout

```
Dockerfile              ← multi-stage build shared by all 5 modules (ARG MODULE)
.dockerignore           ← keeps .env / target / config-repo out of build contexts
docker-compose.yml      ← BASE stack (both modes, no host ports)
docker-compose.dev.yml  ← DEV overlay (dev profile + ports on 127.0.0.1)
docker-compose.prod.yml ← PROD overlay (pro profile, hardened, gateway only)
docker/                 ← MariaDB init script (env-driven: DB_USERNAME/DB_PASSWORD)
config-repo/            ← all configuration (global + per-service dev/pro)
.env                    ← secrets (gitignored, loaded by every module + compose)
.env.example            ← template for .env (documentation only)
customer-service/       ← REST API + JPA (MariaDB)
ebank-service/          ← accounts + OpenFeign (MariaDB)
discovery-service/      ← Eureka
gateway-service/        ← Spring Cloud Gateway
ebank-bot/              ← Spring AI agent (Ollama Cloud + Tavily tool)
```
