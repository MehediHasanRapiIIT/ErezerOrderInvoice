#!/usr/bin/env node
// Loads realistic demo data into the LOCAL panel, so every feature can be tried: every order status, returns
// (refused and after delivery), discounts, prepaid orders, a returning customer, product images (invoice image
// pages), exchanges (Erezer- and customer-paid, cheaper with a refund, one waiting to ship), courier costs,
// internal notes and a month of expenses. Dates are spread over the last 30 days so the dashboard has a shape.
//
//   node scripts/seed-demo-data.mjs            load the demo data
//   node scripts/seed-demo-data.mjs --remove   delete it again (demo orders, their exchanges, demo expenses)
//
// Demo orders are numbered DEMO-1001…; demo expenses carry the reference DEMO; demo stock variants are named
// Black, White, Navy, Maroon, Olive, Khaki and Grey (--remove deletes variants with these names).
// Settings (environment): PANEL_URL (default http://localhost:8088), ADMIN_EMAIL, ADMIN_PASSWORD.
// Local only: it refuses to run against anything but localhost, and it adjusts dates directly in the local database.
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { deflateSync } from 'node:zlib';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const BASE = (process.env.PANEL_URL ?? 'http://localhost:8088').replace(/\/+$/, '');
const EMAIL = process.env.ADMIN_EMAIL ?? 'admin@erezer.local';
const PASSWORD = process.env.ADMIN_PASSWORD ?? 'Admin@12345678';

if (!/^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(BASE)) {
  console.error(`Refusing to run against ${BASE}: demo data is for the local panel only.`);
  process.exit(1);
}

// ---- local database (dates can only be set there) ---------------------------------------------------------
const envFile = (() => {
  try {
    return Object.fromEntries(readFileSync(resolve(ROOT, '.env'), 'utf8').split(/\r?\n/)
      .filter((l) => /^[A-Z_]+=/.test(l)).map((l) => [l.slice(0, l.indexOf('=')), l.slice(l.indexOf('=') + 1)]));
  } catch {
    return {};
  }
})();
const DB_USER = envFile.POSTGRES_USER ?? 'erezer';
const DB_NAME = envFile.POSTGRES_DB ?? 'erezer';

function sql(statement) {
  const result = spawnSync('docker', ['compose', 'exec', '-T', 'postgres', 'psql', '-U', DB_USER, '-d', DB_NAME,
    '-v', 'ON_ERROR_STOP=1', '-q', '-t', '-A', '-c', statement], { cwd: ROOT, encoding: 'utf8' });
  if (result.status !== 0) {
    throw new Error(`database: ${result.stderr || result.stdout}`);
  }
  return result.stdout.trim();
}

// ---- API ----------------------------------------------------------------------------------------------------
let token;
async function api(method, path, body) {
  const res = await fetch(`${BASE}/api${path}`, {
    method,
    headers: { ...(body instanceof FormData ? {} : { 'Content-Type': 'application/json' }), ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body instanceof FormData ? body : body ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) {
    throw new Error(`${method} ${path} → ${res.status} ${await res.text()}`);
  }
  return res.status === 204 ? null : res.json();
}

// ---- product images: simple tee/pant/cap pictures, so invoices get their image pages --------------------------
function png(width, height, pixel) {
  const raw = Buffer.alloc((width * 3 + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (width * 3 + 1)] = 0;
    for (let x = 0; x < width; x++) {
      const [r, g, b] = pixel(x, y);
      const o = y * (width * 3 + 1) + 1 + x * 3;
      raw[o] = r; raw[o + 1] = g; raw[o + 2] = b;
    }
  }
  const crcTable = Array.from({ length: 256 }, (_, n) => {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    return c >>> 0;
  });
  const crc = (buf) => { let c = 0xffffffff; for (const b of buf) c = crcTable[(c ^ b) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const td = Buffer.concat([Buffer.from(type), data]);
    const c = Buffer.alloc(4); c.writeUInt32BE(crc(td));
    return Buffer.concat([len, td, c]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0); ihdr.writeUInt32BE(height, 4); ihdr[8] = 8; ihdr[9] = 2;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
}

const BG = [241, 239, 236];
const shapes = {
  tee: (x, y) => (y > 110 && y < 470 && x > 130 && x < 370) || (y > 110 && y < 230 && x > 40 && x < 460 && y - 110 > Math.abs(x - 250) - 210),
  pant: (x, y) => y > 70 && y < 480 && ((x > 140 && x < 245) || (x > 255 && x < 360) || (y < 180 && x > 140 && x < 360)),
  cap: (x, y) => ((x - 250) ** 2 / 150 ** 2 + (y - 300) ** 2 / 110 ** 2 < 1 && y < 300) || (y >= 300 && y < 325 && x > 100 && x < 460),
};
async function uploadImage(kind, rgb) {
  const image = png(500, 560, (x, y) => (shapes[kind](x, y) ? rgb : BG));
  const form = new FormData();
  form.append('files', new Blob([image], { type: 'image/png' }), `${kind}.png`);
  return (await api('POST', '/files/images', form))[0].id;
}

// ---- helpers ----------------------------------------------------------------------------------------------------
/** Demo stock: plenty of most colours, Maroon running low, Grey out of stock. */
const DEMO_VARIANTS = [['Black', 40], ['White', 35], ['Navy', 20], ['Maroon', 6], ['Olive', 15], ['Khaki', 12], ['Grey', 0]];

const FLOW = ['ACCEPTED', 'PROCESSING', 'SHIPPED', 'OUT_FOR_DELIVERY', 'DELIVERED'];
const COURIERS = ['Pathao', 'RedX', 'eCourier'];

async function advance(id, target, extra = {}) {
  if (!FLOW.includes(target)) {
    await api('POST', `/orders/${id}/status`, { status: target, ...extra });
    return;
  }
  const current = (await api('GET', `/orders/${id}`)).status;
  for (const status of FLOW.slice(FLOW.indexOf(current) + 1)) {
    const body = { status };
    if (status === 'SHIPPED') {
      body.courier = COURIERS[id % COURIERS.length];
      body.trackingNumber = `${body.courier.slice(0, 2).toUpperCase()}${100000 + id * 7919 % 900000}`;
    }
    await api('POST', `/orders/${id}/status`, body);
    if (status === target) return;
  }
}

let withVariantFn = (item) => item;
async function createOrder(number, customer, items, { payment = 'CASH', delivery = 70, discount = 0, advance = 0 } = {}) {
  const { id } = await api('POST', '/orders', {
    orderNumber: number, paymentMethod: payment, deliveryCharge: delivery, discount, advance,
    customer, items: items.map((i) => withVariantFn({ description: null, color: null, size: null, imageIds: [], ...i })),
  });
  return id;
}

/** Moves an order's dates back: created / delivered / returned / cancelled N days (+ hours) ago. */
function backdate(id, { created, delivered, returned, cancelled }) {
  const ago = (d) => (d === undefined ? null : `now() - interval '${d} days' - interval '${(id * 37) % 9} hours'`);
  sql(`update orders set created_at = ${ago(created)}, updated_at = ${ago(Math.min(created, delivered ?? created, returned ?? created))},
        delivered_at = case when delivered_at is not null then ${ago(delivered) ?? 'delivered_at'} end,
        returned_at = case when returned_at is not null then ${ago(returned) ?? 'returned_at'} end,
        cancelled_at = case when cancelled_at is not null then ${ago(cancelled) ?? 'cancelled_at'} end
      where id = ${id}`);
  sql(`with h as (select id, to_status, row_number() over (order by changed_at, id) as rn from order_status_history where order_id = ${id})
       update order_status_history s set changed_at = case
           when h.to_status = 'DELIVERED' then o.delivered_at
           when h.to_status = 'RETURNED' then o.returned_at
           when h.to_status = 'CANCELLED' then o.cancelled_at
           else o.created_at + (h.rn - 1) * interval '2 hours' end
       from h, orders o where s.id = h.id and o.id = ${id}`);
  sql(`update order_notes set created_at = (select created_at + interval '1 hour' from orders where id = ${id}) where order_id = ${id}`);
}

// ---- remove -------------------------------------------------------------------------------------------------------
function removeDemo() {
  const demo = `select id from orders where order_number like 'DEMO-%'`;
  const all = `select id from orders where order_number like 'DEMO-%' or exchange_of_order_id in (${demo})`;
  const before = sql(`select count(*) from orders where id in (${all})`);
  sql(`delete from courier_batch_items where order_id in (${all})`);
  sql(`delete from courier_shipments where order_id in (${all})`);
  sql(`delete from orders where exchange_of_order_id in (${demo})`);
  sql(`delete from orders where order_number like 'DEMO-%'`);
  const expenses = sql(`with d as (delete from expenses where reference = 'DEMO' returning 1) select count(*) from d`);
  const names = DEMO_VARIANTS.map(([n]) => `'${n.toLowerCase()}'`).join(',');
  const variants = sql(`with d as (delete from stock_variants where lower(name) in (${names}) returning 1) select count(*) from d`);
  console.log(`Removed ${before} demo orders (with their exchanges), ${expenses} demo expenses and ${variants} demo stock variants.`);
}

// ---- seed -----------------------------------------------------------------------------------------------------------
async function seed() {
  token = (await api('POST', '/auth/login', { email: EMAIL, password: PASSWORD })).accessToken;
  if (sql(`select count(*) from orders where order_number like 'DEMO-%'`) !== '0') {
    console.error('Demo data is already loaded. Run with --remove first to load it again.');
    process.exit(1);
  }

  console.log('Uploading product images…');
  const img = {
    blackTee: await uploadImage('tee', [28, 28, 30]), whiteTee: await uploadImage('tee', [250, 250, 250]),
    navyTee: await uploadImage('tee', [32, 45, 92]), maroonTee: await uploadImage('tee', [122, 30, 48]),
    olivePant: await uploadImage('pant', [96, 104, 58]), blackPant: await uploadImage('pant', [40, 40, 44]),
    cap: await uploadImage('cap', [190, 150, 90]),
  };

  console.log('Adding stock variants…');
  const stock = {};
  for (const v of await api('POST', '/stock/variants/bulk', { items: DEMO_VARIANTS.map(([name, quantity]) => ({ name, quantity })) })) {
    stock[v.name] = v.id;
  }
  // Every demo item picks its colour's stock variant, so orders move stock like real ones.
  const withVariant = (item) => (item.color && stock[item.color] ? { ...item, variantId: stock[item.color] } : item);
  withVariantFn = withVariant;

  const c = (name, phone, address) => ({ name, phone, address });
  const rafi = c('Rafi Ahmed', '01711555001', 'House 7, Road 3, Mirpur 10, Dhaka');            // returning customer
  const people = [
    c('Nadia Islam', '01911555003', 'Flat 4B, Road 27, Dhanmondi, Dhaka'),
    c('Karim Uddin', '01822555002', 'Shop 12, New Market, Chattogram'),
    c('Sumaiya Akter', '01611555005', 'Sector 7, Uttara, Dhaka'),
    c('Tanvir Hasan', '01511555006', 'Zindabazar, Sylhet'),
    c('Mehedi Hasan', '01646065553', 'Mirpur-2, Dhaka'),
    c('Farhana Rahman', '01311555008', 'Shaheb Bazar, Rajshahi'),
    c('Arif Chowdhury', '01755555009', 'Gulshan 2, Dhaka'),
    c('Jannat Ferdous', '01855555010', 'Khulna Sadar, Khulna'),
    c('Sakib Hossain', '01955555011', 'Banani, Dhaka'),
  ];
  const tee = (color, size, image, price = 990, qty = 1) => ({ productName: 'Regular Fit T-Shirt', productCode: 'TS-101', color, size, unitPrice: price, quantity: qty, imageIds: image ? [image] : [] });
  const over = (color, size, image, qty = 1) => ({ productName: 'Oversized Drop-Shoulder Tee', productCode: 'TS-210', color, size, unitPrice: 1190, quantity: qty, imageIds: image ? [image] : [], description: '220 GSM cotton' });
  const pant = (color, size, image) => ({ productName: 'Cargo Pant', productCode: 'PN-310', color, size, unitPrice: 1650, quantity: 1, imageIds: image ? [image] : [] });
  const cap = (qty = 1) => ({ productName: 'Embroidered Cap', productCode: 'CP-050', color: 'Khaki', unitPrice: 450, quantity: qty, imageIds: [img.cap] });

  const made = [];
  const add = async (label, number, customer, items, opts, then) => {
    const id = await createOrder(number, customer, items, opts);
    made.push({ number, label, id });
    if (then) await then(id);
    return id;
  };

  console.log('Creating orders…');
  // Not finished yet (Active tab), created recently
  const placed = await add('Placed, ৳200 advance by bKash (due ৳860)', 'DEMO-1001', people[0], [tee('Black', 'M', img.blackTee)], { advance: 200 });
  const accepted = await add('Accepted, prepaid by bKash (COD ৳0 on Steadfast)', 'DEMO-1002', people[1], [over('White', 'L', img.whiteTee), cap()], { payment: 'BKASH' }, (id) => advance(id, 'ACCEPTED'));
  const production = await add('Processing, with a ৳200 discount', 'DEMO-1003', people[2], [tee('Navy', 'L', img.navyTee, 990, 2), pant('Olive', '32', img.olivePant)], { discount: 200 }, (id) => advance(id, 'PROCESSING'));
  const processing = await add('Processing, 4 products with images (invoice image pages)', 'DEMO-1004', people[3],
    [tee('Maroon', 'XL', img.maroonTee), over('Black', 'M', img.blackTee), pant('Black', '34', img.blackPant), cap(2)], {}, (id) => advance(id, 'PROCESSING'));
  const shipped = await add('Shipped with Pathao/RedX, delivery charge paid in advance', 'DEMO-1005', people[4], [tee('White', 'S', img.whiteTee)], { advance: 70 }, (id) => advance(id, 'SHIPPED'));
  const outForDelivery = await add('Out for delivery, paid by Nagad', 'DEMO-1006', people[5], [over('Navy', 'XL', img.navyTee)], { payment: 'NAGAD' }, (id) => advance(id, 'OUT_FOR_DELIVERY'));

  // Delivered over the last month (dashboard and chart)
  const deliveredPlan = [
    ['DEMO-1007', people[6], [tee('Black', 'L', img.blackTee, 990, 2)], 28, 25, 70],
    ['DEMO-1008', people[7], [pant('Olive', '30', img.olivePant), cap()], 24, 21, 120],
    ['DEMO-1009', people[8], [over('White', 'M', img.whiteTee)], 19, 16, null],
    ['DEMO-1010', rafi, [tee('Navy', 'M', img.navyTee)], 15, 13, 65],
    ['DEMO-1011', people[1], [over('Black', 'XL', img.blackTee), over('White', 'XL', img.whiteTee)], 12, 10, 70],
    ['DEMO-1012', people[2], [pant('Black', '32', img.blackPant)], 8, 6, 75],
  ];
  for (const [number, customer, items, created, delivered, courierCharge] of deliveredPlan) {
    const id = await add(`Delivered ${delivered} days ago${courierCharge ? ` (courier charged ৳${courierCharge})` : ' (courier cost estimated)'}`,
      number, customer, items, { delivery: number === 'DEMO-1008' ? 130 : 70 }, (oid) => advance(oid, 'DELIVERED'));
    if (courierCharge) await api('PUT', `/orders/${id}/courier-cost`, { courierCharge });
    backdate(id, { created, delivered });
  }

  // Ready for you to try an exchange
  const exchangeToday = await add('Delivered today: try an exchange (3 products, ৳1,000 advance)', 'DEMO-1013', rafi,
    [tee('Black', 'M', img.blackTee, 990, 2), pant('Olive', '32', img.olivePant), cap()], { advance: 1000 }, (id) => advance(id, 'DELIVERED'));
  backdate(exchangeToday, { created: 3, delivered: 0 });
  const exchange2 = await add('Delivered 2 days ago: try an exchange (still inside 3 days)', 'DEMO-1014', people[3],
    [over('Navy', 'L', img.navyTee), tee('Maroon', 'L', img.maroonTee)], {}, (id) => advance(id, 'DELIVERED'));
  backdate(exchange2, { created: 5, delivered: 2 });
  const late = await add('Delivered 6 days ago: exchange window closed (admin override)', 'DEMO-1015', people[4],
    [tee('White', 'XL', img.whiteTee)], {}, (id) => advance(id, 'DELIVERED'));
  backdate(late, { created: 9, delivered: 6 });

  // Cancelled and returned
  const cancelled = await add('Cancelled', 'DEMO-1016', people[5], [cap(3)], {}, (id) => advance(id, 'CANCELLED'));
  backdate(cancelled, { created: 11, cancelled: 10 });
  const refused = await add('Returned: refused at the door (delivery + ৳50 return)', 'DEMO-1017', rafi, [over('Black', 'L', img.blackTee)], {},
    async (id) => { await advance(id, 'OUT_FOR_DELIVERY'); await api('POST', `/orders/${id}/status`, { status: 'RETURNED', returnDeliveryCharge: 50, note: 'Customer refused at the door' }); });
  backdate(refused, { created: 7, returned: 4 });
  const returnedLate = await add('Returned after delivery (refund + ৳60 return charge)', 'DEMO-1018', people[6], [pant('Black', '36', img.blackPant)], {},
    async (id) => { await advance(id, 'DELIVERED'); await api('POST', `/orders/${id}/status`, { status: 'RETURNED', returnDeliveryCharge: 60, note: 'Size too big, refunded by bKash' }); });
  backdate(returnedLate, { created: 20, delivered: 17, returned: 14 });

  // Orders with exchanges already made
  const withExchanges = await add('Has exchanges: one delivered (Erezer paid), one waiting to ship', 'DEMO-1019', people[7],
    [tee('Black', 'S', img.blackTee), over('White', 'M', img.whiteTee), cap()], {}, (id) => advance(id, 'DELIVERED'));
  const items19 = await api('GET', `/orders/${withExchanges}/exchange-options`);
  const exA = (await api('POST', `/orders/${withExchanges}/exchanges`, {
    reason: 'DEFECTIVE', deliveryPaidBy: 'EREZER', deliveryCharge: 120, refund: false, overrideTimeLimit: true,
    note: 'Print was cracked on arrival',
    items: [{ originalItemId: items19.items[0].id, quantity: 1,
      replacement: withVariant({ productName: 'Premium Heavyweight Tee', productCode: 'TS-900', color: 'Black', size: 'S', unitPrice: 1290, quantity: 1, imageIds: [img.blackTee], description: null }) }],
  })).id;
  await advance(exA, 'DELIVERED');
  await api('PUT', `/orders/${exA}/courier-cost`, { courierCharge: 110 });
  const exC = (await api('POST', `/orders/${withExchanges}/exchanges`, {
    reason: 'WRONG_SIZE', deliveryPaidBy: 'CUSTOMER', deliveryCharge: 70, refund: false, overrideTimeLimit: true,
    items: [{ originalItemId: items19.items[1].id, quantity: 1,
      replacement: withVariant({ productName: 'Oversized Drop-Shoulder Tee', productCode: 'TS-210', color: 'White', size: 'L', unitPrice: 1190, quantity: 1, imageIds: [img.whiteTee], description: '220 GSM cotton' }) }],
  })).id;
  backdate(withExchanges, { created: 13, delivered: 11 });
  backdate(exA, { created: 10, delivered: 7 });
  backdate(exC, { created: 1 });

  const cheaper = await add('Has a cheaper exchange with a ৳340 refund', 'DEMO-1020', people[8], [pant('Olive', '34', img.olivePant)], {}, (id) => advance(id, 'DELIVERED'));
  const items20 = await api('GET', `/orders/${cheaper}/exchange-options`);
  const exB = (await api('POST', `/orders/${cheaper}/exchanges`, {
    reason: 'CHANGED_MIND', deliveryPaidBy: 'CUSTOMER', deliveryCharge: 70, refund: true, overrideTimeLimit: true,
    items: [{ originalItemId: items20.items[0].id, quantity: 1,
      replacement: withVariant({ productName: 'Jogger Pant', productCode: 'PN-120', color: 'Black', size: '34', unitPrice: 1310, quantity: 1, imageIds: [img.blackPant], description: null }) }],
  })).id;
  await advance(exB, 'DELIVERED');
  backdate(cheaper, { created: 9, delivered: 6 });
  backdate(exB, { created: 5, delivered: 3 });

  // Recent ones keep "today" dates but get a little spread
  backdate(placed, { created: 0 });
  backdate(accepted, { created: 1 });
  backdate(production, { created: 2 });
  backdate(processing, { created: 3 });
  backdate(shipped, { created: 4 });
  backdate(outForDelivery, { created: 4 });

  // Internal notes
  await api('POST', `/orders/${production}/notes`, { body: 'Customer wants the print 2 cm higher than usual.' });
  await api('POST', `/orders/${refused}/notes`, { body: 'Third time this number refused a parcel. Ask for advance delivery charge next time.' });

  console.log('Adding expenses…');
  const categories = await api('GET', '/expense-categories');
  const cat = (name) => categories.find((x) => x.name === name)?.id ?? categories[0].id;
  const day = (n) => { const d = new Date(Date.now() + 6 * 3600e3 - n * 86400e3); return d.toISOString().slice(0, 10); };
  const expenses = [
    [27, 'Raw materials', 'Cotton fabric 40 yards (black, white, navy)', 18500, 'BANK_TRANSFER'],
    [25, 'Printing', 'DTF print film and ink', 4200, 'BKASH'],
    [22, 'Packaging', 'Branded poly mailers ×500', 3600, 'CASH'],
    [20, 'Rent', 'Workshop rent (October)', 15000, 'BANK_TRANSFER'],
    [14, 'Marketing', 'Facebook ads boost', 5000, 'CARD'],
    [10, 'Salaries', 'Tailor weekly wages', 9000, 'CASH'],
    [6, 'Utilities', 'Electricity bill', 2350, 'BKASH'],
    [2, 'Courier & delivery', 'Pickup van for bulk dispatch', 800, 'CASH'],
  ];
  for (const [n, category, description, amount, paymentMethod] of expenses) {
    await api('POST', '/expenses', { expenseDate: day(n), categoryId: cat(category), description, amount, paymentMethod, reference: 'DEMO', notes: null, receiptFileId: null });
  }

  console.log('\nDemo data loaded:\n');
  for (const m of made) console.log(`  ${m.number}  ${m.label}`);
  console.log(`\n  + 3 exchange orders (delivered Erezer-paid, delivered cheaper with refund, accepted waiting to ship)`);
  console.log(`  + ${expenses.length} expenses over the last month`);
  console.log(`  + ${DEMO_VARIANTS.length} stock variants (${DEMO_VARIANTS.map(([n, q]) => `${n} ${q}`).join(', ')} before orders)`);
  console.log(`\nOpen ${BASE} and log in as ${EMAIL}. Remove it again with: node scripts/seed-demo-data.mjs --remove`);
  console.log('\nCAUTION: if real Steadfast keys are saved in this local panel, "Send to Steadfast" on a demo order books a REAL parcel.');
}

try {
  if (process.argv.includes('--remove')) {
    removeDemo();
  } else {
    await seed();
  }
} catch (err) {
  console.error(`\nFailed: ${err.message}`);
  process.exit(1);
}
