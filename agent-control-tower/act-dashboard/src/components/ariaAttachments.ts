import type { AriaAttachmentPayload } from '../api/aria';

export const ALLOWED_MIME = ['image/jpeg', 'image/png', 'image/webp'];
export const MAX_ATTACHMENTS = 5;
export const MAX_IMAGE_BYTES = 4 * 1024 * 1024;
export const MAX_TOTAL_BYTES = 10 * 1024 * 1024;
const DOWNSCALE_EDGE = 2048;
const DOWNSCALE_BYTES = 1.5 * 1024 * 1024;

export interface PendingImage {
  id: string;
  filename: string;
  mimeType: string;
  dataBase64: string;
  width: number;
  height: number;
  previewUrl: string;
  bytes: number;
}

export function rejectionFor(files: File[]): string | null {
  if (files.length > MAX_ATTACHMENTS) return `At most ${MAX_ATTACHMENTS} images per turn.`;
  for (const file of files) {
    if (!ALLOWED_MIME.includes(file.type)) {
      return 'Phase 1 supports images only (JPEG, PNG, WebP).';
    }
    if (file.size > MAX_IMAGE_BYTES) return 'Each image must be at most 4 MB.';
  }
  return null;
}

function readBase64(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const value = String(reader.result ?? '');
      resolve(value.slice(value.indexOf(',') + 1));
    };
    reader.onerror = () => reject(reader.error ?? new Error('image read failed'));
    reader.readAsDataURL(blob);
  });
}

async function loadBitmap(file: File): Promise<{ source: CanvasImageSource; width: number; height: number }> {
  const bitmap = await createImageBitmap(file);
  return { source: bitmap, width: bitmap.width, height: bitmap.height };
}

/** The redraw target: never upscale; only the over-2048 edge shrinks. */
export function redrawTarget(
  width: number,
  height: number,
  encodedBytes: number,
): { redraw: boolean; width: number; height: number } {
  const longest = Math.max(width, height);
  const redraw = longest > DOWNSCALE_EDGE || encodedBytes > DOWNSCALE_BYTES;
  if (!redraw) return { redraw, width, height };
  const scale = Math.min(1, DOWNSCALE_EDGE / longest);
  return {
    redraw,
    width: Math.max(1, Math.round(width * scale)),
    height: Math.max(1, Math.round(height * scale)),
  };
}

export async function prepareImage(file: File): Promise<PendingImage> {
  const image = await loadBitmap(file);
  const target = redrawTarget(image.width, image.height, file.size);
  let blob: Blob = file;
  let width = image.width;
  let height = image.height;
  let mimeType = file.type;
  if (target.redraw) {
    width = target.width;
    height = target.height;
    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    const context = canvas.getContext('2d');
    if (!context) throw new Error('canvas unavailable');
    context.drawImage(image.source, 0, 0, width, height);
    blob = await new Promise<Blob>((resolve, reject) =>
      canvas.toBlob((value) => (value ? resolve(value) : reject(new Error('encode failed'))),
        'image/jpeg', 0.85));
    mimeType = 'image/jpeg';
  }
  if ('close' in image.source && typeof image.source.close === 'function') image.source.close();
  return {
    id: crypto.randomUUID(),
    filename: target.redraw ? file.name.replace(/\.[^.]+$/, '') + '.jpg' : file.name,
    mimeType,
    dataBase64: await readBase64(blob),
    width,
    height,
    previewUrl: URL.createObjectURL(blob),
    bytes: blob.size,
  };
}

export function toPayload(images: PendingImage[]): AriaAttachmentPayload[] {
  return images.map((image) => ({
    mimeType: image.mimeType,
    filename: image.filename,
    dataBase64: image.dataBase64,
    width: image.width,
    height: image.height,
  }));
}
