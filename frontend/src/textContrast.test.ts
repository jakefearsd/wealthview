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
