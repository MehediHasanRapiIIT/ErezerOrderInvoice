/** Production build. The SPA and API share an origin behind nginx, so the API base is relative. */
export const environment = {
  production: true,
  apiBaseUrl: '/api',
};
