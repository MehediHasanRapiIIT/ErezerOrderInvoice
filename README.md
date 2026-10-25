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

- `POST /api/auth/login` returns a 15-minute JWT access token in the body. A refresh token goes into an `HttpOnly; SameSite=Strict` cookie scoped to `/api/auth`.
- **30-minute idle timeout:** the panel signs out after 30 minutes without mouse, keyboard or touch activity in any open tab, and the login page says why. The server enforces it too: a refresh token lasts 30 minutes from its last use (`JWT_REFRESH_TTL`, default `30m`), so a closed browser can't resume later.
- The SPA keeps the access token in memory only. On page load, and shortly before the token expires, it calls `/api/auth/refresh`. That call rotates the refresh token.
- Reusing an already-rotated refresh token after a 20-second grace period (meant for parallel tabs) is treated as theft: every session of that user is revoked.
- Five wrong passwords lock the account for 15 minutes. A password change signs out all other devices.
- **Forgot password** (login page, no email needed): staff enter their email and the request waits for an admin.
  - Admins see the requests at the top of **Staff Management**, with a count on its menu item. **Set new password** opens the usual reset dialog; the admin gives the new password to the person, who can change it after signing in. **Dismiss** closes a request without a change.
  - The answer on the login page is the same whether or not the email has an account, so nobody can probe which emails exist. Asking again doesn't add a second request.
  - Setting the password signs the account out on every device and lifts a lockout.
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
| Return Revenue | Returns **recorded** in the period: the full total of parcels refused at the door, and the products' value of returns after delivery |
| Return Product Revenue | Σ (total − delivery charge) of those returns |
| Return Product Delivery Total | Courier cost of those returns: refused at the door → delivery + return charge; returned after delivery → return charge only |
| Refunds | Product value paid back for returns of orders that had been delivered |
| Total Expenses | Σ expenses dated in the period |
| Returned items | Total Returned Items, items whose return charge was **paid by Customer** and **paid by Erezer**, with the charges |
| **Net Profit** (calculated, not shown as a card) | Product Revenue − Refunds − Return Product Delivery Total − Expenses + return charges recovered from customers on refused parcels |

Rules:
- A sale belongs to the day it was **delivered** and stays there. If the customer returns it later, the refund and return charge are booked on the return day, so past periods never change.
- Cancelled orders and orders still in progress count nowhere: no money has moved.
- Product cost is not tracked per item. It reaches profit through the expenses you record (fabric, printing, packaging…).
- The courier's real fee is assumed to equal the delivery charge you set on the order.
- Changes (▲▼) compare with the period of the same length immediately before. Return rate = returns ÷ (deliveries + parcels refused at the door).

### Assets

**Assets menu:** things the shop owns, such as CCTV, tables, chairs, computers and machines. It is kept **separate from expenses on purpose**: nothing here changes the expense totals, net profit, Sold Products or the dashboard.

- **An asset:** name, category (its own list: Furniture, Electronics, CCTV & Security, Machines, Tools, Other; admins can add more), quantity × unit cost = total cost, purchase date, location, serial / model no., warranty until, bought from, payment and reference, notes, a photo and the receipt (photo or PDF). One row covers identical items ("Plastic chair × 6").
- **Status:** In use · Under repair · In storage · Damaged · Sold (with the sale price) · Disposed. Sold and disposed assets leave the asset value but stay on record (filter *Status: All*).
- **Repairs & service:** date, what was done and cost, on the asset's page. The cost stays with the asset ("Spent on repairs") and is **not** an expense. A repair record can be removed; the rest of the history can't.
- **History:** every add, edit (which fields changed), move between locations, status change and repair, with who did it and when.
- **List:** a summary strip for the whole shop (asset value, items, under repair, warranty ending within 30 days; the last two filter the list), plus search, status, category, location, warranty and purchase date filters, and Excel export with a total row.
- **Delete** is a soft delete, like expenses. For things that left the shop, use Sold or Disposed instead.
- **No depreciation:** value = what was paid.
- **Permissions:** *View assets*, *Add and edit assets* (includes categories, status changes and repairs) and *Delete assets*. Admins always have all three.

### Staff management

- Admins add staff (Moderator or Admin) with an initial password, tick each moderator's permissions, reset passwords, unlock locked accounts, and deactivate or reactivate accounts (`/api/staff`, admin-only).
- **Changes apply immediately.** Every API request checks that the account is still active and that its token was issued after the account's `sessions_valid_after` mark. That mark moves on deactivation, password reset, a self-service password change, and any role or permission change.
  - A deactivated user is rejected on their next request.
  - A moderator whose permissions changed is silently given a new token with the new permissions; they are not signed out.
- **Safety rules:** you can't deactivate yourself or remove your own admin role, and the last active admin can't be removed.
- Signing in updates last-login and lockout data with direct updates that leave the version unchanged, so a staff member signing in never makes an admin's open edit form "stale".

### Sold Products

**Delivered sales by category.** The category is the **product name** on the order, grouped ignoring capitals and extra spaces and shown with its most common spelling. Product name in Add Order suggests names already used, so a category isn't typed three ways.

- **Filters:** delivery date (presets or custom), category and Order ID. **Export** produces an Excel file with a *By category* sheet and an *Orders* sheet.
- **By category:** orders, pieces, total sale, delivery charge and courier cost, plus a totals row. Click a category to see its orders.
- **Orders:** one row per order and category, showing Order ID, delivery date, category, pieces, sale, delivery charge and courier cost.
- **Rules:**
  - Only **delivered** sales count, the same rule as the dashboard. Returned orders and exchanges are left out, and pieces sent back from a partly returned order are taken off.
  - Sale = the items' price − their share of the discount, without the delivery charge.
  - The order's **discount, delivery charge and courier cost are shared by pieces**: 2 tees + 1 cap → the tees carry 2/3. The order's largest category takes the rounding leftover, so the shares add up to the order exactly.
  - Courier cost = the courier's real charge, or the delivery charge when unknown.
- **Permission:** *View sold products*.

### Stock

**Stock is counted per variant and size.** A variant is a colour or design the admin names (*Black*, *Navy Blue*); it comes in some of the admin's sizes, and each variant + size has its own count. The Stock menu is a grid: one row per variant, one column per size (— = it doesn't come in that size), and the total.
- **Sizes (admins only):** **Sizes** on the Stock page holds the one size list for the shop (S, M, L, XL, XXL to start). Add, rename, reorder (the order is used everywhere) and switch sizes off. A size that variants use can't be deleted, only switched off. Permission *Manage the size list* is admin-only.
- **Adding:** **Add Variant** takes a name and the pieces of each size (leave a size empty if the variant doesn't come in it). **Add Bulk Variant** is a table with a column per size. Nothing is saved if a name is repeated or already exists, ignoring case.
- **Updating:** **Update** renames a variant and sets each size's count, adds sizes, and takes sizes off (only once they're at 0), with an optional note. **Bulk Update** lists every size of the ticked variants: *Add*, *Remove* or *Set to*; nothing changes if any would drop below 0.
- **History:** every change, with the size, who made it and the linked order.
- **Low stock:** a card and a filter for sizes with 5 or fewer pieces (`app.stock.low-threshold`).
- **Before sizes existed:** each variant's count moved into a **No size** column, so nothing was lost and old orders still balance. Spread it with **Update** (lower *No size*, raise the sizes by the same number); an empty *No size* disappears. Variants named with the size in them (*Navy Blue - M*) can be moved with **Merge**: choose *Navy Blue* and size *M*, and its pieces and the orders holding them move across; the old variant is removed.

**Order form:** **Variant** replaces Color. Typing suggests every variant whose name contains the text. Once a variant is picked, **Size** becomes a list of its sizes with how many are left; out-of-stock sizes can't be picked, and a variant with a single size picks it by itself. The chosen size is written on the order (invoice, Steadfast note). Without a variant, Size stays free text and the item isn't tracked. Exchanges choose the replacement's variant and size the same way.

**Stock follows orders automatically:**

| Event | Stock |
|---|---|
| Order placed | taken |
| Quantity edited | moves by the difference |
| Order cancelled or deleted | given back |
| Returned (refused at the door or after delivery) | **no change**: add resellable pieces back yourself with Update |
| Exchange created | replacement taken |
| Exchange delivered | **no change**: the item the customer sent back isn't restocked by itself |
| Returned order later cancelled or deleted | **no change** for the returned pieces (pieces never sent back are freed as usual) |

An order needing more than is in stock is refused as a whole ("Only 3 of Black / M left").

**Implementation:** each order change re-checks how much stock of each variant + size the order should hold against what its history says it holds, and moves only the difference. Every quantity change is one guarded SQL update, so two orders can't both take the last piece. Orders placed before stock existed never move stock.

**Permissions:** *View stock* and *Add and update stock*. Order takers can search variants without either.

### Order status, notes and courier cost permissions

- **Flow:** Placed → Accepted → Processing → Shipped → Out for delivery → Delivered, plus Cancelled and Returned. *In production* is retired: its orders became Processing, and old timeline entries still show it.
- **Change status freely** (`ORDER_STATUS_OVERRIDE`; admins always): move an order to **any** status at any time, backwards and after Cancelled or Delivered.
  - The change is logged as "Status changed manually".
  - The delivered/returned/cancelled dates follow the new status, so the dashboard and Sold Products stay right. Moving back before delivery removes the sale; undoing a mistaken return keeps the original delivery date.
  - Stock follows too; reopening a cancelled order takes stock again, or is refused when there isn't enough.
  - Before overriding an order booked with Steadfast, the panel warns that Steadfast's own updates may change it again.
- **Without that permission,** staff with *Update order status* follow the normal forward flow.
- **Add internal notes** (`ORDER_NOTE_ADD`) and **Record the courier cost** (`ORDER_COURIER_COST`) are separate permissions. Both work on any order, at any time; the courier cost can be entered before shipping. When these were introduced, staff who had *Edit orders* or *Update order status* were given both, so nobody lost access.

### Advance and due

- **On an order:** an optional **Advance** is what the customer paid before delivery (e.g. by bKash). **Due** = total − advance. You can type either one; the other is filled in, and the advance can't exceed the total.
- **Steadfast:** on cash-on-delivery orders it collects only the **due**.
- **Invoice:** shows *Advance paid* and *Due*.
- **Orders list:** shows both under the total. The Payment dropdown filters by **Advance paid** or **Due** (something still to collect).
- **Excel export:** includes both columns.
- **Dashboard:** unchanged, because revenue is still booked at delivery for the full total.

### Courier cost (internal)

Every shipped order has a **Total courier cost** panel on its page. It's visible to staff only and never on the invoice.

- **Value used:** the courier's real charge. Steadfast fills it in automatically; staff can enter or correct it, e.g. for other couriers.
- **When unknown:** the delivery charge stands in, marked *estimate*.
- **Returns:**
  - Returned after delivery: the return charge is added.
  - Refused at the door: the courier's charge covers both ways; when unknown, it's delivery + return.
- **Dashboard card, Total Courier Cost:** everything paid to couriers in the period. That is the delivery cost of deliveries (exchanges included) plus the courier cost of returns, shown split into delivery and returns.

### Exchanges

An exchange swaps items of a **delivered** order. It is a new order of type *Exchange*, linked to the original. The original order, its invoice and its dashboard figures never change. The exchange goes through production, Send to Steadfast and tracking like any order.

- **Start:** the **Exchange** button beside **Add Order** (find the order by number or phone), or the button on a delivered order's page.
- **Time limit:** none. A delivered order can be exchanged at any time.
- **Items:** each item is **Exchange** or **Not included**. Partial quantities are allowed (1 of 2). A unit can be exchanged (or returned) only once; a cancelled exchange, or one refused at the door, frees its units again.
- **Replacement:** pre-filled with the original product, including its images, so a size change is one edit.
- **Money:**
  - **Credit** = what the customer paid for the returned items (the price, less its share of the original discount).
  - **Price difference** = new items − credit. A positive difference is collected. If the replacement is cheaper, the admin chooses **Refund** or **No refund** each time.
  - **Delivery paid by Customer / Erezer:** covers only the exchange delivery charge. The reason (Wrong size, Defective, …) suggests who pays.
  - **Rider collects (COD)** = the positive difference + the delivery charge if the customer pays.
- **Courier:** the Steadfast note always starts with "EXCHANGE - collect the old item(s): …".
- **Exchange invoice:** a separate PDF showing the new items, returned items and the summary.
- **Order ID:** optional, like Add Order; left empty, one is generated.
- **Editing:** an exchange order is edited like any order (items, sizes, prices, customer, delivery charge, advance). The money is worked out again: the rider collects the positive difference against the returned items' credit, plus the delivery if the customer pays it. Exchanges have no discount. The order that was exchanged keeps its items locked.
- **Dashboard:** booked on the exchange's delivery date.
  - **Total Exchange Items**, plus the items **paid by Customer** and **paid by Erezer**.
  - Product revenue grows only by the positive difference. Refunds on cheaper exchanges, and Erezer-paid delivery (the courier's real charge when known), reduce net profit.
- **Permission:** *Create exchanges*, which can be given to moderators.

### Returns

Every return is a **record** on the order (reason, return charge, **Paid by Customer / Erezer**, refund, items), shown as a *Return* card on the order page and editable afterwards. There is **no time limit**. **Returns don't change stock**: if the pieces can be sold again, add them back on the Stock page.

- **Return after delivery:** the **Return** button beside **Add Order** (find the order by number or phone), or the button on a delivered order's page.
  - Tick the products and how many pieces come back. Some products only → the order stays *Delivered* with a **Partly returned** badge; everything → it becomes *Returned*.
  - The **reason** suggests who pays; it stays editable.
  - **Refund** = the products' value (price less its share of the discount) − the return charge **if the customer pays it**. The delivery charge is not refunded.
- **Refused at the door:** mark the order **Returned** (or Steadfast does). When marking it by hand, the admin must choose **Return charge paid by** each time; there is no default. Steadfast's automatic updates record Erezer. If the customer pays, their share (delivery + return charge) is kept from any **advance** and the rest refunded.
- **Undoing:** moving a Returned order to another status cancels its return records; the card disappears.
- **Dashboard:** booked on the day the return is recorded. **Total Returned Items**, plus items **paid by Customer** and **paid by Erezer** with their charges. Erezer-paid charges are a cost; customer-paid charges on refused parcels are recovered and added back to net profit.
- **Permission:** *Create returns* (moderators can be given it). Correcting a return also works with *Update order status*.

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
