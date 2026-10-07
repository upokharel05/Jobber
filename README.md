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
# Start infrastructure: Postgres (localhost:5433) and RabbitMQ (localhost:5672)
docker compose up -d

# Build and test everything
./mvnw clean verify

# Package runnable jars. Always build from `clean`: Maven does not rebuild an app jar when only
# jobber-core changed, so an incremental build can silently bundle stale core code.
./mvnw clean package -DskipTests

# Run the API and one or more workers (separate terminals)
java -jar jobber-api/target/jobber-api-0.0.1-SNAPSHOT.jar
java -jar jobber-worker/target/jobber-worker-0.0.1-SNAPSHOT.jar --server.port=8081 --jobber.worker.id=worker-A
java -jar jobber-worker/target/jobber-worker-0.0.1-SNAPSHOT.jar --server.port=8082 --jobber.worker.id=worker-B

# Submit a job and check on it
curl -X POST localhost:8080/jobs -H "Content-Type: application/json"      -d '{"type": "email.send", "payload": {"to": "a@example.com"}}'
curl localhost:8080/jobs/1
```

RabbitMQ's management UI is at http://localhost:15672 (user `jobber`, password `jobber`).

## How a job flows

1. `POST /jobs` stores the job as `SCHEDULED` (a reused `Idempotency-Key` returns the original job).
2. The dispatcher (in `jobber-api`) finds due jobs, publishes their IDs to RabbitMQ, waits for the
   broker's publisher confirms, then marks them `QUEUED`.
3. A worker receives the ID, claims the job (`QUEUED` -> `RUNNING`) with a conditional update so only
   one worker can win, runs it, and marks it `SUCCEEDED`.
4. If the handler throws, the worker records the outcome in Postgres and acknowledges the message:
   - a temporary error with attempts left -> back to `SCHEDULED`, due again after exponential backoff
     with jitter (10s, 20s, 40s ... capped at 10 min, randomized to 50-100%);
   - a `PermanentJobFailureException`, or the last attempt failing -> `FAILED` (final; see `lastError`).
5. Messages the worker cannot process at all (unreadable, or for a job that doesn't exist) are
   dead-lettered to the `jobber.jobs.dead` RabbitMQ queue. Job failures never go there.

## Simulating work and failures

Job handlers are currently simulated. Control them through an optional `simulate` object in the payload:

```json
{"type": "data.process", "maxAttempts": 3,
 "payload": {"simulate": {"durationMs": 500, "failTimes": 2, "permanentFailure": false}}}
```

- `durationMs`: how long the "work" takes (default depends on the job type)
- `failTimes`: fail temporarily on attempts 1..n, then succeed
- `permanentFailure`: fail permanently on the first attempt
