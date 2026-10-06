import { describe, expect, it } from 'vitest';

// Guards against low-contrast text colours creeping back in. Each pattern below failed WCAG AA (4.5:1)
// on the white / #f5f5f5 surfaces the app uses. Borders, strokes and chart fills may still use these
// hexes; only `color:` values are checked. Layout.tsx is excluded: its sidebar is dark (#1a1a2e).
const sources = import.meta.glob(['./**/*.tsx', './**/*.ts', '!./**/*.test.ts', '!./**/*.test.tsx'], {
    query: '?raw',
    import: 'default',
    eager: true,
}) as Record<string, string>;

const FORBIDDEN_TEXT_COLOURS: Array<[string, RegExp]> = [
    ['#999 text (2.84:1)', /color:\s*['"]#(999|999999)['"]/i],
    ['#888 text (3.5:1)', /color:\s*['"]#(888|888888)['"]/i],
    ['#9ca3af text (~2.5:1)', /color:\s*['"]#9ca3af['"]/i],
    ['#ccc text (~1.5:1)', /color:\s*['"]#(ccc|cccccc)['"]/i],
    ['#ef5350 text (3.48:1)', /\bcolor:[^\n]*['"]#ef5350['"]/i],
    ['#ffa726 text (~2:1)', /\bcolor:[^\n]*['"]#ffa726['"]/i],
    ['#94a3b8 text (2.6:1)', /\bcolor:[^\n]*['"]#94a3b8['"]/i],
    ['#818cf8 text (~3.0:1)', /\bcolor:[^\n]*['"]#818cf8['"]/i],
    ['#c7d2fe text (~1.5:1)', /\bcolor:[^\n]*['"]#c7d2fe['"]/i],
    ['#e2e8f0 text (~1.2:1)', /\bcolor:[^\n]*['"]#e2e8f0['"]/i],
    ['#22c55e text (2.3:1)', /\bcolor:[^\n]*['"]#22c55e['"]/i],
    ['#1976d2 data cells on the #fff8e1 retired-row tint (4.33:1)', /textAlign:\s*'right',\s*color:\s*'#1976d2'/],
    ['#e65100 text (3.78:1)', /(color:\s*['"]|valueColor=")#e65100['"]/i],
];

describe('text contrast', () => {
    it('scans a realistic number of source files', () => {
        expect(Object.keys(sources).length).toBeGreaterThan(50);
    });

    for (const [label, pattern] of FORBIDDEN_TEXT_COLOURS) {
        it(`no source file uses ${label}`, () => {
            const offenders = Object.entries(sources)
                .filter(([path]) => !path.endsWith('components/Layout.tsx'))
                .filter(([, text]) => pattern.test(text))
                .map(([path]) => path);

            expect(offenders).toEqual([]);
        });
    }
});

// White text needs a background of at least 4.5:1: #ef5350 (3.48:1), #4a9eff (2.75:1) and #ff9800 (2.15:1) fail.
const LOW_CONTRAST_BUTTON_BACKGROUNDS = /background:\s*['"]#(ef5350|4a9eff|ff9800)['"][^}]{0,120}color:\s*['"]#fff['"]/i;

describe('button contrast', () => {
    it('no source file puts white text on a low-contrast button background', () => {
        const offenders = Object.entries(sources)
            .filter(([, text]) => LOW_CONTRAST_BUTTON_BACKGROUNDS.test(text))
            .map(([path]) => path);

        expect(offenders).toEqual([]);
    });
});

// Recharts paints legend text in the series colour. These charts use pale series (orange, slate, indigo),
// so their legends must pass a formatter that renders the label in dark grey.
const LEGEND_CHART_FILES = ['SpendingChart', 'SpendingCorridorChart', 'PortfolioFanChart'];

describe('chart legend contrast', () => {
    for (const name of LEGEND_CHART_FILES) {
        it(`${name} renders legend text in a readable colour`, () => {
            const text = sources[`./components/${name}.tsx`];

            expect(text).toBeDefined();
            expect(text).not.toMatch(/<Legend\s*\/>/);
            expect(text).toMatch(/<Legend formatter=\{legendTextFormatter\}/);
        });
    }
});
