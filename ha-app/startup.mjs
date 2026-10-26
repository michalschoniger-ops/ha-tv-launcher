import { constants } from 'node:fs';
import { chmod, cp, link, lstat, mkdir, open, readFile, readdir, rename, rm, unlink } from 'node:fs/promises';
import { createHash, randomBytes } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const SOURCE = path.dirname(fileURLToPath(import.meta.url));
const LIMIT = 2 * 1024 * 1024;
const fail = () => new Error('HA TV Launcher: zkontrolujte soukromou konfiguraci aplikace.');
const record = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const hash = bytes => createHash('sha256').update(bytes).digest('hex');

export function connectionOptions(options, supervisorToken) {
  if (!record(options)) throw fail();
  let origin;
  try {
    const url = new URL(options.public_origin);
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.pathname !== '/' || url.search || url.hash) throw fail();
    origin = url.origin;
  } catch { throw fail(); }
  const connection = options.ha_connection || 'supervisor';
  let url, token, websocketUrl;
  if (connection === 'supervisor') {
    if (typeof supervisorToken !== 'string' || supervisorToken.length < 16) throw fail();
    url = 'http://supervisor/core/'; token = supervisorToken; websocketUrl = 'ws://supervisor/core/websocket';
  } else if (connection === 'direct') {
    try {
      const parsed = new URL(options.ha_url);
      if (!['http:', 'https:'].includes(parsed.protocol) || parsed.username || parsed.password || parsed.search || parsed.hash ||
          typeof options.ha_access_token !== 'string' || options.ha_access_token.length < 16) throw fail();
      url = parsed.href.endsWith('/') ? parsed.href : `${parsed.href}/`;
      const socket = new URL('api/websocket', url); socket.protocol = parsed.protocol === 'https:' ? 'wss:' : 'ws:';
      websocketUrl = socket.href; token = options.ha_access_token;
    } catch { throw fail(); }
  } else throw fail();
  for (const [key, min] of [['pairing_code', 8], ['webhook_token', 16]]) {
    const value = options[key];
    if (value !== undefined && (typeof value !== 'string' || value.length < min || value.length > 200 || /[\u0000-\u001f\u007f]/.test(value))) throw fail();
  }
  const days = options.session_days ?? 90, revision = options.config_import_revision ?? 0;
  if (!Number.isSafeInteger(days) || days < 1 || days > 90 || !Number.isSafeInteger(revision) || revision < 0 || revision > 1000000 ||
      (options.night_control_enabled !== undefined && typeof options.night_control_enabled !== 'boolean')) throw fail();
  return { origin, url, token, websocketUrl, days, revision, night: options.night_control_enabled === true };
}

async function privateDirectory(directory) {
  directory = path.resolve(directory);
  let current = path.parse(directory).root;
  for (const part of directory.slice(current.length).split(path.sep).filter(Boolean)) {
    current = path.join(current, part);
    if (current === directory) await mkdir(current, { mode: 0o700 }).catch(error => { if (error.code !== 'EEXIST') throw fail(); });
    const info = await lstat(current);
    if (!info.isDirectory() || info.isSymbolicLink()) throw fail();
  }
  const info = await lstat(directory);
  if (process.getuid && info.uid !== process.getuid()) throw fail();
  await chmod(directory, 0o700);
}

async function jsonFile(filename, { optional = false, privateFile = false } = {}) {
  let info;
  try { info = await lstat(filename); }
  catch (error) { if (optional && error.code === 'ENOENT') return null; throw fail(); }
  const valid = stat => stat.isFile() && !stat.isSymbolicLink() && stat.nlink === 1 && stat.size <= LIMIT &&
    (!process.getuid || stat.uid === process.getuid()) && (!privateFile || (stat.mode & 0o777) === 0o600);
  if (!valid(info)) throw fail();
  const handle = await open(filename, constants.O_RDONLY | constants.O_NOFOLLOW);
  try {
    const actual = await handle.stat();
    if (!valid(actual) || actual.dev !== info.dev || actual.ino !== info.ino) throw fail();
    const raw = await handle.readFile(); if (raw.length > LIMIT) throw fail();
    const value = JSON.parse(raw.toString('utf8')); if (!record(value)) throw fail();
    return { raw, value, sha256: hash(raw) };
  } catch { throw fail(); } finally { await handle.close(); }
}

function dashboardConfig(value) {
  if (!record(value)) throw fail();
  // Authentication belongs in private app options, never in dashboard JSON.
  const forbidden = /^(?:ha_token|ha_access_token|access_token|password|webhook_token|pairing_code)$/i;
  const inspect = item => {
    if (Array.isArray(item)) return item.forEach(inspect);
    if (record(item)) for (const [key, child] of Object.entries(item)) { if (forbidden.test(key)) throw fail(); inspect(child); }
  };
  inspect(value); return value;
}

async function syncDirectory(directory) {
  const handle = await open(directory, constants.O_RDONLY | constants.O_DIRECTORY | constants.O_NOFOLLOW);
  try { await handle.sync(); } finally { await handle.close(); }
}
async function temporaryFile(filename, bytes) {
  if (bytes.length > LIMIT) throw fail();
  const temporary = path.join(path.dirname(filename), `.${path.basename(filename)}.${randomBytes(12).toString('hex')}.tmp`);
  const handle = await open(temporary, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, 0o600);
  try { await handle.writeFile(bytes); await handle.sync(); }
  catch (error) { await unlink(temporary).catch(() => {}); throw error; }
  finally { await handle.close(); }
  return temporary;
}
async function recoverPublication(filename) {
  const info = await lstat(filename).catch(error => { if (error.code === 'ENOENT') return null; throw fail(); });
  if (!info || info.nlink === 1) return;
  if (!info.isFile() || info.isSymbolicLink() || info.nlink !== 2 || (info.mode & 0o777) !== 0o600 ||
      (process.getuid && info.uid !== process.getuid())) throw fail();
  const prefix = `.${path.basename(filename)}.`;
  for (const name of await readdir(path.dirname(filename))) {
    if (!name.startsWith(prefix) || !/^[a-f0-9]{24}\.tmp$/.test(name.slice(prefix.length))) continue;
    const temporary = path.join(path.dirname(filename), name), candidate = await lstat(temporary);
    if (!candidate.isSymbolicLink() && candidate.dev === info.dev && candidate.ino === info.ino) {
      await unlink(temporary); await syncDirectory(path.dirname(filename)); return;
    }
  }
  throw fail();
}
async function exclusiveFile(filename, bytes) {
  const temporary = await temporaryFile(filename, bytes);
  try { await link(temporary, filename).catch(error => { if (error.code !== 'EEXIST') throw error; }); }
  finally { await unlink(temporary); }
  await syncDirectory(path.dirname(filename));
}
async function replaceFile(filename, bytes, expectedHash) {
  const temporary = await temporaryFile(filename, bytes);
  try {
    const current = await jsonFile(filename, { optional: true, privateFile: true });
    if ((current?.sha256 ?? null) !== expectedHash) throw fail();
    await rename(temporary, filename); await syncDirectory(path.dirname(filename));
  } finally { await unlink(temporary).catch(error => { if (error.code !== 'ENOENT') throw error; }); }
  if ((await jsonFile(filename, { privateFile: true })).sha256 !== hash(bytes)) throw fail();
}

/** Default updates never replace personal config. Import is explicitly requested
 * by increasing config_import_revision and supplying /config/config.json.
 * Explicit revision imports are independent of the configured household.
 */
export async function configureDashboard({ source = SOURCE, data = '/data', configImport = '/config/config.json', revision = 0 } = {}) {
  if (!Number.isSafeInteger(revision) || revision < 0 || revision > 1000000) throw fail();
  await privateDirectory(data);
  const filename = path.join(data, 'config.json'), markerName = path.join(data, 'config-import.json');
  await recoverPublication(filename);
  let current = await jsonFile(filename, { optional: true, privateFile: true });
  let initialized = false;
  if (!current) {
    const initial = await jsonFile(path.join(source, 'config.example.json')); dashboardConfig(initial.value);
    await exclusiveFile(filename, initial.raw);
    current = await jsonFile(filename, { privateFile: true }); dashboardConfig(current.value); initialized = true;
  } else dashboardConfig(current.value);
  const marker = await jsonFile(markerName, { optional: true, privateFile: true });
  if (marker && (marker.value.formatVersion !== 1 || !Number.isSafeInteger(marker.value.revision) || marker.value.revision < 1 || marker.value.revision > 1000000 ||
      !/^[a-f0-9]{64}$/.test(marker.value.sourceSha256 || '') || !/^[a-f0-9]{64}$/.test(marker.value.previousSha256 || ''))) throw fail();
  if (!revision || revision <= (marker?.value.revision ?? 0)) return { status: initialized ? 'initialized' : 'preserved', revision: marker?.value.revision ?? 0 };
  // Validate all new input before publishing the private backup or changing config.
  const incoming = await jsonFile(configImport); dashboardConfig(incoming.value);
  const backupName = path.join(data, `config-before-import-${revision}.json`);
  await recoverPublication(backupName);
  let backup = await jsonFile(backupName, { optional: true, privateFile: true });
  if (!backup) {
    await exclusiveFile(backupName, current.raw); backup = await jsonFile(backupName, { privateFile: true });
    if (backup.sha256 !== current.sha256) throw fail();
  }
  current = await jsonFile(filename, { privateFile: true });
  if (![backup.sha256, incoming.sha256].includes(current.sha256)) throw fail();
  if (current.sha256 !== incoming.sha256) await replaceFile(filename, incoming.raw, backup.sha256);
  const proof = { formatVersion: 1, revision, sourceSha256: incoming.sha256, previousSha256: backup.sha256, importedAt: new Date().toISOString() };
  await replaceFile(markerName, Buffer.from(JSON.stringify(proof) + '\n'), marker?.sha256 ?? null);
  return { status: 'imported', revision };
}

export async function persistentSecret(filename, supplied) {
  if (supplied) return supplied;
  await recoverPublication(filename);
  try {
    const handle = await open(filename, constants.O_RDONLY | constants.O_NOFOLLOW);
    try {
      const info = await handle.stat();
      if (!info.isFile() || info.nlink !== 1 || info.size > 200 || (info.mode & 0o777) !== 0o600 || (process.getuid && info.uid !== process.getuid())) throw fail();
      const value = (await handle.readFile('utf8')).trim(); if (!/^[A-Za-z0-9_-]{32}$/.test(value)) throw fail(); return value;
    } finally { await handle.close(); }
  } catch (error) { if (error.code !== 'ENOENT') throw fail(); }
  const value = randomBytes(24).toString('base64url');
  await exclusiveFile(filename, Buffer.from(value));
  return persistentSecret(filename);
}

export async function start({ source = SOURCE, data = '/data', configImport = '/config/config.json', supervisorToken = process.env.SUPERVISOR_TOKEN } = {}) {
  const options = (await jsonFile(path.join(data, 'options.json'))).value;
  const connection = connectionOptions(options, supervisorToken);
  await configureDashboard({ source, data, configImport, revision: connection.revision });
  const root = path.join(data, 'runtime'); await privateDirectory(root); await privateDirectory(path.join(root, 'private'));
  for (const name of ['state', 'profile', 'photos']) await privateDirectory(path.join(root, 'private', name));
  // Only shipped code is refreshed. All state and user files stay in /data.
  for (const name of ['lib', 'public']) {
    const destination = path.join(root, name), info = await lstat(destination).catch(error => { if (error.code !== 'ENOENT') throw fail(); return null; });
    if (info?.isSymbolicLink()) throw fail();
    await rm(destination, { recursive: true, force: true }); await cp(path.join(source, name), destination, { recursive: true, dereference: false, errorOnExist: true });
  }
  const config = (await jsonFile(path.join(data, 'config.json'), { privateFile: true })).value;
  config.publicOrigin = connection.origin; config.allowedOrigins = [connection.origin];
  const env = {
    HOST: '0.0.0.0', PORT: '4317', HA_URL: connection.url, HA_TOKEN: connection.token, HA_WEBSOCKET_URL: connection.websocketUrl,
    TV_PUBLIC_ORIGIN: connection.origin,
    TV_CLIENT_TOKEN: await persistentSecret(path.join(root, 'private', 'state', 'client-token'), options.pairing_code),
    TV_WEBHOOK_TOKEN: await persistentSecret(path.join(root, 'private', 'state', 'webhook-token'), options.webhook_token),
    TV_SESSION_STORE: path.join(root, 'private', 'state', 'sessions.json'), TV_SESSION_DAYS: String(connection.days),
    PHOTO_DIR: path.join(root, 'private', 'photos'), NIGHT_CONTROL_ENABLED: connection.night ? '1' : '0', TV_CONTROL_ENABLED: '0',
  };
  const { createBridge } = await import(pathToFileURL(path.join(source, 'server.mjs')).href);
  const bridge = await createBridge({ root, config, env });
  await new Promise((resolve, reject) => { bridge.server.once('error', reject); bridge.server.listen(4317, '0.0.0.0', resolve); });
  console.log('HA TV Launcher: místní server naslouchá na portu 4317.');
  for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => { bridge.close().finally(() => process.exit(0)); });
  return bridge;
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  start().catch(() => { console.error('HA TV Launcher se nepodařilo spustit. Zkontrolujte volby aplikace a soukromou konfiguraci.'); process.exitCode = 1; });
}
