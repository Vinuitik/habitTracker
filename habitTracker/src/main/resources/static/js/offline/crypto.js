// WebCrypto AES-256-GCM — exact inverse of habitTracker.sync.VaultEncryptionService.
// Wire format: [12B IV][ciphertext + 16B GCM tag]. No compression, no KDF (the key is already
// a real random 256-bit key generated server-side at Drive-connect time, base64-encoded).
const OfflineCrypto = (() => {
  function base64ToBytes(b64) {
    const bin = atob(b64);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return bytes;
  }

  async function importKey(base64Key) {
    return crypto.subtle.importKey('raw', base64ToBytes(base64Key), 'AES-GCM', false, ['encrypt']);
  }

  async function encryptJson(base64Key, obj) {
    const key = await importKey(base64Key);
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const plaintext = new TextEncoder().encode(JSON.stringify(obj));
    const ciphertext = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, plaintext));
    const wire = new Uint8Array(iv.length + ciphertext.length);
    wire.set(iv, 0);
    wire.set(ciphertext, iv.length);
    return wire;
  }

  return { encryptJson };
})();
