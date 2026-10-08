# AGENTS.md

Single-module Spring Boot REST backend for events & ticketing ("attend"). Java 21,
Gradle wrapper 9.7.1, Spring Boot 4.1.1, PostgreSQL + Flyway + JPA, JWT auth via
HttpOnly cookies. Root project name is `attend`.

`docs/API.md` and `docs/openapi.yaml` are the source of truth for endpoints, the error
body, and the authorization model. Read them before changing an API.

## Commands

- `./gradlew test` — full suite. Needs a running Docker daemon: Testcontainers pulls `postgres:latest`.
- `./gradlew test --tests "mg.improve.attend.service.TicketServiceTests"` — single class; append a method name for one test.
- `./gradlew build -x test` — compile + jar, no Docker needed.
- `./gradlew bootRun` — needs Postgres and the env vars below.
- No linter, formatter, or static-analysis plugin is configured. Do not add or assume one.

## Running locally

`src/main/resources/application.yaml` requires env vars for the datasource and JWT (no
defaults) and there is **no `.env` auto-loader**. `.env` is read only by the VS Code
launch config (`.vscode/launch.json` `envFile`). From a shell, export/source the values:
`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `JWT_EXPIRATION_ACCESS`,
`JWT_EXPIRATION_REFRESH`. `JWT_SECRET` must be >= 256 bits (HS256) or startup fails.

## Architecture (not obvious from names)

Per feature: `rest/controller` → `service` → (`domain/mapper`) → `repository`.
Two model types exist on purpose — don't collapse them:

- `repository/entities/J*` are the JPA entities (`@Entity`, `@Table`).
- `domain/entity/*` are plain domain objects with **no JPA annotations**.
- `domain/mapper/*` translates between the two; repositories only handle `J*` types.

DTOs live in `domain/dto/{request,response}`. Request/query validation lives in
`validator/*` (collects all errors at once), not in controllers. Controllers only delegate
to services and shape HTTP responses; services own transactions and business rules.

## Security / authorization

- `config/SecurityConfig` is coarse URL-level only: auth endpoints and three `GET` routes are public, everything else requires authentication. No roles there.
- Per-event roles are enforced in services via `event_organizer` rows (e.g. `requireOrganizer` in `TicketService`). The JWT carries identity only, no role claims. Do not add role checks to `SecurityConfig`.
- Auth uses `access_token` / `refresh_token` HttpOnly cookies, not `Authorization` headers.
- `exceptions/GlobalExceptionHandler` turns thrown exceptions into the `ApiError` body. From services, throw `ApiException` subtypes (`BadRequestException`, `NotFoundException`, `ForbiddenException`, `ConflictException`) instead of building responses.

## Testing

- Unit tests `service/*Tests.java`: plain Mockito (`@ExtendWith(MockitoExtension.class)`), no Spring context, no Docker.
- Integration tests `controller/*IT.java`: extend `IntegrationTestSupport`, real HTTP on a random port against a real Postgres from `TestcontainersConfiguration` (`@ServiceConnection`). Its inherited `@BeforeEach` truncates all tables, so each test seeds its own data.
- `AttendApplicationTests` is a context smoke test and also needs Docker.

## Persistence / schema

- Flyway owns the schema; Hibernate is `ddl-auto: validate`. Any entity change needs a **new** `src/main/resources/db/migration/V*__*.sql`; never edit an applied migration.
- Postgres native enums (`event_status`, `event_role`, `ticket_status`) map with `@JdbcTypeCode(SqlTypes.NAMED_ENUM)`.
- List endpoints paginate with `page`/`size` (size 1..100), bounds enforced by the feature's `validator/*Validator`.

## Gotchas

- Spring Boot 4 uses Jackson 3: `ObjectMapper`/databind are `tools.jackson.*`, but annotations stay `com.fasterxml.jackson.annotation.*`. Both appear in this codebase; don't "unify" them.
- Boot 4 modular starters: this project uses `spring-boot-starter-webmvc`, not the older `-web`.
- `bin/` is gitignored build output, not a scripts directory.
