import { describe, expect, it } from 'vitest';
import { MAX_PHOTO_BYTES, PhotoError, compressImage, fitUnderLimit, type DecodedImage, type Encoder } from './image';

/** Fake JPEG encoder: size grows with the pixel count and the quality. */
const fake = (bytesPerPixelAtQ1: number): Encoder => async (w, h, q) =>
  new Blob([new Uint8Array(Math.round(w * h * q * bytesPerPixelAtQ1))], { type: 'image/jpeg' });

describe('fitUnderLimit', () => {
  it('keeps full size and 0.85 quality when the photo already fits', async () => {
    const r = await fitUnderLimit({ width: 1000, height: 800 }, fake(1));
    expect(r).toMatchObject({ width: 1000, height: 800, quality: 0.85 });
    expect(r.blob.size).toBeLessThanOrEqual(MAX_PHOTO_BYTES);
  });

  it('caps the long side at 2048 px', async () => {
    const r = await fitUnderLimit({ width: 4000, height: 3000 }, fake(0.1));
    expect(Math.max(r.width, r.height)).toBe(2048);
    expect(r.height).toBe(1536);
  });

  it('lowers the quality first, then the resolution, until it is at most 2 MB', async () => {
    // 4000x3000 capped to 2048x1536 = 3.1 MP; at 1 byte/px and q 0.85 that is 2.67 MB: quality must drop
    const r = await fitUnderLimit({ width: 4000, height: 3000 }, fake(1));
    expect(r.blob.size).toBeLessThanOrEqual(MAX_PHOTO_BYTES);
    expect(r.width).toBe(2048);
    expect(r.quality).toBeLessThan(0.85);
    // heavier content forces a smaller resolution as well
    const heavy = await fitUnderLimit({ width: 4000, height: 3000 }, fake(3));
    expect(heavy.blob.size).toBeLessThanOrEqual(MAX_PHOTO_BYTES);
    expect(heavy.width).toBeLessThan(2048);
  });

  it('gives up with too-large when nothing fits', async () => {
    const sizeOnly: Encoder = async (w, h) => ({ size: w * h * 1000 }) as Blob;
    await expect(fitUnderLimit({ width: 4000, height: 3000 }, sizeOnly)).rejects.toMatchObject({ code: 'too-large' });
  });
});

describe('compressImage', () => {
  const decoded = (w: number, h: number): DecodedImage => ({ width: w, height: h, draw: () => {} });

  it('returns a .jpg file of image/jpeg at most 2 MB', async () => {
    const file = new File([new Uint8Array(6_000_000)], 'IMG 0042.HEIC', { type: 'image/heic' });
    const out = await compressImage(file, {}, { decode: async () => decoded(4032, 3024), makeEncoder: () => fake(1) });
    expect(out.name).toBe('IMG_0042.jpg');
    expect(out.type).toBe('image/jpeg');
    expect(out.size).toBeLessThanOrEqual(MAX_PHOTO_BYTES);
  });

  it('rejects non-images', async () => {
    const file = new File(['x'], 'a.pdf', { type: 'application/pdf' });
    await expect(compressImage(file, {}, { decode: async () => decoded(1, 1), makeEncoder: () => fake(1) })).rejects.toBeInstanceOf(PhotoError);
  });

  it('passes a small undecodable JPEG through, rejects a large undecodable one', async () => {
    const deps = { decode: async () => { throw new Error('no decoder'); }, makeEncoder: () => fake(1) };
    const small = new File([new Uint8Array(1000)], 'a.jpg', { type: 'image/jpeg' });
    expect(await compressImage(small, {}, deps)).toBe(small);
    const big = new File([new Uint8Array(3_000_000)], 'b.jpg', { type: 'image/jpeg' });
    await expect(compressImage(big, {}, deps)).rejects.toMatchObject({ code: 'unsupported' });
  });
});
