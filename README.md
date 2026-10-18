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
| Postgres      | 5434            | dev override only                                 |
| MinIO API     | 9010            | dev override only; browsers load images from here |
| MinIO console | 9011            | dev override only                                 |

Ports were picked to avoid other local stacks on this machine (4200, 4300, 5432, 5433, 8080, 9000); change them in `.env` if needed.

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

### Expenses

- `GET /api/expenses` takes `q`, `categoryId`, `paymentMethod`, `from`/`to` (expense date) and `minAmount`/`maxAmount`. Besides the page of rows it returns `totalAmount` and per-category totals for **all** matching expenses. `GET /api/expenses/export.xlsx` takes the same filters and adds a total row.
- Expense dates can't be in the future; "today" is the business day in `Asia/Dhaka`.
- Categories are managed in the panel. A category that is already used can only be deactivated, which keeps past records intact. Ten starter categories are seeded by migration `V3`.
- Receipts (a JPEG/PNG/WebP photo or a PDF, up to 10 MB) go to `POST /api/files/receipts` and are attached by id. The real file type is checked from the file's bytes, not trusted from the browser.
- Deleting an expense hides it rather than erasing it, so it and its receipt remain for audits.

### Dashboard: how the numbers are calculated

`GET /api/dashboard/summary?from=&to=` (dates are inclusive business days in Asia/Dhaka; the default is this month so far). The figures are **cash basis** for a cash-on-delivery courier business, and they are checked against a hand-calculated scenario in `DashboardIntegrationTest`.

| Card | Formula |
|---|---|
| Total Revenue | Σ order total of orders **delivered** in the period (what customers paid, incl. delivery charge) |
| Product Revenue | Σ (total − delivery charge) of those orders: product sales after discounts |
| Total Delivery Charge | Σ delivery charge of those orders. It is collected from the customer and paid to the courier, so it is not profit |
| Return Revenue | Σ order total of orders **returned** in the period |
| Return Product Revenue | Σ (total − delivery charge) of those returns |
| Return Product Delivery Total | Courier cost of those returns: refused at the door → delivery + return charge; returned after delivery → return charge only |
| Refunds | Product value paid back for returns of orders that had been delivered |
| Total Expenses | Σ expenses dated in the period |
| **Net Profit** | Product Revenue − Refunds − Return Product Delivery Total − Expenses |

Rules:
- A sale belongs to the day it was **delivered** and stays there. If the customer returns it later, the refund and return charge are booked on the return day, so past periods never change.
- Cancelled orders and orders still in progress count nowhere: no money has moved.
- Product cost is not tracked per item. It reaches profit through the expenses you record (fabric, printing, packaging…).
- The courier's real fee is assumed to equal the delivery charge you set on the order.
- Changes (▲▼) compare with the period of the same length immediately before. Return rate = returns ÷ (deliveries + parcels refused at the door).

### Staff management

- Admins add staff (Moderator or Admin) with an initial password, tick each moderator's permissions, reset passwords, unlock locked accounts, and deactivate or reactivate accounts (`/api/staff`, admin-only).
- **Changes apply immediately.** Every API request checks that the account is still active and that its token was issued after the account's `sessions_valid_after` mark. That mark moves on deactivation, password reset, a self-service password change, and any role or permission change.
  - A deactivated user is rejected on their next request.
  - A moderator whose permissions changed is silently given a new token with the new permissions; they are not signed out.
- **Safety rules:** you can't deactivate yourself or remove your own admin role, and the last active admin can't be removed.
- Signing in updates last-login and lockout data with direct updates that leave the version unchanged, so a staff member signing in never makes an admin's open edit form "stale".

### Customers

- There is no separate customer table: customers are aggregated from order details, **one per phone number**. A generated column `orders.customer_phone_key` (migration `V5`) strips formatting and the `880` country code, so `+880 1711-111111`, `8801711111111` and `01711 111111` are the same customer. Order search uses the same key.
- Name, email and address come from the customer's most recent order. **Total paid** = order totals of their delivered orders (incl. delivery charge); pending, cancelled and refused orders are not counted. Customers with a return rate of 50% or more are flagged.
- `GET /api/customers` (server-side paging, search, `segment=repeat|returns`, `sort`) needs **View customers**. `GET /api/customers/export.xlsx` needs the separate **Export customers** permission, because it takes personal data out of the system. Admins have both.

### Courier: Steadfast integration

**Setup (admin):** in **Courier Settings**:
1. Enter the API key and secret key from your Steadfast merchant panel. They are stored encrypted with `COURIER_ENCRYPTION_KEY`.
2. Click **Test connection**. It first calls `/ping` (no keys), so "Steadfast can't be reached" is told apart from "keys rejected", then shows your balance.
3. Turn on **Enabled**.
4. For instant status updates, paste the callback URL `https://<your-domain>/api/webhooks/steadfast` and a token into Steadfast's **Webhook** page. Either generate the token in the panel or paste one generated in Steadfast (letters, digits and `- _ . =`, 16-200 characters). This needs a public https domain. Without it, a background job re-checks parcels in transit every 10 minutes.
   - Every webhook must carry the token as `Authorization: Bearer` **and** a valid `X-Signature`: the hex HMAC-SHA256 of the raw body, keyed with the token. Both are compared in constant time, and the token is stored encrypted.
   - Parcel events (`delivery_status`, `tracking_update`) are applied. Account events (`payment_request`, `user_update` and the rest) get 200 "ignored", so Steadfast doesn't retry them.

**Sending** (permission **Send orders to courier**). Accepted, In production and Processing orders can be sent:
- **One order:** the **Send to Steadfast** button on the order page opens a preview.
  - It shows recipient, normalised phone and address, and has editable COD (default: the total for cash on delivery, ৳0 for prepaid), delivery type, item description and rider note.
  - It warns about every problem and about customers who returned parcels before.
  - **Check record** shows Steadfast's fraud check for the phone across all merchants: delivery and cancellation ratio, parcel counts, fraud reports. A ratio Steadfast can't compute is shown as "Unknown", never as 0%. The check runs on demand because Steadfast limits it per account; when the limit is used up, a 429 message appears and sending still works.
  - COD can have paisa (up to 2 decimals, ৳0-৳1,000,000). The characters `{ } ; < > $`, which Steadfast replaces with spaces, are removed in the preview, so it shows exactly what will be booked.
  - On success the order becomes **Shipped**, with Steadfast's tracking code.
- **Bulk:** tick orders on the Orders list (or "select all N matching" the current filters, up to 500), then **Send to Steadfast**.
  - The preview marks each order Ready / Needs fixing / Not eligible and lets you adjust COD.
  - Sending runs in the background, 4 orders at a time, each through the same safe path. A live progress view shows the per-order result, and failures can be reviewed and resent.

**Safety:**
- **No double booking:** one shipment per order is enforced by a unique index plus a row lock.
- **Unknown outcomes:** a timeout leaves the shipment "confirming"; it is resolved by looking up the invoice, never by resending.
- **Steadfast rejections** (4xx) release the order so it can be fixed.
- **Duplicate invoice:** if Steadfast already has a parcel with the order number (`THIS_INVOICE_ALREADY_EXISTS`), the existing parcel is linked to the order instead of being released or rebooked.
- **Lockout protection:** Steadfast locks an account for 60 minutes after 10 failed authentications in 5 minutes, even with the right keys afterwards. So a bulk send **stops at the first 401 or 429**: the remaining orders are marked "Not sent", with the reason. The poller also pauses until its next run on 401 or 429, and wrong keys are never retried.
- **Keys** never leave the server, and requests never follow redirects.

**Status sync** (webhook, poller or the **Refresh status** button):

| Steadfast status | Effect |
|---|---|
| `delivered` | Order → **Delivered** |
| `cancelled` | Order → **Returned** |
| `partial_delivered`, `hold`, `exceptional` (lost or damaged), `unknown` | Flagged "needs attention"; no automatic change. Releasing a `hold` needs Steadfast support |
| `in_review`, `pending` (with the courier, not yet attempted), `*_approval_pending` | Shown on the order only |

- Orders never move backwards.
- The courier's real charge is stored in `orders.courier_charge`, and the dashboard uses it:
  - a refused parcel costs exactly what Steadfast charged;
  - delivered orders add the difference between the delivery charge collected and the courier's charge.

**Checked against the official API guide** (base URL `https://portal.packzy.com/api/v1`, `Api-Key`/`Secret-Key` headers, field limits, statuses, webhook signing, fraud check). Steadfast has no sandbox, so before going live book **one real test parcel** and cancel it in the Steadfast panel. Bulk sending deliberately uses `create_order` once per parcel instead of `/create_order/bulk-order`. That keeps one safe path (reserve → book → confirm) with a per-order result. At 4 in parallel it stays far below the 6,000/min booking limit.

### Object storage

The backend talks to MinIO through the AWS S3 SDK, so any S3-compatible store works by changing `STORAGE_*`.

> ⚠ MinIO stopped publishing free Docker images in late 2025. `minio/minio:latest` works here because this machine has it cached (RELEASE.2025-09-07). Before deploying to a new server, do one of the following and set `MINIO_IMAGE`:
> - push that image to your own registry;
> - build MinIO from source;
> - switch to another S3-compatible store (Garage, SeaweedFS).

## Production

Live at **https://erezer.site**, on the same Hostinger VPS as the Erezer shop. Push to `main` and GitHub Actions tests, builds and deploys, rolling back on failure. The shop's Caddy serves the panel with automatic https.

**[DEPLOYMENT.md](DEPLOYMENT.md)** has the full setup (DNS, VPS, GitHub secrets), plus day-to-day operation, backups and troubleshooting.

| File | Role |
|---|---|
| `compose.prod.yaml` | Production overlay: no host ports, joins the shared `erezer-edge` network, memory caps, nightly backups |
| `compose.deploy.yaml` | Runs the Docker Hub images CI built, instead of building on the VPS |
| `deploy/deploy.sh` | Runs on the VPS: pull, start, health gate, rollback, Caddy site update |
| `deploy/caddy/erezer-invoice.caddy.template` | The `erezer.site` site block for the shop's Caddy |
| `.github/workflows/cd.yml` | Push to `main` → test → build → deploy → smoke test |
| `.env.prod.example` | Template for the VPS's private `.env` |
