/** The few Redis commands the ops decision store uses, in memory, for tests. */
export function fakeRedis() {
  const kv = new Map<string, string>();
  return {
    kv,
    async set(key: string, value: string, ..._opts: unknown[]) {
      if (kv.has(key)) return null;
      kv.set(key, value);
      return 'OK';
    },
    async get(key: string) {
      return kv.get(key) ?? null;
    },
    async eval(script: string, _n: number, key: string, own: string, next?: string) {
      if (script.includes("'set'")) {
        const current = kv.get(key);
        if (current === undefined || current === own) {
          kv.set(key, next!);
          return 'OK';
        }
        return 0;
      }
      return kv.get(key) === own ? Number(kv.delete(key)) : 0;
    },
  };
}
