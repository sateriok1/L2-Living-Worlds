#!/usr/bin/env python3
"""Convert clan-crest PNG sources to the DDS (DXT1) texture format the Lineage 2
Interlude client requires for pledge, ally, and large crests.

Why this exists
---------------
The game server relays clan-crest bytes to the client untouched (see
CrestTable / PledgeCrest). The Interlude client cannot decode a PNG as a crest;
it decodes a DDS DXT1 texture, the same format it produces itself when a player
uploads a BMP through the in-game crest dialog. Shipping PNGs under
data/crests/<set>/ therefore loads fine server-side (each bot clan gets a crest
id) but renders as nothing on the client.

This tool reads every *.png crest under the target directory and writes a *.dds
sibling next to it. The PNGs stay as the editable design source; the .dds files
are what BotClanManager loads at runtime (pledgeCrestFile="pledge_16x12.dds").

Requirements
------------
Python 3 and Pillow (pip install Pillow). A minimal DXT1 encoder is implemented
inline so no native DDS library is needed.

Usage
-----
    python3 tools/crest_png_to_dds.py [crests_dir]

Defaults to ../dist/game/data/crests relative to this script.

The client rejects crests whose dimensions are not powers of two, so each
image is padded up to the next power of two before encoding (16x12 -> 16x16,
8x12 -> 8x16, 24x12 -> 32x16). The padding is written as fully transparent
DXT1 blocks (the crest dimensions are multiples of 4, so padding is always whole
blocks and never touches the crest's own blocks). It used to be edge-replicated,
which drew as a coloured line under the crest wherever the client showed more
than the crest region.

Note: the small pledge crest is capped at 256 bytes on the wire
(RequestSetPledgeCrest, check is `> 256`). A 16x16 DXT1 crest is exactly
256 bytes, at the cap.
"""
import struct
import sys
from pathlib import Path

from PIL import Image


def _rgb_to_565(r, g, b):
    return ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3)


def _c565_to_rgb(c):
    r = (c >> 11) & 0x1F
    g = (c >> 5) & 0x3F
    b = c & 0x1F
    return (r << 3) | (r >> 2), (g << 2) | (g >> 4), (b << 3) | (b >> 2)


def _encode_dxt1_block(pixels):
    """pixels: 16 (r,g,b) tuples in row-major 4x4 order. Returns 8 bytes."""
    rs = [p[0] for p in pixels]
    gs = [p[1] for p in pixels]
    bs = [p[2] for p in pixels]
    c0 = _rgb_to_565(max(rs), max(gs), max(bs))
    c1 = _rgb_to_565(min(rs), min(gs), min(bs))

    # 4-colour (opaque) mode requires c0 > c1.
    if c0 == c1:
        if c0 > 0:
            c1 = c0 - 1
        else:
            c0 = 1
    elif c0 < c1:
        c0, c1 = c1, c0

    p0 = _c565_to_rgb(c0)
    p1 = _c565_to_rgb(c1)
    p2 = tuple((2 * a + b) // 3 for a, b in zip(p0, p1))
    p3 = tuple((a + 2 * b) // 3 for a, b in zip(p0, p1))
    palette = (p0, p1, p2, p3)

    indices = 0
    for i, px in enumerate(pixels):
        best_j, best_d = 0, None
        for j, pj in enumerate(palette):
            d = (px[0] - pj[0]) ** 2 + (px[1] - pj[1]) ** 2 + (px[2] - pj[2]) ** 2
            if best_d is None or d < best_d:
                best_d, best_j = d, j
        indices |= best_j << (2 * i)

    return struct.pack("<HHI", c0, c1, indices)


# DXT1 block with both colours 0 (c0 <= c1 selects 3-colour + transparent mode) and every index 3 = transparent.
_TRANSPARENT_BLOCK = struct.pack("<HHI", 0, 0, 0xFFFFFFFF)


def _next_pow2(n):
    p = 1
    while p < n:
        p <<= 1
    return p


def _pad_pow2(img):
    """The client rejects a crest whose dimensions are not powers of two, so pad
    up to the next power of two (e.g. 16x12 -> 16x16). The padding pixels are
    black here but are never encoded: _encode_dxt1 writes transparent blocks for
    every block that lies wholly outside the crest."""
    w, h = img.size
    nw, nh = _next_pow2(w), _next_pow2(h)
    if (nw, nh) == (w, h):
        return img, w, h
    canvas = Image.new("RGB", (nw, nh))
    canvas.paste(img, (0, 0))
    return canvas, w, h


def _encode_dxt1(img):
    img = img.convert("RGB")
    img, src_w, src_h = _pad_pow2(img)
    w, h = img.size
    if w % 4 != 0 or h % 4 != 0:
        raise ValueError(f"padded dimensions {w}x{h} must be multiples of 4 for DXT1")
    px = img.load()
    out = bytearray()
    for by in range(0, h, 4):
        for bx in range(0, w, 4):
            if bx >= src_w or by >= src_h:
                out += _TRANSPARENT_BLOCK  # padding: nothing to draw
                continue
            block = [px[bx + x, by + y] for y in range(4) for x in range(4)]
            out += _encode_dxt1_block(block)
    return bytes(out), w, h


def _build_dds(dxt1_data, w, h):
    DDSD_CAPS = 0x1
    DDSD_HEIGHT = 0x2
    DDSD_WIDTH = 0x4
    DDSD_PIXELFORMAT = 0x1000
    DDSD_LINEARSIZE = 0x80000
    flags = DDSD_CAPS | DDSD_HEIGHT | DDSD_WIDTH | DDSD_PIXELFORMAT | DDSD_LINEARSIZE

    header = bytearray()
    header += b"DDS "
    header += struct.pack("<I", 124)             # dwSize
    header += struct.pack("<I", flags)           # dwFlags
    header += struct.pack("<I", h)               # dwHeight
    header += struct.pack("<I", w)               # dwWidth
    header += struct.pack("<I", len(dxt1_data))  # dwPitchOrLinearSize
    header += struct.pack("<I", 0)               # dwDepth
    header += struct.pack("<I", 0)               # dwMipMapCount
    header += b"\x00" * (11 * 4)                 # dwReserved1[11]
    # DDS_PIXELFORMAT (32 bytes)
    header += struct.pack("<I", 32)              # dwSize
    header += struct.pack("<I", 0x4)             # dwFlags = DDPF_FOURCC
    header += b"DXT1"                             # dwFourCC
    header += struct.pack("<I", 0) * 5           # bit count + 4 masks
    # caps
    header += struct.pack("<I", 0x1000)          # dwCaps = DDSCAPS_TEXTURE
    header += struct.pack("<I", 0) * 4           # dwCaps2..4, dwReserved2
    assert len(header) == 128, len(header)
    return bytes(header) + dxt1_data


def convert(png_path):
    data, w, h = _encode_dxt1(Image.open(png_path))
    dds = _build_dds(data, w, h)
    out = png_path.with_suffix(".dds")
    out.write_bytes(dds)
    return out, len(dds), w, h


def main(argv):
    if len(argv) > 1:
        root = Path(argv[1])
    else:
        root = Path(__file__).resolve().parent.parent / "dist" / "game" / "data" / "crests"
    if not root.is_dir():
        print(f"Crests directory not found: {root}")
        return 1
    pngs = sorted(root.rglob("*.png"))
    if not pngs:
        print(f"No PNG crests under {root}")
        return 1
    for p in pngs:
        out, size, w, h = convert(p)
        warn = "  <-- over 256B pledge cap" if (p.name == "pledge_16x12.png" and size > 256) else ""
        print(f"{p.relative_to(root)} -> {out.name}  {w}x{h}  {size} bytes{warn}")
    print(f"\nConverted {len(pngs)} crest(s) under {root}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
