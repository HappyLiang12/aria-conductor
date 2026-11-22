import { describe, expect, it } from 'vitest';
import { ALLOWED_MIME, MAX_ATTACHMENTS, redrawTarget, rejectionFor } from '../ariaAttachments';

function file(name: string, type: string, size = 1024): File {
  return new File([new Uint8Array(size)], name, { type });
}

describe('ariaAttachments', () => {
  it('accepts the three image types within the limits', () => {
    expect(ALLOWED_MIME).toEqual(['image/jpeg', 'image/png', 'image/webp']);
    expect(rejectionFor([file('a.jpg', 'image/jpeg'), file('b.png', 'image/png')])).toBeNull();
  });

  it('rejects documents with the phase-1 copy', () => {
    expect(rejectionFor([file('d.pdf', 'application/pdf')]))
      .toBe('Phase 1 supports images only (JPEG, PNG, WebP).');
  });

  it('rejects more than five images', () => {
    const files = Array.from({ length: MAX_ATTACHMENTS + 1 }, (_, i) => file(`i${i}.jpg`, 'image/jpeg'));
    expect(rejectionFor(files)).toBe('At most 5 images per turn.');
  });

  it('rejects an image over four mebibytes before downscaling', () => {
    expect(rejectionFor([file('big.jpg', 'image/jpeg', 4 * 1024 * 1024 + 1)]))
      .toBe('Each image must be at most 4 MB.');
  });

  it('shrinks only the over-2048 edge', () => {
    expect(redrawTarget(4096, 1024, 1024)).toEqual({ redraw: true, width: 2048, height: 512 });
  });

  it('re-encodes a heavy small image without upscaling it', () => {
    expect(redrawTarget(1500, 1000, 2 * 1024 * 1024)).toEqual({
      redraw: true, width: 1500, height: 1000,
    });
  });

  it('keeps a light in-range image untouched', () => {
    expect(redrawTarget(1200, 800, 1024)).toEqual({ redraw: false, width: 1200, height: 800 });
  });
});
