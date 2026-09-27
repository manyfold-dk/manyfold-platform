import { describe, expect, it } from 'vitest';
import { BoundedMap, BoundedSet } from '../../src/services/bounded.js';

describe('BoundedSet', () => {
  it('forgets the oldest entry past the limit', () => {
    const set = new BoundedSet<string>(2);
    set.add('a');
    set.add('b');
    set.add('c');
    expect([set.has('a'), set.has('b'), set.has('c')]).toEqual([false, true, true]);
    expect(set.size).toBe(2);
  });

  it('refreshes an entry that is added again', () => {
    const set = new BoundedSet<string>(2);
    set.add('a');
    set.add('b');
    set.add('a');
    set.add('c');
    expect([set.has('a'), set.has('b')]).toEqual([true, false]);
  });
});

describe('BoundedMap', () => {
  it('forgets the oldest key past the limit', () => {
    const map = new BoundedMap<string, number>(2);
    map.set('a', 1);
    map.set('b', 2);
    map.set('c', 3);
    expect([map.get('a'), map.get('b'), map.get('c')]).toEqual([undefined, 2, 3]);
  });
});
