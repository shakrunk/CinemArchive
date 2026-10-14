# Web startup and GitHub Pages routing

`apps/web/public/bootstrap.js` runs synchronously in the HTML head, before the React
module and first paint. It applies the saved theme (or system fallback) and consumes
the one-shot `sessionStorage.redirect` value from the Pages fallback. Only a valid
same-origin URL can replace browser history; malformed or cross-origin values are
discarded. Storage errors do not interrupt startup.

GitHub Pages serves `public/404.html` for paths without a physical file. Its external
`redirect.js` saves the full original URL and replaces the navigation with `/`.
Bootstrap restores the original path, query and fragment before the app router starts.
If session storage is unavailable, the fallback instead navigates to `/` with the
original query and fragment intact. The app's query-based view/title routing and
authentication parameters survive, but an arbitrary pathname cannot be restored.
The fallback also provides a plain home link when JavaScript cannot run.

Keep both scripts as classic, synchronous scripts: `async`, `defer` or module loading
would let the first paint/router race ahead. Vite copies these public assets verbatim;
the PWA's existing JavaScript precache includes them. Both HTML entrypoints avoid
inline scripts, and ESLint checks the public JavaScript. The production-bundle browser
suite serves the actual 404 document and checks normal recovery, unavailable storage,
and stale cross-origin redirects across all configured engines.

This removes avoidable inline-script dependencies under [#277](https://github.com/shakrunk/CinemArchive/issues/277).
It does **not** deploy a Content Security Policy or certify browser storage availability
throughout the app. CSP, response headers/hosting controls, PWA assets, metadata,
performance and device accessibility evidence remain in the release blocker.
