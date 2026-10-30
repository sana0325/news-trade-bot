// Відкриті файли зберігаються на пристрої в IndexedDB.
const DB = 'office-docs';
const STORE = 'files';

function open() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB, 1);
    req.onupgradeneeded = () => req.result.createObjectStore(STORE, { keyPath: 'id' });
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function tx(mode, fn) {
  const db = await open();
  return new Promise((resolve, reject) => {
    const t = db.transaction(STORE, mode);
    const req = fn(t.objectStore(STORE));
    t.oncomplete = () => resolve(req?.result);
    t.onerror = () => reject(t.error);
  });
}

export const listFiles = () => tx('readonly', (s) => s.getAll());
export const saveFile = (rec) => tx('readwrite', (s) => s.put(rec));
export const deleteFile = (id) => tx('readwrite', (s) => s.delete(id));
