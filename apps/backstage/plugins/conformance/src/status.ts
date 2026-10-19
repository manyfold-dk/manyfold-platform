/** The card's reading of one check's exit code. */
export type ConformanceStatus = 'clean' | 'drift' | 'check-failed';

/**
 * Maps a conformance check's exit code to its status: 0 is clean, 3 is drift,
 * and anything else means the check itself failed.
 */
export function statusFromExitCode(exitCode: number): ConformanceStatus {
  if (exitCode === 0) {
    return 'clean';
  }
  if (exitCode === 3) {
    return 'drift';
  }
  return 'check-failed';
}

/** The label the card shows for a status. */
export const statusLabels: Record<ConformanceStatus, string> = {
  clean: 'clean',
  drift: 'drift',
  'check-failed': 'check failed',
};
