import { statusFromExitCode, statusLabels } from './status';

describe('statusFromExitCode', () => {
  it('reads 0 as clean', () => {
    expect(statusFromExitCode(0)).toBe('clean');
  });

  it('reads 3 as drift', () => {
    expect(statusFromExitCode(3)).toBe('drift');
  });

  it.each([1, 2, 4, 127, -1, 255])('reads %i as a failed check', code => {
    expect(statusFromExitCode(code)).toBe('check-failed');
  });

  it('labels every status', () => {
    expect(statusLabels).toEqual({
      clean: 'clean',
      drift: 'drift',
      'check-failed': 'check failed',
    });
  });
});
