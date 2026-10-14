# EREZER Admin — Frontend

Angular 21 admin panel (standalone components, signals, Tailwind 4). See the [root README](../README.md) for the full stack.

```bash
npm ci
npm start                    # http://localhost:4201, proxies /api to the backend on :8081 (proxy.conf.json)
npm test -- --watch=false    # Vitest unit tests
npm run build                # production build -> dist/erezer-admin/browser
```

## Layout

```
src/app/
├── core/        Singletons: auth (service, interceptor, guards), HTTP error parsing, toasts, page titles
├── layout/      Shell with sidebar + top bar; nav-items.ts drives the menu and its permissions
├── shared/      Reusable pieces (e.g. *appHasPermission directive)
└── features/    One folder per section (auth, dashboard, orders, expenses, staff), lazy-loaded
```

Conventions: one feature per folder with its own `*.routes.ts`, `OnPush` change detection, signals for state,
and every new page guarded with `requirePermission(...)` in its route.
