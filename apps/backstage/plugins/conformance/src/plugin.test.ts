import { conformancePlugin } from './plugin';

describe('conformance', () => {
  it('should export plugin', () => {
    expect(conformancePlugin).toBeDefined();
  });
});
