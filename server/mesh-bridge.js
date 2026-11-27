'use strict';
/**
 * MeshNet bridge: the internet side of the mesh. Works as Express middleware or with plain `http`.
 *
 *   const meshBridge = require('./mesh-bridge');
 *   app.use('/api/mesh', meshBridge());            // Express
 *
 * What it does (and nothing more):
 *  - remembers who polled in the last 45 s and in which zone (presence, in RAM)
 *  - relays one packet to a node that is online right now (short RAM queue, 120 s, 200 packets)
 *  - never writes anything to MongoDB: a message for an offline user is refused (404) and stays
 *    queued on the sender's phone and in the mesh until the user shows up.
 * Packets are end-to-end encrypted by the app; the server only sees node ids, sizes and times.
 *
 * Wire format (binary, no JSON, no base64):
 *   POST /send?to=ID&from=ID   body = one core packet           -> 200 ok | 404 offline | 413 too big
 *   GET  /inbox/ID?wait=20&zone=Z   long poll                   -> 200 body = [len u16 BE][packet]...  (maybe empty)
 *   GET  /events/ID?zone=Z          SSE live packet stream      -> text/event-stream (event: packet, data: base64)
 *   POST /presence?node=ID&zone=Z                               -> 200
 *   GET  /where?ids=1,2,3 (or ?zone=Z)                          -> text lines "id online(0/1) zone"
 *   GET  /stats                                                 -> JSON zone-sharded telemetry
 * Optional auth: set MESH_BRIDGE_TOKEN; clients send it in the X-Mesh-Token header.
 */
module.exports = function createMeshBridge(options) {
  const opt = Object.assign({
    token: process.env.MESH_BRIDGE_TOKEN || '',
    onlineMs: 45000, queueTtlMs: 120000, queueMax: 200, maxPacket: 65535, maxWaitSec: 25,
  }, options || {});

  const presence = new Map();   // id -> { seen, zone }
  const queues = new Map();     // id -> [ { t, buf } ]
  const waiters = new Map();    // id -> { res, timer }
  const sseClients = new Map(); // id -> Set<res>
  let relayedCount = 0;

  function online(id) {
    const p = presence.get(id);
    return !!p && Date.now() - p.seen < opt.onlineMs;
  }
  function touch(id, zone) {
    const p = presence.get(id) || { seen: 0, zone: -1 };
    p.seen = Date.now();
    if (Number.isInteger(zone) && zone >= 0) p.zone = zone;
    presence.set(id, p);
  }
  function takeQueue(id) {
    const q = queues.get(id) || [];
    queues.delete(id);
    const now = Date.now();
    return q.filter((x) => now - x.t < opt.queueTtlMs).map((x) => x.buf);
  }
  function frame(list) {
    const parts = [];
    for (const b of list) {
      const h = Buffer.alloc(2);
      h.writeUInt16BE(b.length, 0);
      parts.push(h, b);
    }
    return Buffer.concat(parts);
  }
  function reply(res, code, body, type) {
    res.statusCode = code;
    res.setHeader('Content-Type', type || 'application/octet-stream');
    res.setHeader('Cache-Control', 'no-store');
    res.end(body === undefined ? '' : body);
  }
  function pushSse(id, buf) {
    const set = sseClients.get(id);
    if (!set || set.size === 0) return false;
    const line = 'event: packet\ndata: ' + buf.toString('base64') + '\n\n';
    for (const r of set) {
      try { r.write(line); } catch (e) { set.delete(r); }
    }
    return set.size > 0;
  }
  function release(id, list) {
    const w = waiters.get(id);
    if (!w) return false;
    clearTimeout(w.timer);
    waiters.delete(id);
    reply(w.res, 200, frame(list));
    return true;
  }
  function id32(v) {
    const n = Number(v);
    return Number.isInteger(n) && n > 0 && n <= 0x7fffffff ? n : null;
  }
  function readBody(req, limit, cb) {
    const chunks = [];
    let size = 0, dead = false;
    req.on('data', (c) => {
      if (dead) return;
      size += c.length;
      if (size > limit) { dead = true; cb(null); return; }
      chunks.push(c);
    });
    req.on('end', () => { if (!dead) cb(Buffer.concat(chunks)); });
    req.on('error', () => { if (!dead) { dead = true; cb(null); } });
  }

  setInterval(() => {
    const now = Date.now();
    for (const [id, p] of presence) if (now - p.seen > opt.onlineMs * 4) presence.delete(id);
    for (const [id, q] of queues) {
      const keep = q.filter((x) => now - x.t < opt.queueTtlMs);
      if (keep.length) queues.set(id, keep); else queues.delete(id);
    }
  }, 30000).unref();

  return function meshBridge(req, res, next) {
    let u;
    try { u = new URL(req.url, 'http://x'); } catch (e) { return reply(res, 400, 'bad url', 'text/plain'); }
    const path = u.pathname.replace(/\/+$/, '');
    const known = path === '/send' || path === '/presence' || path === '/where' || path === '/stats'
      || path.startsWith('/inbox/') || path.startsWith('/events/');
    if (!known) return next ? next() : reply(res, 404, 'not found', 'text/plain');
    if (opt.token && req.headers['x-mesh-token'] !== opt.token) return reply(res, 401, 'unauthorized', 'text/plain');

    if (req.method === 'GET' && path.startsWith('/inbox/')) {
      const id = id32(path.slice(7));
      if (!id) return reply(res, 400, 'bad id', 'text/plain');
      touch(id, parseInt(u.searchParams.get('zone'), 10));
      const waitSec = Math.max(0, Math.min(opt.maxWaitSec, parseInt(u.searchParams.get('wait'), 10) || 0));
      const old = waiters.get(id);
      if (old) release(id, []);                         // a newer poll replaces the older one
      const ready = takeQueue(id);
      if (ready.length || waitSec === 0) return reply(res, 200, frame(ready));
      const timer = setTimeout(() => { if (waiters.get(id) && waiters.get(id).res === res) release(id, []); }, waitSec * 1000);
      waiters.set(id, { res, timer });
      req.on('close', () => { const w = waiters.get(id); if (w && w.res === res) { clearTimeout(w.timer); waiters.delete(id); } });
      return undefined;
    }
    if (req.method === 'GET' && path.startsWith('/events/')) {
      const id = id32(path.slice(8));
      if (!id) return reply(res, 400, 'bad id', 'text/plain');
      touch(id, parseInt(u.searchParams.get('zone'), 10));
      res.statusCode = 200;
      res.setHeader('Content-Type', 'text/event-stream');
      res.setHeader('Cache-Control', 'no-cache');
      res.setHeader('Connection', 'keep-alive');
      res.write(': connected\n\n');
      let set = sseClients.get(id);
      if (!set) { set = new Set(); sseClients.set(id, set); }
      set.add(res);
      for (const pkt of takeQueue(id)) pushSse(id, pkt);
      const keepAlive = setInterval(() => { try { res.write(': ping\n\n'); touch(id, -1); } catch (e) {} }, 20000);
      req.on('close', () => {
        clearInterval(keepAlive);
        set.delete(res);
        if (set.size === 0) sseClients.delete(id);
      });
      return undefined;
    }
    if (req.method === 'POST' && path === '/send') {
      const to = id32(u.searchParams.get('to'));
      const from = id32(u.searchParams.get('from'));
      if (!to) return reply(res, 400, 'bad to', 'text/plain');
      readBody(req, opt.maxPacket, (buf) => {
        if (!buf) return reply(res, 413, 'too big', 'text/plain');
        if (from) touch(from);
        if (!online(to)) return reply(res, 404, 'offline', 'text/plain');
        relayedCount++;
        if (!release(to, [buf]) && !pushSse(to, buf)) {
          const q = queues.get(to) || [];
          if (q.length >= opt.queueMax) q.shift();
          q.push({ t: Date.now(), buf });
          queues.set(to, q);
        }
        return reply(res, 200, 'ok', 'text/plain');
      });
      return undefined;
    }
    if (req.method === 'POST' && path === '/presence') {
      const id = id32(u.searchParams.get('node'));
      if (!id) return reply(res, 400, 'bad node', 'text/plain');
      touch(id, parseInt(u.searchParams.get('zone'), 10));
      return reply(res, 200, 'ok', 'text/plain');
    }
    if (req.method === 'GET' && path === '/where') {
      const lines = [];
      const zoneFilter = u.searchParams.get('zone');
      if (zoneFilter !== null && zoneFilter !== '') {
        const z = parseInt(zoneFilter, 10);
        for (const [id, p] of presence) {
          if (p.zone === z && online(id)) {
            lines.push(id + ' 1 ' + p.zone);
            if (lines.length >= 200) break;
          }
        }
        return reply(res, 200, lines.join('\n'), 'text/plain');
      }
      for (const s of String(u.searchParams.get('ids') || '').split(',').slice(0, 200)) {
        const id = id32(s);
        if (!id) continue;
        const p = presence.get(id);
        lines.push(id + ' ' + (online(id) ? 1 : 0) + ' ' + (p ? p.zone : -1));
      }
      return reply(res, 200, lines.join('\n'), 'text/plain');
    }
    if (req.method === 'GET' && path === '/stats') {
      const zones = {};
      let onlineCount = 0;
      for (const [id, p] of presence) {
        if (!online(id)) continue;
        onlineCount++;
        const zk = String(p.zone);
        zones[zk] = (zones[zk] || 0) + 1;
      }
      let queuedPackets = 0;
      for (const q of queues.values()) queuedPackets += q.length;
      return reply(res, 200, JSON.stringify({
        onlineNodes: onlineCount,
        totalTrackedNodes: presence.size,
        activeWaiters: waiters.size,
        activeSse: sseClients.size,
        queuedPackets: queuedPackets,
        relayedPackets: relayedCount,
        zones: zones,
      }), 'application/json');
    }
    return reply(res, 405, 'method not allowed', 'text/plain');
  };
};
