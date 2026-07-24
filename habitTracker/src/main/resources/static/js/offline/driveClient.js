// Talks to the user's own Google Drive directly from the browser. Deliberately never holds a
// Drive refresh token or the OAuth client secret — those stay server-side (habitTracker.sync).
// Instead it caches a short-lived "bridge" access token that the server mints and hands down
// on every successful /api/sync/status call (see SyncController.status()). This sidesteps the
// open question of whether a raw refresh_token grant works cross-origin from a browser at all —
// there is simply no token-endpoint call from the browser, ever.
//
// Consequence: direct-to-Drive pushes only work while the cached bridge token is still valid
// (usually ~1h after the last time the app successfully talked to the server). Once it expires
// with no server contact, writes fall back to the pure local queue (outbox.js) — safe, just
// delayed until the next time the app can reach either the server or a fresh bridge token.
const DriveClient = (() => {
  const FILES_URL = 'https://www.googleapis.com/drive/v3/files';
  const UPLOAD_URL = 'https://www.googleapis.com/upload/drive/v3/files';
  const EXPIRY_SAFETY_MARGIN_MS = 60 * 1000;

  async function deviceId() {
    let id = await OfflineDB.getMeta('deviceId');
    if (!id) {
      id = crypto.randomUUID();
      await OfflineDB.setMeta('deviceId', id);
    }
    return id;
  }

  // Called whenever the app is online and can reach the server — refreshes the cached bridge.
  async function refreshBridge() {
    try {
      const statusUrl = (window.ENV && ENV.ENDPOINTS.SYNC_STATUS) || '/api/sync/status';
      const resp = await fetch(statusUrl, { credentials: 'include' });
      if (!resp.ok) return null;
      const data = await resp.json();
      if (!data.connected) {
        await OfflineDB.setMeta('driveBridge', null);
        return null;
      }
      await OfflineDB.setMeta('driveBridge', data);
      return data;
    } catch (e) {
      return null; // server unreachable — keep whatever bridge is already cached
    }
  }

  async function getValidBridge() {
    const bridge = await OfflineDB.getMeta('driveBridge');
    if (!bridge || !bridge.driveAccessToken) return null;
    if (Date.now() > bridge.driveAccessTokenExpiresAt - EXPIRY_SAFETY_MARGIN_MS) return null;
    return bridge;
  }

  async function isAvailable() {
    return !!(await getValidBridge());
  }

  // Pushes one batch of queued intents as a single encrypted file into the user's own
  // "_mailbox_requests" Drive folder. Mirrors the server's own encryption format exactly.
  async function pushBatch(intents) {
    const bridge = await getValidBridge();
    if (!bridge) throw new Error('No valid Drive bridge token cached');

    const id = await deviceId();
    const batch = { deviceId: id, requests: intents };
    const wire = await OfflineCrypto.encryptJson(bridge.encryptionKey, batch);
    const filename = `${id}-${Date.now()}-${crypto.randomUUID()}.enc`;

    const created = await fetch(FILES_URL, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${bridge.driveAccessToken}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ name: filename, parents: [bridge.mailboxFolderId] }),
    }).then((r) => r.json());

    await fetch(`${UPLOAD_URL}/${created.id}?uploadType=media`, {
      method: 'PATCH',
      headers: {
        'Authorization': `Bearer ${bridge.driveAccessToken}`,
        'Content-Type': 'application/octet-stream',
      },
      body: wire,
    });

    return created.id;
  }

  return { refreshBridge, isAvailable, pushBatch };
})();
