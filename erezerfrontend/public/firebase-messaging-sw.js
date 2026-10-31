// Shows push notifications while the panel is closed. It holds no keys: a message carries everything it needs.
self.addEventListener('push', (event) => {
  let payload = {};
  try {
    payload = event.data ? event.data.json() : {};
  } catch {
    // Not one of ours; show the plain title below.
  }
  const data = payload.data ?? payload;
  event.waitUntil(
    self.registration.showNotification(data.title || 'Erezer', {
      body: data.body || '',
      icon: '/apple-touch-icon.png',
      badge: '/favicon-48.png',
      data: { link: data.link || '/notifications' },
    }),
  );
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const url = new URL(event.notification.data?.link || '/notifications', self.location.origin).href;
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((windows) => {
      const open = windows.find((w) => w.url.startsWith(self.location.origin) && 'navigate' in w);
      return open ? open.navigate(url).then((w) => (w ?? open).focus()) : self.clients.openWindow(url);
    }),
  );
});
