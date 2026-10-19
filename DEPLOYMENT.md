# Local development and production deployment

## Local IntelliJ IDEA setup

1. Install a JDK 21 distribution. In IntelliJ, set **Project SDK** and **Maven Runner JRE** to JDK 21. Maven's compiler is configured with `--release 21`.
2. Start local PostgreSQL and Redis. For containerized integration testing use the isolated stack below; for an IntelliJ-hosted application use the local values in `.env.example`.
3. Create an IntelliJ Spring Boot run configuration for `com.dukankhata.DukanKhataApplication`. Enter the required environment variables directly in that run configuration, or use a trusted local environment-variable plugin. Spring Boot does not automatically load `.env`; `.env.example` is a reference, not a runtime dependency.
4. Build and test with `mvn clean verify`.

### Isolated container smoke test

The test stack uses disposable PostgreSQL and Redis containers, test-only credentials, and no persistent volumes. It does not connect to Supabase or any production service.

```powershell
docker compose -p dukankhata-test -f docker-compose.test.yml up --build -d
Invoke-WebRequest http://localhost:18080/actuator/health
Invoke-WebRequest http://localhost:18080/actuator/health/readiness
docker compose -p dukankhata-test -f docker-compose.test.yml logs app
docker compose -p dukankhata-test -f docker-compose.test.yml stop app
docker compose -p dukankhata-test -f docker-compose.test.yml logs app
docker compose -p dukankhata-test -f docker-compose.test.yml down
```

The readiness group checks the process readiness state, PostgreSQL, and Redis. Graceful shutdown allows up to 20 seconds per shutdown phase; Compose allows 30 seconds before killing the container. The final `down` removes this test project's containers and network; it has no volumes to delete.

## Production deployment

- Set `SPRING_PROFILES_ACTIVE=prod`. This profile requires database, Redis, JWT, CORS, and Razorpay values. Until a reviewed migration path is proven, it sets Hibernate schema generation to `none` and disables Flyway so an incomplete migration cannot run automatically. This is a temporary safety gate, not an indication that the schema is ready; the application may not serve requests until a compatible schema exists. Do not enable Flyway or schema validation until the migration path is verified.
- The Docker image defaults to the `prod` profile. The disposable Compose test stack explicitly selects the `test` profile and uses test-only credentials.
- Logout revokes the presented JWT immediately in Redis, retaining only its SHA-256 digest until the token's existing expiry. Protected requests fail with `503` if Redis cannot check revocation; they do not silently accept or reinterpret the token.
- Build and publish only the image produced by the `Backend CI and image release` workflow. The publish job depends on successful Java 21 verification and dependency scanning.
- Configure `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `REDIS_URL`, `CORS_ALLOWED_ORIGINS`, `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, and `RAZORPAY_WEBHOOK_SECRET` as deployment-platform secrets/environment variables. `REDIS_URL` may use provider TLS URLs such as `rediss://`. Do not copy `.env` into an image or deployment artifact. Never use the test Compose credentials in production.
- Configure `CORS_ALLOWED_ORIGINS`, pool sizing, login rate limits, worker settings, subscription plan values, and Razorpay credentials from the deployment environment. The current production profile requires Razorpay configuration values at startup; confirm provider credentials before enabling payment operations. Subscription plans are seeded only when a plan code is absent; startup does not overwrite existing plan prices or reactivate an intentionally inactive plan. Treat plan pricing changes as a separately reviewed business operation.
- Use a production-strength random JWT key (at least 32 bytes), TLS-enabled PostgreSQL/Redis endpoints, and provider-issued credentials. Restrict database and Redis network access to the application. The JWT signing key was present in repository configuration/history and must be rotated through a controlled rollout; changing it invalidates outstanding tokens signed by the old key (maximum configured lifetime: 24 hours).
- Keep production schema mutation and Flyway disabled until the separately verified production schema/migration rollout plan is approved. Do not assume the local `update` default is a safe production migration strategy.
- Configure deployment probes against `/actuator/health/liveness` and `/actuator/health/readiness`. Health detail disclosure remains disabled; health endpoints are public but do not include component detail.
- Set graceful termination timeouts to at least 30 seconds so the JVM can finish in-flight work.
- Protect the `main` branch with required CI checks. GitHub repository branch protection is external to this repository; the workflow itself cannot enforce that setting.
- Keep the dependency graph / Dependabot alerts enabled in repository settings. Configure `NVD_API_KEY` as a GitHub Actions secret to reduce OWASP NVD feed throttling; scan errors fail the scan job rather than being ignored.

## Required runtime variables

`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `REDIS_URL`, `CORS_ALLOWED_ORIGINS`, and all three Razorpay variables are required with the `prod` profile. Local `.env.example` values are development-only; `.env` is git-ignored and excluded from Docker build context. Production must use platform-managed variables, not a local file.

## Secret rotation

### Provider-by-provider checklist

- **PostgreSQL provider:** create a replacement application credential with the same least-privilege grants, update `DB_USERNAME` and `DB_PASSWORD` in the deployment secret store, restart and verify database connectivity, then revoke the exposed credential. Keep `DB_URL` on TLS and do not include credentials in it.
- **Redis provider:** issue a replacement password or token, update the `REDIS_URL` secret while preserving the provider's `rediss://` scheme and TLS settings, verify login rate limiting and token revocation, then revoke the old credential. If Redis data is not migrated, existing revocation entries will be unavailable and should be treated as requiring reauthentication.
- **JWT signing key:** generate a cryptographically random value of at least 32 bytes, update `JWT_SECRET`, and roll application instances in a controlled window. All outstanding tokens signed with the old key become invalid (maximum configured lifetime: 24 hours); users must sign in again. The current implementation has one active signing key and no dual-key transition.

Treat the database, JWT, and Redis credentials found in repository configuration and history as exposed. Rotate them in their providers, update the deployment secret store, and remove old credentials only after dependent deployments have moved. JWT rotation invalidates existing access tokens; schedule it with users expected to sign in again. Git history cleanup, if desired, requires a coordinated repository-wide rewrite and is not part of application remediation.

## Database rollout gate

Do not activate the production profile until the legacy schema has been inventoried and a complete, reviewed Flyway path exists. The current baseline migrations do not create the complete fresh schema, and the existing deployments may rely on Hibernate `update`. Back up the database, compare schema and migration history, test the exact migration sequence on a sanitized clone, review row counts and constraints, then apply during a maintenance window. Hibernate validation can fail after a partial migration; rollback may require restoring the backup because DDL rollback support varies by migration. No production migration has been run as part of this remediation.

An isolated PostgreSQL 18.6 check applied Flyway V1–V3 successfully. With the normal local `ddl-auto=update` setting, Hibernate then created 29 application tables. In a separate empty database with Hibernate DDL disabled, Flyway created only `flyway_schema_history`; application queries failed because business tables were absent. Flyway warned that this version does not claim tested support for PostgreSQL 18 (its reported supported maximum was 17). A saved schema-only inspection from October 18 covers only `customer_ledger_entries` and `daily_closings`, has no migration rows, and does not identify its source environment. It is insufficient to represent an existing installation. These results confirm that the current Flyway scripts are not a fresh-install schema and do not establish an existing-database adoption path.

## Deployment smoke checks

### Evidence required before authoring migrations

Do not create a baseline or adoption migration from the two-table inspection. Before designing that path, obtain an owner-approved, sanitized, non-production PostgreSQL clone (or complete schema-only export) with the exact PostgreSQL major version, all schemas, tables, sequences, views, constraints, indexes, extensions, and `flyway_schema_history` rows. Include provenance identifying the clone as representative of an existing installation and a sanitized data export for preservation checks. Compare that evidence to all JPA mappings and migration scripts; test both empty-database installation and the precise existing migration history on isolated disposable databases. Until this evidence exists and the full migration test passes, keep production schema changes disabled. The database owner must separately review backup, schema-drift checks, staged rollout, post-migration row/constraint validation, and restore-based recovery before any production change.

1. Confirm `SPRING_PROFILES_ACTIVE=prod` and all required variables are present in the deployment secret store.
2. Verify `/actuator/health/liveness` and `/actuator/health/readiness` return healthy without exposing component details.
3. Exercise login, an authenticated shop-scoped read, a denied cross-shop request, and logout/replay behavior.
4. Verify a sandbox payment and webhook only after sandbox credentials are configured; never use live payment credentials in smoke tests.
