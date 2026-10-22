
# Local development and production deployment

## Local IntelliJ IDEA setup

1. Install a JDK 21 distribution. In IntelliJ, set **Project SDK** and **Maven Runner JRE** to JDK 21. Maven's compiler is configured with `--release 21`.
2. Set the application run configuration VM option to `-Duser.timezone=Asia/Kolkata`. pgjdbc sends the JVM default timezone during connection startup; PostgreSQL 18 rejects the legacy `Asia/Calcutta` identifier observed on this workstation. `Instant` values remain UTC and Hibernate JDBC timestamp handling stays set to UTC. Business-day calculations use `APP_BUSINESS_TIME_ZONE`, defaulting to `Asia/Kolkata`.
3. Start local PostgreSQL and Redis only after setting a local `DB_PASSWORD`; Compose binds both ports to loopback. For containerized integration testing use the isolated stack below.
4. Create an IntelliJ Spring Boot run configuration for `com.dukankhata.DukanKhataApplication`. Enter required environment variables directly in that run configuration, or use a trusted local environment-variable plugin. Spring Boot does not automatically load `.env`; `.env.example` is a reference, not a runtime dependency.
5. Build and test with `mvn clean verify`.

### Isolated container smoke test

The test stack uses PostgreSQL 17 and Redis containers, test-only credentials, loopback-only published ports, and no persistent volumes. PostgreSQL initializes from the same `supabase-schema.sql` used for fresh Supabase databases; Flyway is disabled and Hibernate runs `validate`.

```powershell
docker compose -p dukankhata-test -f docker-compose.test.yml up --build -d
Invoke-WebRequest http://localhost:18080/actuator/health
Invoke-WebRequest http://localhost:18080/actuator/health/readiness
docker compose -p dukankhata-test -f docker-compose.test.yml logs app
docker compose -p dukankhata-test -f docker-compose.test.yml stop app
docker compose -p dukankhata-test -f docker-compose.test.yml logs app
docker compose -p dukankhata-test -f docker-compose.test.yml down
```

The readiness group checks PostgreSQL, Redis, and the mapped-schema health indicator. Graceful shutdown allows up to 20 seconds per shutdown phase; Compose allows 30 seconds before killing the container. The final `down` removes this test project's containers and network; it has no volumes to delete.

## Production deployment

### Render Blueprint

The repository-root `render.yaml` defines a Docker web service and uses `/actuator/health/readiness` for Render's deployment health check. Connect the GitHub repository in Render with **New > Blueprint**, select the branch containing `render.yaml`, and provide the prompted database, Redis, CORS, and Razorpay values. The Blueprint generates `JWT_SECRET`; do not replace it with a value exposed in chat or source control. For an existing Render service, `sync: false` does not prompt again; set any missing values in that service's Environment page. Upload the current Supabase root CA as a Render Secret File named `supabase-root.crt`; Render makes it available at `/etc/secrets/supabase-root.crt`, which is the configured `DB_SSL_ROOT_CERT` path. The Docker image's non-root user is added to group 1000 so it can read Render-mounted secret files without making the certificate world-readable. Do not put the certificate in Git or the Docker build context. Run the one-time Supabase schema setup below before the first deploy; Render does not create or migrate the external Supabase database.

- Set `SPRING_PROFILES_ACTIVE=prod`. Hibernate runs `validate`; it never creates or updates the schema. Automatic Flyway execution is disabled in all application profiles. Do not set `FLYWAY_ENABLED` or `SPRING_FLYWAY_ENABLED` to true; initialize a new dedicated database with the one-time procedure below.
- The Docker image defaults to the `prod` profile. The disposable Compose test stack explicitly selects the `test` profile and uses test-only credentials.
- Logout revokes the presented JWT immediately in Redis, retaining only its SHA-256 digest until the token's existing expiry. Protected requests fail with `503` if Redis cannot check revocation; they do not silently accept or reinterpret the token.
- Build and publish only the image produced by the `Backend CI and image release` workflow. The publish job depends on successful Java 21 verification and dependency scanning.
- Configure `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `DB_SSL_ROOT_CERT`, `JWT_SECRET`, `REDIS_URL`, `CORS_ALLOWED_ORIGINS`, `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, and `RAZORPAY_WEBHOOK_SECRET` as deployment-platform secrets/environment variables. `DB_URL` must be the exact JDBC-form host, port, and database from the Supabase Dashboard **Connect** panel. Mount the downloaded Supabase root CA certificate and set `DB_SSL_ROOT_CERT` to its absolute path. Production enforces `sslmode=verify-full`, CA trust, hostname verification, a 10-second connect timeout, a 30-second PostgreSQL statement timeout, and a 45-second socket timeout. Never use `sslmode=require` as a substitute, disable certificate verification, infer a pooler hostname/port, or copy `.env` into an image or deployment artifact. Never use the test Compose credentials in production.
- Redis is required for readiness, rate limiting, and JWT revocation. Production forces SSL and uses 2-second connection/read timeouts. Put the provider's authenticated Redis URI (including ACL username/password only when required by that provider) in `REDIS_URL` in the secret store; use the provider's TLS endpoint. Spring Data Lettuce's default SSL configuration verifies the peer. Do not use a plaintext endpoint or disable peer verification. The actual Redis provider, ACL requirements, and deployment secret are not present here, so validate them in the hosting platform before release.
- Choose the connection type based on the actual hosting runtime's IP connectivity: Supabase documents direct connections as suitable for long-lived backends when IPv6 is available (or IPv4 add-on enabled); for IPv4-only hosts without that add-on, use the **Session pooler** connection details shown by the Dashboard. This Java application uses a persistent Hikari pool, so use the session-mode pooler rather than transaction-mode pooler unless the hosting/provider specifically validates the latter with this app. Supabase Free's shared pooler supports IPv4; the actual host and project settings are unknown here, so select the Dashboard-provided endpoint after checking the hosting host's IPv4/IPv6 capabilities. Keep the app pool small (`DB_POOL_SIZE=3`, `DB_MIN_IDLE=0` defaults) and account for every app replica against the project's connection limit.
- Configure `CORS_ALLOWED_ORIGINS`, pool sizing, login rate limits, worker settings, subscription plan values, and Razorpay credentials from the deployment environment. The current production profile requires Razorpay configuration values at startup; confirm provider credentials before enabling payment operations. Subscription plans are seeded only when a plan code is absent; startup does not overwrite existing plan prices or reactivate an intentionally inactive plan. Treat plan pricing changes as a separately reviewed business operation.
- Use a production-strength random JWT key (at least 32 bytes), TLS-enabled PostgreSQL and Redis endpoints, and provider-issued credentials. Restrict database and Redis network access to the application. The JWT signing key must be reviewed for prior exposure; rotate only after the owner confirms key history and approves the session impact. Changing it invalidates outstanding tokens signed by the old key (maximum configured lifetime: 24 hours).
- Never set Hibernate `ddl-auto` to `update` in production. The V1–V4 files remain historical artifacts and are not executed automatically.
- Configure deployment probes against `/actuator/health/liveness` and `/actuator/health/readiness`. Health detail disclosure remains disabled; health endpoints are public but do not include component detail.
- Set graceful termination timeouts to at least 30 seconds so the JVM can finish in-flight work.
- Protect the `main` branch with required CI checks. GitHub repository branch protection is external to this repository; the workflow itself cannot enforce that setting.
- Keep the dependency graph / Dependabot alerts enabled in repository settings. Configure `NVD_API_KEY` as a GitHub Actions secret to reduce OWASP NVD feed throttling; scan errors fail the scan job rather than being ignored.

## Required runtime variables

`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `REDIS_URL`, `CORS_ALLOWED_ORIGINS`, and all three Razorpay variables are required with the `prod` profile. Local `.env.example` values are examples only; `.env` is git-ignored and excluded from Docker build context. Production must use platform-managed variables, not a local file.

## Supabase Free backup and recovery

Supabase Free does not include managed automatic database backups or PITR. The project therefore has no provider-managed point-in-time recovery guarantee. Free projects may be paused after seven days of inactivity; resumption is a manual Dashboard action. Review the current [Supabase backup documentation](https://supabase.com/docs/guides/platform/backups) and [Free Plan limits](https://supabase.com/pricing) before relying on any provider feature. Backups are an operator responsibility; select an export schedule and retention policy that meet the business RPO/RTO, and export before every schema or release operation.

For an application-data logical backup, install PostgreSQL client tools compatible with the Supabase server major version. Copy the exact host, port, database, and username from the Supabase Dashboard **Connect** panel; set them in the current PowerShell session. Do not put the database password in a command or shell history. Set `PGSSLMODE=verify-full` and `PGSSLROOTCERT` to the current Supabase root certificate:

```powershell
$env:PGHOST = '<exact dashboard host>'
$env:PGPORT = '<exact dashboard port>'
$env:PGDATABASE = '<exact database name>'
$env:PGUSER = '<exact database username>'
$env:PGSSLMODE = 'verify-full'
$env:PGSSLROOTCERT = '<absolute path to Supabase root certificate>'
$backup = Join-Path $env:USERPROFILE ('dukankhata-' + (Get-Date -Format yyyyMMdd-HHmmss) + '.dump')
pg_dump --password --format=custom --no-owner --no-acl --schema=public --file="$backup"
if ($LASTEXITCODE -ne 0) { throw 'Database export failed' }
pg_restore --list "$backup" | Out-File -Encoding ascii "$backup.toc"
Get-FileHash -Algorithm SHA256 "$backup"
```

`pg_dump` prompts for the password. Protect the output as sensitive customer data: encrypt it before off-site storage, restrict access, retain a separately stored decryption key, and record the archive hash. This public-schema export does not back up Supabase Storage objects or files, external services, deployment secrets, or data stored outside `public`.

Rehearse recovery only into a newly provisioned, isolated, empty PostgreSQL database (never use `--clean` or restore over a live target). Set `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGSSLMODE`, and `PGSSLROOTCERT` for that target, decrypt the archive into a protected temporary directory if needed, then remove the archive's `SCHEMA - public` entry so `pg_restore` uses the target's default `public` schema:

```powershell
$restoreList = "$backup.toc"
pg_restore --list "$backup" | Where-Object { $_ -notmatch ' SCHEMA - public ' } | Out-File -Encoding ascii $restoreList
pg_restore --password --use-list="$restoreList" --no-owner --no-acl --exit-on-error --dbname="$env:PGDATABASE" "$backup"
if ($LASTEXITCODE -ne 0) { throw 'Database restore failed' }
```

Validate object and row counts, foreign-key/invariant checks, start the application with the `prod` profile and Hibernate `validate`, and run representative read/write checks before approving recovery. A restore of application tables may need the target's required extensions, roles, grants, secrets, and Supabase settings configured separately. Do not point the deployment at a replacement database until the owner approves the recovery, confirms application validation, and updates the deployment secret store. Recovery requires a manual operator to provision/restore the target, configure secrets, verify it, and switch application traffic; there is no automated failover or PITR on Free.

An isolated PostgreSQL 17 rehearsal successfully dumped and restored the application schema and one synthetic shop row into a separately created empty test database. The restored database contained 29 public tables, 69 indexes, and 95 constraints; the sample row remained present. The container had no published port or persistent mount and was removed after its identity was verified. This demonstrates the logical dump/restore procedure only; no production data, live Supabase connection, or production restoration was tested.

## One-time Supabase initialization

1. Use only a brand-new, dedicated Supabase database whose `public` schema has no application relations. This script is not an upgrade or adoption tool for an existing database.
2. Review and execute the complete repository-root `supabase-schema.sql` once in the Supabase SQL Editor (or with `psql`). It runs transactionally and refuses to continue when non-extension relations already exist in `public`; do not remove that guard or retry against an existing application database.
3. Configure the existing hosting environment variables, keep Flyway disabled, and start the application with Hibernate `ddl-auto=validate`. Check startup and readiness before directing traffic. Never enable Hibernate schema creation.
4. For every future schema change, add a separately numbered, reviewed SQL change script under `supabase-migrations/`, test it on disposable PostgreSQL and a staging clone, and apply it through an approved deployment procedure with a verified backup. Keep `supabase-schema.sql` synchronized so new databases include the same change. Do not edit the historical V1–V4 scripts or use them to initialize the new database.

### Supabase dashboard connection setup

1. Create the new dedicated project/database and execute the one-time schema procedure above before starting the app. In **Project Settings → Database**, download the current server root certificate; mount it read-only in the backend runtime and set `DB_SSL_ROOT_CERT` to that mounted path.
2. In **Connect**, copy the host, port, database, and username for Direct or Session pooler based on the backend host's IP support described above. Convert the dashboard endpoint into `DB_URL` format `jdbc:postgresql://<exact-host>:<exact-port>/<exact-database>`; set the username and password separately. TLS mode and CA file are configured independently by the application. Do not put credentials in `DB_URL`.
3. Configure secrets in the hosting platform, not in source control. Production startup intentionally fails when required database values or the CA certificate path are missing, the TLS certificate/hostname cannot be verified, or Hibernate schema validation fails. `/actuator/health` discloses no component details; use `/actuator/health/readiness` for traffic gating.

## Secret rotation

### Provider-by-provider checklist

- **PostgreSQL provider:** create a replacement application credential with the same least-privilege grants, update `DB_USERNAME` and `DB_PASSWORD` in the deployment secret store, restart and verify database connectivity, then revoke the exposed credential. Keep `DB_URL` on TLS and do not include credentials in it.
- **Redis provider:** issue a replacement password or token, update the `REDIS_URL` secret while preserving the provider's `rediss://` scheme and TLS settings, verify login rate limiting and token revocation, then revoke the old credential. If Redis data is not migrated, existing revocation entries will be unavailable and should be treated as requiring reauthentication.
- **JWT signing key:** generate a cryptographically random value of at least 32 bytes, update `JWT_SECRET`, and roll application instances in a controlled window. All outstanding tokens signed with the old key become invalid (maximum configured lifetime: 24 hours); users must sign in again. The current implementation has one active signing key and no dual-key transition.

Review provider credentials for historical exposure before release. The current scan found no common AWS, GitHub, Slack, PEM, live Razorpay-key, or credential-bearing database-URL patterns, but it cannot establish whether provider credentials were exposed outside those formats or in external systems. Rotate any confirmed exposed credential with its provider owner. JWT rotation invalidates existing access tokens; schedule it with users expected to sign in again. Do not rewrite shared Git history as part of routine remediation.

## Database rollout gate

The consolidated `supabase-schema.sql` was executed on a fresh disposable PostgreSQL 17.11 database. Hibernate `validate` and signup persistence succeeded. This does not establish Supabase-hosted connectivity or compatibility with any existing installation. V1–V4 remain in the repository as historical files but are not run automatically; existing-database adoption is outside this procedure and requires a separate owner-approved plan.

The supplied evidence directory contains a schema-only backup and separate V1–V3 history CSV. The backup claims PostgreSQL 18.6 and includes 29 application tables; its provenance is not independently authenticated. The history CSV claims V2 succeeded, but the dump lacks V2's conditional audit index. A fresh migrated schema matched the supplied backup's application objects, but the history/schema discrepancy blocks existing-install claims.

## Deployment smoke checks

### Evidence required before authoring migrations

Do not create a baseline/adoption migration from the supplied files. Obtain an owner-approved, sanitized non-production clone or complete schema-only export, exact migration history and checksum record, and representative data for preservation checks. Include export provenance and the target PostgreSQL version. Compare the evidence with all entities and migrations; test fresh and existing paths on isolated disposable databases. Keep production migration disabled until those checks pass and the database owner approves backup, drift review, staged rollout, post-migration checks, and restore-based recovery.

1. Confirm `SPRING_PROFILES_ACTIVE=prod` and all required variables are present in the deployment secret store.
2. Verify `/actuator/health/liveness` and `/actuator/health/readiness` return healthy without exposing component details.
3. Exercise login, an authenticated shop-scoped read, a denied cross-shop request, and logout/replay behavior.
4. Verify a sandbox payment and webhook only after sandbox credentials are configured; never use live payment credentials in smoke tests.
