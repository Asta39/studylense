/*
 * Liquid glass for the StudyLens header pill.
 *
 * Adapted from "liquid-glass" by Shu Ding — https://github.com/shuding/liquid-glass
 * MIT License · Copyright (c) 2025 Shu Ding
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions: The above copyright notice and this
 * permission notice shall be included in all copies or substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED.
 *
 * How it works: we draw a displacement map for the pill's exact shape (a rounded-rect signed
 * distance field), so pixels near the rim are pulled inward like a lens. The map feeds an SVG
 * feDisplacementMap that the pill uses as its backdrop-filter. Only Chromium renders SVG filters
 * in backdrop-filter, so other browsers keep the frosted CSS fallback in styles.css.
 */
(function () {
  const pill = document.querySelector('[data-liquid-glass]');
  if (!pill || !window.chrome) return; // Chromium only; others keep the CSS frosted glass

  const NS = 'http://www.w3.org/2000/svg';
  const id = 'lg-' + Math.random().toString(36).slice(2, 8);

  const svg = document.createElementNS(NS, 'svg');
  svg.setAttribute('width', '0'); svg.setAttribute('height', '0');
  svg.setAttribute('aria-hidden', 'true');
  svg.style.cssText = 'position:absolute;width:0;height:0;pointer-events:none';
  const filter = document.createElementNS(NS, 'filter');
  filter.setAttribute('id', id);
  filter.setAttribute('filterUnits', 'userSpaceOnUse');
  filter.setAttribute('color-interpolation-filters', 'sRGB');
  filter.setAttribute('x', '0'); filter.setAttribute('y', '0');
  const feImage = document.createElementNS(NS, 'feImage');
  feImage.setAttribute('result', 'map');
  const disp = document.createElementNS(NS, 'feDisplacementMap');
  disp.setAttribute('in', 'SourceGraphic'); disp.setAttribute('in2', 'map');
  disp.setAttribute('xChannelSelector', 'R'); disp.setAttribute('yChannelSelector', 'G');
  filter.append(feImage, disp);
  svg.append(filter);
  document.body.append(svg);

  const smooth = (a, b, t) => { t = Math.max(0, Math.min(1, (t - a) / (b - a))); return t * t * (3 - 2 * t); };
  const canvas = document.createElement('canvas');
  const ctx = canvas.getContext('2d');

  function build() {
    const w = Math.round(pill.offsetWidth), h = Math.round(pill.offsetHeight);
    if (!w || !h) return;
    canvas.width = w; canvas.height = h;
    const r = h / 2, rim = h * 0.55;            // radius of the pill and how far the lens reaches in
    const raw = new Float32Array(w * h * 2);
    let max = 0;
    for (let y = 0; y < h; y++) {
      for (let x = 0; x < w; x++) {
        const px = x + 0.5 - w / 2, py = y + 0.5 - h / 2;
        // signed distance to the pill's edge (negative inside)
        const qx = Math.abs(px) - (w / 2 - r), qy = Math.abs(py) - (h / 2 - r);
        const sdf = Math.min(Math.max(qx, qy), 0) + Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) - r;
        const k = smooth(rim, 0, -sdf);          // 0 in the middle → 1 at the rim
        const pull = 1 - 0.32 * k * k;           // sample nearer the centre near the rim (magnify)
        const dx = px * pull - px, dy = py * pull - py;
        const i = (y * w + x) * 2;
        raw[i] = dx; raw[i + 1] = dy;
        max = Math.max(max, Math.abs(dx), Math.abs(dy));
      }
    }
    max = max || 1;
    const img = ctx.createImageData(w, h);
    for (let p = 0, i = 0; p < img.data.length; p += 4, i += 2) {
      img.data[p] = (raw[i] / max * 0.5 + 0.5) * 255;
      img.data[p + 1] = (raw[i + 1] / max * 0.5 + 0.5) * 255;
      img.data[p + 2] = 0; img.data[p + 3] = 255;
    }
    ctx.putImageData(img, 0, 0);
    filter.setAttribute('width', w); filter.setAttribute('height', h);
    feImage.setAttribute('width', w); feImage.setAttribute('height', h);
    feImage.setAttributeNS('http://www.w3.org/1999/xlink', 'href', canvas.toDataURL());
    disp.setAttribute('scale', String(max * 2));
    pill.style.backdropFilter = `url(#${id}) blur(3px) saturate(180%) brightness(1.06)`;
    pill.classList.add('is-liquid');
  }

  build();
  new ResizeObserver(build).observe(pill);
  document.fonts?.ready.then(build);
})();
