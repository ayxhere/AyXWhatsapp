// WA Gateway - Node/Baileys engine (Phase 1.1)
// Link, send, always-online, auto-read, auto-reply, anti-delete (all msgs), message log.

const fs = require('fs')
const path = require('path')
const crypto = require('crypto')
let CryptoJS = null
try { CryptoJS = require('crypto-js') } catch (e) {}
const https = require('https')
const express = require('express')
const pino = require('pino')
const QRCode = require('qrcode')
const {
  default: makeWASocket,
  useMultiFileAuthState,
  fetchLatestBaileysVersion,
  DisconnectReason,
  Browsers,
  downloadMediaMessage,
  downloadContentFromMessage,
} = require('@whiskeysockets/baileys')

const PORT = parseInt(process.env.WAGW_PORT || '8765', 10)
const AUTH_DIR = process.env.WAGW_AUTH_DIR || './auth'
const SETTINGS_FILE = path.join(path.dirname(AUTH_DIR), 'settings.json')
const MEDIA_DIR = process.env.WAGW_MEDIA_DIR || path.join(path.dirname(AUTH_DIR), 'media')
const MESSAGES_FILE = path.join(path.dirname(AUTH_DIR), 'messages.json')
const NAMES_FILE = path.join(path.dirname(AUTH_DIR), 'names.json')
const STATUS_FILE = path.join(path.dirname(AUTH_DIR), 'statuses.json')
const REMINDER_FILE = path.join(path.dirname(AUTH_DIR), 'reminders.json')

const logger = pino({ level: 'warn' })

let sock = null
let state = null
let saveCreds = null
let currentQr = null
let pairingCode = null
let pairingNumber = null
let presenceTimer = null

let status = { connection: 'close', registered: false, me: null, lastError: null }

let settings = {
  alwaysOnline: false,
  autoRead: false,
  autoReplyEnabled: false,
  autoReplyRules: [],
  aiReplyEnabled: false,
  aiApiUrl: 'https://api.groq.com/openai/v1/chat/completions',
  aiApiKey: '',
  aiModel: 'openai/gpt-oss-20b',
  groupAiEnabled: false,
  aiSystemPrompt: '',
  saveMedia: false,
  hideStatusRead: true,
  stayOffline: false,
}

// stores for anti-delete + history (in-memory; reset on app restart)
const msgStore = new Map()  // id -> { chat, fromMe, text, ts }
const rawStore = new Map()  // id -> { key, message } for native quoted replies
let deletedList = []        // [{ chat, fromMe, text, ts }]
let msgLog = []             // [{ chat, fromMe, text, ts }]
const chatHistory = new Map()  // jid -> [{ role:'user'|'assistant', content }] for AI context
const dpCache = new Map()      // jid -> profile picture url (or null)
const contacts = new Map()     // jid -> { name, notify }
const presences = new Map()
const statusViewers = {}  // statusId -> Set of viewer jids    // jid -> { presence, lastSeen }
let statuses = []              // status@broadcast items (newest first)
try {
  const loaded = JSON.parse(fs.readFileSync(STATUS_FILE, 'utf8'))
  const cutoff = Date.now() - 24 * 3600 * 1000
  if (Array.isArray(loaded)) statuses = loaded.filter(x => x && x.ts && x.ts > cutoff)
} catch (_) { statuses = [] }
let _statusTimer = null
function saveStatusesDebounced() {
  if (_statusTimer) return
  _statusTimer = setTimeout(() => { _statusTimer = null; try { fs.writeFileSync(STATUS_FILE, JSON.stringify(statuses.slice(0, 120))) } catch (_) {} }, 1500)
}

// ===== presence reminder: watch chosen contacts, log online/offline, queue online alerts for the app =====
// A "watch" tracks ONE contact but may listen on several jid forms (the same person shows up as
// <number>@s.whatsapp.net AND <lid>@lid in modern WhatsApp, and presence.update can arrive on either).
// We subscribe to every jid of every watch, and match an incoming presence to a watch if ANY of its jids hit.
let watches = []                       // [{ id, jids:[] }]  id = the primary/display jid the app added
const presenceState = new Map()        // watch.id -> { online, since, lastSeen }
const presenceLog = new Map()          // watch.id -> [{ p:'online'|'offline', ts }]  (bounded to last 40)
let reminderOnlineQueue = []           // [{ jid, ts }] offline->online transitions, drained by the app for notifications
try {
  const r = JSON.parse(fs.readFileSync(REMINDER_FILE, 'utf8'))
  if (Array.isArray(r)) watches = r.filter(Boolean).map(j => ({ id: j, jids: [j] }))                 // legacy: bare jid array
  else if (r && Array.isArray(r.watches)) watches = r.watches.map(w => ({ id: w.id, jids: (w.jids && w.jids.length) ? w.jids : [w.id] }))
  else if (r && Array.isArray(r.jids)) watches = r.jids.filter(Boolean).map(j => ({ id: j, jids: [j] }))
  if (r && r.log) for (const k of Object.keys(r.log)) if (Array.isArray(r.log[k])) presenceLog.set(k, r.log[k])
} catch (_) {}
function allWatchJids() { const s = new Set(); for (const w of watches) for (const j of (w.jids || [])) if (j) s.add(j); return s }
function watchForJid(id) { return watches.find(w => (w.jids || []).includes(id)) }
let _remTimer = null
function saveReminders() {
  if (_remTimer) return
  _remTimer = setTimeout(() => {
    _remTimer = null
    try {
      const log = {}; for (const [k, v] of presenceLog) log[k] = (v || []).slice(-40)
      fs.writeFileSync(REMINDER_FILE, JSON.stringify({ watches, log }))
    } catch (_) {}
  }, 1200)
}
function isOnlinePresence(p) { return p === 'available' || p === 'composing' || p === 'recording' }
// record presence against a watch id (the primary jid), collapsing all its jid forms into one timeline
function recordPresence(id, presence, lastSeen) {
  const online = isOnlinePresence(presence)
  const prev = presenceState.get(id)
  const now = Date.now()
  if (!prev || prev.online !== online) {
    const arr = presenceLog.get(id) || []
    arr.push({ p: online ? 'online' : 'offline', ts: now })
    while (arr.length > 40) arr.shift()
    presenceLog.set(id, arr)
    presenceState.set(id, { online, since: now, lastSeen: lastSeen || (prev ? prev.lastSeen : null) })
    if (online && prev && !prev.online) reminderOnlineQueue.push({ jid: id, ts: now })  // came online (skip very first sighting)
    saveReminders()
  } else if (lastSeen && lastSeen !== prev.lastSeen) {
    presenceState.set(id, { ...prev, lastSeen })
  }
}
function subscribeTracked() {
  if (!sock) return
  for (const jid of allWatchJids()) { sock.presenceSubscribe(jid).catch(() => {}) }
}
// active loop: keep subscriptions fresh AND sweep the global `presences` map (filled by presence.update
// for whatever jid WhatsApp actually used) into each watch's timeline. This is robust to @lid vs @s.whatsapp.net.
//
// KEY: WhatsApp only PUSHES contacts' presence to a client it considers ACTIVE. A passive linked session
// (connected but never sending its own presence) gets nothing in the background — which is why real
// "last seen tracker" apps keep an active WhatsApp Web client open. So while we have watches we send our
// own presence as 'available' (unless the user froze last seen), then subscribe, then read what came back.
let reminderSweepTimer = null
function startReminderSweep() {
  if (reminderSweepTimer) return
  reminderSweepTimer = setInterval(() => {
    if (!sock || status.connection !== 'open') return
    if (watches.length === 0) return
    if (!settings.stayOffline) { try { sock.sendPresenceUpdate('available') } catch (_) {} }  // become an active client so presence is pushed
    subscribeTracked()
    for (const w of watches) {
      let best = null
      for (const j of (w.jids || [])) { const p = presences.get(j); if (p) best = p }
      if (best) recordPresence(w.id, best.presence, best.lastSeen)
    }
  }, 5000)
}

function pushHistory(jid, role, content) {
  if (!jid || !content) return
  let arr = chatHistory.get(jid) || []
  arr.push({ role, content })
  if (arr.length > 20) arr = arr.slice(-20)  // keep last 20 messages
  chatHistory.set(jid, arr)
}

function log(...a) { console.log('[wagw]', ...a) }
const delay = (ms) => new Promise(r => setTimeout(r, ms))

function loadSettings() {
  try { settings = Object.assign(settings, JSON.parse(fs.readFileSync(SETTINGS_FILE, 'utf8'))) } catch (_) {}
}
function saveSettings() {
  try {
    fs.mkdirSync(path.dirname(SETTINGS_FILE), { recursive: true })
    fs.writeFileSync(SETTINGS_FILE, JSON.stringify(settings, null, 2))
  } catch (e) { log('saveSettings err', e?.message) }
}

let saveMsgTimer = null
let nameStore = {}
try { nameStore = JSON.parse(fs.readFileSync(NAMES_FILE, 'utf8')) } catch (_) { nameStore = {} }
let _namesTimer = null
function saveNamesDebounced() {
  if (_namesTimer) return
  _namesTimer = setTimeout(() => { _namesTimer = null; try { fs.writeFileSync(NAMES_FILE, JSON.stringify(nameStore)) } catch (_) {} }, 1500)
}
function rememberName(jid, name) {
  if (jid && name && String(name).trim() && nameStore[jid] !== String(name).trim()) { nameStore[jid] = String(name).trim(); saveNamesDebounced() }
}

function saveMessagesDebounced() {
  if (saveMsgTimer) return
  saveMsgTimer = setTimeout(() => {
    saveMsgTimer = null
    try { fs.writeFileSync(MESSAGES_FILE, JSON.stringify(msgLog.slice(0, 500))) }
    catch (e) { log('saveMsg err', e?.message) }
  }, 2000)
}
function loadMessages() {
  try {
    const arr = JSON.parse(fs.readFileSync(MESSAGES_FILE, 'utf8'))
    if (Array.isArray(arr)) {
      msgLog = arr
      for (const e of arr) if (e && e.id) msgStore.set(e.id, e)
      log('loaded', arr.length, 'messages from disk')
    }
  } catch (_) {}
}

async function loadAuth() {
  const auth = await useMultiFileAuthState(AUTH_DIR)
  state = auth.state
  saveCreds = auth.saveCreds
  status.registered = !!state.creds.registered
  log('auth loaded from', AUTH_DIR, 'registered=', status.registered)
}

// last-resort: dig through nested message objects for any readable text field
function deepText(obj, depth) {
  if (!obj || typeof obj !== 'object' || depth > 6) return ''
  const keys = ['conversation', 'text', 'caption', 'hydratedContentText', 'contentText', 'selectedDisplayText', 'displayText', 'title', 'description']
  for (const k of keys) if (typeof obj[k] === 'string' && obj[k].trim()) return obj[k].trim()
  for (const k of Object.keys(obj)) {
    if (k === 'contextInfo' || k === 'key' || k === 'jpegThumbnail') continue
    const v = obj[k]
    if (v && typeof v === 'object') { const r = deepText(v, depth + 1); if (r) return r }
  }
  return ''
}

// CTA / quick-reply / url button labels from business (interactive/template/buttons) messages
function buttonLines(m) {
  const out = []
  try {
    const nf = m.interactiveMessage?.nativeFlowMessage?.buttons
    if (Array.isArray(nf)) for (const b of nf) {
      try { const p = JSON.parse(b.buttonParamsJson || '{}'); if (p.display_text) out.push('🔗 ' + p.display_text + (p.url ? ' → ' + p.url : '')) }
      catch (_) { if (b.name) out.push('🔗 ' + b.name) }
    }
    const hb = m.templateMessage?.hydratedTemplate?.hydratedButtons || m.templateMessage?.hydratedFourRowTemplate?.hydratedButtons
    if (Array.isArray(hb)) for (const b of hb) {
      if (b.urlButton) out.push('🔗 ' + (b.urlButton.displayText || 'Open') + (b.urlButton.url ? ' → ' + b.urlButton.url : ''))
      else if (b.callButton) out.push('📞 ' + (b.callButton.displayText || b.callButton.phoneNumber || ''))
      else if (b.quickReplyButton) out.push('• ' + (b.quickReplyButton.displayText || ''))
    }
    const bb = m.buttonsMessage?.buttons
    if (Array.isArray(bb)) for (const b of bb) { const t = b.buttonText?.displayText; if (t) out.push('• ' + t) }
  } catch (_) {}
  return out
}

function extractText(m) {
  if (!m) return ''
  let base = m.conversation
    || m.extendedTextMessage?.text
    || m.imageMessage?.caption
    || m.videoMessage?.caption
    || m.documentMessage?.caption
    || m.buttonsMessage?.contentText
    || m.buttonsMessage?.headerText
    || m.templateMessage?.hydratedTemplate?.hydratedContentText
    || m.templateMessage?.hydratedFourRowTemplate?.hydratedContentText
    || m.templateMessage?.fourRowTemplate?.content?.text
    || m.interactiveMessage?.body?.text
    || m.interactiveMessage?.header?.title
    || m.interactiveResponseMessage?.body?.text
    || m.listMessage?.description
    || m.listMessage?.title
    || m.productMessage?.product?.title
    || m.productMessage?.body?.text
    || m.buttonsResponseMessage?.selectedDisplayText
    || m.templateButtonReplyMessage?.selectedDisplayText
    || m.listResponseMessage?.title
    || m.contactMessage?.displayName
    || m.locationMessage?.name
    || m.pollCreationMessage?.name
    || m.eventMessage?.name
    || ''
  // business/interactive messages: body may be nested deeper — dig for it
  if (!base) base = deepText(m, 0)
  const footer = m.interactiveMessage?.footer?.text
    || m.templateMessage?.hydratedTemplate?.hydratedFooterText
    || m.templateMessage?.hydratedFourRowTemplate?.hydratedFooterText
    || m.buttonsMessage?.footerText || ''
  const btns = buttonLines(m)
  let full = base
  if (footer && footer.trim() && footer.trim() !== base) full += (full ? '\n' : '') + footer.trim()
  if (btns.length) full += (full ? '\n' : '') + btns.join('\n')
  return full
}

function resolveName(msg, jid) {
  const c = contacts.get(jid)
  let n = msg.pushName || msg.verifiedBizName || (c && c.name) || nameStore[jid] || ''
  if (!n && jid && jid.endsWith('@lid') && sock) {
    try {
      const lm = sock.signalRepository && sock.signalRepository.lidMapping
      const pn = lm && lm.getPNForLID && lm.getPNForLID(jid)
      if (pn) { const c2 = contacts.get(pn); n = (c2 && c2.name) || nameStore[pn] || ''; if (n) { rememberName(jid, n) } }
    } catch (_) {}
  }
  if (n && !(msg.key && msg.key.fromMe)) rememberName(jid, n)
  return n
}

function matchReply(text) {
  const t = (text || '').toLowerCase().trim()
  for (const r of settings.autoReplyRules) {
    const m = (r.match || '').toLowerCase().trim()
    if (!m) continue
    const mode = r.mode || 'contains'
    if (mode === 'exact' && t === m) return r.reply
    if (mode === 'starts' && t.startsWith(m)) return r.reply
    if (mode === 'contains' && t.includes(m)) return r.reply
  }
  return null
}

// OpenAI-compatible chat completion (Groq / OpenRouter / etc.)
async function aiReply(jid) {
  if (!settings.aiReplyEnabled || !settings.aiApiUrl || !settings.aiApiKey) return null
  const history = chatHistory.get(jid) || []
  try {
    const res = await fetch(settings.aiApiUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + settings.aiApiKey },
      body: JSON.stringify({
        model: settings.aiModel || 'openai/gpt-oss-20b',
        messages: [
          ...(settings.aiSystemPrompt ? [{ role: 'system', content: settings.aiSystemPrompt }] : []),
          ...history,
        ],
      }),
    })
    if (!res.ok) { log('ai http', res.status, (await res.text()).slice(0, 200)); return null }
    const data = await res.json()
    const out = data?.choices?.[0]?.message?.content
    return out ? String(out).trim() : null
  } catch (e) { log('ai err', e?.message); return null }
}

const groupDaily = new Map()  // groupJid -> { date, count }
function _today() { return new Date().toISOString().slice(0, 10) }
function groupDailyCount(jid) { const e = groupDaily.get(jid); return (!e || e.date !== _today()) ? 0 : e.count }
function incGroupDaily(jid) { const d = _today(); const e = groupDaily.get(jid); if (!e || e.date !== d) groupDaily.set(jid, { date: d, count: 1 }); else e.count++ }

async function groupAiReply(jid) {
  if (!settings.aiApiUrl || !settings.aiApiKey) return null
  const history = (chatHistory.get(jid) || []).slice(-9)
  const sys = 'You are a friendly, witty member of a WhatsApp group chat (not a formal assistant). Reply briefly and naturally like a real person would, in 1-2 short sentences. CRITICAL: read the recent messages and detect the language and script the group is using — Hindi (Devanagari), Hinglish (Roman Hindi), Bengali (Bangla script), English, etc. — and reply in that SAME language and script. Never say you are an AI or a bot. Be relevant to what was just said, warm and casual.'
  try {
    const res = await fetch(settings.aiApiUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + settings.aiApiKey },
      body: JSON.stringify({ model: settings.aiModel || 'openai/gpt-oss-20b', messages: [{ role: 'system', content: sys }, ...history] }),
    })
    if (!res.ok) { log('group ai http', res.status, (await res.text()).slice(0, 150)); return null }
    const data = await res.json()
    const out = data?.choices?.[0]?.message?.content
    return out ? String(out).trim() : null
  } catch (e) { log('group ai err', e?.message); return null }
}

function remember(id, entry) {
  if (id) {
    entry.id = id
    msgStore.set(id, entry)
    if (msgStore.size > 1500) { const k = msgStore.keys().next().value; msgStore.delete(k) }
  }
  msgLog.unshift(entry)
  if (msgLog.length > 500) msgLog = msgLog.slice(0, 500)
  saveMessagesDebounced()
}

function applyPresence() {
  if (presenceTimer) { clearInterval(presenceTimer); presenceTimer = null }
  if (!sock || status.connection !== 'open') return
  if (settings.stayOffline) {
    // Never broadcast "online" — keeps the account appearing offline (freeze last seen).
    const tick = () => { try { sock.sendPresenceUpdate('unavailable') } catch (_) {} }
    tick()
    presenceTimer = setInterval(tick, 10000)
  } else if (settings.alwaysOnline) {
    const tick = () => { try { sock.sendPresenceUpdate('available') } catch (_) {} }
    tick()
    presenceTimer = setInterval(tick, 10000)
  }
}

function handleConnUpdate(u) {
  const { connection, lastDisconnect, qr } = u
  if (qr) { currentQr = qr; log('qr updated') }
  if (connection) status.connection = connection

  if (connection === 'open') {
    status.registered = true
    status.me = sock?.user?.id || null
    status.lastError = null
    currentQr = null; pairingCode = null; pairingNumber = null
    log('CONNECTED as', status.me)
    applyPresence()
    if (watches.length && !settings.stayOffline) { try { sock.sendPresenceUpdate('available') } catch (_) {} }
    subscribeTracked()          // re-arm presence subscriptions for tracked contacts after (re)connect
    startReminderSweep()
  }

  if (connection === 'close') {
    const code = lastDisconnect?.error?.output?.statusCode
    status.lastError = code || (lastDisconnect?.error?.message ?? 'closed')
    const alreadyLinked = !!state?.creds?.registered
    log('closed. code=', code, 'alreadyLinked=', alreadyLinked)

    if (code === DisconnectReason.loggedOut) {
      currentQr = null; pairingCode = null; pairingNumber = null
      if (alreadyLinked) {
        status.registered = false
      } else {
        log('401 while unlinked -> wiping auth, restarting clean')
        try { fs.rmSync(AUTH_DIR, { recursive: true, force: true }) } catch (_) {}
        state = null; sock = null
        setTimeout(async () => { await loadAuth(); startSocket().catch(e => log('fresh start err', e?.message)) }, 5000)
      }
    } else {
      const wait = alreadyLinked ? 2000 : 8000
      setTimeout(() => startSocket().catch(e => log('reconnect err', e?.message)), wait)
    }
  }
}

function applyEdit(origId, contentMsg, ts) {
  if (!origId || !contentMsg) return
  let c = contentMsg
  if (c.editedMessage && c.editedMessage.message) c = c.editedMessage.message
  else if (c.message) c = c.message
  const newText = extractText(c)
  if (!newText) return
  const e = msgStore.get(origId)   // same object also lives in msgLog -> updates in place
  if (e) {
    e.text = newText
    e.edited = true
    if (ts) e.editedTs = ts
    saveMessagesDebounced()
    log('edited msg', origId)
  }
}

function captureDelete(delId) {
  const orig = delId ? msgStore.get(delId) : null
  if (orig && !orig.deleted) {
    orig.deleted = true // same object lives in msgLog too -> marked in place, no duplicate
    saveMessagesDebounced()
    log('marked deleted:', JSON.stringify(orig.text))
  }
}

function pickMedia(node) {
  if (!node) return null
  if (node.imageMessage) return ['image', node.imageMessage, 'jpg']
  if (node.videoMessage) return ['video', node.videoMessage, 'mp4']
  if (node.audioMessage) return ['audio', node.audioMessage, 'ogg']
  if (node.stickerMessage) return ['sticker', node.stickerMessage, 'webp']
  if (node.documentMessage) {
    const fn = node.documentMessage.fileName || ''
    const ext = fn.includes('.') ? fn.split('.').pop() : 'bin'
    return ['document', node.documentMessage, ext]
  }
  return null
}

function mediaKind(m) {
  const direct = pickMedia(m)
  if (direct) return direct
  // business/interactive/template/buttons header media is nested
  const cands = [
    m.interactiveMessage?.header,
    m.templateMessage?.hydratedTemplate,
    m.templateMessage?.hydratedFourRowTemplate,
    m.templateMessage?.fourRowTemplate,
    m.buttonsMessage,
  ]
  for (const c of cands) { const r = pickMedia(c); if (r) return r }
  if (m.productMessage?.product?.productImage) return ['image', m.productMessage.product.productImage, 'jpg']
  return null
}

function quotedOf(message) {
  if (!message) return null
  for (const k of Object.keys(message)) {
    const ci = message[k] && message[k].contextInfo
    if (ci && ci.quotedMessage) return { text: extractText(ci.quotedMessage) || '[media]', sender: ci.participant || '' }
  }
  return null
}

function unwrapInner(message) {
  let m = message
  for (let i = 0; i < 5 && m; i++) {
    if (m.ephemeralMessage?.message) { m = m.ephemeralMessage.message; continue }
    if (m.viewOnceMessage?.message) { m = m.viewOnceMessage.message; continue }
    if (m.viewOnceMessageV2?.message) { m = m.viewOnceMessageV2.message; continue }
    if (m.viewOnceMessageV2Extension?.message) { m = m.viewOnceMessageV2Extension.message; continue }
    if (m.deviceSentMessage?.message) { m = m.deviceSentMessage.message; continue }
    if (m.documentWithCaptionMessage?.message) { m = m.documentWithCaptionMessage.message; continue }
    break
  }
  if (m?.imageMessage) m.imageMessage.viewOnce = false
  if (m?.videoMessage) m.videoMessage.viewOnce = false
  return m
}

async function enrichMedia(msg, entry) {
  const kind = mediaKind(msg.message)
  if (!kind) return
  const [type, node, ext] = kind
  const name = (msg.key.id || Date.now()) + '.' + ext
  const thumb = node.jpegThumbnail ? Buffer.from(node.jpegThumbnail).toString('base64') : null
  const caption = node.caption || node.fileName || ''
  entry.media = { name, type, thumb, saved: false }
  if (!entry.text) entry.text = caption
  if (settings.saveMedia || type === 'image' || type === 'video' || type === 'sticker') {
    try {
      // top-level media downloads via downloadMediaMessage; nested business-header
      // media (interactive/template/buttons) must download the node directly.
      const topLevel = !!pickMedia(msg.message)
      let buf
      if (topLevel) {
        buf = await downloadMediaMessage(msg, 'buffer', {}, { logger, reuploadRequest: sock.updateMediaMessage })
      } else {
        const stream = await downloadContentFromMessage(node, type)
        const chunks = []
        for await (const ch of stream) chunks.push(ch)
        buf = Buffer.concat(chunks)
      }
      fs.mkdirSync(MEDIA_DIR, { recursive: true })
      fs.writeFileSync(path.join(MEDIA_DIR, name), buf)
      entry.media.saved = true
      pruneMediaDir()
      log('media saved', name, type)
    } catch (e) { log('media dl err', e?.message) }
  }
}

// keep the media cache from growing forever: cap total size, delete oldest first,
// but NEVER touch files newer than 25h so live statuses (24h) and recent chats survive
const MEDIA_CAP_BYTES = 300 * 1024 * 1024
const MEDIA_PROTECT_MS = 25 * 3600 * 1000
function pruneMediaDir() {
  try {
    const files = fs.readdirSync(MEDIA_DIR).map(f => {
      const p = path.join(MEDIA_DIR, f)
      try { const st = fs.statSync(p); return { p, size: st.size, mt: st.mtimeMs } } catch (_) { return null }
    }).filter(Boolean)
    let total = files.reduce((a, b) => a + b.size, 0)
    if (total <= MEDIA_CAP_BYTES) return
    const now = Date.now()
    files.sort((a, b) => a.mt - b.mt)
    for (const f of files) {
      if (total <= MEDIA_CAP_BYTES) break
      if (now - f.mt < MEDIA_PROTECT_MS) continue   // keep recent (live statuses, recent media)
      try { fs.unlinkSync(f.p); total -= f.size } catch (_) {}
    }
    log('media pruned to', Math.round(total / 1048576) + 'MB')
  } catch (_) {}
}

// a message is "forwarded" if any node carries a forwarding score / isForwarded flag
function isForwarded(message) {
  try {
    const m = message || {}
    for (const k of Object.keys(m)) {
      const ci = m[k] && m[k].contextInfo
      if (ci && (ci.isForwarded === true || (typeof ci.forwardingScore === 'number' && ci.forwardingScore > 0))) return true
    }
  } catch (_) {}
  return false
}

async function handleMessages({ messages, type }) {
  for (const msg of messages || []) {
    try {
      if (!msg.message) continue
      msg.message = unwrapInner(msg.message) || msg.message
      const from = msg.key.remoteJid
      const fromMe = !!msg.key.fromMe
      const id = msg.key.id
      const sender = msg.key.participant || '' // group: who sent it

      // standalone reaction arrives via messages.reaction; don't show as a message
      if (msg.message.reactionMessage) continue
      // delete-for-everyone -> protocol REVOKE
      const proto = msg.message.protocolMessage
      if (proto && (proto.type === 0 || proto.type === 'REVOKE')) {
        captureDelete(proto.key?.id)
        continue
      }
      if (proto && (proto.type === 14 || proto.type === 'MESSAGE_EDIT') && proto.editedMessage) {
        const eTs = msg.messageTimestamp ? Number(msg.messageTimestamp) * 1000 : Date.now()
        applyEdit(proto.key?.id, proto.editedMessage, eTs)
        continue
      }
      // some clients deliver an incoming edit wrapped in editedMessage (no top-level protocolMessage)
      if (!proto && msg.message.editedMessage) {
        const inner = msg.message.editedMessage.message || msg.message.editedMessage
        const ep = inner && inner.protocolMessage
        const eTs = msg.messageTimestamp ? Number(msg.messageTimestamp) * 1000 : Date.now()
        if (ep && ep.editedMessage) applyEdit(ep.key?.id || id, ep.editedMessage, eTs)
        else if (inner) applyEdit(id, inner, eTs)
        continue   // never render the edit wrapper as a new (empty) bubble
      }

      if (!from) continue
      if (from === 'status@broadcast') {
        const sndr = fromMe ? ((sock && sock.user && sock.user.id) || 'me') : (msg.key.participant || msg.participant)
        if (sndr) {
          const sTs = msg.messageTimestamp ? Number(msg.messageTimestamp) * 1000 : Date.now()
          const sEntry = { sender: sndr, name: fromMe ? 'My Status' : (msg.pushName || ''), mine: fromMe, id: msg.key.id, text: extractText(msg.message), ts: sTs }
          try { await enrichMedia(msg, sEntry) } catch (_) {}
          // keep raw so status media can be re-fetched on demand if the cache file is gone
          if (msg.key.id) { rawStore.set(msg.key.id, { key: msg.key, message: msg.message }); if (rawStore.size > 500) { const rk = rawStore.keys().next().value; rawStore.delete(rk) } }
          statuses.unshift(sEntry)
          if (statuses.length > 120) statuses.length = 120
          saveStatusesDebounced()
        }
        continue
      }
      const ts = msg.messageTimestamp ? Number(msg.messageTimestamp) * 1000 : Date.now()
      const entry = { chat: from, name: resolveName(msg, from), sender, fromMe, text: extractText(msg.message), ts, id }
      if (fromMe) entry.status = (typeof msg.status === 'number' ? msg.status : 1)  // 1 pending,2 sent,3 delivered,4 read
      if (isForwarded(msg.message)) entry.forwarded = true
      entry.quoted = quotedOf(msg.message)
      await enrichMedia(msg, entry)
      const text = entry.text

      // safety net: never show blank incoming bubbles (stray edit/protocol/system messages)
      if (!fromMe && !entry.text && !entry.media) continue

      // store EVERY message (both directions) for anti-delete + history
      remember(id, entry)
      if (id) { rawStore.set(id, { key: msg.key, message: msg.message }); if (rawStore.size > 400) { const rk = rawStore.keys().next().value; rawStore.delete(rk) } }

      // auto-read + auto-reply: only incoming personal chats
      const isPersonal = from.endsWith('@s.whatsapp.net') || from.endsWith('@lid')
      if (!fromMe && isPersonal) {
        if (settings.autoRead) {
          try { await sock.readMessages([msg.key]); log('auto-read ok') }
          catch (e) { log('auto-read err', e?.message) }
        }
        if (text) {
          pushHistory(from, 'user', text)
          let reply = null
          if (settings.autoReplyEnabled) reply = matchReply(text)
          if (!reply && settings.aiReplyEnabled) reply = await aiReply(from)
          if (reply) {
            try {
              await sock.sendMessage(from, { text: reply })
              pushHistory(from, 'assistant', reply)
              log('auto-reply sent:', reply)
            } catch (e) { log('auto-reply err', e?.message) }
          }
        }
      }

      // group AI: /ai command (always) or auto-reply on greeting/question (max 10/day/group)
      const isGroup = from.endsWith('@g.us')
      if (!fromMe && isGroup && text) {
        const t = text.trim()
        const lower = t.toLowerCase()
        let explicit = false, auto = false
        if (lower.startsWith('/ai')) {
          const qq = t.replace(/^\/ai\s*/i, '').trim()
          pushHistory(from, 'user', qq || t)
          if (qq) explicit = true
        } else {
          pushHistory(from, 'user', t)
          if (settings.groupAiEnabled && groupDailyCount(from) < 10) {
            const isGreeting = /^(hi+|he+y+|he+llo+|helo|namaste|namaskar|hola|salaam|assalam|yo|sup)\b/i.test(t)
            const isQuestion = t.includes('?') || /^(what|why|how|when|who|where|which|kya|kaise|kyu|kyun|kaun|kab|kahan|kitna|ki|ke|bolo|batao)\b/i.test(t)
            if (isGreeting || isQuestion) auto = true
          }
        }
        if (explicit || auto) {
          try { await sock.readMessages([msg.key]) } catch (_) {}
          const reply = await groupAiReply(from)
          if (reply) {
            try { await sock.sendMessage(from, { text: reply }); pushHistory(from, 'assistant', reply); if (auto) incGroupDaily(from); log('group ai sent (' + (explicit ? 'cmd' : 'auto ' + groupDailyCount(from) + '/10') + ')') }
            catch (e) { log('group ai send err', e?.message) }
          }
        }
      }
    } catch (e) { log('handleMessages err', e?.message) }
  }
}

// also detect deletions that arrive as an update instead of a new message
function handleUpdates(updates) {
  for (const u of updates || []) {
    try {
      const upd = u.update || {}
      const proto = upd.message?.protocolMessage
      if ((proto && (proto.type === 0 || proto.type === 'REVOKE'))) {
        captureDelete(proto.key?.id || u.key?.id)
      } else if (proto && (proto.type === 14 || proto.type === 'MESSAGE_EDIT') && proto.editedMessage) {
        applyEdit(proto.key?.id || u.key?.id, proto.editedMessage, Date.now())
      } else if (upd.message?.editedMessage) {
        applyEdit(u.key?.id, upd.message.editedMessage, Date.now())
      } else if (upd.messageStubType === 1 /* REVOKE stub */) {
        captureDelete(u.key?.id)
      }
      // delivery/read receipts for messages we sent (2 sent, 3 delivered, 4 read, 5 played)
      if (typeof upd.status === 'number' && u.key?.id) {
        const e = msgStore.get(u.key.id)
        if (e && e.fromMe) {
          const s = upd.status | 0
          if (s > (e.status | 0)) { e.status = s; saveMessagesDebounced() }
        }
      }
    } catch (_) {}
  }
}

// history sync on link -> fill the message log
async function handleHistory({ messages, contacts: cts }) {
  if (cts) for (const c of cts) { if (c.id) { const nm = c.name || c.notify || ''; contacts.set(c.id, { name: nm, notify: c.notify || '' }); rememberName(c.id, nm) } }
  let n = 0
  for (const msg of messages || []) {
    try {
      if (!msg.message) continue
      msg.message = unwrapInner(msg.message) || msg.message
      const from = msg.key.remoteJid
      if (!from) continue
      if (from === 'status@broadcast') {
        const sndr2 = msg.key.fromMe ? ((sock && sock.user && sock.user.id) || 'me') : (msg.key.participant || msg.participant)
        if (sndr2) {
          const sTs2 = msg.messageTimestamp ? Number(msg.messageTimestamp) * 1000 : Date.now()
          const sE = { sender: sndr2, name: msg.key.fromMe ? 'My Status' : (msg.pushName || ''), mine: !!msg.key.fromMe, id: msg.key.id, text: extractText(msg.message), ts: sTs2 }
          try { await enrichMedia(msg, sE) } catch (_) {}
          if (!statuses.find(x => x.sender === sE.sender && x.ts === sE.ts)) { statuses.unshift(sE); if (statuses.length > 120) statuses.length = 120; saveStatusesDebounced() }
        }
        continue
      }
      const fromMe = !!msg.key.fromMe
      const ts = msg.messageTimestamp ? Number(msg.messageTimestamp) * 1000 : Date.now()
      const entry = { chat: from, name: resolveName(msg, from), fromMe, text: extractText(msg.message), ts, id: msg.key.id }
      if (fromMe) entry.status = (typeof msg.status === 'number' ? msg.status : 0)
      if (msg.key.id) msgStore.set(msg.key.id, entry)
      msgLog.push(entry)
      n++
    } catch (_) {}
  }
  msgLog.sort((a, b) => b.ts - a.ts)
  if (msgLog.length > 500) msgLog = msgLog.slice(0, 500)
  log('history.set added', n)
}

async function startSocket() {
  let version
  try { const v = await fetchLatestBaileysVersion(); version = v.version; log('WA version', version.join('.')) }
  catch (e) { log('version fetch failed, using bundled') }

  sock = makeWASocket({
    version,
    auth: state,
    logger,
    printQRInTerminal: false,
    browser: Browsers.ubuntu('imayx.in'),
    markOnlineOnConnect: settings.alwaysOnline && !settings.stayOffline,
    syncFullHistory: false,
  })
  sock.ev.on('creds.update', saveCreds)
  sock.ev.on('connection.update', handleConnUpdate)
  sock.ev.on('messages.upsert', handleMessages)
  sock.ev.on('messages.update', handleUpdates)
  sock.ev.on('messaging-history.set', handleHistory)
  sock.ev.on('messages.reaction', (reactions) => {
    for (const r of reactions || []) {
      const id = r.key?.id
      const e = id ? msgStore.get(id) : null
      if (e) { e.reaction = r.reaction?.text || ''; saveMessagesDebounced() }
    }
  })
  const addContacts = (list) => { for (const c of list || []) { if (c.id) { const nm = c.name || c.notify || ''; contacts.set(c.id, { name: nm, notify: c.notify || '' }); rememberName(c.id, nm) } } }
  sock.ev.on('contacts.upsert', addContacts)
  sock.ev.on('contacts.update', addContacts)
  sock.ev.on('contacts.set', ({ contacts: cs }) => addContacts(cs))
  sock.ev.on('message-receipt.update', (updates) => {
    for (const u of updates || []) {
      try {
        const k = u.key || {}
        if (k.remoteJid === 'status@broadcast' && k.fromMe) {
          const id = k.id
          const viewer = (u.receipt && (u.receipt.userJid || u.receipt.receiptTimestamp && u.receipt.userJid)) || k.participant
          if (id && viewer) {
            if (!statusViewers[id]) statusViewers[id] = new Set()
            statusViewers[id].add(viewer)
          }
        } else if (k.fromMe && k.id) {
          // normal chat delivery/read receipt -> bump tick status
          const e = msgStore.get(k.id)
          if (e) {
            const rec = u.receipt || {}
            const s = rec.readTimestamp ? 4 : (rec.receiptTimestamp ? 3 : 0)
            if (s > (e.status | 0)) { e.status = s; saveMessagesDebounced() }
          }
        }
      } catch (_) {}
    }
  })
  sock.ev.on('presence.update', ({ id, presences: p }) => {
    if (!id || !p) return
    const first = Object.values(p)[0]
    if (first) {
      const presence = first.lastKnownPresence || 'unavailable'
      presences.set(id, { presence, lastSeen: first.lastSeen || null })
      const w = watchForJid(id)
      if (w) recordPresence(w.id, presence, first.lastSeen || null)   // instant path (sweep is the backup)
    }
  })
}

const app = express()
app.use(express.json({ limit: '64mb' }))

app.get('/health', (req, res) => res.json({ ok: true, node: process.version }))

app.get('/status', (req, res) => res.json({
  connection: status.connection,
  registered: status.registered,
  me: status.me,
  lastError: status.lastError,
  hasQr: !!currentQr,
  pairingCode: pairingCode,
}))

app.get('/qr.png', async (req, res) => {
  if (!currentQr) return res.status(204).end()
  try {
    const buf = await QRCode.toBuffer(currentQr, { type: 'png', width: 512, margin: 1 })
    res.setHeader('Content-Type', 'image/png'); res.setHeader('Cache-Control', 'no-store'); res.send(buf)
  } catch (e) { res.status(500).end() }
})

app.post('/pair', async (req, res) => {
  try {
    const number = String(req.body?.number || '').replace(/\D/g, '')
    if (state?.creds?.registered) return res.json({ registered: true })
    if (!number) return res.status(400).json({ error: 'number required' })
    if (pairingCode && pairingNumber === number && sock) return res.json({ code: pairingCode })
    try { await sock?.ws?.close() } catch (_) {}
    try { fs.rmSync(AUTH_DIR, { recursive: true, force: true }) } catch (_) {}
    state = null; sock = null; currentQr = null; pairingCode = null; pairingNumber = null
    await loadAuth(); await startSocket(); await delay(3000)
    pairingCode = await sock.requestPairingCode(number)
    pairingNumber = number
    log('pairing code', pairingCode)
    res.json({ code: pairingCode })
  } catch (e) { log('pair error', e?.message); res.status(500).json({ error: e?.message || 'pair failed' }) }
})

app.post('/send', async (req, res) => {
  try {
    if (!sock || status.connection !== 'open') return res.status(409).json({ error: 'not connected' })
    const to = String(req.body?.to || '').replace(/\D/g, '')
    const text = String(req.body?.text || '')
    if (!to || !text) return res.status(400).json({ error: 'to and text required' })
    const r = await sock.sendMessage(to + '@s.whatsapp.net', { text })
    res.json({ ok: true, id: r?.key?.id || null })
  } catch (e) { log('send error', e?.message); res.status(500).json({ error: e?.message || 'send failed' }) }
})

app.get('/presence', async (req, res) => {
  const jid = String(req.query.jid || '')
  if (!jid || !sock) return res.json({ presence: 'unavailable', lastSeen: null })
  try { await sock.presenceSubscribe(jid) } catch (_) {}
  const p = presences.get(jid) || { presence: 'unavailable', lastSeen: null }
  res.json(p)
})

// ---- presence reminder endpoints ----
app.post('/reminder/add', async (req, res) => {
  const jid = String(req.body?.jid || '')
  if (!jid) return res.json({ ok: false, error: 'no jid' })
  // collect every jid form the app knows for this contact (primary + @s.whatsapp.net + @lid)
  let jids = [jid]
  const extra = req.body?.jids
  if (Array.isArray(extra)) for (const j of extra) if (j && !jids.includes(String(j))) jids.push(String(j))
  const existing = watches.find(w => w.id === jid)
  if (existing) { for (const j of jids) if (!existing.jids.includes(j)) existing.jids.push(j) }
  else watches.push({ id: jid, jids })
  if (!presenceState.has(jid)) presenceState.set(jid, { online: false, since: Date.now(), lastSeen: null })
  saveReminders()
  try {
    if (sock) {
      if (!settings.stayOffline) { try { await sock.sendPresenceUpdate('available') } catch (_) {} }  // go active so presence starts flowing
      for (const j of jids) await sock.presenceSubscribe(j).catch(() => {})
    }
  } catch (_) {}
  startReminderSweep()
  res.json({ ok: true })
})
app.post('/reminder/remove', (req, res) => {
  const jid = String(req.body?.jid || '')
  watches = watches.filter(w => w.id !== jid)
  presenceState.delete(jid); presenceLog.delete(jid)
  reminderOnlineQueue = reminderOnlineQueue.filter(x => x.jid !== jid)
  saveReminders()
  res.json({ ok: true })
})
app.get('/reminder/list', (req, res) => {
  const items = watches.map(w => {
    const st = presenceState.get(w.id) || { online: false, since: 0, lastSeen: null }
    return { jid: w.id, online: !!st.online, since: st.since || 0, lastSeen: st.lastSeen || 0, events: presenceLog.get(w.id) || [] }
  })
  res.json({ items })
})
// drained by the app's background poller to fire "X is online" notifications
app.get('/reminder/pending', (req, res) => {
  const items = reminderOnlineQueue; reminderOnlineQueue = []
  res.json({ items })
})
// diagnostics: which jids we watch vs which jids WhatsApp has actually pushed presence for
app.get('/reminder/debug', (req, res) => {
  const seen = {}; for (const [k, v] of presences) seen[k] = v
  res.json({
    connection: status.connection,
    stayOffline: !!settings.stayOffline,
    watches: watches.map(w => ({ id: w.id, jids: w.jids })),
    presenceSeenJids: Object.keys(seen),
    presenceSeen: seen,
    state: [...presenceState.entries()].map(([k, v]) => ({ id: k, ...v })),
  })
})

app.get('/session/export', (req, res) => {
  try {
    const out = { auth: {}, messages: null, settings: null }
    try { for (const f of fs.readdirSync(AUTH_DIR)) out.auth[f] = fs.readFileSync(path.join(AUTH_DIR, f), 'utf8') } catch (_) {}
    try { out.messages = fs.readFileSync(MESSAGES_FILE, 'utf8') } catch (_) {}
    try { out.settings = fs.readFileSync(SETTINGS_FILE, 'utf8') } catch (_) {}
    res.json(out)
  } catch (e) { res.status(500).json({ error: e?.message }) }
})

app.post('/session/import', async (req, res) => {
  try {
    const b = req.body || {}
    try { await sock?.ws?.close() } catch (_) {}
    fs.mkdirSync(AUTH_DIR, { recursive: true })
    if (b.auth) for (const [f, content] of Object.entries(b.auth)) fs.writeFileSync(path.join(AUTH_DIR, path.basename(f)), content)
    if (b.messages) fs.writeFileSync(MESSAGES_FILE, b.messages)
    if (b.settings) fs.writeFileSync(SETTINGS_FILE, b.settings)
    state = null; sock = null; msgStore.clear(); rawStore.clear(); msgLog = []
    loadSettings(); loadMessages(); await loadAuth(); await startSocket()
    res.json({ ok: true, registered: status.registered })
  } catch (e) { res.status(500).json({ error: e?.message }) }
})

app.get('/contacts', (req, res) => {
  const items = []
  const seen = new Set()
  for (const [jid, c] of contacts) {
    if (!jid.endsWith('@s.whatsapp.net')) continue
    items.push({ jid, name: c.name || c.notify || nameStore[jid] || '', number: jid.split('@')[0] })
    seen.add(jid)
  }
  for (const jid of Object.keys(nameStore)) {
    if (seen.has(jid) || !nameStore[jid]) continue
    items.push({ jid, name: nameStore[jid], number: jid.split('@')[0].split(':')[0] })
  }
  items.sort((a, b) => (a.name || a.number).localeCompare(b.name || b.number))
  res.json({ items })
})

app.post('/message/delete', async (req, res) => {
  try {
    const jid = String(req.body?.jid || '')
    const id = String(req.body?.id || '')
    const forEveryone = !!req.body?.forEveryone
    const fromMe = !!req.body?.fromMe
    const text = req.body?.text
    const ts = Number(req.body?.ts || 0)
    if (!jid) return res.status(400).json({ error: 'jid required' })
    if (forEveryone && sock && id) { try { await sock.sendMessage(jid, { delete: { remoteJid: jid, id, fromMe } }) } catch (_) {} }
    if (id) { msgLog = msgLog.filter(m => m.id !== id); msgStore.delete(id) }
    else if (text != null) { msgLog = msgLog.filter(m => !(m.text === text && Math.abs((m.ts || 0) - ts) < 6000)) }
    saveMessagesDebounced()
    res.json({ ok: true })
  } catch (e) { res.status(500).json({ error: e?.message }) }
})

app.post('/chat/delete', (req, res) => {
  const jid = String(req.body?.jid || '')
  if (!jid) return res.status(400).json({ error: 'jid required' })
  msgLog = msgLog.filter(m => m.chat !== jid)
  for (const [k, v] of msgStore) if (v.chat === jid) msgStore.delete(k)
  chatHistory.delete(jid)
  saveMessagesDebounced()
  res.json({ ok: true })
})

app.post('/react', async (req, res) => {
  try {
    const jid = String(req.body?.jid || '')
    const id = String(req.body?.id || '')
    const emoji = String(req.body?.emoji || '')
    const fromMe = !!req.body?.fromMe
    if (!sock || !jid || !id) return res.status(400).json({ error: 'jid,id required' })
    await sock.sendMessage(jid, { react: { text: emoji, key: { remoteJid: jid, id, fromMe } } })
    res.json({ ok: true })
  } catch (e) { res.status(500).json({ error: e?.message }) }
})

app.post('/block', async (req, res) => {
  try {
    const jid = String(req.body?.jid || '')
    const action = String(req.body?.action || 'block')
    if (!sock || !jid) return res.status(400).json({ error: 'jid required' })
    await sock.updateBlockStatus(jid, action === 'unblock' ? 'unblock' : 'block')
    res.json({ ok: true })
  } catch (e) { res.status(500).json({ error: e?.message }) }
})

app.post('/profile/name', async (req, res) => {
  try {
    const name = String(req.body?.name || '').trim()
    if (!sock || !name) return res.status(400).json({ error: 'name required' })
    await sock.updateProfileName(name)
    res.json({ ok: true })
  } catch (e) { res.status(500).json({ error: e?.message }) }
})

app.post('/profile/bio', async (req, res) => {
  try {
    const bio = String(req.body?.bio || '')
    if (!sock) return res.status(409).json({ error: 'not connected' })
    await sock.updateProfileStatus(bio)
    res.json({ ok: true })
  } catch (e) { res.status(500).json({ error: e?.message }) }
})

app.post('/profile/picture', async (req, res) => {
  try {
    const b64 = String(req.body?.data || '')
    if (!sock || !b64 || !sock.user?.id) return res.status(400).json({ error: 'data required' })
    await sock.updateProfilePicture(sock.user.id, Buffer.from(b64, 'base64'))
    dpCache.delete(sock.user.id)
    res.json({ ok: true })
  } catch (e) { res.status(500).json({ error: e?.message }) }
})

app.post('/sendmedia', async (req, res) => {
  try {
    if (!sock || status.connection !== 'open') return res.status(409).json({ error: 'not connected' })
    const jid = String(req.body?.jid || '')
    const type = String(req.body?.type || 'document')
    const b64 = String(req.body?.data || '')
    const filename = String(req.body?.filename || 'file')
    const caption = String(req.body?.caption || '')
    if (!jid || !b64) return res.status(400).json({ error: 'jid and data required' })
    const buf = Buffer.from(b64, 'base64')
    let content
    if (type === 'image') content = { image: buf, caption }
    else if (type === 'video') content = { video: buf, caption }
    else if (type === 'audio') content = { audio: buf, mimetype: 'audio/mp4' }
    else content = { document: buf, fileName: filename, mimetype: 'application/octet-stream', caption }
    const r = await sock.sendMessage(jid, content)
    res.json({ ok: true, id: r?.key?.id || null })
  } catch (e) { log('sendmedia error', e?.message); res.status(500).json({ error: e?.message || 'send failed' }) }
})

app.get('/dp', async (req, res) => {
  const jid = String(req.query.jid || '')
  if (!jid || !sock) return res.status(404).end()
  try {
    if (!dpCache.has(jid)) {
      const url = await sock.profilePictureUrl(jid, 'image').catch(() => null)
      dpCache.set(jid, url || null)
    }
    const url = dpCache.get(jid)
    if (!url) return res.status(404).end()
    const r = await fetch(url)
    if (!r.ok) return res.status(404).end()
    const buf = Buffer.from(await r.arrayBuffer())
    res.setHeader('Content-Type', 'image/jpeg')
    res.setHeader('Cache-Control', 'max-age=3600')
    res.send(buf)
  } catch (e) { res.status(404).end() }
})

function httpGet(url, headers) {
  return new Promise((resolve, reject) => {
    try {
      const req = https.get(url, { headers: headers || { 'User-Agent': 'Mozilla/5.0', 'Accept': 'application/json' }, rejectUnauthorized: false, timeout: 15000 }, (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          res.resume(); return httpGet(res.headers.location, headers).then(resolve).catch(reject)
        }
        let data = ''
        res.on('data', c => data += c)
        res.on('end', () => resolve(data))
      })
      req.on('error', reject)
      req.on('timeout', () => { req.destroy(); reject(new Error('timeout')) })
    } catch (e) { reject(e) }
  })
}

function saavnDecrypt(encUrl) {
  // Try pure-JS DES first (OpenSSL 3 dropped DES from default provider)
  if (CryptoJS) {
    try {
      const key = CryptoJS.enc.Utf8.parse('38346591')
      const ct = CryptoJS.enc.Base64.parse(encUrl)
      const dec = CryptoJS.DES.decrypt({ ciphertext: ct }, key, { mode: CryptoJS.mode.ECB, padding: CryptoJS.pad.Pkcs7 })
      const url = dec.toString(CryptoJS.enc.Utf8)
      if (url && url.startsWith('http')) return url.replace('_96.mp4', '_320.mp4')
    } catch (e) {}
  }
  // Fallback: native crypto (may work if legacy provider available)
  try {
    const key = Buffer.from('38346591', 'utf8')
    const encrypted = Buffer.from(encUrl, 'base64')
    const decipher = crypto.createDecipheriv('des-ecb', key, null)
    decipher.setAutoPadding(true)
    const out = decipher.update(encrypted, undefined, 'utf8') + decipher.final('utf8')
    return out.replace('_96.mp4', '_320.mp4')
  } catch (e) { return null }
}
function deEnt(str) {
  return String(str || '').replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#039;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>')
}
app.get('/music/search', async (req, res) => {
  const diag = []
  try {
    const q = String(req.query.q || '').trim()
    if (!q) return res.json({ items: [] })
    let items = []
    // JioSaavn official API (direct) + DES decrypt
    try {
      const url = 'https://www.jiosaavn.com/api.php?__call=search.getResults&_format=json&_marker=0&api_version=4&ctx=web6dot0&q=' + encodeURIComponent(q) + '&p=1&n=30'
      const body = await httpGet(url, { 'User-Agent': 'Mozilla/5.0 (Linux; Android 12)', 'Accept': 'application/json', 'Referer': 'https://www.jiosaavn.com/' })
      let data
      try { data = JSON.parse(body) } catch (pe) { data = JSON.parse(body.replace(/^[^{\[]*/, '')) }
      const results = (data && data.results) ? data.results : []
      diag.push('results=' + results.length)
      let noMedia = 0, noDec = 0
      for (const it of results) {
        const mi = it.more_info || {}
        const enc = mi.encrypted_media_url || it.encrypted_media_url
        if (!enc) { noMedia++; continue }
        const audio = saavnDecrypt(enc)
        if (!audio) { noDec++; continue }
        let artist = ''
        try { artist = (mi.artistMap && mi.artistMap.primary_artists ? mi.artistMap.primary_artists.map(a => a.name).join(', ') : '') || it.primary_artists || it.subtitle || '' } catch (_) {}
        items.push({ title: deEnt(it.title || it.song || ''), artist: deEnt(artist), image: String(it.image || '').replace('150x150', '500x500'), url: audio })
      }
      diag.push('playable=' + items.length + (noMedia ? ' noMedia=' + noMedia : '') + (noDec ? ' noDec=' + noDec : ''))
    } catch (e) { diag.push('jio_err=' + (e && e.message)) }
    log('music "' + q + '" ' + diag.join(' | '))
    res.json({ items: items.slice(0, 25), error: items.length === 0 ? diag.join(' | ') : '' })
  } catch (e) { res.json({ items: [], error: (e && e.message) + ' | ' + diag.join('|') }) }
})

app.get('/music/lyrics', async (req, res) => {
  try {
    const rawTitle = String(req.query.title || '').trim()
    const artist = String(req.query.artist || '').trim()
    if (!rawTitle) return res.json({ synced: '', plain: '' })
    const title = rawTitle.replace(/\(.*?\)/g, '').replace(/\[.*?\]/g, '').replace(/\bfrom\b.*$/i, '').replace(/\s*-\s*.*$/, '').trim() || rawTitle
    const firstArtist = artist.split(',')[0].trim()
    let data = {}
    try {
      const body = await httpGet('https://lrclib.net/api/get?track_name=' + encodeURIComponent(title) + '&artist_name=' + encodeURIComponent(firstArtist), { 'User-Agent': 'AyXWhatsApp/1.0', 'Accept': 'application/json' })
      data = JSON.parse(body)
    } catch (_) {}
    if (!data || (!data.syncedLyrics && !data.plainLyrics)) {
      try {
        const sbody = await httpGet('https://lrclib.net/api/search?q=' + encodeURIComponent((title + ' ' + firstArtist).trim()), { 'User-Agent': 'AyXWhatsApp/1.0', 'Accept': 'application/json' })
        const arr = JSON.parse(sbody)
        if (Array.isArray(arr) && arr.length) data = arr.find(x => x.syncedLyrics) || arr[0]
      } catch (_) {}
    }
    res.json({ synced: (data && data.syncedLyrics) || '', plain: (data && data.plainLyrics) || '' })
  } catch (e) { res.json({ synced: '', plain: '' }) }
})

// wait for the socket to actually be open (mobile networks drop/reconnect; sending on a closed
// socket throws Baileys' "reading 'attrs'" crash). Reconnect fires automatically on close.
async function waitForOpen(ms = 15000) {
  const start = Date.now()
  while (status.connection !== 'open' && Date.now() - start < ms) await new Promise(r => setTimeout(r, 400))
  return status.connection === 'open'
}

app.post('/status/post', async (req, res) => {
  const diag = []
  try {
    if (!sock) return res.status(409).json({ ok: false, error: 'not connected' })
    if (status.connection !== 'open') {
      const ok = await waitForOpen()
      diag.push('reconnedwait=' + ok)
      if (!ok) return res.status(409).json({ ok: false, error: 'connection reconnecting — try again in a moment', diag: diag.join(' ') })
    }
    diag.push('conn=' + status.connection)
    const type = String(req.body?.type || 'image')
    const b64 = String(req.body?.data || '')
    const caption = String(req.body?.caption || '')
    if (!b64) return res.status(400).json({ ok: false, error: 'no data' })
    const buf = Buffer.from(b64, 'base64')
    if (!buf || buf.length === 0) return res.status(400).json({ ok: false, error: 'empty media' })
    diag.push('bytes=' + buf.length)
    const content = type === 'video' ? { video: buf, caption } : { image: buf, caption }
    const audience = String(req.body?.audience || 'all')
    const selJids = Array.isArray(req.body?.jids) ? req.body.jids : []
    const set = new Set()
    for (const j of contacts.keys()) if (j.endsWith('@s.whatsapp.net')) set.add(j)
    for (const m of msgLog) if (m.chat && m.chat.endsWith('@s.whatsapp.net')) set.add(m.chat)
    const all = Array.from(set)
    let jids
    if (audience === 'only') jids = selJids
    else if (audience === 'except') jids = all.filter(j => !selJids.includes(j))
    else jids = Array.from(new Set([...all, ...selJids]))   // 'all': union gateway-known + client-supplied contacts
    try { const meJid = sock?.user?.id?.split(':')[0] + '@s.whatsapp.net'; if (meJid && !jids.includes(meJid)) jids.push(meJid) } catch (_) {}
    diag.push('recipients=' + jids.length)
    if (jids.length === 0) { diag.push('WARN:no-recipients'); }
    const r = await sock.sendMessage('status@broadcast', content, { statusJidList: jids, broadcast: true, backgroundColor: '#000000', font: 3 })
    const id = (r && r.key && r.key.id) || null
    diag.push('id=' + id)
    try {
      fs.mkdirSync(MEDIA_DIR, { recursive: true })
      const ext = type === 'video' ? '.mp4' : '.jpg'
      const mediaName = 'own_' + (id || Date.now()) + ext
      fs.writeFileSync(path.join(MEDIA_DIR, mediaName), buf)
      const meJid = (sock && sock.user && sock.user.id) || 'me'
      statuses = statuses.filter(x => !(x.mine && x.id === id))
      statuses.unshift({ sender: meJid, name: 'My Status', mine: true, id, text: caption, ts: Date.now(), mediaName, mediaType: type === 'video' ? 'video' : 'image' })
      if (statuses.length > 120) statuses.length = 120
      saveStatusesDebounced()
    } catch (_) {}
    log('status post ' + diag.join(' '))
    res.json({ ok: !!id, id, recipients: jids.length, diag: diag.join(' ') })
  } catch (e) {
    log('status post ERR ' + (e && e.message) + ' | ' + diag.join(' '))
    res.status(500).json({ ok: false, error: (e && e.message) || 'send failed', diag: diag.join(' ') })
  }
})

app.post('/sendreply', async (req, res) => {
  try {
    const jid = String(req.body?.jid || '')
    const text = String(req.body?.text || '')
    const quotedId = String(req.body?.quotedId || '')
    if (!sock || !jid || !text) return res.status(400).json({ error: 'jid,text required' })
    const quoted = rawStore.get(quotedId)
    try {
      if (quoted && quoted.message) await sock.sendMessage(jid, { text }, { quoted })
      else await sock.sendMessage(jid, { text })
    } catch (e) { await sock.sendMessage(jid, { text }) }
    res.json({ ok: true })
  } catch (e) { res.status(500).json({ error: e?.message }) }
})

app.post('/editmessage', async (req, res) => {
  try {
    if (!sock || status.connection !== 'open') return res.json({ ok: false, error: 'not connected' })
    const jid = String(req.body?.jid || '')
    const id = String(req.body?.id || '')
    const text = String(req.body?.text || '')
    if (!jid || !id || !text) return res.json({ ok: false, error: 'jid,id,text required' })
    // reuse the original stanza key when we have it, else reconstruct for our own message
    const raw = rawStore.get(id)
    const key = (raw && raw.key) ? raw.key : { remoteJid: jid, fromMe: true, id }
    await sock.sendMessage(jid, { text, edit: key })
    const e = msgStore.get(id)   // reflect locally right away (also lives in msgLog)
    if (e) { e.text = text; e.edited = true; e.editedTs = Date.now(); saveMessagesDebounced() }
    res.json({ ok: true })
  } catch (e) { res.json({ ok: false, error: e?.message }) }
})

app.post('/forward', async (req, res) => {
  try {
    if (!sock || status.connection !== 'open') return res.json({ ok: false, error: 'not connected' })
    const id = String(req.body?.id || '')
    const jids = Array.isArray(req.body?.jids) ? req.body.jids.map(String).filter(Boolean) : []
    if (!id || jids.length === 0) return res.json({ ok: false, error: 'id,jids required' })
    const raw = rawStore.get(id)          // full { key, message } preserves media/type
    const e = msgStore.get(id)
    let sent = 0; const errs = []
    for (const jid of jids) {
      try {
        if (raw && raw.message) await sock.sendMessage(jid, { forward: { key: raw.key, message: raw.message }, force: true })
        else if (e && e.text) await sock.sendMessage(jid, { text: e.text })
        else throw new Error('no content to forward')
        sent++
      } catch (err) { errs.push((jid.split('@')[0]) + ': ' + (err?.message || 'err')) }
    }
    res.json({ ok: sent > 0, sent, total: jids.length, error: errs.join(' | ') })
  } catch (e) { res.json({ ok: false, error: e?.message }) }
})

function wipeDirContents(dir) {
  let n = 0
  try { for (const f of fs.readdirSync(dir)) { try { fs.rmSync(path.join(dir, f), { recursive: true, force: true }); n++ } catch (_) {} } } catch (_) {}
  return n
}

// Clear Cache: only re-downloadable media. No logout, no settings/chat loss.
app.post('/clearcache', (req, res) => {
  try {
    const removed = wipeDirContents(MEDIA_DIR)
    res.json({ ok: true, removed })
  } catch (e) { res.json({ ok: false, error: e?.message }) }
})

// Clear Data: destructive — logs out and wipes local node state (auth, chats, statuses, media, settings).
app.post('/cleardata', async (req, res) => {
  try {
    try { if (sock) await sock.logout() } catch (_) {}
    msgLog = []; msgStore.clear(); rawStore.clear(); statuses = []; deletedList = []
    for (const k of Object.keys(statusViewers)) delete statusViewers[k]
    wipeDirContents(MEDIA_DIR)
    for (const F of [MESSAGES_FILE, STATUS_FILE, NAMES_FILE, SETTINGS_FILE]) { try { fs.rmSync(F, { force: true }) } catch (_) {} }
    wipeDirContents(AUTH_DIR)
    status.registered = false
    res.json({ ok: true })
  } catch (e) { res.json({ ok: false, error: e?.message }) }
})

app.post('/sendraw', async (req, res) => {
  try {
    if (!sock || status.connection !== 'open') return res.status(409).json({ error: 'not connected' })
    const jid = String(req.body?.jid || '')
    const text = String(req.body?.text || '')
    if (!jid || !text) return res.status(400).json({ error: 'jid and text required' })
    const r = await sock.sendMessage(jid, { text })
    res.json({ ok: true, id: r?.key?.id || null })
  } catch (e) { log('sendraw error', e?.message); res.status(500).json({ error: e?.message || 'send failed' }) }
})

app.get('/settings', (req, res) => res.json(settings))
app.post('/settings', (req, res) => {
  const b = req.body || {}
  if (typeof b.alwaysOnline === 'boolean') settings.alwaysOnline = b.alwaysOnline
  if (typeof b.autoRead === 'boolean') settings.autoRead = b.autoRead
  if (typeof b.hideStatusRead === 'boolean') settings.hideStatusRead = b.hideStatusRead
  if (typeof b.autoReplyEnabled === 'boolean') settings.autoReplyEnabled = b.autoReplyEnabled
  if (Array.isArray(b.autoReplyRules)) settings.autoReplyRules = b.autoReplyRules
  if (typeof b.aiReplyEnabled === 'boolean') settings.aiReplyEnabled = b.aiReplyEnabled
  if (typeof b.aiApiUrl === 'string') settings.aiApiUrl = b.aiApiUrl
  if (typeof b.aiApiKey === 'string') settings.aiApiKey = b.aiApiKey
  if (typeof b.aiModel === 'string') settings.aiModel = b.aiModel
  if (typeof b.groupAiEnabled === 'boolean') settings.groupAiEnabled = b.groupAiEnabled
  if (typeof b.aiSystemPrompt === 'string') settings.aiSystemPrompt = b.aiSystemPrompt
  if (typeof b.saveMedia === 'boolean') settings.saveMedia = b.saveMedia
  if (typeof b.stayOffline === 'boolean') settings.stayOffline = b.stayOffline
  saveSettings(); applyPresence()
  res.json(settings)
})

app.post('/onwhatsapp', async (req, res) => {
  try {
    const nums = Array.isArray(req.body?.numbers) ? req.body.numbers : []
    if (!sock || nums.length === 0) return res.json({ items: [] })
    const out = []
    for (let i = 0; i < nums.length; i += 80) {
      const chunk = nums.slice(i, i + 80).map(n => String(n).replace(/\D/g, '') + '@s.whatsapp.net')
      try {
        const r = await sock.onWhatsApp(...chunk)
        for (const x of (r || [])) {
          if (x && x.exists) {
            const number = String(x.jid).split('@')[0].split(':')[0]
            const lid = x.lid ? String(x.lid).split('@')[0].split(':')[0] : null
            out.push({ number, lid })
          }
        }
      } catch (_) {}
    }
    res.json({ items: out })
  } catch (e) { res.json({ items: [] }) }
})

// mark someone's status as seen so they get the "viewed" receipt (only called when Hide-status-view is OFF)
app.post('/status/read', async (req, res) => {
  try {
    if (!sock || status.connection !== 'open') return res.json({ ok: false, error: 'not connected' })
    const id = String(req.body?.id || '')
    const sender = String(req.body?.sender || '')
    if (!id || !sender) return res.json({ ok: false, error: 'id,sender required' })
    const key = { remoteJid: 'status@broadcast', id, participant: sender }
    await sock.readMessages([key])
    res.json({ ok: true })
  } catch (e) { res.json({ ok: false, error: e?.message }) }
})

app.post('/status/delete', async (req, res) => {
  try {
    if (!sock) return res.status(409).json({ error: 'not connected' })
    const id = String(req.body?.id || '')
    if (!id) return res.status(400).json({ error: 'id required' })
    let meJid
    try { meJid = sock?.user?.id ? sock.user.id.split(':')[0] + '@s.whatsapp.net' : undefined } catch (_) {}
    const key = { remoteJid: 'status@broadcast', id, fromMe: true }
    if (meJid) key.participant = meJid
    let ok = false, err = ''
    try { await sock.sendMessage('status@broadcast', { delete: key }); ok = true }
    catch (e) { err = (e && e.message) || 'delete failed' }
    statuses = statuses.filter(x => x.id !== id); saveStatusesDebounced()
    res.json({ ok, error: err })
  } catch (e) { res.status(500).json({ error: e && e.message }) }
})

app.get('/status/viewers', (req, res) => {
  const ids = String(req.query.id || '').split(',').map(x => x.trim()).filter(Boolean)
  const set = new Set()
  for (const id of ids) { const v = statusViewers[id]; if (v) for (const j of v) set.add(j) }
  const out = Array.from(set).map(jid => ({ jid, name: resolveName(jid) }))
  res.json({ viewers: out })
})

app.get('/me', (req, res) => {
  const jid = (sock && sock.user && sock.user.id) ? sock.user.id.split(':')[0] + '@s.whatsapp.net' : ''
  res.json({ jid, name: (sock && sock.user && sock.user.name) || '' })
})
app.get('/statuses', (req, res) => {
  const cutoff = Date.now() - 24 * 3600 * 1000
  statuses = statuses.filter(x => x && x.ts && x.ts > cutoff)
  // app reads NESTED media (o.media.{name,type,thumb}); normalise both own-posts (flat) and collected (nested) to nested
  const items = statuses.slice(0, 120).map(s => {
    const mn = s.mediaName || (s.media && s.media.name) || ''
    const mt = s.mediaType || (s.media && s.media.type) || ''
    const th = s.thumb || (s.media && s.media.thumb) || ''
    const out = { sender: s.sender, name: s.name || '', text: s.text || '', ts: s.ts, mine: !!s.mine, id: s.id || '' }
    if (mn) out.media = { name: mn, type: mt, thumb: th }
    return out
  })
  res.json({ items })
})
app.get('/messages', (req, res) => res.json({ items: msgLog.slice(0, 200) }))
app.get('/deleted', (req, res) => res.json({ items: deletedList }))

app.get('/media/:name', async (req, res) => {
  const name = path.basename(req.params.name)
  const f = path.join(MEDIA_DIR, name)
  if (fs.existsSync(f)) return res.sendFile(f)
  // missing (pruned / cache cleared) -> re-download from the raw message if we still have it
  const id = name.replace(/\.[^.]+$/, '')
  const raw = rawStore.get(id)
  if (raw && raw.message && sock) {
    try {
      const buf = await downloadMediaMessage({ key: raw.key, message: raw.message }, 'buffer', {}, { logger, reuploadRequest: sock.updateMediaMessage })
      fs.mkdirSync(MEDIA_DIR, { recursive: true })
      fs.writeFileSync(f, buf)
      return res.sendFile(f)
    } catch (e) { log('media re-download failed', name, e?.message) }
  }
  res.status(404).end()
})

app.post('/logout', async (req, res) => {
  try {
    try { await sock?.logout() } catch (_) {}
    try { await sock?.ws?.close() } catch (_) {}
    fs.rmSync(AUTH_DIR, { recursive: true, force: true })
    sock = null; currentQr = null; pairingCode = null; pairingNumber = null
    msgStore.clear(); rawStore.clear(); deletedList = []; msgLog = []; chatHistory.clear(); dpCache.clear(); presences.clear(); statuses = []
    watches = []; presenceState.clear(); presenceLog.clear(); reminderOnlineQueue = []
    try { fs.rmSync(MESSAGES_FILE, { force: true }) } catch (_) {}
    try { fs.rmSync(REMINDER_FILE, { force: true }) } catch (_) {}
    status = { connection: 'close', registered: false, me: null, lastError: null }
    await loadAuth(); await startSocket()
    res.json({ ok: true })
  } catch (e) { res.status(500).json({ error: e?.message }) }
})

app.use((req, res) => res.status(404).json({ ok: false, error: 'not found', path: req.path }))
app.use((err, req, res, next) => { log('http err', err?.message); res.status(500).json({ ok: false, error: err?.message || 'server error' }) })

app.listen(PORT, '127.0.0.1', async () => {
  log('server on 127.0.0.1:' + PORT, 'auth=', AUTH_DIR)
  loadSettings(); loadMessages(); await loadAuth()
  startSocket().catch(e => log('initial start err', e?.message))
  startReminderSweep()   // self-guards until the socket is open
})

process.on('uncaughtException', e => log('uncaughtException', e?.message))
process.on('unhandledRejection', e => log('unhandledRejection', e?.message))
