import { resultsForRepository } from './types';

const document = {
  generated_at: '2026-10-19T00:00:00Z',
  results: [
    {
      repository: 'example-org/one',
      check: 'conformance',
      exit_code: 0,
      timestamp: '2026-10-19T00:00:00Z',
    },
    {
      repository: 'example-org/two',
      check: 'agent',
      exit_code: 3,
      timestamp: '2026-10-19T00:00:00Z',
    },
    {
      repository: 'example-org/one',
      check: 'agent',
      exit_code: 1,
      timestamp: '2026-10-19T00:00:00Z',
    },
  ],
};

describe('resultsForRepository', () => {
  it('keeps the results of the given repository', () => {
    expect(
      resultsForRepository(document, 'example-org/one').map(r => r.check),
    ).toEqual(['conformance', 'agent']);
  });

  it('returns nothing without a repository', () => {
    expect(resultsForRepository(document, undefined)).toEqual([]);
  });
});
