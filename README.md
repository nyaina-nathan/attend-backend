# attend

A single-module Spring Boot REST backend for **events & ticketing**: users create events,
define ticket tiers, issue tickets, check attendees in by scanning a ticket's QR code, and
view per-event stats. Authentication is JWT-based over `HttpOnly` cookies, and roles are
scoped **per event** rather than globally.

## Tech stack

| | |
|---|---|
| Language | Java 21 |
| Build | Gradle (wrapper 9.7.1) |
| Framework | Spring Boot 4.1.1 — Web MVC, Security, Data JPA, Flyway |
| Database | PostgreSQL (schema owned by Flyway, Hibernate `ddl-auto: validate`) |
| Auth | JWT via `io.jsonwebtoken` (jjwt 0.13.0) |
| Misc | Lombok |
| Testing | JUnit 5, Mockito, Testcontainers (`postgres:latest`) |

## Prerequisites

- JDK 21
- Docker — required for the test suite (Testcontainers pulls `postgres:latest`)
- A PostgreSQL instance for local runs

## Configuration

All configuration comes from environment variables; there is **no `.env` auto-loader**.
VS Code's launch config (`.vscode/launch.json`) reads `.env`, but from a shell you must
export or `source` the values yourself. Copy `.env.example` to `.env` as a starting point.

| Variable | Required | Default | Purpose |
|---|---|---|---|
| `DB_URL` | yes | — | JDBC URL, e.g. `jdbc:postgresql://localhost:5432/attend` |
| `DB_USERNAME` | yes | — | Database user |
| `DB_PASSWORD` | yes | — | Database password |
| `DB_POOL_MAX_SIZE` | no | `10` | Hikari maximum pool size |
| `DB_POOL_MIN_IDLE` | no | `2` | Hikari minimum idle connections |
| `JPA_SHOW_SQL` | no | `false` | Log executed SQL |
| `JWT_SECRET` | yes | — | HMAC key; must be **≥ 256 bits (32+ ASCII chars)** or startup fails |
| `JWT_EXPIRATION_ACCESS` | yes | — | Access-token TTL, e.g. `15m` |
| `JWT_EXPIRATION_REFRESH` | yes | — | Refresh-token TTL, e.g. `7d` |
| `COOKIE_SECURE` | no | `true` | Send auth cookies only over HTTPS |
| `COOKIE_SAME_SITE` | no | `None` | Cookie `SameSite` (`None`, `Lax`, `Strict`) |
| `CORS_ALLOWED_ORIGIN` | yes | — | Exact frontend origin allowed to send credentialed requests |
| `REFRESH_ROTATION_THRESHOLD` | no | `24h` | Rotate refresh token when within this window of expiry |

> A cross-site frontend needs `COOKIE_SAME_SITE=None`, which browsers only accept together
> with `COOKIE_SECURE=true` over HTTPS. Set `CORS_ALLOWED_ORIGIN` to that same frontend
> origin — it must be an exact origin (no `*`), because the cookies are credentialed.

## Running locally

```bash
# Export or source the variables first, e.g.
set -a; source .env; set +a

./gradlew bootRun
```

The API is served under `/api` on port 8080 by default.

## API

The authoritative references are [`docs/API.md`](docs/API.md) (spec, examples, error
format, authorization model) and [`docs/openapi.yaml`](docs/openapi.yaml). Read them before
changing an endpoint.

- **Base URL:** `/api`, JSON, UTF-8.
- **Auth:** `access_token` and `refresh_token` are set as `HttpOnly` cookies — there is no
  `Authorization` header. Endpoints marked 🔒 need a valid access cookie.
- **Errors:** a consistent `ApiError` body
  `{ timestamp, status, error, message, path, details[] }`.
- **Pagination:** list endpoints use `page`/`size` (size 1–100) and return
  `{ content, page, size, totalElements, totalPages }`.

### Endpoint summary

| Method | Path | Auth |
|---|---|---|
| POST | `/auth/register`, `/auth/login`, `/auth/refresh` | public |
| POST | `/auth/logout` | 🔒 |
| GET / PATCH / DELETE | `/users/me` | 🔒 |
| GET | `/users/me/dashboard` | 🔒 |
| POST | `/users/me/password` | 🔒 |
| POST | `/events` | 🔒 |
| GET | `/events`, `/events/{eventId}`, `/events/{eventId}/tiers` | optional (visibility rules apply) |
| PATCH / POST cancel / DELETE | `/events/{eventId}` | creator |
| GET / POST | `/events/{eventId}/organizers` | organizer / creator |
| DELETE | `/events/{eventId}/organizers/{organizerId}` | creator |
| POST | `/events/{eventId}/tiers` | organizer |
| PATCH / DELETE | `/tiers/{tierId}` | organizer |
| POST | `/tiers/{tierId}/tickets` | user |
| GET | `/tickets/me`, `/tickets/{ticketId}` | 🔒 |
| POST | `/tickets/{ticketId}/check-in`, `/refund` | organizer |
| POST | `/tickets/{ticketId}/cancel` | owner |
| GET | `/events/{eventId}/tickets`, `/events/{eventId}/stats` | organizer |

## Testing

The full suite needs a running Docker daemon because integration tests use a real
PostgreSQL from Testcontainers.

```bash
./gradlew test                                                   # everything
./gradlew test --tests "mg.improve.attend.service.TicketServiceTests"
./gradlew test --tests "mg.improve.attend.service.TicketServiceTests.someMethod"
./gradlew build -x test                                          # compile + jar, no Docker
```

- Unit tests (`service/*Tests.java`): plain Mockito, no Spring context, no Docker.
- Integration tests (`controller/*IT.java`): real HTTP on a random port against real
  Postgres; the base class truncates all tables before each test.
- `AttendApplicationTests`: context smoke test (also needs Docker).

## Project structure

```
src/main/java/mg/improve/attend/
├── config/          # Security, CORS, properties records, password encoder
├── domain/
│   ├── entity/       # plain domain objects (no JPA annotations)
│   ├── mapper/       # translate between domain objects and JPA entities
│   ├── dto/          # request/response DTOs
│   └── enums/
├── repository/
│   ├── entities/     # J* JPA entities (@Entity, @Table)
│   └── J*Repository
├── rest/controller/  # HTTP layer, delegates to services
├── security/         # JWT filter/service, cookie factory, user details
├── service/          # transactions + business rules (per-event role checks)
├── validator/        # request/query validation (collects every error at once)
└── exceptions/       # ApiException subtypes + GlobalExceptionHandler
src/main/resources/
├── application.yaml
└── db/migration/     # Flyway migrations (V*__*.sql)
```

## Architecture notes

- **Layering:** `rest/controller → service → (domain/mapper) → repository`.
- **Two model types on purpose:** `repository/entities/J*` are JPA entities; `domain/entity/*`
  are plain domain objects. Mappers translate between them; repositories only handle `J*`.
- **Validation** lives in `validator/*` (collects all errors at once), not controllers.
- **Security is two-tier:** `config/SecurityConfig` only decides *authenticated vs public* at
  the URL level. Per-event roles are enforced in services via `event_organizer` rows. The JWT
  carries identity only — no role claims.
- **Errors:** services throw `ApiException` subtypes (`BadRequestException`,
  `NotFoundException`, `ForbiddenException`, `ConflictException`, …);
  `exceptions/GlobalExceptionHandler` turns them into the `ApiError` body.
- **Schema:** Flyway owns it and Hibernate validates against it. Any entity change needs a
  **new** `src/main/resources/db/migration/V*__*.sql`; never edit an applied migration.

## Continuous integration

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs the full test suite and a
`build -x test` on pull requests targeting `main`.
