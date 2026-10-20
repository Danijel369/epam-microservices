# Microservices: Resource Service, Song Service & Resource Processor

A microservices system for MP3 file processing and song-metadata management, with a
**Eureka service registry** for service registration, discovery and client-side load balancing,
and a **LocalStack S3 emulator** as the object store for the uploaded MP3 files.

- **Eureka Server** (port `8761`) — the service registry. All services register here and discover
  each other through it.
- **Resource Service** (port `8081`) — accepts MP3 uploads, **stores the file in S3** (LocalStack)
  and keeps only the object location in its database. On delete it removes the object from S3 and the
  row from the database, then **cascades the deletion to the Song Service**, which it **discovers
  through Eureka** (not a fixed URL).
- **Song Service** (ports `8082`/`8083`) — manages song metadata, keyed by the Resource ID. Runs as
  **two instances** so the Resource Service load-balances across them.
- **Resource Processor** — a standalone, **non-web** Spring Boot application that extracts MP3 tags
  with Apache Tika (name, artist, album, duration as `mm:ss`, year). It is **build-only** in this
  module: it is not part of `compose.yaml` and has no runtime wiring yet (it processes files in the
  upcoming modules).

## Architecture

```
                          ┌──────────────────────┐
                          │  Eureka Server 8761  │  (registry + dashboard)
                          └──────────▲───────────┘
               register / discover   │   register (heartbeats)
        ┌────────────────────────────┼────────────────────────────┐
        │                            │                            │
┌───────┴─────────┐  delete cascade (LB)   ┌────────────────┴──────┐
│ resource-service │───────────────────────▶│   song-service ×2     │
│      8081        │  http://song-service    │     8082 / 8083       │
└──┬────────────┬──┘  (resolved via Eureka)  └───────────┬───────────┘
   │ store/get  │                                        │
   │ MP3 object ▼                                        ▼
   │     ┌───────────────┐                       ┌───────────────┐
   │     │  LocalStack   │                       │    song-db    │
   │     │   S3  4566    │                       │ postgres 5433 │
   │     └───────────────┘                       └───────────────┘
   ▼
┌───────────────┐
│  resource-db  │  stores the S3 object location (s3://resource-files/<uuid>.mp3), not the bytes
│ postgres 5432 │
└───────────────┘

resource-processor — standalone, non-web Spring Boot app (Apache Tika MP3 tag extraction).
                     Build-only in this module: not wired into compose.yaml.
```

On **upload** the Resource Service validates the content type and the MP3 magic bytes, stores the file
in the S3 bucket and persists only its `s3_location`. On **get** it streams the bytes back from S3. On
**delete** it removes the S3 object and the database row, then calls the Song Service through a
`@LoadBalanced RestClient` whose base URL is the logical id `http://song-service`; Spring Cloud
LoadBalancer resolves that id against the Eureka registry and round-robins across the registered
instances. The Song Service ignores ids it does not know, so deleting an unknown id is a no-op.

## Tech stack

- Java 17, Spring Boot 3.4.1, Maven (no Lombok, no Kotlin)
- Spring Cloud 2024.0.1 (Moorgate): Netflix Eureka **server** + **client**, and Spring Cloud
  LoadBalancer (pulled in by the Eureka client starter) for client-side load balancing
- AWS SDK for Java v2 (BOM 2.55.14): `s3` + the `apache-client` synchronous HTTP client
- LocalStack 3.8 as the S3 emulator (S3 service only)
- PostgreSQL 17 (Alpine) — one database per service, run in Docker
- Schema created by SQL init scripts inside the DB containers; Hibernate `ddl-auto=none`
  (no Flyway/Liquibase, no `schema.sql`/`data.sql`)
- Apache Tika 2.9.2 (`tika-core` + `tika-parser-audiovideo-module`) for MP3 tag extraction — used by
  the **Resource Processor**
- Spring `RestClient` (load-balanced) for service-to-service calls
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
├── resource-processor/        # standalone, non-web; build-only in this module
│   ├── src/ · pom.xml · Dockerfile (no port exposed)
├── postman/
│   └── microservices_fundamentals_module_1.postman_collection.json
├── compose.yaml               # Eureka + LocalStack + DBs + both services
├── .env                       # DB names/users/passwords + Eureka + S3 settings
└── .gitignore
```

## Prerequisites

- Docker with Docker Compose (developed with **OrbStack** on macOS)
- For local mode and for building the Resource Processor: JDK 17+ and Maven 3.9+

> **Apple Silicon note:** the task's recommended base images
> (`maven:3.9-eclipse-temurin-17-alpine`, `eclipse-temurin:17-jre-alpine`) are published for
> `linux/amd64` only, so on an ARM Mac the JVM service containers run under emulation. This is made
> explicit with `platform: linux/amd64` on the JVM service blocks in `compose.yaml` (LocalStack is
> multi-arch and runs natively). Everything works unchanged, builds are just a little slower than
> native. The same flag is needed when building the Resource Processor image directly:
> `docker build --platform linux/amd64 -t resource-processor resource-processor`. (Verify emulation
> with `docker run --rm --platform linux/amd64 alpine uname -m` → `x86_64`.)

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

## Running the system

The **same** `application.yaml` works in both modes with no profile switching: container-specific
values are injected as environment variables in Docker, and fall back to `localhost` defaults when
run locally.

### Docker mode (everything in containers)

Build and start Eureka, LocalStack, both databases and both services — with the Song Service scaled to
**two instances** — with a single command. Either form produces the same result (2 song-service
replicas on fixed host ports 8082 and 8083):

```bash
# Replicas are declared in compose.yaml (deploy.replicas: 2)
docker compose up -d --build

# Explicit scale flag — equivalent, and overrides deploy.replicas
docker compose up -d --build --scale song-service=2
```

- `eureka-server` → `localhost:8761` (dashboard)
- `localstack` (S3) → `localhost:4566`
- `resource-db` → `localhost:5432`, `song-db` → `localhost:5433`
- `resource-service` → `localhost:8081`
- `song-service` → **two** instances on `localhost:8082` and `localhost:8083` (both container port 8082)
- The Resource Service reaches the Song Service internally through Eureka, load-balancing across both
  replicas; it stores/reads MP3 objects in LocalStack S3.
- Databases are created by `POSTGRES_DB`; tables are created by the mounted `init-scripts/*/init.sql`.
  The S3 bucket is created by the Resource Service on startup.
- No data volume is used, so `docker compose down` fully resets the databases **and** the S3 bucket.

Open **http://localhost:8761** and wait until it lists **1 instance of RESOURCE-SERVICE** and
**2 instances of SONG-SERVICE** before running the API tests (registration takes a few seconds).

Check status and stop:

```bash
docker compose ps          # 7 containers: eureka, localstack, resource-service, 2× song-service, 2 DBs
docker compose down
```

### Local mode (services on the host, infra in Docker)

Start the databases **and LocalStack** in Docker, then run the registry and the services from the host
**in this order** (Eureka first so the clients can register with it):

```bash
docker compose up -d resource-db song-db localstack

mvn -f eureka-server/pom.xml spring-boot:run     # 1) registry on :8761
mvn -f song-service/pom.xml spring-boot:run      # 2) registers as SONG-SERVICE
mvn -f resource-service/pom.xml spring-boot:run  # 3) registers, discovers song-service
```

The services use the `localhost` defaults for the databases (`5432`/`5433`), the registry
(`http://localhost:8761/eureka`) and S3 (`http://localhost:4566`), all baked into `application.yaml` —
so no configuration change or Spring profile is needed. Open **http://localhost:8761** to confirm both
services are registered before testing.

### Resource Processor (build-only)

The Resource Processor is a standalone, non-web application; it is not part of the running stack in
this module. Build and run it on its own:

```bash
mvn -f resource-processor/pom.xml clean package              # produces the runnable jar
java -jar resource-processor/target/resource-processor-*.jar # starts, logs, exits (no web server)
docker build --platform linux/amd64 -t resource-processor resource-processor
```

## Inspecting S3 and the databases

With the stack up, look at the stored MP3 objects and the database rows:

```bash
# S3: list the bucket (empty until the first upload, then one <uuid>.mp3 per resource)
docker compose exec localstack awslocal s3 ls s3://resource-files/

# resource-db: the row stores the S3 location, not the bytes
docker compose exec resource-db psql -U postgres -d resource_db -c "SELECT id, s3_location FROM resources;"

# song-db: metadata rows
docker compose exec song-db psql -U postgres -d song_db -c "SELECT id, name, artist FROM songs;"
```

## Running the tests

Each service is an independent Maven project:

```bash
mvn -f eureka-server/pom.xml test        # no unit tests (builds clean)
mvn -f resource-service/pom.xml test     # 34 tests
mvn -f song-service/pom.xml test         # no unit tests (builds clean)
mvn -f resource-processor/pom.xml test   # 3 tests (MP3 tag extraction)
```

## API summary

### Resource Service (`http://localhost:8081`)

| Method | Path                | Request                       | Success response          |
|--------|---------------------|-------------------------------|---------------------------|
| POST   | `/resources`        | `Content-Type: audio/mpeg`, binary MP3 body | `200 {"id": 1}` (stored in S3) |
| GET    | `/resources/{id}`   | —                             | `200` binary (`audio/mpeg`) streamed from S3 |
| DELETE | `/resources?id=1,2` | CSV of ids (max 200 chars)    | `200 {"ids": [1, 2]}` (S3 + DB removed, delete cascaded to Song Service) |

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
