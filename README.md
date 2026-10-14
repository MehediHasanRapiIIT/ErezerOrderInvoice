# EREZER Admin Panel

Order, expense and staff management for EREZER: an Angular admin panel on top of a Spring Boot API, with PostgreSQL for data and MinIO (S3-compatible) for product images.

```
ErezerInvoice/
├── erezerbackend/        Spring Boot 4.1 · Java 21 · Maven  (REST API, port 8080; 8081 in dev)
├── erezerfrontend/       Angular 21 · Tailwind 4            (admin SPA, served by nginx)
├── docker/minio/init.sh  Creates the bucket and a least-privilege storage user
├── compose.yaml          Full stack: postgres, minio, minio-init, backend, frontend
├── compose.override.yaml Dev-only host ports (auto-loaded by `docker compose up`)
└── .env.example          Every setting and secret, documented
```

## Quick start

Requires Docker, JDK 21 and Node 24.

```bash
cp .env.example .env                 # defaults work for local development
```

**Option A: everything in Docker**

```bash
docker compose up -d --build
```

Open http://localhost:8088 and sign in with `BOOTSTRAP_ADMIN_EMAIL` / `BOOTSTRAP_ADMIN_PASSWORD` from `.env`.

**Option B: infrastructure in Docker, apps run natively (hot reload)**

```bash
docker compose up -d postgres minio minio-init

cd erezerbackend && ./mvnw spring-boot:run      # http://localhost:8081, dev profile
cd erezerfrontend && npm ci && npm start        # http://localhost:4201, proxies /api to 8081
```

API docs (dev profile only): http://localhost:8081/api/docs/ui

| Service       | Host port (dev) | Notes                                             |
|---------------|-----------------|---------------------------------------------------|
| Admin panel   | 8088            | nginx; serves the SPA and proxies `/api`          |
| Postgres      | 5433            | dev override only                                 |
| MinIO API     | 9010            | dev override only; browsers load images from here |
| MinIO console | 9011            | dev override only                                 |

Ports were picked to avoid the other EREZER stacks that use 4200, 4300, 5432, 8080 and 9000.

## Tests

```bash
cd erezerbackend && ./mvnw verify        # unit + integration tests (Testcontainers starts Postgres; needs Docker)
cd erezerfrontend && npm test -- --watch=false
```

## Architecture notes

### Authentication

- `POST /api/auth/login` returns a 15-minute JWT access token in the body. A 7-day refresh token goes into an `HttpOnly; SameSite=Strict` cookie scoped to `/api/auth`.
- The SPA keeps the access token in memory only. On page load, and shortly before the token expires, it calls `/api/auth/refresh`. That call rotates the refresh token.
- Reusing an already-rotated refresh token after a 20-second grace period (meant for parallel tabs) is treated as theft: every session of that user is revoked.
- Five wrong passwords lock the account for 15 minutes. A password change signs out all other devices.
- The first admin comes from `BOOTSTRAP_ADMIN_*` and is created only when no admin exists yet.

### Permissions

- `ADMIN` has every permission. A `MODERATOR` has only what an admin grants, from the catalog in [Permission.java](erezerbackend/src/main/java/com/erezer/admin/security/Permission.java).
- `STAFF_MANAGE` can never be granted to a moderator.
- Every endpoint is protected with `@PreAuthorize("hasAuthority('...')")`. The frontend's route guards and `*appHasPermission` only hide UI the user cannot use.
- Permissions travel inside the access token, so a change takes effect within one token lifetime (at most 15 minutes).

### Errors

Every API error is RFC 9457 `application/problem+json` with a stable `code` field (for example `INVALID_CREDENTIALS` or `VALIDATION_FAILED` with `fieldErrors`).

### Database

The schema is managed only by Flyway (`erezerbackend/src/main/resources/db/migration`); Hibernate runs in `validate` mode. Never edit a migration that has already run anywhere: add a new `V<n>__*.sql`.

### Invoices and exports

- `GET /api/orders/{id}/invoice.pdf` renders the invoice on the server with OpenPDF. Add `?download=true` to get it as a file download.
  - Page 1 is the invoice itself.
  - From page 2, two product slots per page, each with up to two images side by side. A product with more images continues in the next slot.
- Photos are re-oriented using their EXIF data, flattened onto white, scaled to at most 1600 px and re-encoded as JPEG, which keeps PDFs small. WebP is supported.
- Text uses the embedded Noto Sans fonts, with Noto Sans Bengali for `৳` (both OFL-licensed; see `erezerbackend/src/main/resources/fonts`). OpenPDF does not shape Bangla script, so Bangla names may print with broken conjuncts. Latin text and `৳` print correctly.
- The seller details come from `COMPANY_*` in `.env`. The logo is `erezerbackend/src/main/resources/branding/logo.png`.
- `GET /api/orders/export.xlsx` takes the same filters as the order list and returns every matching order, up to 20,000 rows.

### Object storage

The backend talks to MinIO through the AWS S3 SDK, so any S3-compatible store works by changing `STORAGE_*`.

> ⚠ MinIO stopped publishing free Docker images in late 2025. `minio/minio:latest` works here because this machine has it cached (RELEASE.2025-09-07). Before deploying to a new server, do one of the following and set `MINIO_IMAGE`:
> - push that image to your own registry;
> - build MinIO from source;
> - switch to another S3-compatible store (Garage, SeaweedFS).

## Production checklist

- Generate new values for `JWT_SECRET` (`openssl rand -base64 48`), `POSTGRES_PASSWORD`, `MINIO_ROOT_PASSWORD` and `STORAGE_SECRET_KEY`.
- Put HTTPS in front of port `HTTP_PORT` (Caddy, Traefik or a cloud load balancer) and keep `REFRESH_COOKIE_SECURE=true`.
- Set `STORAGE_PUBLIC_ENDPOINT` to a URL browsers can reach.
- Deploy with `docker compose -f compose.yaml up -d --build`. This skips the dev port overrides, so Postgres and MinIO are not exposed.
- Back up the `postgres-data` and `minio-data` volumes.
