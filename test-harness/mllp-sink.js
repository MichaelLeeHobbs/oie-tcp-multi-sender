#!/usr/bin/env node
/*
 * Controllable MLLP sink for deterministic failover testing of the Multi-Endpoint TCP Sender.
 *
 * Listens for MLLP (HL7 over MLLP: VT ... FS CR) on one or more data ports and exposes an HTTP control
 * API so a test can command each port's behavior and read what it received. Zero dependencies (Node core).
 *
 *   Env:
 *     DATA_PORTS    comma list of MLLP ports        (default "6661,6662")
 *     CONTROL_PORT  HTTP control/observe port       (default 9000)
 *
 *   Per-port modes (settable at runtime):
 *     ack   -> reply with an MLLP-framed AA acknowledgement (message accepted)   [default]
 *     nack  -> reply with an MLLP-framed AE acknowledgement (application reject)
 *     hang  -> accept + read the message but NEVER reply (simulates a lost ACK / response timeout)
 *   Plus up/down: a "down" port stops listening entirely -> the sender gets connection-refused.
 *
 *   Control API (JSON):
 *     GET  /state                      -> { ports: { "6661": {mode,up,count,last}, ... } }
 *     POST /control {port,mode?,up?}   -> update a port (mode and/or up)
 *     POST /reset                      -> counts=0, mode=ack, up=true for all ports
 */
'use strict';
const net = require('net');
const http = require('http');

const VT = 0x0b, FS = 0x1c, CR = 0x0d;
const DATA_PORTS = (process.env.DATA_PORTS || '6661,6662').split(',').map(s => parseInt(s.trim(), 10));
const CONTROL_PORT = parseInt(process.env.CONTROL_PORT || '9000', 10);

/** @type {Map<number, {mode:string, up:boolean, count:number, last:string[], server:net.Server|null}>} */
const ports = new Map();

function log(...a) { console.log(new Date().toISOString(), ...a); }

// Build an MLLP-framed HL7 ACK for a received message. code = "AA" (accept) or "AE" (error/reject).
function buildAck(inbound, code) {
  const firstSeg = inbound.split(/\r|\n/)[0] || '';
  const f = firstSeg.split('|');
  // Echo enough of MSH to look like a real ACK; MSA-2 carries the original control id (MSH-10) if present.
  const controlId = f[9] || 'MSGID';
  const sendingApp = f[4] || 'SINK';
  const msh = ['MSH', '^~\\&', 'SINK', 'SINK', sendingApp, f[3] || 'SENDER', '', '', 'ACK', controlId, 'P', '2.3'].join('|');
  const msa = ['MSA', code, controlId].join('|');
  const hl7 = msh + '\r' + msa + '\r';
  return Buffer.concat([Buffer.from([VT]), Buffer.from(hl7, 'utf8'), Buffer.from([FS, CR])]);
}

function startListener(port) {
  const st = ports.get(port);
  if (st.server) return;
  const server = net.createServer(sock => {
    let buf = Buffer.alloc(0);
    sock.on('data', chunk => {
      buf = Buffer.concat([buf, chunk]);
      // Extract complete MLLP frames: VT ... FS CR
      let start, end;
      while ((start = buf.indexOf(VT)) !== -1
          && (end = buf.indexOf(FS, start + 1)) !== -1
          && buf[end + 1] === CR) {
        const hl7 = buf.slice(start + 1, end).toString('utf8');
        buf = buf.slice(end + 2);
        const cur = ports.get(port);
        cur.count++;
        cur.last.push(hl7.split(/\r|\n/)[0] || hl7);
        if (cur.last.length > 20) cur.last.shift();
        log(`port ${port} [${cur.mode}] received msg #${cur.count}`);
        if (cur.mode === 'ack') sock.write(buildAck(hl7, 'AA'));
        else if (cur.mode === 'nack') sock.write(buildAck(hl7, 'AE'));
        // 'hang' -> intentionally no response.
      }
    });
    sock.on('error', () => {});
  });
  server.on('error', e => log(`port ${port} server error: ${e.message}`));
  server.listen(port, '0.0.0.0', () => log(`MLLP listening on ${port}`));
  st.server = server;
}

function stopListener(port) {
  const st = ports.get(port);
  if (st.server) { st.server.close(); st.server = null; log(`port ${port} DOWN (not listening)`); }
}

for (const p of DATA_PORTS) {
  ports.set(p, { mode: 'ack', up: true, count: 0, last: [], server: null });
  startListener(p);
}

// ---- control server ----
function readBody(req) {
  return new Promise(resolve => {
    let b = ''; req.on('data', c => (b += c)); req.on('end', () => { try { resolve(b ? JSON.parse(b) : {}); } catch { resolve({}); } });
  });
}
http.createServer(async (req, res) => {
  const send = (code, obj) => { res.writeHead(code, { 'content-type': 'application/json' }); res.end(JSON.stringify(obj)); };
  if (req.method === 'GET' && req.url === '/state') {
    const out = {};
    for (const [p, s] of ports) out[p] = { mode: s.mode, up: s.up, count: s.count, last: s.last };
    return send(200, { ports: out });
  }
  if (req.method === 'POST' && req.url === '/reset') {
    for (const [p, s] of ports) { s.count = 0; s.last = []; s.mode = 'ack'; if (!s.up) { s.up = true; startListener(p); } }
    return send(200, { ok: true });
  }
  if (req.method === 'POST' && req.url === '/control') {
    const body = await readBody(req);
    const p = parseInt(body.port, 10);
    const s = ports.get(p);
    if (!s) return send(404, { error: `unknown port ${body.port}` });
    if (typeof body.mode === 'string') {
      if (!['ack', 'nack', 'hang'].includes(body.mode)) return send(400, { error: `bad mode ${body.mode}` });
      s.mode = body.mode;
    }
    if (typeof body.up === 'boolean' && body.up !== s.up) {
      s.up = body.up;
      if (body.up) startListener(p); else stopListener(p);
    }
    return send(200, { port: p, mode: s.mode, up: s.up });
  }
  send(404, { error: 'not found' });
}).listen(CONTROL_PORT, '0.0.0.0', () => log(`control API on ${CONTROL_PORT}`));
