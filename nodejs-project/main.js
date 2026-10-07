// WA Gateway - Node/Baileys engine (Phase 1.1)
// Link, send, always-online, auto-read, auto-reply, anti-delete (all msgs), message log.

const fs = require('fs')
const path = require('path')
const crypto = require('crypto')
let CryptoJS = null
try { CryptoJS = require('crypto-js') } catch (e) {}
// WAV → OGG/Opus encoder (real WhatsApp voice notes). Safe to miss — caller falls back to audio file.
let _opus = null
try { _opus = require('./opus') } catch (e) {}
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
const AIMEM_FILE = path.join(path.dirname(AUTH_DIR), 'aimemory.json')

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
  aiPresenceFlow: false, // appear offline; for each AI reply: mark read → go online + typing → reply → go offline
  aiExcludeJids: [],   // chats where NO auto-reply / AI reply is sent ("reply nothing to this person")
  aiReplyVoice: false, // transcribe incoming voice notes (Groq Whisper) then AI-reply
  aiReplyImage: false, // "look at" incoming images (vision model) then AI-reply
  aiVisionModel: 'meta-llama/llama-4-scout-17b-16e-instruct', // multimodal model for images
  aiVisionApiUrl: '', // optional separate provider for images (e.g. Gemini free); empty = use main (Groq) API
  aiVisionApiKey: '',
  aiVisionApiUrl2: '', // FALLBACK vision provider (e.g. OpenRouter) — used automatically when the primary is down/overloaded
  aiVisionApiKey2: '',
  aiVisionModel2: '',
  aiImageOcr: false, // read text IN the image on-device (offline, unlimited) and feed it to the AI
  aiImageFree: true, // use free no-key vision (Pollinations) as a fallback so images work without any API key
  aiCommandsEnabled: true, // /create (make image) and /prompt (describe image as a prompt)
  aiVoiceNoteReply: false, // reply to incoming voice notes WITH a voice note (TTS)
  aiTtsVoice: 'Arista-PlayAI', // Groq PlayAI voice (uses the working Groq key) — real, varied voices
  aiTtsModel: 'playai-tts',
  aiSarvamKey: '', // Sarvam AI key (free) — real HINDI/BANGLA human voices; empty = Sarvam voices unavailable
  aiFullContext: true, // forward the real recent conversation (both sides) to the AI every reply
  aiLangMode: 'auto', // auto | hinglish | bangla | english | hindi | banglascript | custom  (forces ONE reply language)
  aiReplyLang: 'Reply in Roman Hindi (Hinglish). If they write Bangla, reply in Roman Bangla. ALWAYS use Latin/English letters — never Devanagari or Bangla script. Mirror the sender language.',
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


function pushHistory(jid, role, content) {
  if (!jid || !content) return
  let arr = chatHistory.get(jid) || []
  arr.push({ role, content, ts: Date.now() })
  if (arr.length > 30) arr = arr.slice(-30)  // keep last 30 messages per chat
  chatHistory.set(jid, arr)
  saveAiMemDebounced()
}
// history mapped to clean {role, content} for the API (strips ts — some APIs reject extra fields)
function histMsgs(jid, n) {
  const arr = chatHistory.get(jid) || []
  const slice = n ? arr.slice(-n) : arr
  return slice.map(h => ({ role: h.role, content: h.content }))
}
// Build the REAL conversation for this chat from the full message log (both sides), newest-first store
// reversed to chronological order. This is what we forward to the AI so replies use real context,
// not generic lines. Falls back to the AI-turn history if the log is empty.
function fullChatContext(jid, n) {
  const out = []
  for (const e of msgLog) {           // msgLog is newest-first
    if (!e || e.chat !== jid) continue
    const t = (e.text || '').trim()
    if (!t) continue
    out.push({ role: e.fromMe ? 'assistant' : 'user', content: t })
    if (out.length >= (n || 16)) break
  }
  out.reverse()                        // chronological (oldest -> newest)
  return out.length ? out : histMsgs(jid, n)
}

// ---- AI Memory: per-contact chat logs + who they are, persisted so it survives restarts ----
const contactMem = new Map()  // jid -> { jid, name, lid, pn, msgCount, lastTs, lang }
function detectLang(t) {
  if (!t) return ''
  if (/[ঀ-৿]/.test(t)) return 'bangla-script'
  if (/[ऀ-ॿ]/.test(t)) return 'hindi-script'
  return ''  // romanized text — can't reliably tell Hindi vs Bangla
}
function touchContact(jid, name, text) {
  if (!jid) return
  const m = contactMem.get(jid) || { jid, name: '', lid: '', pn: '', msgCount: 0, lastTs: 0, lang: '' }
  if (name && String(name).trim()) m.name = String(name).trim()
  if (jid.endsWith('@lid')) m.lid = jid
  else if (jid.endsWith('@s.whatsapp.net')) m.pn = jid
  m.msgCount++
  m.lastTs = Date.now()
  const d = detectLang(text); if (d) m.lang = d
  contactMem.set(jid, m)
  saveAiMemDebounced()
}
let _aimemTimer = null
function saveAiMemDebounced() {
  if (_aimemTimer) return
  _aimemTimer = setTimeout(() => {
    _aimemTimer = null
    try {
      const history = {}
      for (const [k, v] of chatHistory) history[k] = v.slice(-30)
      fs.writeFileSync(AIMEM_FILE, JSON.stringify({ history, contacts: [...contactMem.values()] }))
    } catch (e) { log('aimem save err', e?.message) }
  }, 2000)
}
function loadAiMem() {
  try {
    const o = JSON.parse(fs.readFileSync(AIMEM_FILE, 'utf8'))
    if (o.history) for (const [k, v] of Object.entries(o.history)) if (Array.isArray(v)) chatHistory.set(k, v)
    if (Array.isArray(o.contacts)) for (const c of o.contacts) if (c && c.jid) contactMem.set(c.jid, c)
    log('aimem loaded', contactMem.size, 'contacts')
  } catch (_) {}
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
// true if this chat is on the "don't reply" list (matched by digits so @lid and @s.whatsapp.net both hit)
function aiExcluded(jid) {
  const list = settings.aiExcludeJids || []
  if (!list.length) return false
  if (list.includes(jid)) return true
  const d = String(jid).split('@')[0].split(':')[0].replace(/\D/g, '')
  if (!d) return false
  return list.some(x => String(x).split('@')[0].split(':')[0].replace(/\D/g, '') === d)
}

// strict single-language presets — picking one STOPS the Hindi/Bangla mixing
const LANG_PRESETS = {
  hinglish: 'Reply ONLY in Roman Hindi (Hinglish) — Hindi written in English/Latin letters. NEVER use Bangla words. NEVER use Devanagari. Keep it short and casual.',
  bangla: 'Reply ONLY in Roman Bangla — Bangla written in English/Latin letters. NEVER use Hindi words. NEVER use Bangla script. Keep it short and casual.',
  english: 'Reply ONLY in casual English. NEVER use Hindi or Bangla words.',
  hindi: 'Reply ONLY in Hindi using Devanagari script (हिंदी). Never mix English or Bangla.',
  banglascript: 'Reply ONLY in Bangla using Bangla script (বাংলা). Never mix Hindi or English.',
}
// the dedicated reply-language instruction. A selected mode FORCES one language; 'custom' uses the free text; 'auto' mirrors.
function langRule() {
  const mode = settings.aiLangMode || 'auto'
  if (LANG_PRESETS[mode]) return LANG_PRESETS[mode] + ' Use the SAME language in EVERY reply — never switch or mix languages.'
  if (mode === 'custom') return (settings.aiReplyLang && settings.aiReplyLang.trim()) || LANG_PRESETS.hinglish
  // auto
  return (settings.aiReplyLang && settings.aiReplyLang.trim())
    || 'Reply in Roman Hindi (Hinglish). If they clearly write Bangla, reply in Roman Bangla. ALWAYS use Latin/English letters — never Devanagari or Bangla script. Pick ONE language per chat and stay consistent — do not mix Hindi and Bangla in the same reply.'
}
// build the system message: strict language rule + no-refusal + the user's persona prompt
function sysWith(base, langOverride) {
  const lang = 'LANGUAGE RULE (MUST follow for every reply): ' + (langOverride || langRule())
  const noRefuse = 'You are a real human chatting on WhatsApp, never an AI/bot/assistant. Never refuse, never apologize, never say "I can\'t help" or "I\'m sorry". Always answer in character, short and casual.'
  const ctx = 'The messages above are the real recent conversation with this person (their lines = user, your past lines = assistant). READ them and reply to the LAST message specifically, using that context. Do NOT give generic replies like "haan dekh liya" — actually answer what they said. If they refer to an earlier message, use it.'
  const persona = (settings.aiSystemPrompt && settings.aiSystemPrompt.trim()) ? settings.aiSystemPrompt.trim() : (base || '')
  return [lang, noRefuse, ctx, persona].filter(Boolean).join('\n\n')
}
function looksRefusal(t) {
  const s = (t || '').toLowerCase()
  return s.includes("i'm sorry") || s.includes('i am sorry') || s.includes("can't help") || s.includes('cannot help') ||
         s.includes('as an ai') || s.includes("can't assist") || s.includes('cannot assist') || s.includes("i can't do")
}
// one chat-completion call → trimmed text or null
async function chatComplete(model, messages) {
  try {
    const res = await fetch(settings.aiApiUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + settings.aiApiKey },
      body: JSON.stringify({ model, messages, temperature: 0.7 }),
    })
    if (!res.ok) { log('ai http', res.status, (await res.text()).slice(0, 160)); return null }
    const data = await res.json()
    const out = data?.choices?.[0]?.message?.content
    return out ? String(out).trim() : null
  } catch (e) { log('ai err', e?.message); return null }
}

// add who-we're-talking-to context from AI memory so replies stay consistent per person
function contactContext(jid) {
  const m = contactMem.get(jid)
  if (!m) return ''
  const bits = []
  if (m.name) bits.push('You are chatting with "' + m.name + '".')
  if (m.lang === 'bangla-script') bits.push('This person tends to write in Bangla.')
  else if (m.lang === 'hindi-script') bits.push('This person tends to write in Hindi.')
  return bits.length ? ('\n\n' + bits.join(' ') + ' Keep your language consistent across the whole chat.') : ''
}

async function aiReply(jid, force, currentText, langHint) {
  // force = called from voice/image path, which has its own enable toggle (don't require the master text toggle)
  if ((!force && !settings.aiReplyEnabled) || !settings.aiApiUrl || !settings.aiApiKey) return null
  // forward the REAL recent conversation (both sides) so Groq replies in context, not generic
  let history = (settings.aiFullContext === false) ? histMsgs(jid) : fullChatContext(jid, 18)
  // currentText = the actual message to answer NOW (voice transcript). It may not be in msgLog (voice has no
  // text there), so append it as the LAST user turn — otherwise the AI replies to the previous image/text.
  if (currentText && currentText.trim()) {
    history = history.filter(h => !(h.role === 'user' && h.content === currentText.trim()))
    history = [...history, { role: 'user', content: currentText.trim() }]
  }
  const model = settings.aiModel || 'openai/gpt-oss-20b'
  // langHint (from the spoken language) FORCES reply in that language+script, so a Bangla voice note
  // gets a Bangla reply (and Sarvam speaks real Bangla, not Hindi). Only set for the voice path.
  const langOv = langHint === 'bangla-script' ? 'Reply ONLY in Bangla using Bangla script (বাংলা). Never use Hindi or English. Keep it short and casual.'
               : langHint === 'hindi-script' ? 'Reply ONLY in Hindi using Devanagari script (हिंदी). Never use Bangla or English. Keep it short and casual.'
               : null
  const sys = sysWith(undefined, langOv) + contactContext(jid)
  let out = await chatComplete(model, [{ role: 'system', content: sys }, ...history])
  if (out && looksRefusal(out)) {
    // the text model sometimes refuses — retry once, harder, in character; if still a refusal, send nothing (no ugly "I can't help")
    const harder = sys + '\n\nDo NOT refuse or apologize. Reply as the character in one short casual line.'
    const out2 = await chatComplete(model, [{ role: 'system', content: harder }, ...history, { role: 'user', content: '(reply in character, casually, do not refuse)' }])
    out = (out2 && !looksRefusal(out2)) ? out2 : null
  }
  return out ? String(out).trim() : null
}

const groupDaily = new Map()  // groupJid -> { date, count }
function _today() { return new Date().toISOString().slice(0, 10) }
function groupDailyCount(jid) { const e = groupDaily.get(jid); return (!e || e.date !== _today()) ? 0 : e.count }
function incGroupDaily(jid) { const d = _today(); const e = groupDaily.get(jid); if (!e || e.date !== d) groupDaily.set(jid, { date: d, count: 1 }); else e.count++ }

async function groupAiReply(jid) {
  if (!settings.aiApiUrl || !settings.aiApiKey) return null
  const history = (settings.aiFullContext === false) ? histMsgs(jid, 9) : fullChatContext(jid, 12)
  const sys = sysWith('You are a friendly, witty member of a WhatsApp group chat. Reply briefly and naturally like a real person, in 1-2 short lines. Be relevant to what was just said, warm and casual.')
  try {
    const res = await fetch(settings.aiApiUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + settings.aiApiKey },
      body: JSON.stringify({ model: settings.aiModel || 'openai/gpt-oss-20b', messages: [{ role: 'system', content: sys }, ...history], temperature: 0.7 }),
    })
    if (!res.ok) { log('group ai http', res.status, (await res.text()).slice(0, 150)); return null }
    const data = await res.json()
    const out = data?.choices?.[0]?.message?.content
    return out ? String(out).trim() : null
  } catch (e) { log('group ai err', e?.message); return null }
}

// derive the OpenAI-compatible API base from the chat-completions URL (works for Groq & others)
function apiBase() { return String(settings.aiApiUrl || '').replace(/\/chat\/completions.*$/, '') }

// Groq Whisper: turn a saved voice note into text. Returns the transcript or null.
async function transcribeMedia(name) {
  if (!settings.aiApiKey) return null
  try {
    const p = path.join(MEDIA_DIR, name)
    if (!fs.existsSync(p)) return null
    const buf = fs.readFileSync(p)
    const fd = new FormData()
    fd.append('file', new Blob([buf]), name)
    fd.append('model', 'whisper-large-v3')
    const res = await fetch(apiBase() + '/audio/transcriptions', {
      method: 'POST', headers: { Authorization: 'Bearer ' + settings.aiApiKey }, body: fd,
    })
    if (!res.ok) { log('stt http', res.status, (await res.text()).slice(0, 150)); return null }
    const data = await res.json()
    const t = (data?.text || '').trim()
    return t || null
  } catch (e) { log('stt err', e?.message); return null }
}

// Known Groq multimodal model IDs to fall back through (Groq rotates these; the configured one is tried first).
const VISION_FALLBACKS = [
  'meta-llama/llama-4-scout-17b-16e-instruct',
  'meta-llama/llama-4-maverick-17b-128e-instruct',
  'llama-3.2-90b-vision-preview',
  'llama-3.2-11b-vision-preview',
]

// split a "model" field that may hold several comma-separated IDs (absorbs provider model-ID churn)
function splitModels(s, def) {
  const arr = String(s || '').split(',').map(x => x.trim()).filter(Boolean)
  return arr.length ? arr : (def || [])
}
// ordered list of vision providers to try: primary (e.g. Gemini) -> fallback (e.g. OpenRouter) -> main Groq API.
// Each has {url, key, models[]}. "Out of service" on one automatically moves to the next.
// Pollinations: free vision with no key (best-effort — they are moving to paid tiers, so may fail).
const POLLINATIONS_VISION_URL = 'https://text.pollinations.ai/openai?referrer=ayxwhatsapp'
const POLLINATIONS_VISION_MODELS = ['openai', 'gemini']
function visionProviders() {
  const list = []
  const seen = new Set()
  const add = (url, key, models, noauth) => {
    url = (url || '').trim(); key = (key || '').trim()
    if (!url || (!key && !noauth) || !models.length) return
    const sig = url + '|' + key
    if (seen.has(sig)) return
    seen.add(sig)
    list.push({ url, key, models })
  }
  // primary dedicated provider (if the user added one, e.g. Gemini). Broad model list absorbs Gemini's renames.
  add(settings.aiVisionApiUrl, settings.aiVisionApiKey, splitModels(settings.aiVisionModel, ['gemini-flash-latest', 'gemini-3-flash', 'gemini-2.5-flash', 'gemini-2.0-flash']))
  // secondary / fallback provider
  add(settings.aiVisionApiUrl2, settings.aiVisionApiKey2, splitModels(settings.aiVisionModel2, []))
  // the main (Groq) API with its known rotating vision IDs (only if a key is set)
  add(settings.aiApiUrl, settings.aiApiKey, splitModels(settings.aiVisionModel, []).concat(VISION_FALLBACKS))
  // ALWAYS-ON free fallback: Pollinations needs no key, so image replies work even with nothing configured
  if (settings.aiImageFree !== false) add(POLLINATIONS_VISION_URL, '', POLLINATIONS_VISION_MODELS, true)
  return list
}

// detect the real image type from the file's magic bytes so the data URL mime is correct.
// (sending a PNG/WebP labelled as image/jpeg makes some providers ignore the image → "what message?" replies)
function detectImageMime(buf) {
  if (!buf || buf.length < 12) return 'image/jpeg'
  if (buf[0] === 0xFF && buf[1] === 0xD8) return 'image/jpeg'
  if (buf[0] === 0x89 && buf[1] === 0x50 && buf[2] === 0x4E && buf[3] === 0x47) return 'image/png'
  if (buf[0] === 0x47 && buf[1] === 0x49 && buf[2] === 0x46) return 'image/gif'
  if (buf[0] === 0x52 && buf[1] === 0x49 && buf[2] === 0x46 && buf[8] === 0x57 && buf[9] === 0x45 && buf[10] === 0x42 && buf[11] === 0x50) return 'image/webp'
  return 'image/jpeg'
}

// one vision call. Returns { ok, text, status, err } so callers (and the self-test) can see WHY it failed.
// key may be empty for no-auth providers (e.g. Pollinations) — then no Authorization header is sent.
async function visionCall(url, key, model, sys, userText, dataUrl) {
  const ctrl = new AbortController()
  const to = setTimeout(() => ctrl.abort(), 25000)
  try {
    const headers = { 'Content-Type': 'application/json' }
    if (key) headers.Authorization = 'Bearer ' + key
    const res = await fetch(url, {
      method: 'POST', signal: ctrl.signal,
      headers,
      body: JSON.stringify({
        model,
        messages: [
          { role: 'system', content: sys },
          { role: 'user', content: [
            { type: 'text', text: userText },
            { type: 'image_url', image_url: { url: dataUrl } },
          ] },
        ],
      }),
    })
    if (!res.ok) {
      const body = (await res.text()).slice(0, 200)
      log('vision http', model, res.status, body)
      return { ok: false, text: null, status: res.status, err: body }
    }
    const data = await res.json()
    const out = data?.choices?.[0]?.message?.content
    const text = out ? String(out).trim() : null
    return { ok: !!text, text, status: 200, err: text ? null : 'empty response' }
  } catch (e) { log('vision call err', model, e?.message); return { ok: false, text: null, status: 0, err: e?.message || 'network error' } }
  finally { clearTimeout(to) }
}

// Auto-discover which Gemini models THIS key can actually use (fixes "model not found" after Google renames).
// Cached for an hour. Returns usable vision-capable model IDs, flash first.
let _gemCache = null
async function resolveGeminiModels(url, key) {
  try {
    if (_gemCache && _gemCache.key === key && (Date.now() - _gemCache.ts) < 3600000) return _gemCache.models
    const origin = String(url).split('/v1beta/')[0]
    const ctrl = new AbortController(); const to = setTimeout(() => ctrl.abort(), 12000)
    const res = await fetch(origin + '/v1beta/models?key=' + encodeURIComponent(key), { signal: ctrl.signal })
    clearTimeout(to)
    if (!res.ok) { log('gemini list http', res.status); return [] }
    const data = await res.json()
    const models = (data.models || [])
      .filter(m => (m.supportedGenerationMethods || []).includes('generateContent'))
      .map(m => String(m.name || '').replace(/^models\//, ''))
      .filter(n => /gemini/i.test(n) && /(flash|pro)/i.test(n) && !/(embedding|aqa|tts|image|audio|thinking|live|exp-)/i.test(n))
    models.sort((a, b) => (a.includes('flash') ? 0 : 1) - (b.includes('flash') ? 0 : 1))
    _gemCache = { key, ts: Date.now(), models }
    log('gemini models resolved:', models.slice(0, 6).join(', '))
    return models
  } catch (e) { log('gemini list err', e?.message); return [] }
}

// shared provider loop over a ready data URL (used by both real replies and the self-test)
async function visionAskDataUrl(dataUrl, sys, userText) {
  for (const prov of visionProviders()) {
    let models = prov.models
    // for Gemini, ask the key what it actually supports and try those first
    if (/generativelanguage/i.test(prov.url) && prov.key) {
      const fetched = await resolveGeminiModels(prov.url, prov.key)
      if (fetched.length) models = [...new Set([...fetched, ...prov.models])]
    }
    const tried = new Set()
    for (const model of models) {
      if (!model || tried.has(model)) continue
      tried.add(model)
      const r = await visionCall(prov.url, prov.key, model, sys, userText, dataUrl)
      if (r.ok && r.text && !looksRefusal(r.text)) { log('vision ok via', model); return r.text }
    }
  }
  return null
}

// a tiny built-in image (red circle + blue square + the text "AYX TEST") used by the "Test Image AI" button
const VISION_TEST_B64 = '/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAYEBQYFBAYGBQYHBwYIChAKCgkJChQODwwQFxQYGBcUFhYaHSUfGhsjHBYWICwgIyYnKSopGR8tMC0oMCUoKSj/2wBDAQcHBwoIChMKChMoGhYaKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCj/wAARCADwAWgDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD6pooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAopk80dvBJNPIkUMal3d2CqqgZJJPQAV5z4l+MGg6XJLBpqTapcJwGiISEndgjeeTwM5VSDxz6ROpGCvJ2OnDYOvipctGDl/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'

// run the current Image-AI setup against the built-in test image and report EXACTLY what each provider said
async function visionSelfTest() {
  const provs = visionProviders()
  if (!provs.length) return { ok: false, error: 'No image provider set. Image AI me Gemini/OpenRouter ka URL + key daal ke Save kar.', report: [] }
  const dataUrl = 'data:image/jpeg;base64,' + VISION_TEST_B64
  const sys = 'You are a vision check. Describe what is in this image in one short line, naming any shapes, colours or text you can see.'
  const report = []
  for (const prov of provs) {
    const host = (String(prov.url).match(/https?:\/\/([^/]+)/) || ['', prov.url])[1]
    let models = prov.models
    if (/generativelanguage/i.test(prov.url) && prov.key) {
      const fetched = await resolveGeminiModels(prov.url, prov.key)
      if (fetched.length) { report.push({ host, model: 'available for your key: ' + fetched.slice(0, 6).join(', '), status: 200, ok: true, err: null, text: null }); models = [...new Set([...fetched, ...prov.models])] }
    }
    const tried = new Set()
    for (const model of models) {
      if (!model || tried.has(model)) continue
      tried.add(model)
      const r = await visionCall(prov.url, prov.key, model, sys, 'What is in this image?', dataUrl)
      report.push({ host, model, status: r.status, ok: r.ok, err: r.err ? String(r.err).slice(0, 180) : null, text: r.text ? r.text.slice(0, 180) : null })
      if (r.ok && r.text) return { ok: true, via: host + ' · ' + model, text: r.text, report }
    }
  }
  return { ok: false, error: 'Koi provider image nahi dekh paaya — neeche details dekh.', report }
}

// ---- on-device OCR (optional) ----
// Reads the text that is IN the image, fully on the phone (offline, unlimited). We read with the
// 'eng' model because the whole app replies in ROMAN Hindi/Bangla/English (Latin letters), which is
// exactly what 'eng' recognises. Lazy-required + fully isolated: if tesseract.js can't load/run on
// this device it just disables itself — it can NEVER crash the gateway.
let _ocrBroken = false
async function ocrImage(name) {
  if (!settings.aiImageOcr || _ocrBroken) return ''
  try {
    const p = path.join(MEDIA_DIR, name)
    if (!fs.existsSync(p)) return ''
    let Tesseract
    try { Tesseract = require('tesseract.js') }
    catch (e) { _ocrBroken = true; log('ocr: tesseract.js unavailable —', e?.message); return '' }
    const run = Tesseract.recognize(p, 'eng')
    const timeout = new Promise(r => setTimeout(() => r(null), 22000))
    const res = await Promise.race([run, timeout])
    const t = (res && res.data && res.data.text ? res.data.text : '').replace(/\s+/g, ' ').trim()
    if (t) log('ocr text:', t.slice(0, 90))
    return t
  } catch (e) { log('ocr err', e?.message); return '' }
}

// Vision: send a saved image (+ caption + any OCR'd text) to a multimodal model and get a natural reply.
// Tries every configured provider/model in order; if ALL vision providers fail but OCR found text,
// falls back to a plain TEXT reply built from that text (works even when every vision API is down).
// Core: read a saved image and ask every configured vision provider/model until one answers.
// Returns text or null. (No OCR here — OCR is only used as a last-resort fallback so it never
// slows down the normal, fast image reply.)
async function visionAsk(name, sys, userText) {
  const p = path.join(MEDIA_DIR, name)
  if (!fs.existsSync(p)) return null
  let buf
  try { buf = fs.readFileSync(p) } catch (_) { return null }
  const dataUrl = 'data:' + detectImageMime(buf) + ';base64,' + buf.toString('base64')
  return await visionAskDataUrl(dataUrl, sys, userText)
}

async function visionReply(jid, name, caption) {
  // LEAN system for vision: the full persona prompt overflows vision models and kills the reply.
  const sys = 'You are Raju, a real person chatting on WhatsApp (never an AI/bot, never say you are one). Look at the image and reply in ONE short, casual, natural line. Never refuse or apologize. LANGUAGE (must follow): ' + langRule()
  const userText = (caption && caption.trim()) ? caption.trim() : 'reply to this image'

  // FAST PATH: vision first (no OCR wait) — this is what fixes the reply delay.
  const out = await visionAsk(name, sys, userText)
  if (out) return out

  // SLOW FALLBACK only when every vision provider failed: read text on-device, answer from that.
  // Quality-gate the OCR: only use it if it found several real words. Garbled game/UI text made the
  // model reply "ye kya garbled message hai" — so if OCR is junk, send nothing instead of nonsense.
  const ocrText = await ocrImage(name)
  const words = (ocrText.match(/[A-Za-z]{3,}/g) || [])
  if (words.length >= 3 && settings.aiApiUrl && settings.aiApiKey) {
    const r = await chatComplete(settings.aiModel || 'openai/gpt-oss-20b', [
      { role: 'system', content: sysWith() },
      { role: 'user', content: 'Someone sent you an image. The text detected inside it is: "' + ocrText.slice(0, 600) + '". Reply in ONE short casual line as if you just glanced at their picture. Do NOT say it is garbled, confusing, or that you cannot understand it.' },
    ])
    if (r && !looksRefusal(r)) { log('vision fallback via OCR+text'); return r }
  }
  log('vision: all providers failed (no reply sent)')
  return null
}

// /prompt command: describe the image as a detailed image-generation prompt.
async function describeAsPrompt(name) {
  const sys = 'You are an expert image-prompt engineer. Look at the image and output ONE detailed, vivid text-to-image generation prompt in English: subject, style, colours, lighting, composition, mood, camera/lens. Output ONLY the prompt text, no preamble, no quotes.'
  return await visionAsk(name, sys, 'Describe this image as a generation prompt.')
}

// /create command: free, unlimited text-to-image via Pollinations (no API key needed). Returns a JPEG buffer.
async function generateImage(prompt) {
  try {
    const seed = Math.floor(Math.random() * 1e6)
    const url = 'https://image.pollinations.ai/prompt/' + encodeURIComponent(prompt) +
      '?width=1024&height=1024&nologo=true&model=flux&seed=' + seed
    const ctrl = new AbortController()
    const to = setTimeout(() => ctrl.abort(), 70000)
    const res = await fetch(url, { signal: ctrl.signal })
    clearTimeout(to)
    if (!res.ok) { log('imggen http', res.status); return null }
    const ab = await res.arrayBuffer()
    const buf = Buffer.from(ab)
    return buf.length > 1000 ? buf : null
  } catch (e) { log('imggen err', e?.message); return null }
}

// ---- Text-to-speech (FREE, NO API KEY) ----
// Microsoft Edge neural voices via a direct WebSocket (ws is already a Baileys dependency). Returns
// OGG/Opus so WhatsApp plays it as a real voice note. No key, no extra install.
function xmlEsc(s) { return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&apos;') }
async function edgeTtsOgg(text, voice) {
  let WS
  try { WS = require('ws') } catch (e) { return null }
  return await new Promise((resolve) => {
    let done = false, ws = null
    const chunks = []
    const finish = (v) => { if (!done) { done = true; try { ws && ws.close() } catch (_) {} resolve(v) } }
    try {
      const TRUSTED = '6A5AA1D4EAFF4E9FB37E23D68491D6F4'
      const sec = Math.floor(Date.now() / 1000) + 11644473600
      const ticks = BigInt(sec - (sec % 300)) * 10000000n
      const gec = crypto.createHash('sha256').update(ticks.toString() + TRUSTED, 'ascii').digest('hex').toUpperCase()
      const url = 'wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1' +
        '?TrustedClientToken=' + TRUSTED + '&Sec-MS-GEC=' + gec + '&Sec-MS-GEC-Version=1-130.0.2849.68'
      ws = new WS(url, { headers: {
        Origin: 'chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold',
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0',
      } })
      const to = setTimeout(() => finish(chunks.length ? Buffer.concat(chunks) : null), 20000)
      ws.on('open', () => {
        try {
          ws.send('X-Timestamp:' + new Date().toString() + '\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n' +
            '{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},"outputFormat":"ogg-24khz-16bit-mono-opus"}}}}')
          const reqId = crypto.randomUUID().replace(/-/g, '')
          const ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'><voice name='" +
            (voice || 'hi-IN-SwaraNeural') + "'>" + xmlEsc(text) + '</voice></speak>'
          ws.send('X-RequestId:' + reqId + '\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:' + new Date().toString() + 'Z\r\nPath:ssml\r\n\r\n' + ssml)
        } catch (e) { clearTimeout(to); finish(null) }
      })
      ws.on('message', (data, isBinary) => {
        try {
          const buf = Buffer.isBuffer(data) ? data : Buffer.from(data)
          const head = buf.slice(0, Math.min(buf.length, 130)).toString('utf8')
          if (isBinary || head.includes('Path:audio')) {
            if (buf.length > 2) { const hlen = buf.readUInt16BE(0); if (2 + hlen <= buf.length) chunks.push(buf.slice(2 + hlen)) }
          } else if (head.includes('Path:turn.end')) { clearTimeout(to); finish(chunks.length ? Buffer.concat(chunks) : null) }
        } catch (_) {}
      })
      ws.on('error', (e) => { clearTimeout(to); log('edge-tts ws err', e?.message); finish(chunks.length ? Buffer.concat(chunks) : null) })
      ws.on('close', () => { clearTimeout(to); finish(chunks.length ? Buffer.concat(chunks) : null) })
    } catch (e) { log('edge-tts err', e?.message); finish(null) }
  })
}

// StreamElements TTS (free, NO API key) — Amazon Polly voices. Returns MP3. This is the reliable path.
async function streamElementsTts(text, voice) {
  try {
    const v = (voice && /^[A-Za-z]+$/.test(voice)) ? voice : 'Aditi'
    const url = 'https://api.streamelements.com/kappa/v2/speech?voice=' + encodeURIComponent(v) + '&text=' + encodeURIComponent(text.slice(0, 480))
    const ctrl = new AbortController(); const to = setTimeout(() => ctrl.abort(), 25000)
    const res = await fetch(url, { signal: ctrl.signal, headers: { 'User-Agent': 'Mozilla/5.0' } }); clearTimeout(to)
    if (!res.ok) { log('se tts http', res.status); return null }
    const buf = Buffer.from(await res.arrayBuffer())
    return buf.length > 500 ? buf : null
  } catch (e) { log('se tts err', e?.message); return null }
}

// Google Translate TTS fallback (free, no key) — MP3, short text only.
async function googleTts(text) {
  try {
    const url = 'https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=hi&q=' + encodeURIComponent(text.slice(0, 190))
    const ctrl = new AbortController(); const to = setTimeout(() => ctrl.abort(), 15000)
    const res = await fetch(url, { signal: ctrl.signal, headers: { 'User-Agent': 'Mozilla/5.0' } }); clearTimeout(to)
    if (!res.ok) return null
    const buf = Buffer.from(await res.arrayBuffer())
    return buf.length > 500 ? buf : null
  } catch (e) { return null }
}

// Groq TTS — uses the SAME Groq key that already works on this device, so it's the most reliable source
// of real, DIFFERENT, human voices. Tries PlayAI (needs 1-time terms) then Orpheus (fallback). Returns MP3.
let _groqTtsErr = ''
async function groqTtsTry(text, model, voice) {
  try {
    const ctrl = new AbortController(); const to = setTimeout(() => ctrl.abort(), 30000)
    const res = await fetch(apiBase() + '/audio/speech', {
      method: 'POST', signal: ctrl.signal,
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + settings.aiApiKey },
      body: JSON.stringify({ model, voice, input: text.slice(0, 900), response_format: 'wav' }),
    })
    clearTimeout(to)
    if (!res.ok) { _groqTtsErr = (await res.text()).slice(0, 300) || ('HTTP ' + res.status); log('groq tts http', model, res.status, _groqTtsErr); return null }
    const buf = Buffer.from(await res.arrayBuffer())
    if (buf.length > 500) { _groqTtsErr = ''; return buf }
    return null
  } catch (e) { _groqTtsErr = e?.message || 'err'; log('groq tts err', model, e?.message); return null }
}
async function groqTts(text, voice) {
  if (!settings.aiApiKey) { _groqTtsErr = 'no key'; return null }
  const v = voice || 'Arista-PlayAI'
  // 1) PlayAI (the main voices) — needs one-time terms acceptance on console.groq.com
  let b = await groqTtsTry(text, settings.aiTtsModel || 'playai-tts', v)
  if (b) return b
  // 2) Orpheus fallback (different model — may not need terms)
  const ov = /female|arista|celeste|deedee|gail|quinn|cheyenne|hannah/i.test(v) ? 'hannah' : 'troy'
  b = await groqTtsTry(text, 'canopylabs/orpheus-v1-english', ov)
  if (b) return b
  return null
}

// Sarvam AI TTS — real HINDI / BANGLA (and other Indian) human voices. Needs a free Sarvam key.
// bulbul:v3. output_audio_codec can be opus (WhatsApp's own voice codec), mp3, wav, ...
let _sarvamErr = ''
let _sarvamOpusOk = null   // null=unknown, true=Sarvam opus is OGG-wrapped (real voice note), false=use mp3
// Valid Sarvam bulbul:v3 speakers (lowercase). Anything else falls back to a safe default.
const SARVAM_V3 = ['ritu', 'roopa', 'priya', 'kavya', 'neha', 'shreya', 'pooja', 'rahul', 'amit', 'dev', 'varun', 'kabir', 'rohan', 'aditya']
const SARVAM_MALE = /^(rahul|amit|dev|varun|kabir|rohan|aditya)$/
async function sarvamTts(text, speaker, lang, codec) {
  if (!settings.aiSarvamKey) { _sarvamErr = 'no key'; return null }
  let sp = String(speaker || 'priya').toLowerCase()
  if (!SARVAM_V3.includes(sp)) sp = 'priya'   // guard against stale v2 names
  const body = { text: text.slice(0, 480), target_language_code: lang || 'hi-IN', speaker: sp, model: 'bulbul:v3', pace: 1.0, output_audio_codec: codec || 'mp3' }
  const ctrl = new AbortController(); const to = setTimeout(() => ctrl.abort(), 30000)
  try {
    const res = await fetch('https://api.sarvam.ai/text-to-speech', {
      method: 'POST', signal: ctrl.signal,
      headers: { 'Content-Type': 'application/json', 'api-subscription-key': settings.aiSarvamKey },
      body: JSON.stringify(body),
    })
    clearTimeout(to)
    if (!res.ok) { _sarvamErr = (await res.text()).slice(0, 240) || ('HTTP ' + res.status); log('sarvam http', res.status, _sarvamErr); return null }
    const data = await res.json()
    const b64 = data && data.audios && data.audios[0]
    if (!b64) { _sarvamErr = 'empty response'; return null }
    _sarvamErr = ''
    return Buffer.from(b64, 'base64')
  } catch (e) { clearTimeout(to); _sarvamErr = e?.message || 'err'; log('sarvam err', e?.message); return null }
}
// Decide the WhatsApp mime + ptt from the ACTUAL audio bytes (never trust the requested codec).
// OGG/Opus = WhatsApp's own voice codec → real voice note (ptt:true, plays directly). Everything
// else goes as a normal audio message (ptt:false) — sending non-opus as ptt makes WhatsApp say
// "this audio is not available / something is wrong with the audio file".
function audioKind(buf) {
  if (!buf || buf.length < 4) return { mime: 'audio/mpeg', ptt: false }
  const h = buf.toString('latin1', 0, 4)
  if (h === 'OggS') return { mime: 'audio/ogg; codecs=opus', ptt: true }   // real voice note
  if (h === 'RIFF') return { mime: 'audio/wav', ptt: false }
  if (h.slice(0, 3) === 'ID3') return { mime: 'audio/mpeg', ptt: false }
  if (buf[0] === 0xFF && (buf[1] & 0xE0) === 0xE0) return { mime: 'audio/mpeg', ptt: false }   // mp3 frame sync
  if (buf.length > 11 && buf.toString('latin1', 4, 8) === 'ftyp') return { mime: 'audio/mp4', ptt: false }
  return { mime: 'audio/mpeg', ptt: false }
}
function mkAudio(buf) { if (!buf || !buf.length) return null; const k = audioKind(buf); return { buf, mime: k.mime, ptt: k.ptt } }
// Turn any TTS output into the best WhatsApp payload. A WAV is re-encoded to real OGG/Opus so it
// sends as a true "recording" voice note (ptt) that plays directly — exactly what WhatsApp uses.
// If opus encoding isn't possible, it goes as a normal (still playable) audio message.
function finalizeVoice(buf) {
  if (!buf || !buf.length) return null
  const h = buf.toString('latin1', 0, 4)
  if (h === 'OggS') return { buf, mime: 'audio/ogg; codecs=opus', ptt: true }   // already a voice note
  if (h === 'RIFF' && _opus && _opus.opusAvailable && _opus.opusAvailable()) {
    try {
      const ogg = _opus.wavToOggOpus(buf)
      if (ogg && ogg.length > 40 && ogg.toString('latin1', 0, 4) === 'OggS') {
        return { buf: ogg, mime: 'audio/ogg; codecs=opus', ptt: true }   // real recording voice note
      }
    } catch (e) { log('opus encode err', e?.message) }
  }
  return mkAudio(buf)   // fallback: plain audio message (plays, just not a voice-note bubble)
}
// Sarvam → ask for WAV (clean PCM) and encode to OGG/Opus ourselves → a real voice note.
async function sarvamTtsSmart(text, speaker, lang) {
  const w = await sarvamTts(text, speaker, lang, 'wav')
  if (w) return finalizeVoice(w)
  return null
}
// TTS language code from any text's native script. '' if romanized/unknown (can't tell hi vs bn).
function ttsLangCode(text) {
  const d = detectLang(text)
  if (d === 'bangla-script') return 'bn-IN'
  if (d === 'hindi-script') return 'hi-IN'
  return ''
}
// rough spoken length (secs) so WhatsApp shows a sensible voice-note duration
function estSeconds(text) { return Math.min(90, Math.max(1, Math.round(String(text || '').length / 13))) }

// map an Edge neural voice to the closest StreamElements/Polly voice (used if Edge can't connect)
function pollyFallback(voice) {
  const v = String(voice || '')
  if (v.startsWith('hi-') || v.startsWith('bn-')) return 'Aditi'
  if (v.startsWith('en-IN')) return v.includes('Prabhat') ? 'Matthew' : 'Raveena'
  if (v.startsWith('en-GB')) return v.includes('Ryan') ? 'Brian' : 'Amy'
  return 'Joanna'
}

// Produce speech → { buf, mime, ptt } or null (caller sends TEXT). Every provider's audio goes
// through finalizeVoice, which re-encodes WAV → real OGG/Opus so the reply sends as a proper
// "recording" voice note that plays directly on the recipient's WhatsApp.
// Sarvam is preferred whenever a key exists (clean Hindi/Bangla WAV → opus). Groq (WAV) also becomes
// a real voice note via the encoder. langOverride ('hi-IN'/'bn-IN') forces the spoken language.
async function synthVoice(text, voice, langOverride) {
  if (!text) return null
  const v = voice || 'Arista-PlayAI'
  // target language: explicit override → native script of the text → default Hindi (this user base)
  const lang = langOverride || ttsLangCode(text) || 'hi-IN'
  // Sarvam first (clean WAV → encoded to a real WhatsApp-native opus voice note)
  if (settings.aiSarvamKey) {
    const speaker = v.startsWith('sarvam:') ? (v.split(':')[2] || 'priya') : 'priya'
    const r = await sarvamTtsSmart(text, speaker, lang)
    if (r) return r
    if (v.startsWith('sarvam:')) return null   // sarvam voice chosen but failed → text reply (no robot)
    // else: fall through to other engines
  }
  if (/-PlayAI$/i.test(v) || /^(troy|hannah|austin)$/i.test(v)) {
    const g = await groqTts(text, v); if (g) return finalizeVoice(g)   // Groq WAV → opus voice note
  } else if (/Neural/i.test(v)) {
    const ogg = await edgeTtsOgg(text, v); if (ogg && ogg.length > 500) return finalizeVoice(ogg)
    const se = await streamElementsTts(text, pollyFallback(v)); if (se) return finalizeVoice(se)
  } else if (!v.startsWith('sarvam:')) {
    const se = await streamElementsTts(text, v); if (se) return finalizeVoice(se)
  }
  // last resort: Groq human voice (NOT Google). If this also fails, caller falls back to a text reply.
  const g2 = await groqTts(text, /-PlayAI$/i.test(v) ? v : 'Arista-PlayAI'); if (g2) return finalizeVoice(g2)
  return null
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

// Human-like presence for AI replies: come online, read, "type", then go back offline after sending.
async function presenceBefore(jid, key) {
  if (!settings.aiPresenceFlow || !sock) return
  try { if (key) await sock.readMessages([key]) } catch (_) {}
  try { await sock.sendPresenceUpdate('available') } catch (_) {}
  try { await sock.sendPresenceUpdate('composing', jid) } catch (_) {}
}
async function presenceAfter(jid) {
  if (!settings.aiPresenceFlow || !sock) return
  try { await sock.sendPresenceUpdate('paused', jid) } catch (_) {}
  try { await sock.sendPresenceUpdate('unavailable') } catch (_) {}
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
  if (settings.saveMedia || type === 'image' || type === 'video' || type === 'sticker' || (type === 'audio' && settings.aiReplyVoice)) {
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
        if (!aiExcluded(from)) {   // excluded chats: reply nothing (no keyword, no AI, no voice/image)
          const mtype = entry.media && entry.media.type
          const cmd = (text || '').trim()
          const lc = cmd.toLowerCase()
          // remember who this is (name, lid/number, language) for AI Memory + consistent replies
          touchContact(from, resolveName(msg, from), text)
          // all auto-replies are sent as a swipe-left QUOTED reply to the exact message
          const q = { quoted: msg }
          // human-like presence: read + come online + "typing" before replying (restored to offline after)
          await presenceBefore(from, msg.key)

          if (settings.aiCommandsEnabled && lc.startsWith('/create')) {
            // /create <prompt> → free unlimited text-to-image, sent back as an image
            const prompt = cmd.slice(7).trim() || 'a beautiful creative artwork, highly detailed'
            log('cmd /create:', prompt.slice(0, 60))
            const img = await generateImage(prompt)
            try {
              if (img) await sock.sendMessage(from, { image: img, caption: prompt.slice(0, 200) }, q)
              else await sock.sendMessage(from, { text: "Couldn't make the image right now, try again in a bit 🙏" }, q)
            } catch (e) { log('create send err', e?.message) }

          } else if (settings.aiCommandsEnabled && lc.startsWith('/prompt')) {
            // /prompt (with an image) → detailed image-generation prompt of that image
            if (mtype === 'image') {
              log('cmd /prompt')
              const pr = await describeAsPrompt(entry.media.name)
              try { await sock.sendMessage(from, { text: pr || "Couldn't read the image, send it again." }, q) } catch (e) { log('prompt send err', e?.message) }
            } else {
              try { await sock.sendMessage(from, { text: 'Send an image with /prompt in the caption — I will generate its detailed prompt.' }, q) } catch (e) {}
            }

          } else if (settings.aiCommandsEnabled && lc.startsWith('/voice')) {
            // /voice <text> → read the text aloud and send it back as a recording voice note
            const say = cmd.slice(6).trim()
            if (!say) {
              try { await sock.sendMessage(from, { text: 'Use: /voice <text> — I will turn it into a voice note and send it.' }, q) } catch (e) {}
            } else {
              log('cmd /voice:', say.slice(0, 60))
              const v = await synthVoice(say, settings.aiTtsVoice, ttsLangCode(say))   // auto Hindi/Bangla from the text's script
              if (v && v.buf) {
                try { await sock.sendMessage(from, { audio: v.buf, ptt: v.ptt !== false, mimetype: v.mime, seconds: estSeconds(say) }, q) }
                catch (e) { log('voice cmd send err', e?.message) }
              } else {
                try { await sock.sendMessage(from, { text: "Couldn't make the voice — check your Sarvam/Groq key in AI Voice settings." }, q) } catch (e) {}
              }
            }

          } else if (mtype === 'image' && settings.aiReplyImage) {
            // IMAGE → vision reply (own toggle, independent of the master text toggle)
            pushHistory(from, 'user', text ? text : '[image]')
            const vr = await visionReply(from, entry.media.name, text)
            if (vr) {
              try { await sock.sendMessage(from, { text: vr }, q); pushHistory(from, 'assistant', vr); log('ai image reply sent') }
              catch (e) { log('ai image reply err', e?.message) }
            }
          } else if (mtype === 'audio' && settings.aiReplyVoice) {
            // VOICE → transcribe then reply (own toggle; AI forced so master text toggle isn't required)
            const tr = await transcribeMedia(entry.media.name)
            if (tr) {
              log('voice transcribed:', tr.slice(0, 60))
              pushHistory(from, 'user', tr)
              const spoken = detectLang(tr)   // '' | 'hindi-script' | 'bangla-script'
              let reply = settings.autoReplyEnabled ? matchReply(tr) : null
              // reply in the SAME language they SPOKE (auto Hindi↔Bangla switch)
              if (!reply) reply = await aiReply(from, true, tr, spoken)
              if (reply) {
                pushHistory(from, 'assistant', reply)
                let sent = false
                // reply WITH a real recording voice note (TTS) when enabled; otherwise plain text
                if (settings.aiVoiceNoteReply) {
                  // auto language switch: speak in the reply's language (falls back to the spoken one)
                  let langOverride = ttsLangCode(reply)
                  if (!langOverride) { if (spoken === 'bangla-script') langOverride = 'bn-IN'; else if (spoken === 'hindi-script') langOverride = 'hi-IN' }
                  const v = await synthVoice(reply, settings.aiTtsVoice, langOverride)
                  if (v && v.buf) {
                    // ptt:true → sent as a "recording" voice note (not a plain audio file)
                    try { await sock.sendMessage(from, { audio: v.buf, ptt: v.ptt !== false, mimetype: v.mime, seconds: estSeconds(reply) }, q); sent = true; log('ai voice reply sent', v.mime) }
                    catch (e) { log('voice send err', e?.message) }
                  }
                }
                if (!sent) {
                  // voice note OFF, or TTS failed → text fallback (keeps the reply working)
                  try { await sock.sendMessage(from, { text: reply }, q); log('ai voice reply sent (text)') }
                  catch (e) { log('ai voice reply err', e?.message) }
                }
              }
            }
          } else if (text) {
            // TEXT → keyword rule, then AI (master toggle)
            pushHistory(from, 'user', text)
            let reply = null
            if (settings.autoReplyEnabled) reply = matchReply(text)
            if (!reply && settings.aiReplyEnabled) reply = await aiReply(from)
            if (reply) {
              try { await sock.sendMessage(from, { text: reply }, q); pushHistory(from, 'assistant', reply); log('auto-reply sent:', reply) }
              catch (e) { log('auto-reply err', e?.message) }
            }
          }
          // done replying → go back offline (only if presence flow is on)
          await presenceAfter(from)
        }
      }

      // group AI: /ai command (always) or auto-reply on greeting/question (max 10/day/group)
      const isGroup = from.endsWith('@g.us')
      if (!fromMe && isGroup && text && !aiExcluded(from)) {
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
    keepAliveIntervalMs: 25000,   // proper websocket keepalive so the socket doesn't idle-drop (stops reconnect flapping)
    connectTimeoutMs: 60000,
    retryRequestDelayMs: 500,
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
    if (first) presences.set(id, { presence: first.lastKnownPresence || 'unavailable', lastSeen: first.lastSeen || null })
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
app.get('/visiontest', async (req, res) => {
  try { res.json(await visionSelfTest()) }
  catch (e) { res.json({ ok: false, error: e?.message || 'test failed', report: [] }) }
})
// Voice check: tells the app whether Groq TTS works with the current key (or needs terms acceptance)
app.get('/voicecheck', async (req, res) => {
  try {
    const voice = String(req.query.voice || settings.aiTtsVoice || 'Arista-PlayAI')
    const opusEnc = !!(_opus && _opus.opusAvailable && _opus.opusAvailable())
    // run the REAL pipeline so we can tell the user if a true voice note will be produced
    const v = await synthVoice('Hello, this is a short voice test.', voice)
    if (v && v.buf) {
      const isVN = v.ptt === true && /ogg/i.test(v.mime || '')
      return res.json({
        ok: true, provider: voice.startsWith('sarvam:') ? 'sarvam' : 'groq', voice,
        opusEncoder: opusEnc, voiceNote: isVN, mime: v.mime, bytes: v.buf.length,
        message: isVN
          ? '✅ A real voice note will be made (OGG/Opus) — it plays directly on the recipient\'s WhatsApp. You can select this voice.'
          : ('⚠️ Audio will be made, but as a normal audio file (not a voice-note recording). ' + (opusEnc ? "The provider didn't return WAV." : "The Opus encoder didn't load.")),
      })
    }
    // failed → report the provider error
    if (voice.startsWith('sarvam:')) {
      const se = String(_sarvamErr || '')
      if (se === 'no key') return res.json({ ok: false, reason: 'nokey', message: 'Add a Sarvam key on the Voice page and Save (free: sarvam.ai).' })
      return res.json({ ok: false, reason: 'err', message: 'Sarvam TTS error:\n' + se.slice(0, 200) })
    }
    const e = String(_groqTtsErr || '')
    if (e === 'no key') return res.json({ ok: false, reason: 'nokey', message: 'Add a Groq key in API configuration and Save.' })
    if (/terms|accept|playground\?model|has not been accepted|model_terms/i.test(e)) {
      return res.json({ ok: false, reason: 'terms', message: 'You need to accept the terms once:\nconsole.groq.com/playground?model=playai-tts\nTap "Accept"/"Agree" on that page, then Check again.' })
    }
    return res.json({ ok: false, reason: 'err', message: 'Voice TTS error:\n' + (e || _sarvamErr || 'unknown').slice(0, 200) })
  } catch (e) { res.json({ ok: false, reason: 'err', message: e?.message || 'error' }) }
})
// Voice demo: synthesize a short sample in the chosen voice so the user can hear it before saving
app.get('/ttsdemo', async (req, res) => {
  try {
    const voice = String(req.query.voice || 'hi-IN-SwaraNeural')
    const text = String(req.query.text || 'Hi! This is how I will reply to your messages.')
    const v = await synthVoice(text, voice)
    if (!v || !v.buf) { res.status(502).json({ ok: false, error: 'tts failed' }); return }
    res.setHeader('Content-Type', v.mime.split(';')[0])
    res.setHeader('Cache-Control', 'no-store')
    res.send(v.buf)
  } catch (e) { res.status(500).json({ ok: false, error: e?.message || 'tts error' }) }
})
// AI Memory: list every contact the AI has talked to, newest first, with recent chat logs
app.get('/aimemory', (req, res) => {
  const items = [...contactMem.values()].sort((a, b) => (b.lastTs || 0) - (a.lastTs || 0)).map(m => ({
    jid: m.jid, name: m.name || '', lid: m.lid || '', pn: m.pn || '',
    number: String(m.pn || m.jid || '').split('@')[0].split(':')[0],
    msgCount: m.msgCount || 0, lastTs: m.lastTs || 0, lang: m.lang || '',
    recent: (chatHistory.get(m.jid) || []).slice(-10).map(h => ({ role: h.role, content: h.content, ts: h.ts || 0 })),
  }))
  res.json({ items })
})
app.post('/aimemory/clear', (req, res) => {
  const jid = (req.body && req.body.jid) || ''
  if (jid) { contactMem.delete(jid); chatHistory.delete(jid) }
  else { contactMem.clear(); chatHistory.clear() }
  saveAiMemDebounced()
  res.json({ ok: true })
})
// Translate one message into a target language (uses the configured text AI — Groq). If romanize is
// set, Hindi/other-script text is rendered in English letters (transliteration) instead of translated.
app.post('/translate', async (req, res) => {
  try {
    const text = String((req.body && req.body.text) || '').slice(0, 2000)
    const lang = String((req.body && req.body.lang) || 'English')
    const romanize = !!(req.body && req.body.romanize)
    if (!text.trim()) return res.json({ ok: false, error: 'no text' })
    if (!settings.aiApiUrl || !settings.aiApiKey) return res.json({ ok: false, error: 'Add a Groq key in API configuration first.' })
    const sys = romanize
      ? 'You are a transliteration engine. Rewrite the user message in English (Latin) letters, keeping the SAME language and words (do not translate the meaning). Output ONLY the transliterated text — no notes, keep emojis and @mentions as-is.'
      : ('You are a translation engine. Translate the user message into ' + lang + '. Output ONLY the translation text — no quotes, no notes. Keep emojis and @mentions as-is.')
    const out = await chatComplete(settings.aiModel || 'openai/gpt-oss-20b', [{ role: 'system', content: sys }, { role: 'user', content: text }])
    if (out) return res.json({ ok: true, text: out })
    return res.json({ ok: false, error: 'translation failed' })
  } catch (e) { res.json({ ok: false, error: e && e.message ? e.message : 'error' }) }
})
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
  if (typeof b.aiPresenceFlow === 'boolean') settings.aiPresenceFlow = b.aiPresenceFlow
  if (Array.isArray(b.aiExcludeJids)) settings.aiExcludeJids = b.aiExcludeJids.map(x => String(x)).filter(Boolean)
  if (typeof b.aiReplyVoice === 'boolean') settings.aiReplyVoice = b.aiReplyVoice
  if (typeof b.aiReplyImage === 'boolean') settings.aiReplyImage = b.aiReplyImage
  if (typeof b.aiVisionModel === 'string') settings.aiVisionModel = b.aiVisionModel
  if (typeof b.aiVisionApiUrl === 'string') settings.aiVisionApiUrl = b.aiVisionApiUrl
  if (typeof b.aiVisionApiKey === 'string') settings.aiVisionApiKey = b.aiVisionApiKey
  if (typeof b.aiVisionApiUrl2 === 'string') settings.aiVisionApiUrl2 = b.aiVisionApiUrl2
  if (typeof b.aiVisionApiKey2 === 'string') settings.aiVisionApiKey2 = b.aiVisionApiKey2
  if (typeof b.aiVisionModel2 === 'string') settings.aiVisionModel2 = b.aiVisionModel2
  if (typeof b.aiImageOcr === 'boolean') settings.aiImageOcr = b.aiImageOcr
  if (typeof b.aiImageFree === 'boolean') settings.aiImageFree = b.aiImageFree
  if (typeof b.aiCommandsEnabled === 'boolean') settings.aiCommandsEnabled = b.aiCommandsEnabled
  if (typeof b.aiVoiceNoteReply === 'boolean') settings.aiVoiceNoteReply = b.aiVoiceNoteReply
  if (typeof b.aiTtsVoice === 'string') settings.aiTtsVoice = b.aiTtsVoice
  if (typeof b.aiTtsModel === 'string') settings.aiTtsModel = b.aiTtsModel
  if (typeof b.aiSarvamKey === 'string') settings.aiSarvamKey = b.aiSarvamKey
  if (typeof b.aiFullContext === 'boolean') settings.aiFullContext = b.aiFullContext
  if (typeof b.aiLangMode === 'string') settings.aiLangMode = b.aiLangMode
  if (typeof b.aiReplyLang === 'string') settings.aiReplyLang = b.aiReplyLang
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
    try { fs.rmSync(MESSAGES_FILE, { force: true }) } catch (_) {}
    status = { connection: 'close', registered: false, me: null, lastError: null }
    await loadAuth(); await startSocket()
    res.json({ ok: true })
  } catch (e) { res.status(500).json({ error: e?.message }) }
})

app.use((req, res) => res.status(404).json({ ok: false, error: 'not found', path: req.path }))
app.use((err, req, res, next) => { log('http err', err?.message); res.status(500).json({ ok: false, error: err?.message || 'server error' }) })

app.listen(PORT, '127.0.0.1', async () => {
  log('server on 127.0.0.1:' + PORT, 'auth=', AUTH_DIR)
  loadSettings(); loadMessages(); loadAiMem(); await loadAuth()
  startSocket().catch(e => log('initial start err', e?.message))
})

process.on('uncaughtException', e => log('uncaughtException', e?.message))
process.on('unhandledRejection', e => log('unhandledRejection', e?.message))
