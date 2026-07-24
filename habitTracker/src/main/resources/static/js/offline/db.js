// Minimal IndexedDB wrapper for the offline-sync feature. Two stores:
//   outbox — queued intents that haven't reached the server OR Drive yet (pure local queue)
//   meta   — cached Drive bridge bundle from /api/sync/status (see driveClient.js)
const OfflineDB = (() => {
  const DB_NAME = 'habittracker-offline';
  const DB_VERSION = 1;
  let dbPromise = null;

  function open() {
    if (dbPromise) return dbPromise;
    dbPromise = new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, DB_VERSION);
      req.onupgradeneeded = () => {
        const db = req.result;
        if (!db.objectStoreNames.contains('outbox')) {
          db.createObjectStore('outbox', { keyPath: 'requestId' });
        }
        if (!db.objectStoreNames.contains('meta')) {
          db.createObjectStore('meta', { keyPath: 'key' });
        }
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
    return dbPromise;
  }

  async function tx(storeName, mode, fn) {
    const db = await open();
    return new Promise((resolve, reject) => {
      const t = db.transaction(storeName, mode);
      const store = t.objectStore(storeName);
      const result = fn(store);
      t.oncomplete = () => resolve(result);
      t.onerror = () => reject(t.error);
    });
  }

  return {
    async enqueue(intent) {
      return tx('outbox', 'readwrite', (store) => store.put(intent));
    },
    async remove(requestId) {
      return tx('outbox', 'readwrite', (store) => store.delete(requestId));
    },
    async all() {
      const db = await open();
      return new Promise((resolve, reject) => {
        const t = db.transaction('outbox', 'readonly');
        const req = t.objectStore('outbox').getAll();
        req.onsuccess = () => resolve(req.result || []);
        req.onerror = () => reject(req.error);
      });
    },
    async count() {
      const all = await this.all();
      return all.length;
    },
    async setMeta(key, value) {
      return tx('meta', 'readwrite', (store) => store.put({ key, value }));
    },
    async getMeta(key) {
      const db = await open();
      return new Promise((resolve, reject) => {
        const t = db.transaction('meta', 'readonly');
        const req = t.objectStore('meta').get(key);
        req.onsuccess = () => resolve(req.result ? req.result.value : undefined);
        req.onerror = () => reject(req.error);
      });
    },
  };
})();
