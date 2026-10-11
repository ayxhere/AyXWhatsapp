// Media handling: magic-byte (content sniffing) validation and orphan cleanup.
//
// SECURITY: the client-supplied MIME type is never trusted on its own. Every
// upload is validated by (1) declared MIME allowlist, (2) actual file magic
// bytes, (3) size limit. Mismatches are rejected with 422 and the temp file
// is deleted.

import { createHash } from 'node:crypto';
import { createReadStream, promises as fs } from 'node:fs';
import path from 'node:path';
import { get, all, insertRow, updateById, deleteById, newId, nowIso } from '../db.js';
import { config } from '../config.js';

export const ALLOWED_MIME = new Set([
  'image/jpeg',
  'image/png',
  'image/gif',
  'image/webp',
  'video/mp4',
  'video/webm',
  'video/quicktime',
]);

const EXT_BY_MIME = {
  'image/jpeg': 'jpg',
  'image/png': 'png',
  'image/gif': 'gif',
  'image/webp': 'webp',
  'video/mp4': 'mp4',
  'video/webm': 'webm',
  'video/quicktime': 'mov',
};

async function readHead(filePath, n = 32) {
  const fh = await fs.open(filePath, 'r');
  try {
    const buf = Buffer.alloc(n);
    const { bytesRead } = await fh.read(buf, 0, n, 0);
    return buf.subarray(0, bytesRead);
  } finally {
    await fh.close();
  }
}

function startsWith(buf, bytes) {
  return bytes.every((b, i) => buf[i] === b);
}

// Detect the real container format from magic bytes. Returns a canonical
// mime or null when the content is not a supported image/video.
export function sniffMime(head) {
  if (head.length < 12) return null;
  if (startsWith(head, [0xff, 0xd8, 0xff])) return 'image/jpeg';
  if (startsWith(head, [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])) return 'image/png';
  if (startsWith(head, [0x47, 0x49, 0x46, 0x38])) return 'image/gif';
  // WebP: RIFF....WEBP
  if (
    head[0] === 0x52 && head[1] === 0x49 && head[2] === 0x46 && head[3] === 0x46 &&
    head[8] === 0x57 && head[9] === 0x45 && head[10] === 0x42 && head[11] === 0x50
  ) {
    return 'image/webp';
  }
  // MP4/MOV: ....ftyp
  if (head[4] === 0x66 && head[5] === 0x74 && head[6] === 0x79 && head[7] === 0x70) {
    return head.toString('ascii', 8, 12).includes('qt') ? 'video/quicktime' : 'video/mp4';
  }
  // WebM/Matroska: 1A 45 DF A3
  if (startsWith(head, [0x1a, 0x45, 0xdf, 0xa3])) return 'video/webm';
  return null;
}

export async function sha256File(filePath) {
  const hash = createHash('sha256');
  await new Promise((resolve, reject) => {
    const s = createReadStream(filePath);
    s.on('data', (d) => hash.update(d));
    s.on('end', resolve);
    s.on('error', reject);
  });
  return hash.digest('hex');
}

export function uploadDir() {
  return path.resolve(config.uploadDir);
}

export async function ensureUploadDir() {
  await fs.mkdir(uploadDir(), { recursive: true });
  await fs.mkdir(path.join(uploadDir(), 'tmp'), { recursive: true });
}

export function tmpDir() {
  return path.join(uploadDir(), 'tmp');
}

/**
 * Validate a multer temp file and promote it into the media library.
 * Returns the MediaAsset row. Throws { status, code, message } on failure.
 */
export async function ingestUpload({ tmpPath, originalName, declaredMime, sizeBytes, createdBy }) {
  const fail = async (code, message, status = 422) => {
    await fs.unlink(tmpPath).catch(() => {});
    const err = new Error(message);
    err.status = status;
    err.code = code;
    throw err;
  };

  if (!ALLOWED_MIME.has(declaredMime)) {
    await fail('unsupported_media_type', `MIME type not allowed: ${declaredMime}`);
  }

  const head = await readHead(tmpPath);
  const sniffed = sniffMime(head);
  if (!sniffed) {
    await fail('invalid_media_content', 'File content is not a supported image or video.');
  }
  // Declared MIME must agree with the sniffed content (same family at least).
  const declaredFamily = declaredMime.split('/')[0];
  const sniffedFamily = sniffed.split('/')[0];
  if (declaredFamily !== sniffedFamily) {
    await fail('mime_mismatch', 'Declared MIME type does not match file content.');
  }

  const digest = await sha256File(tmpPath);

  // Content dedupe: reuse an existing asset with identical bytes.
  const existing = get(
    'media_assets',
    'SELECT * FROM media_assets WHERE sha256 = ? ORDER BY created_at ASC LIMIT 1',
    [digest]
  );
  if (existing) {
    await fs.unlink(tmpPath).catch(() => {});
    return existing;
  }

  const ext = EXT_BY_MIME[sniffed] ?? 'bin';
  const assetId = newId();
  insertRow('media_assets', {
    id: assetId,
    filename: 'pending',
    original_name: String(originalName).slice(0, 255),
    mime: sniffed,
    size_bytes: sizeBytes,
    sha256: digest,
    created_by: createdBy ?? null,
    created_at: nowIso(),
  });

  const finalName = `${assetId}.${ext}`;
  await fs.rename(tmpPath, path.join(uploadDir(), finalName));
  return updateById('media_assets', assetId, { filename: finalName });
}

export async function deleteAssetFile(asset) {
  const p = path.join(uploadDir(), asset.filename);
  await fs.unlink(p).catch(() => {});
}

/**
 * Orphan cleanup: delete media_assets that are not referenced by any status
 * and are older than 1 hour (grace period for in-flight uploads).
 * Returns the number of assets removed.
 */
export async function cleanupOrphanMedia() {
  const oneHourAgo = new Date(Date.now() - 60 * 60 * 1000);
  const orphans = all(
    'media_assets',
    `SELECT * FROM media_assets
      WHERE created_at < ?
        AND NOT EXISTS (SELECT 1 FROM statuses WHERE media_id = media_assets.id)`,
    [oneHourAgo]
  );
  let removed = 0;
  for (const asset of orphans) {
    await deleteAssetFile(asset);
    deleteById('media_assets', asset.id);
    removed++;
  }
  return removed;
}
