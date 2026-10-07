// opus.js — turn a WAV (PCM) buffer into a real OGG/Opus file, the exact format WhatsApp uses for
// voice notes. This makes the AI reply play as a proper "recording" voice note on the recipient's
// phone, regardless of what the TTS provider returned (Sarvam/Groq give WAV/MP3, not OGG-Opus).
//
// Pure JS + opusscript (WASM, no native build) so it runs inside nodejs-mobile. If opusscript can't
// load, wavToOggOpus throws and the caller falls back to sending a normal audio message.

let _Opus = null
let _opusTried = false
function getOpus() {
  if (_opusTried) return _Opus
  _opusTried = true
  try { _Opus = require('opusscript') } catch (e) { _Opus = null }
  return _Opus
}
function opusAvailable() { return !!getOpus() }

// ---- WAV parsing (16-bit PCM) ----
function parseWav(buf) {
  if (buf.length < 44 || buf.toString('latin1', 0, 4) !== 'RIFF' || buf.toString('latin1', 8, 12) !== 'WAVE') {
    throw new Error('not a WAV file')
  }
  let off = 12, fmt = null, dataOff = -1, dataLen = 0
  while (off + 8 <= buf.length) {
    const id = buf.toString('latin1', off, off + 4)
    const sz = buf.readUInt32LE(off + 4)
    const body = off + 8
    if (id === 'fmt ') {
      fmt = {
        audioFormat: buf.readUInt16LE(body),
        channels: buf.readUInt16LE(body + 2),
        sampleRate: buf.readUInt32LE(body + 4),
        bits: buf.readUInt16LE(body + 14),
      }
    } else if (id === 'data') {
      dataOff = body
      dataLen = Math.min(sz, buf.length - body)
      break
    }
    off = body + sz + (sz & 1)
  }
  if (!fmt || dataOff < 0) throw new Error('WAV missing fmt/data')
  if (fmt.audioFormat !== 1 || fmt.bits !== 16) throw new Error('WAV not 16-bit PCM')
  // bytes -> Int16 samples (interleaved)
  const n = (dataLen - (dataLen % 2)) / 2
  const inter = new Int16Array(n)
  for (let i = 0; i < n; i++) inter[i] = buf.readInt16LE(dataOff + i * 2)
  // downmix to mono
  let mono
  if (fmt.channels === 2) {
    const m = (n / 2) | 0
    mono = new Int16Array(m)
    for (let i = 0; i < m; i++) mono[i] = (inter[i * 2] + inter[i * 2 + 1]) >> 1
  } else {
    mono = inter
  }
  return { sampleRate: fmt.sampleRate, samples: mono }
}

// linear resample to 48000 Hz mono (Opus/WhatsApp standard)
function resample48k(samples, inRate) {
  if (inRate === 48000) return samples
  const ratio = 48000 / inRate
  const outLen = Math.max(1, Math.floor(samples.length * ratio))
  const out = new Int16Array(outLen)
  const last = samples.length - 1
  for (let i = 0; i < outLen; i++) {
    const pos = i / ratio
    const i0 = Math.floor(pos)
    const i1 = i0 < last ? i0 + 1 : last
    const frac = pos - i0
    out[i] = (samples[i0] * (1 - frac) + samples[i1] * frac) | 0
  }
  return out
}

// ---- OGG muxing ----
const CRC_TABLE = (() => {
  const t = new Uint32Array(256)
  for (let i = 0; i < 256; i++) {
    let r = (i << 24) >>> 0
    for (let j = 0; j < 8; j++) r = (r & 0x80000000) ? (((r << 1) ^ 0x04c11db7) >>> 0) : ((r << 1) >>> 0)
    t[i] = r >>> 0
  }
  return t
})()
function oggCrc(buf) {
  let crc = 0
  for (let i = 0; i < buf.length; i++) crc = (((crc << 8) >>> 0) ^ CRC_TABLE[((crc >>> 24) ^ buf[i]) & 0xff]) >>> 0
  return crc >>> 0
}
function writeUInt64LE(buf, value, offset) {
  const lo = value >>> 0
  const hi = Math.floor(value / 4294967296) >>> 0
  buf.writeUInt32LE(lo, offset)
  buf.writeUInt32LE(hi, offset + 4)
}
// one page carrying exactly one packet (fine for short voice notes)
function oggPage(serial, seq, headerType, granule, packet) {
  const laces = []
  let len = packet.length
  while (len >= 255) { laces.push(255); len -= 255 }
  laces.push(len)
  const header = Buffer.alloc(27 + laces.length)
  header.write('OggS', 0, 'latin1')
  header.writeUInt8(0, 4)                 // stream structure version
  header.writeUInt8(headerType, 5)        // 0x02 BOS, 0x04 EOS, 0x00 normal
  writeUInt64LE(header, granule, 6)       // granule position (48k samples)
  header.writeUInt32LE(serial >>> 0, 14)
  header.writeUInt32LE(seq >>> 0, 18)
  header.writeUInt32LE(0, 22)             // CRC placeholder
  header.writeUInt8(laces.length, 26)
  for (let i = 0; i < laces.length; i++) header.writeUInt8(laces[i], 27 + i)
  const page = Buffer.concat([header, packet])
  page.writeUInt32LE(oggCrc(page), 22)
  return page
}
function opusHead(channels, preSkip, inputRate) {
  const b = Buffer.alloc(19)
  b.write('OpusHead', 0, 'latin1')
  b.writeUInt8(1, 8)                 // version
  b.writeUInt8(channels, 9)
  b.writeUInt16LE(preSkip, 10)
  b.writeUInt32LE(inputRate, 12)     // original input sample rate (informational)
  b.writeUInt16LE(0, 16)             // output gain
  b.writeUInt8(0, 18)                // channel mapping family 0
  return b
}
function opusTags() {
  const vendor = Buffer.from('WhatsAyX', 'utf8')
  const b = Buffer.alloc(8 + 4 + vendor.length + 4)
  b.write('OpusTags', 0, 'latin1')
  b.writeUInt32LE(vendor.length, 8)
  vendor.copy(b, 12)
  b.writeUInt32LE(0, 12 + vendor.length)   // zero user comments
  return b
}

// WAV buffer -> OGG/Opus buffer (mono, 48k). Throws if opus can't run or input is unusable.
function wavToOggOpus(wavBuf, opts) {
  const Opus = getOpus()
  if (!Opus) throw new Error('opusscript unavailable')
  const { sampleRate, samples } = parseWav(wavBuf)
  const pcm = resample48k(samples, sampleRate)
  const FRAME = 960                 // 20 ms @ 48 kHz
  const PRESKIP = 312               // typical Opus lookahead
  const bitrate = (opts && opts.bitrate) || 24000
  const enc = new Opus(48000, 1, Opus.Application && Opus.Application.AUDIO ? Opus.Application.AUDIO : 2049)
  try { if (enc.setBitrate) enc.setBitrate(bitrate) } catch (e) {}
  const serial = (Math.random() * 0xffffffff) >>> 0
  const pages = [
    oggPage(serial, 0, 0x02, 0, opusHead(1, PRESKIP, 48000)),  // BOS: OpusHead
    oggPage(serial, 1, 0x00, 0, opusTags()),                   // OpusTags
  ]
  let seq = 2
  let granule = 0
  const total = Math.ceil(pcm.length / FRAME)
  if (total === 0) throw new Error('empty audio')
  const frameBuf = Buffer.alloc(FRAME * 2)
  for (let f = 0; f < total; f++) {
    const start = f * FRAME
    frameBuf.fill(0)
    for (let i = 0; i < FRAME; i++) {
      const s = start + i
      frameBuf.writeInt16LE(s < pcm.length ? pcm[s] : 0, i * 2)
    }
    const packet = enc.encode(frameBuf, FRAME)   // standard Opus packet
    granule += FRAME
    const isLast = f === total - 1
    pages.push(oggPage(serial, seq++, isLast ? 0x04 : 0x00, granule, packet))
  }
  try { if (enc.delete) enc.delete() } catch (e) {}
  return Buffer.concat(pages)
}

module.exports = { wavToOggOpus, opusAvailable, parseWav, resample48k, oggPage, oggCrc, opusHead, opusTags }
