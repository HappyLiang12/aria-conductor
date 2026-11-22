import { describe, expect, it } from 'vitest';
import { ALLOWED_MIME, MAX_ATTACHMENTS, rejectionFor } from '../ariaAttachments';

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
});
