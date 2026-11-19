import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';

/**
 * Web Storage shim for newer Node runtimes.
 *
 * Node 25+ ships a built-in, experimental `globalThis.localStorage` / `sessionStorage`. Unless Node is
 * started with `--localstorage-file`, those getters return `undefined` — and because the property
 * already exists on the global, Vitest's jsdom environment does not replace it with jsdom's working
 * Storage. Zustand's `persist` middleware then fails with
 * "Cannot read properties of undefined (reading 'setItem')".
 *
 * We only install the in-memory implementation when the runtime's storage is unusable, so on
 * Node 20/22 (where jsdom's Storage is used) behaviour is unchanged.
 */
class MemoryStorage implements Storage {
  private store = new Map<string, string>();

  get length(): number {
    return this.store.size;
  }

  clear(): void {
    this.store.clear();
  }

  getItem(key: string): string | null {
    return this.store.has(key) ? this.store.get(key)! : null;
  }

  key(index: number): string | null {
    return Array.from(this.store.keys())[index] ?? null;
  }

  removeItem(key: string): void {
    this.store.delete(key);
  }

  setItem(key: string, value: string): void {
    this.store.set(key, String(value));
  }
}

function isUsable(name: 'localStorage' | 'sessionStorage'): boolean {
  try {
    const storage = (globalThis as Record<string, unknown>)[name] as Storage | undefined;
    if (!storage || typeof storage.setItem !== 'function') return false;
    storage.setItem('__vitest_probe__', '1');
    storage.removeItem('__vitest_probe__');
    return true;
  } catch {
    return false;
  }
}

for (const name of ['localStorage', 'sessionStorage'] as const) {
  if (!isUsable(name)) {
    const storage = new MemoryStorage();
    Object.defineProperty(globalThis, name, { value: storage, configurable: true, writable: true });
    if (typeof window !== 'undefined' && window !== globalThis) {
      Object.defineProperty(window, name, { value: storage, configurable: true, writable: true });
    }
  }
}

// Keep tests isolated: persisted zustand state must not leak between tests.
afterEach(() => {
  globalThis.localStorage?.clear();
  globalThis.sessionStorage?.clear();
});
