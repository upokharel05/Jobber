# Jobber

A distributed background job scheduling and execution platform. Applications submit jobs
(emails, reports, data processing, exports) over a REST API; Jobber queues them, distributes
them across workers, tracks their status, and handles failures and retries.

## Modules

| Module          | Type             | Responsibility                                   |
|-----------------|------------------|--------------------------------------------------|
| `jobber-core`   | Library          | Shared job domain model and contracts            |
| `jobber-api`    | Spring Boot app  | REST API: job submission, status, auth (`:8080`) |
| `jobber-worker` | Spring Boot app  | Consumes and executes jobs (`:8081`)             |

## Prerequisites

- JDK 25
- Docker (with Compose) — also used by the tests, which start throwaway Postgres containers

Maven does not need to be installed; use the included wrapper (`./mvnw`, or `mvnw.cmd` on Windows).

## Running locally

```bash
# Start infrastructure (Postgres on localhost:5433)
docker compose up -d

# Build and test everything
./mvnw clean verify

# Run the API and a worker (separate terminals)
./mvnw -pl jobber-api spring-boot:run
./mvnw -pl jobber-worker spring-boot:run

# Health checks
curl localhost:8080/actuator/health
curl localhost:8081/actuator/health
```
