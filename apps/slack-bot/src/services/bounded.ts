/**
 * A Set that forgets its oldest entries past `limit`. For deduplication of stream messages: the
 * consumer group already guarantees at-least-once delivery, so the set only has to cover a
 * redelivery window, not the process lifetime.
 */
export class BoundedSet<T> {
  private readonly items = new Set<T>();

  constructor(private readonly limit: number) {}

  has(value: T): boolean {
    return this.items.has(value);
  }

  add(value: T): void {
    this.items.delete(value);
    this.items.add(value);
    while (this.items.size > this.limit) {
      const oldest = this.items.values().next().value as T;
      this.items.delete(oldest);
    }
  }

  delete(value: T): void {
    this.items.delete(value);
  }

  get size(): number {
    return this.items.size;
  }
}

/** A Map that forgets its oldest entries past `limit`; insertion order is Map's own. */
export class BoundedMap<K, V> {
  private readonly items = new Map<K, V>();

  constructor(private readonly limit: number) {}

  get(key: K): V | undefined {
    return this.items.get(key);
  }

  set(key: K, value: V): void {
    this.items.delete(key);
    this.items.set(key, value);
    while (this.items.size > this.limit) {
      const oldest = this.items.keys().next().value as K;
      this.items.delete(oldest);
    }
  }

  delete(key: K): void {
    this.items.delete(key);
  }

  clear(): void {
    this.items.clear();
  }

  get size(): number {
    return this.items.size;
  }
}
