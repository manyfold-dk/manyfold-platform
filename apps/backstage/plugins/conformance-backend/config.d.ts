export interface Config {
  /** The Baseline conformance card's data source. */
  conformance?: {
    /**
     * URL of conformance.json, read through the backend's URL reader. For a file
     * in a private GitHub repository use the github.com blob form
     * (https://github.com/<owner>/<repo>/blob/<ref>/<path>): the GitHub reader
     * matches the integration's host and authenticates with its token. Unset, the
     * route answers 503.
     */
    sourceUrl?: string;
  };
}
