// The app icons, drawn once in the 108×108 adaptive-icon grid and generated for every platform by
// generate.mjs. Android shows the middle 72×72 (18..90) through the launcher's mask and keeps
// anything inside the 66-wide circle around (54, 54) whatever the mask; iOS gets that same 72×72
// window at 1024 px and rounds the corners itself.
//
// An element is { d, fill?, stroke?, width?, evenOdd? }. A colour is "#RRGGBB" or "#RRGGBB@alpha";
// a fill may also be a gradient: { linear: [x1, y1, x2, y2], stops: [[offset, colour], …] } or
// { radial: [cx, cy, r], stops }.
//
// After an edit, run `node tools/app-icons/generate.mjs` and look at design/app-icons/contact-sheet.png.
// A new icon also needs, under the same id: an AppIcon entry in :shared (appicon/AppIcon.kt), its
// settings_app_icon_<id> label in strings_settings.xml, and an <activity-alias> in androidApp's
// manifest. iOS picks the new icon set up by itself.

const circle = (cx, cy, r) =>
  `M${cx - r},${cy}a${r},${r} 0 1,0 ${2 * r},0a${r},${r} 0 1,0 ${-2 * r},0z`;
const diagonal = (from, to) => ({ linear: [18, 18, 90, 90], stops: [[0, from], [1, to]] });

const n = (v) => +v.toFixed(2);
const rect = (x, y, w, h) => `M${n(x)},${n(y)}h${n(w)}v${n(h)}h${n(-w)}z`;
const polar = (cx, cy, r, degrees) => {
  const a = (degrees * Math.PI) / 180;
  return [n(cx + r * Math.cos(a)), n(cy + r * Math.sin(a))];
};

// The watch: a house drawn as one line, with a camera lens where the window would be.
const houseLine = (dx = 0) =>
  `M${54 + dx},32L${73 + dx},48.5L${73 + dx},72a3,3 0 0,1 -3,3L${38 + dx},75a3,3 0 0,1 -3,-3L${35 + dx},48.5z`;
const HOUSE_LINE = houseLine();
function watch({ line, lens, iris, pupil, weight = 5.5, outlineLens = false }) {
  const house = { d: HOUSE_LINE, stroke: line, width: weight };
  if (outlineLens) {
    return [
      house,
      { d: circle(54, 58.5, 9), stroke: line, width: weight * 0.6 },
      { d: circle(54, 58.5, 3.4), fill: line },
    ];
  }
  return [
    house,
    { d: circle(54, 58.5, 10), fill: lens },
    { d: circle(54, 58.5, 6), fill: iris },
    { d: circle(54, 58.5, 2.8), fill: pupil },
    { d: circle(56.3, 56.2, 1.3), fill: '#FFFFFF@0.9' },
  ];
}
const watchMono = [
  { d: HOUSE_LINE, stroke: '#FFFFFF', width: 5.5 },
  { d: circle(54, 58.5, 10) + circle(54, 58.5, 6) + circle(54, 58.5, 2.8), fill: '#FFFFFF', evenOdd: true },
];

// The shield: a house cut out of a shield, its door left standing.
const SHIELD =
  'M54,29C62,33 69,34 75,34L75,52C75,66 66,75 54,80C42,75 33,66 33,52L33,34C39,34 46,33 54,29z' +
  'M54,41L66,51.5L63.5,51.5L63.5,67L44.5,67L44.5,51.5L42,51.5z' +
  'M51,57.5h6v9.5h-6z';
const shield = (colour) => [{ d: SHIELD, fill: colour, evenOdd: true }];

// The porch light: a house in silhouette, one window lit, the moon up.
const PORCH_HOUSE = 'M54,31L64,39.8L64,35L69,35L69,44.2L77,51.2L72,51.2L72,76L36,76L36,51.2L31,51.2z';
const PORCH_WINDOW = 'M46.5,55.5h15v14h-15z';
const PORCH_MULLIONS = 'M53.2,55.5h1.6v14h-1.6z M46.5,61.7h15v1.6h-15z';
// A crescent over the left eave (clear of the chimney and inside the safe circle): most of a
// circle round the right, back along a shallower arc.
const MOON = 'M37.1,29.38A5.2,5.2 0 1,1 34.66,38.48A5,5 0 0,0 37.1,29.38z';
function porch({ house, glow, sky }) {
  return [
    { d: PORCH_HOUSE + PORCH_WINDOW, fill: house, evenOdd: true },
    { d: PORCH_WINDOW, fill: glow },
    { d: PORCH_MULLIONS, fill: house },
    { d: MOON, fill: sky },
  ];
}
const porchMono = [
  { d: PORCH_HOUSE + PORCH_WINDOW, fill: '#FFFFFF', evenOdd: true },
  { d: PORCH_MULLIONS, fill: '#FFFFFF' },
  { d: MOON, fill: '#FFFFFF' },
];

function grid(colour, step = 6) {
  let d = '';
  for (let p = step; p < 108; p += step) d += `M${p},0V108M0,${p}H108`;
  return { d, stroke: colour, width: 0.35 };
}

// A small solid house, for marks that put one inside something else (a dial, a radar, a lens).
function houseGlyph(cx, cy, w) {
  const [eave, wall, top, bottom] = [0.56 * w, 0.38 * w, cy - 0.56 * w, cy + 0.5 * w];
  const y = cy - 0.02 * w;
  return `M${n(cx)},${n(top)}L${n(cx + eave)},${n(y)}L${n(cx + wall)},${n(y)}L${n(cx + wall)},${n(bottom)}` +
    `L${n(cx - wall)},${n(bottom)}L${n(cx - wall)},${n(y)}L${n(cx - eave)},${n(y)}z`;
}

// Neon: the watch as a sign on a brick wall, each tube drawn as widening, fading strokes.
function neonTube(d, colour, core, width = 4.2) {
  return [
    { d, stroke: `${colour}@0.10`, width: width * 2.6 },
    { d, stroke: `${colour}@0.22`, width: width * 1.7 },
    { d, stroke: colour, width },
    { d, stroke: core, width: width * 0.38 },
  ];
}
function bricks(colour) {
  let d = '';
  for (let row = 0, y = 0; y < 108; row++, y += 9) {
    d += `M0,${y}H108`;
    for (let x = row % 2 ? 9 : 0; x < 108; x += 18) d += `M${x},${y}v9`;
  }
  return { d, stroke: colour, width: 0.5 };
}

// Arcade: the watch as 8-bit art in the four Game Boy greens. '#' darkest, 'o' the lens, '+' its glint.
const SPRITE = [
  '......##......',
  '.....####.....',
  '....##..##....',
  '...##....##...',
  '..##......##..',
  '.##........##.',
  '##..........##',
  '.#...oooo...#.',
  '.#..oooooo..#.',
  '.#..o+##oo..#.',
  '.#..oo##oo..#.',
  '.#..oooooo..#.',
  '.#...oooo...#.',
  '.############.',
];
const PIXEL = 3.5;
function sprite(char, dx = 0, dy = 0) {
  const origin = 54 - (SPRITE[0].length * PIXEL) / 2;
  let d = '';
  SPRITE.forEach((row, y) => {
    for (const run of row.matchAll(new RegExp(`\\${char}+`, 'g'))) {
      d += rect(origin + run.index * PIXEL + dx, origin + y * PIXEL + dy, run[0].length * PIXEL, PIXEL);
    }
  });
  return d;
}

// Vault: a home safe. A combination dial with a house on its brass knob.
function dialTicks() {
  let d = '';
  for (let i = 0; i < 36; i++) {
    const [x1, y1] = polar(54, 54, 15.2, i * 10 - 90);
    const [x2, y2] = polar(54, 54, i % 3 === 0 ? 19.2 : 17.4, i * 10 - 90);
    d += `M${x1},${y1}L${x2},${y2}`;
  }
  return d;
}
function brushed(colour) {
  let d = '';
  for (let y = 1; y < 108; y += 1.5) d += `M0,${y}H108`;
  return { d, stroke: colour, width: 0.3 };
}

// Radar: a phosphor sweep with the house at its centre.
const RADAR_RINGS = circle(54, 54, 11) + circle(54, 54, 21) + circle(54, 54, 31) + 'M54,23V85M23,54H85';
function sweep(from, to, r = 31) {
  const [x1, y1] = polar(54, 54, r, from);
  const [x2, y2] = polar(54, 54, r, to);
  return `M54,54L${x1},${y1}A${r},${r} 0 0,1 ${x2},${y2}z`;
}

// Constellation: the house as stars joined up, a roof over walls, with a bright one where the
// window is.
const STARS = [[54, 27], [31, 48], [77, 48], [37, 48], [71, 48], [37.5, 79], [70.5, 78.5]];
const STAR_LINES = `M${STARS[0]}L${STARS[1]}L${STARS[2]}z M${STARS[3]}L${STARS[5]}L${STARS[6]}L${STARS[4]}`;
function sparkle(cx, cy, r) {
  const k = r * 0.22;
  return `M${cx},${cy - r}Q${cx + k},${cy - k} ${cx + r},${cy}Q${cx + k},${cy + k} ${cx},${cy + r}` +
    `Q${cx - k},${cy + k} ${cx - r},${cy}Q${cx - k},${cy - k} ${cx},${cy - r}z`;
}
function starField(count, seed) {
  let x = seed;
  const next = () => ((x = (x * 16807) % 2147483647) / 2147483647);
  let bright = '';
  let dim = '';
  for (let i = 0; i < count; i++) {
    const [cx, cy] = [n(20 + next() * 68), n(20 + next() * 68)];
    // Keep the field off the house's own stars and lines.
    if (cx > 30 && cx < 78 && cy > 26 && cy < 80) continue;
    if (i % 3 === 0) bright += circle(cx, cy, 0.7);
    else dim += circle(cx, cy, 0.45);
  }
  return [{ d: bright, fill: '#FFFFFF@0.8' }, { d: dim, fill: '#FFFFFF@0.45' }];
}

// Glitch: the outline knocked out of register, cyan one way and red the other, on a scanned screen.
function scanlines(colour) {
  let d = '';
  for (let y = 0; y < 108; y += 2) d += `M0,${y}H108`;
  return { d, stroke: colour, width: 0.5 };
}
function glitchWatch(dx, colour, width = 4.6) {
  return [
    { d: houseLine(dx), stroke: colour, width },
    { d: circle(54 + dx, 58.5, 8.6), stroke: colour, width: width * 0.62 },
    { d: circle(54 + dx, 58.5, 3), fill: colour },
  ];
}

// Peephole: the view through a brass door viewer, the house bent round the glass.
function planks() {
  const elements = [];
  for (let x = 0, i = 0; x < 108; x += 12, i++) {
    elements.push({ d: rect(x, 0, 12, 108), fill: i % 2 ? '#7A4A2B' : '#704227' });
  }
  let seams = '';
  for (let x = 12; x < 108; x += 12) seams += `M${x},0V108`;
  elements.push({ d: seams, stroke: '#3F2312', width: 0.8 });
  return elements;
}

// `id` names the Android resources (ic_launcher_<id>), the activity-alias (AppIcon<Id>) and the
// iOS icon set (AppIcon-<Id>), so it must match the AppIcon enum in :shared. The first icon is the
// default: on Android it is plain ic_launcher, on iOS the primary AppIcon. Its colours are the
// app's own (FrigateDarkColorScheme): peach on charcoal.
const square = 'M0,0h108v108h-108z';
export const ICONS = [
  {
    id: 'classic',
    background: [{ d: square, fill: diagonal('#34322F', '#131312') }],
    foreground: watch({ line: '#FFB59D', lens: '#FFB59D', iris: '#7A2F14', pupil: '#1C0A04' }),
    monochrome: watchMono,
    // iOS 18 dark and tinted home screens: the mark alone, the system draws the background.
    dark: watch({ line: '#FFB59D', lens: '#FFB59D', iris: '#7A2F14', pupil: '#1C0A04' }),
    // Debug installs sit beside release, so their default icon swaps the charcoal for teal.
    debugBackground: [{ d: square, fill: diagonal('#2B6F78', '#0F343A') }],
  },
  {
    id: 'terracotta',
    background: [{ d: square, fill: diagonal('#E58A69', '#9A4529') }],
    foreground: watch({ line: '#FFF4EE', lens: '#FFF4EE', iris: '#9A4529', pupil: '#2E0D03' }),
    monochrome: watchMono,
  },
  {
    id: 'midnight',
    background: [{ d: square, fill: diagonal('#1A2750', '#05080F') }],
    foreground: watch({ line: '#E9EEFA', lens: '#E9EEFA', iris: '#FFB43D', pupil: '#1A1206' }),
    monochrome: watchMono,
  },
  {
    id: 'paper',
    background: [{ d: square, fill: '#F6F2EA' }],
    foreground: watch({ line: '#1F2633', lens: '#1F2633', iris: '#E5484D', pupil: '#1F2633' }),
    monochrome: watchMono,
  },
  {
    id: 'blueprint',
    background: [{ d: square, fill: '#1F56B8' }, grid('#FFFFFF@0.16')],
    foreground: watch({ line: '#FFFFFF', weight: 3.2, outlineLens: true }),
    monochrome: watchMono,
  },
  {
    id: 'sage',
    background: [{ d: square, fill: diagonal('#5B7350', '#25321F') }],
    foreground: shield('#E8EFDF'),
    monochrome: shield('#FFFFFF'),
  },
  {
    id: 'cobalt',
    background: [{ d: square, fill: diagonal('#3D7EFF', '#1B3DB8') }],
    foreground: shield('#FFFFFF'),
    monochrome: shield('#FFFFFF'),
  },
  {
    id: 'porch',
    background: [{ d: square, fill: { linear: [54, 18, 54, 90], stops: [[0, '#16123A'], [1, '#3A3F86']] } }],
    foreground: porch({
      house: '#F2E9DC',
      glow: { radial: [54, 62.5, 10], stops: [[0, '#FFF1B8'], [1, '#FFAA2B']] },
      sky: '#FFE7A8',
    }),
    monochrome: porchMono,
  },
  {
    id: 'neon',
    background: [{ d: square, fill: diagonal('#1D0F33', '#07040F') }, bricks('#FFFFFF@0.05')],
    foreground: [
      ...neonTube(HOUSE_LINE, '#FF4FD8', '#FFE6FA'),
      ...neonTube(circle(54, 58.5, 8.4), '#3DF2FF', '#E6FFFF', 3.2),
      { d: circle(54, 58.5, 4.4), fill: '#3DF2FF@0.25' },
      { d: circle(54, 58.5, 2.4), fill: '#E6FFFF' },
    ],
    monochrome: watchMono,
  },
  {
    id: 'arcade',
    background: [{ d: square, fill: '#9BBC0F' }, grid('#8BAC0F', PIXEL)],
    foreground: [
      { d: sprite('#', PIXEL / 2, PIXEL / 2) + sprite('o', PIXEL / 2, PIXEL / 2), fill: '#8BAC0F' },
      { d: sprite('o'), fill: '#306230' },
      { d: sprite('#'), fill: '#0F380F' },
      { d: sprite('+'), fill: '#9BBC0F' },
    ],
    monochrome: [{ d: sprite('#') + sprite('+'), fill: '#FFFFFF' }],
  },
  {
    id: 'vault',
    background: [{ d: square, fill: diagonal('#DDE1E7', '#7F8894') }, brushed('#FFFFFF@0.07')],
    foreground: [
      { d: circle(54, 54, 25), fill: '#000000@0.18' },
      { d: circle(54, 54, 24), fill: diagonal('#F4F6F9', '#8C95A1') },
      { d: circle(54, 54, 21), fill: { linear: [40, 36, 68, 72], stops: [[0, '#40464F'], [1, '#14161A']] } },
      { d: dialTicks(), stroke: '#D9DEE5', width: 0.9 },
      { d: circle(54, 54, 11), fill: { linear: [46, 45, 62, 63], stops: [[0, '#F8DC8E'], [1, '#A8761F']] } },
      { d: houseGlyph(54, 54.6, 11), fill: '#3A2706' },
      { d: 'M51.6,27.6h4.8l-2.4,4.6z', fill: '#E5484D' },
    ],
    monochrome: [
      { d: circle(54, 54, 24) + circle(54, 54, 21), fill: '#FFFFFF', evenOdd: true },
      { d: dialTicks(), stroke: '#FFFFFF', width: 0.9 },
      { d: circle(54, 54, 11) + houseGlyph(54, 54.6, 11), fill: '#FFFFFF', evenOdd: true },
    ],
  },
  {
    id: 'bauhaus',
    background: [{ d: square, fill: '#EFE6D2' }],
    foreground: [
      { d: rect(33, 50, 42, 29), fill: '#1D4A9C' },
      { d: 'M54,27L79,50L29,50z', fill: '#D7372B' },
      { d: circle(54, 64.5, 10), fill: '#F4B400' },
      { d: 'M54,54.5A10,10 0 0,0 54,74.5z', fill: '#151515' },
      { d: circle(77, 33, 4.5), fill: '#151515' },
    ],
    monochrome: [
      { d: 'M54,27L79,50L29,50z', fill: '#FFFFFF' },
      { d: rect(33, 50, 42, 29) + circle(54, 64.5, 10), fill: '#FFFFFF', evenOdd: true },
      { d: 'M54,54.5A10,10 0 0,0 54,74.5z', fill: '#FFFFFF' },
      { d: circle(77, 33, 4.5), fill: '#FFFFFF' },
    ],
  },
  {
    id: 'radar',
    background: [{ d: square, fill: { radial: [54, 54, 52], stops: [[0, '#0F3D24'], [1, '#030D07']] } }],
    foreground: [
      { d: RADAR_RINGS, stroke: '#3CFF9A@0.3', width: 0.7 },
      { d: sweep(-150, -55), fill: { linear: [...polar(54, 54, 22, -150), ...polar(54, 54, 22, -55)], stops: [[0, '#3CFF9A@0'], [1, '#3CFF9A@0.5']] } },
      { d: `M54,54L${polar(54, 54, 31, -55)}`, stroke: '#9DFFC9', width: 1 },
      { d: circle(54, 55, 15), fill: { radial: [54, 55, 15], stops: [[0, '#3CFF9A@0.35'], [1, '#3CFF9A@0']] } },
      { d: houseGlyph(54, 55, 17), fill: '#8DFFC0' },
      { d: circle(65.5, 38, 1.7), fill: '#C6FFE0' },
      { d: circle(38, 70.5, 1.3), fill: '#3CFF9A@0.5' },
    ],
    monochrome: [
      { d: RADAR_RINGS, stroke: '#FFFFFF', width: 1.2 },
      { d: houseGlyph(54, 55, 17), fill: '#FFFFFF' },
    ],
  },
  {
    id: 'constellation',
    background: [{ d: square, fill: diagonal('#0A0F2C', '#24104D') }, ...starField(60, 7)],
    foreground: [
      { d: STAR_LINES, stroke: '#C8D6FF@0.55', width: 0.8 },
      { d: STARS.map(([x, y]) => circle(x, y, 3.6)).join(''), fill: '#C8D6FF@0.18' },
      { d: STARS.map(([x, y]) => circle(x, y, 1.7)).join(''), fill: '#FFFFFF' },
      { d: circle(54, 63, 9), fill: { radial: [54, 63, 9], stops: [[0, '#FFE7A8@0.45'], [1, '#FFE7A8@0']] } },
      { d: sparkle(54, 63, 7), fill: '#FFF1C9' },
    ],
    monochrome: [
      { d: STAR_LINES, stroke: '#FFFFFF', width: 1.6 },
      { d: STARS.map(([x, y]) => circle(x, y, 2.8)).join(''), fill: '#FFFFFF' },
      { d: sparkle(54, 63, 7), fill: '#FFFFFF' },
    ],
  },
  {
    id: 'glitch',
    background: [{ d: square, fill: '#0D0D12' }, scanlines('#FFFFFF@0.035')],
    foreground: [
      ...glitchWatch(-1.9, '#00E5FF@0.9'),
      ...glitchWatch(1.9, '#FF2D55@0.9'),
      ...glitchWatch(0, '#FFFFFF'),
      { d: rect(29, 43.5, 20, 2.2), fill: '#00E5FF@0.8' },
      { d: rect(57, 67, 22, 1.6), fill: '#FF2D55@0.8' },
    ],
    monochrome: watchMono,
  },
  {
    id: 'peephole',
    background: [
      ...planks(),
      { d: square, fill: { radial: [54, 54, 60], stops: [[0.45, '#000000@0'], [1, '#000000@0.45']] } },
    ],
    foreground: [
      { d: circle(54.8, 55.6, 20), fill: '#000000@0.3' },
      { d: circle(54, 54, 20), fill: diagonal('#FBE3A0', '#8A5D17') },
      { d: circle(54, 54, 15.5), fill: '#5A3B0C' },
      { d: circle(54, 54, 14), fill: { radial: [54, 52, 14], stops: [[0, '#6F93B8'], [1, '#0E1A28']] } },
      { d: 'M41.5,60.5Q54,55 66.5,60.5', stroke: '#A9CBEA@0.35', width: 0.8 },
      { d: houseGlyph(54, 55, 12.5), fill: '#F2E9DC' },
      { d: rect(52, 55.5, 4, 4), fill: '#FFC24D' },
      { d: `M${polar(54, 54, 11, 200)}A11,11 0 0,1 ${polar(54, 54, 11, 250)}`, stroke: '#FFFFFF@0.4', width: 1.6 },
    ],
    monochrome: [
      { d: circle(54, 54, 20) + circle(54, 54, 14), fill: '#FFFFFF', evenOdd: true },
      { d: houseGlyph(54, 55, 12.5) + rect(52, 55.5, 4, 4), fill: '#FFFFFF', evenOdd: true },
    ],
  },
];
