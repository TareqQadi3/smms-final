/**
 * Client-side JPEG compression for site photos: the result is at most 2 MB (the phone camera
 * produces 3-10 MB), scaled to at most 2048 px on the long side, EXIF orientation applied.
 */

export const MAX_PHOTO_BYTES = 2 * 1024 * 1024;
export const MAX_DIMENSION = 2048;

export class PhotoError extends Error {
  constructor(public readonly code: 'not-image' | 'unsupported' | 'too-large') {
    super(code);
    this.name = 'PhotoError';
  }
}

export type Encoder = (width: number, height: number, quality: number) => Promise<Blob>;

export interface FitOptions {
  maxBytes?: number;
  maxDimension?: number;
  startQuality?: number;
  minQuality?: number;
  qualityStep?: number;
  /** Each failed quality sweep scales the picture by this factor. */
  scaleStep?: number;
  minDimension?: number;
}

export interface FitResult { blob: Blob; width: number; height: number; quality: number }

/**
 * Finds the best JPEG that fits in `maxBytes`: first lower the quality, then the resolution.
 * Pure (the encoder is injected) so it is testable without a canvas.
 */
export async function fitUnderLimit(
  size: { width: number; height: number },
  encode: Encoder,
  opts: FitOptions = {}
): Promise<FitResult> {
  const maxBytes = opts.maxBytes ?? MAX_PHOTO_BYTES;
  const maxDim = opts.maxDimension ?? MAX_DIMENSION;
  const start = opts.startQuality ?? 0.85;
  const minQ = opts.minQuality ?? 0.5;
  const qStep = opts.qualityStep ?? 0.1;
  const scaleStep = opts.scaleStep ?? 0.8;
  const minDim = opts.minDimension ?? 320;

  let scale = Math.min(1, maxDim / Math.max(size.width, size.height));
  for (let round = 0; round < 12; round++) {
    const w = Math.max(1, Math.round(size.width * scale));
    const h = Math.max(1, Math.round(size.height * scale));
    for (let q = start; q >= minQ - 1e-9; q -= qStep) {
      const quality = Math.round(q * 100) / 100;
      const blob = await encode(w, h, quality);
      if (blob.size <= maxBytes) return { blob, width: w, height: h, quality };
    }
    if (Math.max(w, h) * scaleStep < minDim) break;
    scale *= scaleStep;
  }
  throw new PhotoError('too-large');
}

export interface DecodedImage {
  width: number;
  height: number;
  /** Draws the image (already rotated) into a canvas context of the given size. */
  draw: (ctx: CanvasRenderingContext2D, width: number, height: number) => void;
  close?: () => void;
}

export interface ImageDeps {
  decode: (file: File) => Promise<DecodedImage>;
  makeEncoder: (img: DecodedImage) => Encoder;
}

async function decodeInBrowser(file: File): Promise<DecodedImage> {
  if (typeof createImageBitmap === 'function') {
    try {
      const bmp = await createImageBitmap(file, { imageOrientation: 'from-image' });
      return {
        width: bmp.width,
        height: bmp.height,
        draw: (ctx, w, h) => ctx.drawImage(bmp, 0, 0, w, h),
        close: () => bmp.close(),
      };
    } catch { /* fall through to <img> */ }
  }
  const url = URL.createObjectURL(file);
  try {
    const img = await new Promise<HTMLImageElement>((resolve, reject) => {
      const el = new Image();
      el.onload = () => resolve(el);
      el.onerror = () => reject(new PhotoError('unsupported'));
      el.src = url;
    });
    return { width: img.naturalWidth, height: img.naturalHeight, draw: (ctx, w, h) => ctx.drawImage(img, 0, 0, w, h) };
  } finally {
    URL.revokeObjectURL(url);
  }
}

function canvasEncoder(img: DecodedImage): Encoder {
  return (width, height, quality) =>
    new Promise<Blob>((resolve, reject) => {
      const canvas = document.createElement('canvas');
      canvas.width = width;
      canvas.height = height;
      const ctx = canvas.getContext('2d');
      if (!ctx) { reject(new PhotoError('unsupported')); return; }
      ctx.fillStyle = '#fff'; // JPEG has no alpha: transparent PNGs would turn black
      ctx.fillRect(0, 0, width, height);
      img.draw(ctx, width, height);
      canvas.toBlob((b) => (b ? resolve(b) : reject(new PhotoError('unsupported'))), 'image/jpeg', quality);
    });
}

const browserDeps: ImageDeps = { decode: decodeInBrowser, makeEncoder: canvasEncoder };

function baseName(name: string): string {
  const stem = name.replace(/\.[^./\\]+$/, '').replace(/[^\w.-]+/g, '_');
  return stem || 'photo';
}

/**
 * Turns a camera/gallery file into a JPEG <= 2 MB named `<name>.jpg` (the backend checks the
 * extension against the file's magic bytes, and JPEG matches). Falls back to the original file only
 * when it cannot be decoded but is already a small JPEG/PNG.
 */
export async function compressImage(file: File, opts: FitOptions = {}, deps: ImageDeps = browserDeps): Promise<File> {
  if (!file.type.startsWith('image/')) throw new PhotoError('not-image');
  const maxBytes = opts.maxBytes ?? MAX_PHOTO_BYTES;
  let decoded: DecodedImage;
  try {
    decoded = await deps.decode(file);
  } catch {
    if (file.size <= maxBytes && (file.type === 'image/jpeg' || file.type === 'image/png')) return file;
    throw new PhotoError('unsupported');
  }
  try {
    const { blob } = await fitUnderLimit(decoded, deps.makeEncoder(decoded), opts);
    return new File([blob], `${baseName(file.name)}.jpg`, { type: 'image/jpeg', lastModified: Date.now() });
  } finally {
    decoded.close?.();
  }
}
