/** One check's result for one repository, as conformance.json carries it. */
export interface ConformanceResult {
  repository: string;
  check: string;
  exit_code: number;
  timestamp: string;
  deviation_adrs?: string[];
}

/** conformance.json as the conformance backend serves it. */
export interface ConformanceDocument {
  generated_at?: string;
  estate_baseline_commit?: string;
  results: ConformanceResult[];
  /** Added by the backend: when it read the file. */
  fetched_at?: string;
}

/** The results that belong to one repository, `<owner>/<name>`. */
export function resultsForRepository(
  document: ConformanceDocument,
  repository: string | undefined,
): ConformanceResult[] {
  if (!repository) {
    return [];
  }
  return document.results.filter(r => r.repository === repository);
}
