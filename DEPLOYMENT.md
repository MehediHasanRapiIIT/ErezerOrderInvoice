# Going live: erezer.site on the Hostinger VPS

A push to `main` runs the tests, builds two Docker images, pushes them to Docker Hub, and rolls them out on the VPS. If the new version doesn't come up healthy, the VPS rolls back to the previous one by itself.

The VPS already runs the Erezer shop (from `D:\Erezer`, in `/opt/erezer`). This panel runs next to it in `/opt/erezer-invoice`, with its own database and image storage. The shop's Caddy keeps ports 80/443 and also serves `erezer.site`, with automatic https.

```
 browser ──https──► shop's Caddy (80/443) ──erezer-edge network──► panel nginx ──► /api → Spring Boot
                     │  erezer.site, www → erezer.site                      └──► /erezer/ → MinIO (image links)
                     └  shop sites (unchanged)
```

Do the steps in order. Steps 1–4 are one-time.

---

## Step 1: DNS at Namecheap

1. **Verify your contact info first.** Namecheap shows "15 days left to verify contact info". Click **Verify contacts** and confirm the email they send. An unverified domain gets suspended, and the site goes offline.
2. Go to **Domain List → erezer.site → Manage → Advanced DNS**.
3. Delete Namecheap's default parking records: the `CNAME www → parkingpage.namecheap.com` and the `URL Redirect @` record.
4. Add two records, both pointing at your VPS's IP (Hostinger panel → VPS → Overview → IPv4):

   | Type | Host | Value | TTL |
   |---|---|---|---|
   | A Record | `@` | `YOUR_VPS_IP` | Automatic |
   | A Record | `www` | `YOUR_VPS_IP` | Automatic |

5. Wait until both answer with your IP. This usually takes 5–30 minutes. Check from your PC:

   ```
   nslookup erezer.site
   nslookup www.erezer.site
   ```

   **Don't deploy before this works.** Caddy requests the https certificate on the first deploy, and repeated failures make Let's Encrypt block you for hours.

## Step 2: Deploy the shop's small update first

The shop repo (`D:\Erezer`) got a backward-compatible change so its Caddy can serve other sites on the same VPS:

- `deploy/Caddyfile`: loads extra site files from `/opt/erezer/sites/`.
- `docker-compose.prod.yml`: Caddy mounts that folder and joins the shared `erezer-edge` network.
- `deploy/deploy.sh`: creates the network and the folder if they're missing.

With no panel file present, the shop behaves exactly as before. Commit and push it the usual way:

```
cd D:\Erezer
git add deploy/Caddyfile deploy/deploy.sh docker-compose.prod.yml
git commit -m "Let Caddy serve other sites on this VPS (erezer.site panel)"
git push origin main
```

Wait for the shop's CD run to go green, and check the shop still opens.

## Step 3: Prepare the VPS

SSH in as the same user the shop deploys with:

```bash
ssh youruser@YOUR_VPS_IP

# Check there is room next to the shop (the panel is capped at ~2 GB, uses ~0.7 GB):
free -h

sudo mkdir -p /opt/erezer-invoice
sudo chown "$USER":"$USER" /opt/erezer-invoice
cd /opt/erezer-invoice
nano .env
```

Paste the contents of [`.env.prod.example`](.env.prod.example) into `nano`, then replace every `CHANGE_ME`. Generate each secret on the VPS:

```bash
openssl rand -hex 24                   # each password (POSTGRES, MINIO_ROOT, STORAGE_SECRET_KEY)
openssl rand -base64 48 | tr -d '\n'   # JWT_SECRET
openssl rand -base64 32                # COURIER_ENCRYPTION_KEY (exactly this command)
```

| Variable | What to put |
|---|---|
| `IMAGE_REPO_OWNER` | your Docker Hub username (the same one the shop uses) |
| `PANEL_HOST` | `erezer.site` (no `https://`) |
| `BOOTSTRAP_ADMIN_EMAIL` / `_PASSWORD` | your first admin login (10+ characters, a letter and a digit) |
| `COURIER_ENCRYPTION_KEY` | **Save a copy somewhere safe.** If it's lost, the Steadfast keys must be re-entered. |
| `CADDY_SITES_DIR`, `CADDY_CONTAINER` | leave as they are if the shop lives in `/opt/erezer` |

Save with Ctrl+O, Enter, Ctrl+X, then lock the file:

```bash
chmod 600 .env
```

If your Docker Hub repositories are private, the VPS is probably already logged in (the shop needed it). Check with `docker login`.

## Step 4: GitHub secrets for this repo

In **github.com/MehediHasanRapiIIT/ErezerOrderInvoice → Settings → Secrets and variables → Actions**.

**Secrets.** Use the same values as the shop's repo; the same deploy key works for both:

| Secret | Value |
|---|---|
| `DOCKERHUB_USERNAME` | Docker Hub username, lowercase |
| `DOCKERHUB_TOKEN` | Docker Hub access token (Read & Write) |
| `VM_HOST` | the VPS IP |
| `VM_USER` | the Linux user from Step 3 |
| `VM_SSH_KEY` | the whole private deploy key, including the BEGIN/END lines |
| `VM_KNOWN_HOSTS` | the output of `ssh-keyscan -H YOUR_VPS_IP` |
| `VM_SSH_PORT` | only if SSH isn't on port 22 |

**Variables tab:**

| Variable | Value |
|---|---|
| `PANEL_HOST` | `erezer.site` (used for the smoke test after each deploy) |

GitHub doesn't show the values of existing secrets, so if you don't have the shop's deploy key any more, make a new one:

```
ssh-keygen -t ed25519 -C "github-actions-erezer-invoice" -f %USERPROFILE%\.ssh\erezer_invoice_deploy -N ""
type %USERPROFILE%\.ssh\erezer_invoice_deploy.pub
```

Add the printed `.pub` line to `~/.ssh/authorized_keys` on the VPS, and put the private file's contents into `VM_SSH_KEY`.

## Step 5: First deploy

Commit and push this repo:

```
cd D:\ErezerInvoice
git add -A
git commit -m "Go live on erezer.site"
git push origin main
```

Watch **Actions → CD**. The first run takes about 10–15 minutes while caches are cold. It:

1. Runs the backend and frontend tests and checks the compose files.
2. Builds and pushes `erezer-invoice-backend` and `erezer-invoice-frontend`.
3. Copies the compose files to `/opt/erezer-invoice` and runs `deploy/deploy.sh`.
4. Smoke-tests `https://erezer.site`.

`deploy/deploy.sh` waits for the containers to be healthy. It then writes `/opt/erezer/sites/erezer-invoice.caddy` and reloads the shop's Caddy. Caddy checks the new file first, so a bad file can never break the shop.

The certificate can take up to a minute on the first visit. If the browser shows a TLS warning, wait and reload.

## Step 6: After the first deploy

1. Open **https://erezer.site** and log in with the bootstrap admin. Change the password (top right menu).
2. **Courier Settings**: enter the Steadfast API key and secret key, click **Test connection**, then tick **Enabled**.
3. **Webhook** for instant status updates. Click **Generate token**, then in the Steadfast panel → **Webhook** enter:
   - Callback URL: `https://erezer.site/api/webhooks/steadfast`
   - Token: the one you copied
4. Add your staff under **Staff Management**.

The live site starts with an empty database. Test orders on your PC stay on your PC.

---

## Day-to-day

**Deploy:** push to `main`. That's it.

**Roll back:**
- **Automatic:** if the new version isn't healthy within 5 minutes, the previous one is restored and the run turns red.
- **Manual:** go to **Actions → CD → Run workflow** and enter a known-good 7-character commit SHA. It redeploys those exact images without rebuilding. Run `cat /opt/erezer-invoice/.deployed-tag` on the VPS to see what's running.

**Logs and status** (on the VPS):

```bash
cd /opt/erezer-invoice
C="-f compose.yaml -f compose.prod.yaml -f compose.deploy.yaml"
docker compose $C ps
docker compose $C logs -f backend
docker logs --tail 100 erezer-caddy        # certificates / routing (the shop's Caddy)
```

**Never run `docker compose down -v`** on the VPS: `-v` deletes the database and all uploaded images.

**Backups:** a `pg_dump` runs at startup and every 24 hours, keeping 14 days. The dumps are stored on the same VPS, so copy them to your PC regularly:

```bash
# on the VPS: copy the dumps out of the container into ~/erezer-backups
cd /opt/erezer-invoice
docker compose $C cp pg-backup:/backups ~/erezer-backups
```

```
:: on your PC (Command Prompt): download them
scp -r youruser@YOUR_VPS_IP:erezer-backups D:\erezer-backups
```

Restoring a dump (this replaces the live data):

```bash
gunzip -c ~/erezer-backups/erezer-YYYYMMDD-HHMMSS.sql.gz | docker compose $C exec -T postgres psql -U erezer -d erezer
```

Product images live in the `minio-data` volume. Back that up too if they matter:

```bash
docker run --rm -v erezer-invoice_minio-data:/d -v "$PWD":/out alpine tar czf /out/minio-$(date +%F).tgz -C /d .
```

---

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Browser: "site can't be reached" | DNS isn't pointing at the VPS yet (Step 1), or Namecheap's parking records are still there. |
| TLS warning that doesn't go away | Caddy couldn't get the certificate. Run `docker logs erezer-caddy \| grep -i erezer.site`. Usually DNS, or ports 80/443 closed in Hostinger's firewall (VPS → Security → Firewall). |
| Deploy says "Caddy container 'erezer-caddy' not found" | The shop isn't running, or isn't deployed with Step 2's change. Deploy the shop, then rerun this CD. |
| 502 Bad Gateway on erezer.site | The shop's Caddy isn't on `erezer-edge`. Run `docker network inspect erezer-edge` and check it lists `erezer-caddy` and the panel's frontend. Redeploy the shop (Step 2). |
| Deploy fails, backend unhealthy | Read the log lines the run prints. Usually a missing or wrong value in `/opt/erezer-invoice/.env`. |
| Product images don't show | `STORAGE_BUCKET` must be `erezer`, and `PANEL_HOST` must be exactly the address in the browser. |
| Logged out on every refresh | You're on `http://` instead of `https://`; the login cookie is https-only. |
| Steadfast "Test connection" fails with "cannot be reached" | The VPS can't reach `portal.packzy.com`. Run `curl -I https://portal.packzy.com` on the VPS. |

---

## Security notes

- Only the shop's Caddy is reachable from the internet. The panel's Postgres, MinIO and API aren't published at all.
- `/actuator` is hidden. Through the site, image links are read-only: writes are refused, and a link stops working after 15 minutes.
- The `.env` never leaves the VPS. CI never sees or overwrites it.
- Back up `COURIER_ENCRYPTION_KEY` and `JWT_SECRET` outside the VPS, for example in a password manager.
