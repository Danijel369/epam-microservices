# Microservices: Resource Service, Song Service & Resource Processor

A microservices system for MP3 file processing and song-metadata management, with a
**Eureka service registry** for service registration, discovery and client-side load balancing,
a **LocalStack S3 emulator** as the object store for the uploaded MP3 files, and a
**RabbitMQ message broker** for async event delivery.

- **Eureka Server** (port `8761`) — the service registry. Resource Service and Song Service register
  here; Resource Processor uses it for discovery only (`register-with-eureka: false`). The dashboard
  therefore lists only **RESOURCE-SERVICE** and **SONG-SERVICE**.
- **Resource Service** (port `8081`) — accepts MP3 uploads, **stores the file in S3** (LocalStack)
  and keeps only the object location in its database. After a successful upload it **publishes the
  `resourceId` to RabbitMQ** for async processing. On delete it removes the object from S3 and the
  row from the database, then **cascades the deletion synchronously to the Song Service**, which it
  **discovers through Eureka** (not a fixed URL).
- **Song Service** (ports `8082`/`8083`) — manages song metadata, keyed by the Resource ID. Runs as
  **two instances**; both the Resource Service (DELETE cascade) and the Resource Processor
  (POST `/songs`) load-balance across them via Eureka.
- **Resource Processor** — a **non-web** Spring Boot application that listens to the RabbitMQ queue,
  fetches the uploaded MP3 bytes from the Resource Service, extracts MP3 tags with Apache Tika
  (name, artist, album, duration as `mm:ss`, year), and saves the metadata to the Song Service.

## Architecture

```
                                           ┌──────────────────────┐
                                           │  Eureka Server 8761  │
                                           └──────────▲───────────┘
                                                      │  register + discover (processor: discover only)
               ┌──────────────────────────────────────┴────────────────────────────────┐
┌──────────────┴───────────────┐ 1. publish ┌──────────────────┐ 2. consume ┌──────────┴─────────┐
│       resource-service       │───────────▶│  RabbitMQ  5672  │───────────▶│ resource-processor │
│             8081             │            │ queue: resource. │            │     (non-web)      │
│                              │            │    processing    │            │                    │
│                              │            └──────────────────┘            │                    │
│                              │◀───────────────────────────────────────────│                    │
│                              │   3. GET /resources/{id} (sync, binary)    │                    │
└─────┬───────────────┬───────┬┘                                            └──────────┬─────────┘
      │               │       └───────────────────────────────────────┐                │  4. POST /songs
      ▼               ▼         DELETE cascade (sync, load-balanced)  │                │  (sync, load-balanced)
┌────────────┐ ┌─────────────┐                                        │                ▼
│ LocalStack │ │ resource-db │                                        │   ┌────────────────────────┐
│  S3  4566  │ │  postgres   │                                        └──▶│    song-service  ×2    │
└────────────┘ │    5432     │                                            │       8082 / 8083      │
               └─────────────┘                                            └────────────┬───────────┘
 (MP3 bytes)   (S3 location)                                                           ▼
                                                                              ┌────────────────┐
                                                                              │    song-db     │
                                                                              │ postgres  5433 │
                                                                              └────────────────┘
```

On **delete** the Resource Service removes the S3 object and DB row, then calls the Song Service
through a `@LoadBalanced RestClient` whose base URL is the logical id `http://song-service`; Spring
Cloud LoadBalancer resolves that id against the Eureka registry and round-robins across the registered
instances. The Resource Processor uses the same load-balanced approach for `POST /songs`. The Song
Service ignores ids it does not know, so deleting an unknown id is a no-op.

### Upload flow (hybrid async + sync)

1. **Client → Resource Service**: POST `/resources` with MP3 binary.
2. **Resource Service**: validates content type + MP3 magic bytes → stores file in S3 → persists
   S3 location in DB → **publishes `resourceId` to the `resource.processing` queue** (async) → returns
   `{"id": <resourceId>}` to the client immediately (publish failure is logged but does not fail the upload).
3. **Resource Processor** (consumer): receives `resourceId` from the queue → makes a synchronous
   **GET** `/resources/{id}` to the Resource Service to fetch the MP3 bytes → extracts metadata with
   Apache Tika → makes a synchronous **POST** `/songs` to the Song Service to persist the metadata.

### Delete flow (synchronous)

DELETE stays a synchronous cascade: Resource Service removes the S3 object and DB row, then calls
`DELETE /songs?id=…` on the Song Service via the load-balanced RestClient (resolved through Eureka).
RabbitMQ is not involved.

## Retry strategy

| Layer | Mechanism | Config |
|-------|-----------|--------|
| **Publish (Resource Service → broker)** | Spring AMQP `template.retry`: max 3 attempts, 1 s initial backoff | `spring.rabbitmq.template.retry.*` |
| **Consume (broker → Resource Processor)** | Spring AMQP `listener.simple.retry`: max 3 attempts, 1 s initial backoff; **permanent failures (`AmqpRejectAndDontRequeueException`) are never retried** (custom `RabbitRetryTemplateCustomizer` with `traverseCauses=true`) | `spring.rabbitmq.listener.simple.retry.*` |
| **Sync calls (Processor → Resource Service / Song Service)** | `@Retryable` for transient faults (`ResourceAccessException`, `HttpServerErrorException`) only | `maxAttempts = 2`, 500 ms backoff, set in the annotation on the client methods; listener retry × `@Retryable` compound |
| **HTTP timeouts** | `SimpleClientHttpRequestFactory` with `connectTimeout` (2 s) and `readTimeout` (5 s) | defaults in `application.yaml`; see optional overrides note in env-vars section |

**Permanent failures** are logged and dropped: the listener throws `AmqpRejectAndDontRequeueException`,
the broker discards the message without requeue. No DLQ is configured. Permanent failures include:
- Unparseable MP3 (`InvalidMp3Exception` from the extractor)
- Missing required tags → Song Service returns 400
- Any other non-409 4xx
- 404 from Resource Service (resource deleted before processing completed)

### Idempotency

`resourceId` is the Song Service primary key. If the processor handles a redelivered message, the
Song Service returns **409 Conflict**; the processor treats 409 as success (ack) and continues — no
duplicate row is created.

## Known limitations

- If publish fails after a successful upload, the resource is stored in S3 but its metadata is never
  processed (no Outbox/saga).
- Messages that exhaust listener retries are dropped (no DLQ/DLX).
- Both will be addressed in Module 6 (Fault tolerance).

## Tech stack

- Java 17, Spring Boot 3.4.1, Maven (no Lombok, no Kotlin)
- Spring Cloud 2024.0.1 (Moorgate): Netflix Eureka **server** + **client**, and Spring Cloud
  LoadBalancer (pulled in by the Eureka client starter) for client-side load balancing
- AWS SDK for Java v2 (BOM 2.55.14): `s3` + the `apache-client` synchronous HTTP client
- LocalStack 3.8 as the S3 emulator (S3 service only)
- **RabbitMQ 3.13** (`rabbitmq:3.13-management-alpine`, management UI at `:15672`) — one durable
  queue (`resource.processing`), Spring AMQP with `Jackson2JsonMessageConverter`, dedicated user
  `app`/`app` (the default `guest` user is loopback-only and fails container-to-container)
- PostgreSQL 17 (Alpine) — one database per service, run in Docker
- Schema created by SQL init scripts inside the DB containers; Hibernate `ddl-auto=none`
  (no Flyway/Liquibase, no `schema.sql`/`data.sql`)
- Apache Tika 2.9.2 (`tika-core` + `tika-parser-audiovideo-module`) for MP3 tag extraction — used by
  the **Resource Processor**
- Spring `RestClient` (load-balanced) for service-to-service calls; Spring Retry (`@Retryable`) for
  sync transient-fault retries in the processor
- Two-stage Docker builds (Temurin Alpine), orchestrated with a single `compose.yaml`

## Project layout

```
epam-microservices-task/
├── init-scripts/
│   ├── resource-db/init.sql   # CREATE TABLE resources (id, s3_location)
│   └── song-db/init.sql       # CREATE TABLE songs (...)
├── eureka-server/
│   ├── src/ · pom.xml · Dockerfile (EXPOSE 8761) · .dockerignore
├── resource-service/
│   ├── src/ · pom.xml · Dockerfile (EXPOSE 8081) · .dockerignore
├── song-service/
│   ├── src/ · pom.xml · Dockerfile (EXPOSE 8082, scaled to 2 instances) · .dockerignore
├── resource-processor/        # non-web; consumes RabbitMQ, calls Resource Service + Song Service
│   ├── src/ · pom.xml · Dockerfile (no port exposed)
├── postman/
│   └── microservices_fundamentals_module_1.postman_collection.json
├── compose.yaml               # Eureka + RabbitMQ + LocalStack + DBs + all services
├── .env                       # DB / S3 / Eureka / RabbitMQ credentials and endpoints
└── .gitignore
```

## Prerequisites

- Docker with Docker Compose (developed with **OrbStack** on macOS)
- For local mode: JDK 17+ and Maven 3.9+

> **Apple Silicon note:** the task's recommended base images
> (`maven:3.9-eclipse-temurin-17-alpine`, `eclipse-temurin:17-jre-alpine`) are published for
> `linux/amd64` only, so on an ARM Mac the JVM service containers run under emulation. This is made
> explicit with `platform: linux/amd64` on the JVM service blocks in `compose.yaml` (LocalStack and
> RabbitMQ are multi-arch and run natively). Everything works unchanged, builds are just a little
> slower than native.

## Environment variables (`.env`)

`compose.yaml` substitutes these; the services also fall back to `localhost` defaults baked into
`application.yaml`, so the same build runs in Docker and locally with no profile switching.

| Variable | Purpose |
|----------|---------|
| `RESOURCE_DB_NAME` / `RESOURCE_DB_USER` / `RESOURCE_DB_PASSWORD` / `RESOURCE_DB_URL` | Resource Service database |
| `SONG_DB_NAME` / `SONG_DB_USER` / `SONG_DB_PASSWORD` / `SONG_DB_URL` | Song Service database |
| `EUREKA_URL` | Registry URL the clients register with and fetch from |
| `EUREKA_PREFER_IP_ADDRESS` | In Docker, register the container IP (distinct instances when scaled) |
| `S3_ENDPOINT` | S3 endpoint (LocalStack: `http://localstack:4566` in Docker, `http://localhost:4566` locally) |
| `S3_REGION` | S3 region (e.g. `us-east-1`) |
| `S3_BUCKET` | Bucket for the MP3 objects (`resource-files`) |
| `S3_ACCESS_KEY` / `S3_SECRET_KEY` | Dummy credentials — LocalStack accepts any (no real secret) |
| `S3_PATH_STYLE` | `true` — path-style addressing, required by the emulator |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | RabbitMQ broker address |
| `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | RabbitMQ credentials (`app`/`app` — same dummy-cred approach as DB/S3) |
| `RESOURCE_QUEUE` | Queue name for resource-processing events (`resource.processing`) |

**Optional overrides (defaults 2000 / 5000 ms):** `HTTP_CLIENT_CONNECT_TIMEOUT_MS` and
`HTTP_CLIENT_READ_TIMEOUT_MS` tune the processor's REST client timeouts. They are not in `.env` —
defaults are defined in `application.yaml` and can be overridden by setting the env vars directly.

## Running the system

The **same** `application.yaml` works in both modes with no profile switching: container-specific
values are injected as environment variables in Docker, and fall back to `localhost` defaults when
run locally.

### Docker mode (everything in containers)

Build and start all services — Eureka, RabbitMQ, LocalStack, both databases, Resource Service,
Song Service (two instances), and Resource Processor — with a single command. Either form produces
the same result (2 song-service replicas on fixed host ports 8082 and 8083):

```bash
# Replicas are declared in compose.yaml (deploy.replicas: 2)
docker compose up -d --build

# Explicit scale flag — equivalent, and overrides deploy.replicas
docker compose up -d --build --scale song-service=2
```

Container ports exposed to the host:

- `eureka-server` → `localhost:8761` (dashboard)
- `rabbitmq` → `localhost:5672` (AMQP), `localhost:15672` (management UI, `app`/`app`)
- `localstack` (S3) → `localhost:4566`
- `resource-db` → `localhost:5432`, `song-db` → `localhost:5433`
- `resource-service` → `localhost:8081`
- `song-service` → **two** instances on `localhost:8082` and `localhost:8083` (both use container port 8082)
- `resource-processor` → no port (background consumer)

- Databases are created by `POSTGRES_DB`; tables are created by the mounted `init-scripts/*/init.sql`.
  The S3 bucket is created by the Resource Service on startup.
- No data volume is used, so `docker compose down` fully resets the databases, the S3 bucket, **and**
  the RabbitMQ queue.

Wait until the Eureka dashboard at **http://localhost:8761** lists **RESOURCE-SERVICE** and
**SONG-SERVICE** (2 instances) before running API tests.

Check status and stop:

```bash
docker compose ps          # 9 containers
docker compose down
```

### Local mode (services on the host, infra in Docker)

Start infrastructure in Docker, then run the registry and services from the host in this order:

```bash
docker compose up -d resource-db song-db localstack rabbitmq

mvn -f eureka-server/pom.xml spring-boot:run      # 1) registry on :8761
mvn -f song-service/pom.xml spring-boot:run       # 2) registers as SONG-SERVICE
mvn -f resource-service/pom.xml spring-boot:run   # 3) registers, discovers song-service
mvn -f resource-processor/pom.xml spring-boot:run # 4) listens to RabbitMQ (no web server)
```

The services use `localhost` defaults baked into `application.yaml` — no profile switching needed:

- Databases: `localhost:5432` (resource-db) and `localhost:5433` (song-db)
- Eureka: `http://localhost:8761/eureka`
- S3 (LocalStack): `http://localhost:4566`
- RabbitMQ: `localhost:5672`

Open **http://localhost:8761** to confirm RESOURCE-SERVICE and SONG-SERVICE are registered before
testing.

## Inspecting S3, the databases, and RabbitMQ

```bash
# S3: list the bucket (one <uuid>.mp3 per uploaded resource)
docker compose exec localstack awslocal s3 ls s3://resource-files/

# resource-db: the row stores the S3 location, not the bytes
docker compose exec resource-db psql -U postgres -d resource_db -c "SELECT id, s3_location FROM resources;"

# song-db: metadata rows
docker compose exec song-db psql -U postgres -d song_db -c "SELECT id, name, artist FROM songs;"

# RabbitMQ management UI: http://localhost:15672  (app / app)
```

## Running the tests

Each service is an independent Maven project:

```bash
mvn -f eureka-server/pom.xml test        # no unit tests (builds clean)
mvn -f resource-service/pom.xml test     # unit tests: upload/get/delete
mvn -f song-service/pom.xml test         # no unit tests (builds clean)
mvn -f resource-processor/pom.xml test   # unit tests: MP3 extraction
```

## API summary

### Resource Service (`http://localhost:8081`)

| Method | Path                | Request                       | Success response          |
|--------|---------------------|-------------------------------|---------------------------|
| POST   | `/resources`        | `Content-Type: audio/mpeg`, binary MP3 body | `200 {"id": 1}` (stored in S3; `resourceId` queued for async processing) |
| GET    | `/resources/{id}`   | —                             | `200` binary (`audio/mpeg`) streamed from S3 |
| DELETE | `/resources?id=1,2` | CSV of ids (max 200 chars)    | `200 {"ids": [1, 2]}` (S3 + DB removed, delete cascaded synchronously to Song Service) |

### Song Service (`http://localhost:8082`)

| Method | Path            | Request                        | Success response          |
|--------|-----------------|--------------------------------|---------------------------|
| POST   | `/songs`        | JSON metadata body             | `200 {"id": 1}`           |
| GET    | `/songs/{id}`   | —                              | `200` metadata JSON       |
| DELETE | `/songs?id=1,2` | CSV of ids (max 200 chars)     | `200 {"ids": [1, 2]}`     |

### Error format

All errors share a unified body; `errorCode` is always a **string**, and `details` appears only
for validation errors:

```json
{ "errorMessage": "Resource with ID=1 not found", "errorCode": "404" }
```

```json
{
  "errorMessage": "Validation error",
  "details": { "duration": "Duration must be in mm:ss format with leading zeros" },
  "errorCode": "400"
}
```

## API testing (Postman / Newman)

The Module 1 Postman collection `postman/microservices_fundamentals_module_1.postman_collection.json`
covers the Resource Service upload, get and delete flows (valid cases and the 400/404 error cases),
using collection variables and test scripts that assert status codes and response structure.

Run the **full stack** first (the delete requests cascade to the Song Service), then run the
collection headless with Newman from the repo root:

```bash
npx newman run postman/microservices_fundamentals_module_1.postman_collection.json \
  --working-dir postman
```

- `--working-dir postman` lets the MP3 upload body resolve its relative path
  (`mp3/valid-sample-with-required-tags.mp3`).
- `baseUrl` defaults to `http://localhost:8081`; `resourceId` and `mixedId` are set by the upload
  test scripts at run time.
- In the Postman desktop app, select the MP3 file for the two upload requests via the file picker.

All **18 assertions across 11 requests** pass against the full stack.
