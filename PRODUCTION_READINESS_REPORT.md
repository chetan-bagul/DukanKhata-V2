# DukanKhata Backend — Production Readiness Report

**Decision: NOT READY FOR PRODUCTION**

This report accompanies a source-only project candidate. It is not a deployment approval.

## Changes in this candidate

- `SubscriptionDataInitializer` now seeds only plan codes that do not already exist. Existing plan names, prices, currency, duration, and active/inactive status are preserved across application restarts.
- The repository adds `findByCode` so startup logic can distinguish a missing plan from an existing inactive plan.
- The initializer handles a concurrent insert race only when the competing plan is subsequently visible.
- The existing initializer test now asserts that existing plan metadata is preserved and adds coverage for an intentionally inactive plan.
- `DATABASE_SCHEMA_READINESS.md` and `DEPLOYMENT.md` were updated to describe the revised initializer behavior and remaining database limitations.

## Verification performed in this environment

- Java runtime: OpenJDK 21.0.12.
- Maven is not installed, and network access is unavailable, so Maven tests could not be rerun after the changes.
- The two changed production Java files compiled successfully with `javac --release 21` against the uploaded project's existing compiled classes and dependencies. This is a compile check only; it is not a substitute for `mvn verify`.
- `git diff --check` passes after normalizing line endings in the touched Dockerfile and security configuration.
- Existing uploaded Surefire reports show 77 passing tests from the supplied build artifacts, but those results predate the changes in this candidate and must not be treated as verification of the new tests or code.
- No database, Redis instance, Docker daemon, payment provider, or production service was accessed.

## Mandatory blockers

1. The current Flyway V1–V3 migrations do not build the complete application schema from an empty database. Production Flyway is disabled to prevent unsafe execution of incomplete migrations.
2. No complete, provenance-identified sanitized non-production PostgreSQL schema export and matching Flyway history are included. Existing-install migration/adoption and data preservation are unverified.
3. PostgreSQL-specific migration, concurrency, rollback, and integrity tests have not been run for this candidate.
4. Real Redis TLS, token revocation, and shared rate-limit integration tests remain unverified.
5. Docker production-profile build/startup and health/readiness behavior have not been verified in a container in this environment.
6. Java 21 compile verification was performed, but the full Maven suite was not rerun on Java 21.
7. Previously exposed database, Redis, JWT, and payment-provider credentials require operator-led rotation and verification. No credentials were rotated here.
8. Payment-provider test-mode flows, webhook idempotency, and the full business/tenant-isolation regression suite need a fresh run after the final changes.

## Required next release gates

- Run `mvn -B -ntp verify` on Java 21 in the project environment.
- Obtain a complete sanitized non-production schema export and matching Flyway history from an authorized operator.
- Design and test separate fresh-install and existing-install migration paths on disposable PostgreSQL databases.
- Run PostgreSQL concurrency/integrity tests, isolated Redis TLS/revocation/rate-limit tests, and Docker production-profile smoke tests.
- Complete provider credential rotation and review the release/rollback procedure.

Do not enable production Flyway or Hibernate schema mutation until the schema/migration plan is reviewed and verified. Do not deploy based solely on the existing 77-test report.

## Current inspection and implementation checklist (2026-10-20)

This checklist is a planning update, not a production approval. The verification results below supersede only the historical Maven-availability and test-count statements above; no application behavior was changed for this inspection.

- [ ] Obtain owner-confirmed Supabase schema-only and `flyway_schema_history` exports; compare every migration checksum and the existing audit indexes.
- [ ] Reconcile V1–V4 fresh-install and existing-install paths against that evidence; update deployment guidance to match tested V4 behavior without editing applied migrations.
- [ ] Run disposable PostgreSQL/Supabase-compatible migration, Hibernate `validate`, persistence, restart, and data-preservation checks; keep production Flyway disabled until owner approval.
- [ ] Verify production Supabase connectivity/TLS/pooling, Redis TLS, required environment configuration, and health/readiness behavior using the existing hosting setup.
- [ ] Complete security/regression coverage for JWT/logout, role and shop isolation, rate limits/Redis failure, subscription enforcement, and Razorpay payment/webhook handling; confirm whether OTP is in scope before adding it.
- [ ] Complete dependency scanning/SBOM and credential-exposure review; obtain operator approval for any live credential rotation.
- [ ] Run final Java 21 `mvn -B -ntp verify`, `git diff --check`, backup/restore rehearsal, and release/rollback review before deployment approval.

Current baseline: Java 21.0.12, Spring Boot 3.5.5, `mvn -B -ntp verify` completed successfully with 83 tests (0 failures, 0 errors, 0 skipped). `git diff --check` passed with Git line-ending warnings only. Supabase/live database behavior and owner-authenticated migration evidence remain unverified.
