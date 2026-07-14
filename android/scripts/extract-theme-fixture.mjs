#!/usr/bin/env node
// Extracts the editorial design-token oracle from the design prototype's own
// theme source and emits a committed Kotlin test fixture.
//
//   node android/scripts/extract-theme-fixture.mjs
//
// Reads:  design-handoff/playster/project/editorial/theme.jsx  (palettes, accents, faces, type ramps)
//         design-handoff/playster/project/editorial/app.jsx    (size + density step axes)
// Writes: android/app/src/test/java/com/github/jayteealao/playster/ui/editorial/EditorialThemeFixture.kt
//
// Every extraction asserts an exact match count and aborts loudly on drift, so
// a prototype edit can never silently bake a wrong value into the fixture.

import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const themePath = join(repoRoot, 'design-handoff', 'playster', 'project', 'editorial', 'theme.jsx');
const appPath = join(repoRoot, 'design-handoff', 'playster', 'project', 'editorial', 'app.jsx');
const outPath = join(
  repoRoot, 'android', 'app', 'src', 'test', 'java',
  'com', 'github', 'jayteealao', 'playster', 'ui', 'editorial', 'EditorialThemeFixture.kt',
);

const themeSrc = readFileSync(themePath, 'utf8');
const appSrc = readFileSync(appPath, 'utf8');

function fail(msg) {
  console.error(`FIXTURE EXTRACTION FAILED: ${msg}`);
  process.exit(1);
}

// ── Object-literal extraction (balanced braces + vm eval) ────────────────────
function extractObjectLiteral(src, constName) {
  const marker = `const ${constName} = {`;
  const start = src.indexOf(marker);
  if (start < 0) fail(`could not find "${marker}" in theme.jsx`);
  let depth = 0;
  let i = start + marker.length - 1; // points at the opening brace
  for (; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}') {
      depth--;
      if (depth === 0) break;
    }
  }
  if (depth !== 0) fail(`unbalanced braces extracting ${constName}`);
  const literal = src.slice(start + marker.length - 1, i + 1);
  try {
    return vm.runInNewContext(`(${literal})`, {});
  } catch (e) {
    fail(`could not eval ${constName} literal: ${e.message}`);
  }
}

const PAPERS = extractObjectLiteral(themeSrc, 'PAPERS');
const ACCENTS = extractObjectLiteral(themeSrc, 'ACCENTS');
const FACES = extractObjectLiteral(themeSrc, 'FACES');

const expectedPaletteKeys = ['cream', 'vellum', 'newsprint', 'night'];
const expectedColorRoles = ['paper', 'paperDeep', 'paperEdge', 'ink', 'inkSoft', 'inkFaint', 'rule', 'ruleFaint', 'highlight'];
const expectedAccentKeys = ['oxblood', 'forest', 'slate', 'ink'];
const expectedFaceKeys = ['source', 'garamond', 'cormorant', 'fraunces'];

if (JSON.stringify(Object.keys(PAPERS)) !== JSON.stringify(expectedPaletteKeys)) {
  fail(`PAPERS keys drifted: ${Object.keys(PAPERS)}`);
}
if (JSON.stringify(Object.keys(ACCENTS)) !== JSON.stringify(expectedAccentKeys)) {
  fail(`ACCENTS keys drifted: ${Object.keys(ACCENTS)}`);
}
if (JSON.stringify(Object.keys(FACES)) !== JSON.stringify(expectedFaceKeys)) {
  fail(`FACES keys drifted: ${Object.keys(FACES)}`);
}
for (const [key, palette] of Object.entries(PAPERS)) {
  for (const role of expectedColorRoles) {
    if (typeof palette[role] !== 'string') fail(`palette ${key} missing color role ${role}`);
  }
}

// ── Color parsing → ARGB (8-bit quantization matches Compose's toArgb()) ─────
function cssToArgb(css, site) {
  let m = /^#([0-9a-fA-F]{6})$/.exec(css.trim());
  if (m) return `0xFF${m[1].toUpperCase()}`;
  m = /^rgba\((\d+),\s*(\d+),\s*(\d+),\s*(\.?[\d.]+)\)$/.exec(css.trim());
  if (m) {
    const [r, g, b] = [Number(m[1]), Number(m[2]), Number(m[3])];
    const a = Math.round(Number(m[4]) * 255);
    const hex = (v) => v.toString(16).padStart(2, '0').toUpperCase();
    return `0x${hex(a)}${hex(r)}${hex(g)}${hex(b)}`;
  }
  fail(`unrecognized color syntax at ${site}: "${css}"`);
}

// ── Type-ramp extraction (per-primitive function slices, exact match counts) ─
function sliceBlock(src, header, site) {
  const start = src.indexOf(header);
  if (start < 0) fail(`could not find block "${header}" (${site})`);
  const next = src.indexOf('\nfunction ', start + header.length);
  return src.slice(start, next < 0 ? src.length : next);
}

function extractOne(block, regex, site) {
  const matches = [...block.matchAll(regex)];
  if (matches.length !== 1) {
    fail(`expected exactly 1 match for ${site}, found ${matches.length} (pattern ${regex})`);
  }
  return matches[0];
}

function num(block, regex, site) {
  return Number(extractOne(block, regex, site)[1]);
}

const kickerBlock = sliceBlock(themeSrc, 'function Kicker(', 'Kicker');
const displayBlock = sliceBlock(themeSrc, 'function Display(', 'Display');
const deckBlock = sliceBlock(themeSrc, 'function Deck(', 'Deck');
const bodyBlock = sliceBlock(themeSrc, 'function Body(', 'Body');
const dropcapBlock = sliceBlock(themeSrc, 'function Dropcap(', 'Dropcap');
const pullQuoteBlock = sliceBlock(themeSrc, 'function PullQuote(', 'PullQuote');
const folioBlock = sliceBlock(themeSrc, 'function Folio(', 'Folio');
const navBlock = sliceBlock(themeSrc, 'function BottomNav(', 'BottomNav');
const appBarBlock = sliceBlock(themeSrc, 'function AppBar(', 'AppBar');

const fontSizeRe = /fontSize: ed\.s\((-?[\d.]+)\)/g;
const lineHeightRe = /lineHeight: (-?[\d.]+)/g;
const letterSpacingRe = /letterSpacing: (-?[\d.]+)/g;
const fontWeightRe = /fontWeight: (\d+)/g;

const ramps = {
  kicker: {
    fontSize: num(kickerBlock, fontSizeRe, 'kicker fontSize'),
    lineHeight: null,
    letterSpacing: num(kickerBlock, letterSpacingRe, 'kicker letterSpacing'),
    fontWeight: num(kickerBlock, fontWeightRe, 'kicker fontWeight'),
  },
  appBarKicker: {
    fontSize: num(appBarBlock, fontSizeRe, 'appBar fontSize'),
    lineHeight: null,
    letterSpacing: num(appBarBlock, letterSpacingRe, 'appBar letterSpacing'),
    fontWeight: num(appBarBlock, fontWeightRe, 'appBar fontWeight'),
  },
  display: {
    // Display's fontSize is its `size` prop; the default is in the signature.
    fontSize: num(displayBlock, /function Display\(\{ children, size = (-?[\d.]+)/g, 'display default size'),
    lineHeight: num(displayBlock, lineHeightRe, 'display lineHeight'),
    letterSpacing: num(displayBlock, letterSpacingRe, 'display letterSpacing'),
    fontWeight: num(displayBlock, fontWeightRe, 'display fontWeight'),
  },
  deck: {
    fontSize: num(deckBlock, fontSizeRe, 'deck fontSize'),
    lineHeight: num(deckBlock, lineHeightRe, 'deck lineHeight'),
    letterSpacing: null,
    fontWeight: 400, // Deck sets no fontWeight — CSS default.
  },
  body: {
    fontSize: num(bodyBlock, fontSizeRe, 'body fontSize'),
    lineHeight: num(bodyBlock, lineHeightRe, 'body lineHeight'),
    letterSpacing: null,
    fontWeight: 400, // Body sets no fontWeight — CSS default.
  },
  dropcap: {
    fontSize: num(dropcapBlock, fontSizeRe, 'dropcap fontSize'),
    lineHeight: num(dropcapBlock, lineHeightRe, 'dropcap lineHeight'),
    letterSpacing: null,
    fontWeight: num(dropcapBlock, fontWeightRe, 'dropcap fontWeight'),
  },
  pullQuote: {
    fontSize: num(pullQuoteBlock, fontSizeRe, 'pullQuote fontSize'),
    lineHeight: num(pullQuoteBlock, lineHeightRe, 'pullQuote lineHeight'),
    letterSpacing: num(pullQuoteBlock, letterSpacingRe, 'pullQuote letterSpacing'),
    fontWeight: num(pullQuoteBlock, fontWeightRe, 'pullQuote fontWeight'),
  },
  folio: {
    fontSize: num(folioBlock, fontSizeRe, 'folio fontSize'),
    lineHeight: null,
    letterSpacing: num(folioBlock, letterSpacingRe, 'folio letterSpacing'),
    fontWeight: num(folioBlock, fontWeightRe, 'folio fontWeight'),
  },
  navLabel: {
    fontSize: num(navBlock, fontSizeRe, 'nav fontSize'),
    lineHeight: null,
    letterSpacing: num(navBlock, letterSpacingRe, 'nav letterSpacing'),
    // Active/inactive weights come from the ternary below.
    fontWeight: num(navBlock, /fontWeight: isActive \? (\d+) : \d+,/g, 'nav active weight'),
  },
};

const navInactiveWeight = num(navBlock, /fontWeight: isActive \? \d+ : (\d+),/g, 'nav inactive weight');

// ── Step axes from the tweaks panel (editorial/app.jsx) ──────────────────────
const sizeStepsMatch = extractOne(
  appSrc,
  /\[\['S', ([\d.]+)\], \['M', ([\d.]+)\], \['L', ([\d.]+)\], \['XL', ([\d.]+)\]\]/g,
  'size steps',
);
const sizeSteps = sizeStepsMatch.slice(1, 5).map(Number);

const densityStepsMatch = extractOne(
  appSrc,
  /\[\['Compact', ([\d.]+)\], \['Default', ([\d.]+)\], \['Roomy', ([\d.]+)\]\]/g,
  'density steps',
);
const densitySteps = densityStepsMatch.slice(1, 4).map(Number);

// ── FACES pairing (first quoted family in each stack) ────────────────────────
function firstFamily(stack, site) {
  const m = /^"([^"]+)"/.exec(stack.trim());
  if (!m) fail(`could not extract first font family at ${site}: "${stack}"`);
  return m[1];
}

const sansMatch = extractOne(themeSrc, /const SANS = '([^']+)'/g, 'SANS');
const sansFamily = firstFamily(sansMatch[1], 'SANS');

// ── Emit Kotlin ──────────────────────────────────────────────────────────────
function kotlinDouble(n) {
  return Number.isInteger(n) ? `${n}.0` : `${n}`;
}

const paletteEntries = expectedPaletteKeys.map((key) => {
  const p = PAPERS[key];
  const roles = expectedColorRoles
    .map((role) => `                ${role} = ${cssToArgb(p[role], `${key}.${role}`)}`)
    .join(',\n');
  return `        "${key}" to PaletteFixture(\n${roles},\n                dark = ${!!p.dark},\n            )`;
}).join(',\n');

const accentEntries = expectedAccentKeys.map((key) => {
  const a = ACCENTS[key];
  return `        "${key}" to AccentFixture(color = ${cssToArgb(a.color, `${key}.color`)}, soft = ${cssToArgb(a.soft, `${key}.soft`)})`;
}).join(',\n');

const faceEntries = expectedFaceKeys.map((key) => {
  const f = FACES[key];
  return `        "${key}" to FaceFixture(displayFamily = "${firstFamily(f.display, `${key}.display`)}", bodyFamily = "${firstFamily(f.body, `${key}.body`)}")`;
}).join(',\n');

const rampEntries = Object.entries(ramps).map(([name, r]) => {
  const lh = r.lineHeight == null ? 'null' : kotlinDouble(r.lineHeight);
  const ls = r.letterSpacing == null ? 'null' : kotlinDouble(r.letterSpacing);
  return `        "${name}" to RampFixture(fontSize = ${kotlinDouble(r.fontSize)}, lineHeight = ${lh}, letterSpacing = ${ls}, fontWeight = ${r.fontWeight})`;
}).join(',\n');

const kotlin = `// GENERATED FILE — DO NOT EDIT.
// Regenerated by: node android/scripts/extract-theme-fixture.mjs
// Source of truth: design-handoff/playster/project/editorial/theme.jsx
//                  design-handoff/playster/project/editorial/app.jsx (step axes)
// Colors are ARGB longs; rgba() alphas are quantized with round(a * 255),
// matching Compose's Color -> toArgb() 8-bit quantization.
package com.github.jayteealao.playster.ui.editorial

object EditorialThemeFixture {
    data class PaletteFixture(
        val paper: Long,
        val paperDeep: Long,
        val paperEdge: Long,
        val ink: Long,
        val inkSoft: Long,
        val inkFaint: Long,
        val rule: Long,
        val ruleFaint: Long,
        val highlight: Long,
        val dark: Boolean,
    )

    data class AccentFixture(val color: Long, val soft: Long)

    data class FaceFixture(val displayFamily: String, val bodyFamily: String)

    data class RampFixture(
        val fontSize: Double,
        val lineHeight: Double?,
        val letterSpacing: Double?,
        val fontWeight: Int,
    )

    val palettes: Map<String, PaletteFixture> = mapOf(
${paletteEntries},
    )

    val accents: Map<String, AccentFixture> = mapOf(
${accentEntries},
    )

    val faces: Map<String, FaceFixture> = mapOf(
${faceEntries},
    )

    const val SANS_FAMILY: String = "${sansFamily}"

    val ramps: Map<String, RampFixture> = mapOf(
${rampEntries},
    )

    const val NAV_LABEL_INACTIVE_WEIGHT: Int = ${navInactiveWeight}

    val sizeSteps: List<Double> = listOf(${sizeSteps.map(kotlinDouble).join(', ')})

    val densitySteps: List<Double> = listOf(${densitySteps.map(kotlinDouble).join(', ')})

    /** The mock's ed.s() arithmetic: multiply, then round to 2 decimals. */
    fun edS(base: Double, step: Double): Double = Math.round(base * step * 100.0) / 100.0
}
`;

mkdirSync(dirname(outPath), { recursive: true });
writeFileSync(outPath, kotlin, 'utf8');

console.log(`Wrote ${outPath}`);
console.log(`  palettes: ${expectedPaletteKeys.length} x ${expectedColorRoles.length} color roles`);
console.log(`  accents:  ${expectedAccentKeys.length}`);
console.log(`  faces:    ${expectedFaceKeys.length} (sans: ${sansFamily})`);
console.log(`  ramps:    ${Object.keys(ramps).length}`);
console.log(`  steps:    size [${sizeSteps}] density [${densitySteps}]`);
