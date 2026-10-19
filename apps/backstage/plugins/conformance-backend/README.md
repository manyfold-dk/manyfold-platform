# conformance-backend

The backend half of the Baseline conformance card: one route that serves the estate's
`conformance.json` to the card. The data contract and the card are described in the app's
[README](../../README.md#baseline-conformance-card).

| Route | What it returns |
|---|---|
| `GET /api/conformance/conformance.json` | The source document with `fetched_at` added: the time the backend read it |

- **Source.** `conformance.sourceUrl` in app-config, read through the backend's URL reader.
  The GitHub reader matches only the integration's host, so a file in a private repository
  takes the `https://github.com/<owner>/<repo>/blob/<ref>/<path>` form; the reader then
  fetches it with the integration's token. Unset, the route answers 503.
- **Cache.** The parsed body is kept for five minutes. Concurrent requests share one read.
  A failed read serves the previous body and logs a warning; without one, the route answers
  503.
- **Auth.** The default policy: the caller needs a user or service token. The card calls
  the route with the signed-in user's token.
- **No database and no scheduler.**
