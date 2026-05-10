/**
 * Lightweight design tokens for the mobile MVP. Mirrors the same neutral
 * palette as the web frontend (whites, slates, an accent green for primary
 * actions). Numbers, not Tailwind classes — there's no Tailwind here.
 */
export const colors = {
    bg: '#f7f8fa',
    surface: '#ffffff',
    border: '#e2e6ec',
    text: '#0f172a',
    textMuted: '#475569',
    textSubtle: '#94a3b8',
    primary: '#0f766e',
    primaryText: '#ffffff',
    danger: '#b91c1c',
    dangerBg: '#fee2e2',
    // Calm accents for the portfolio surface — gentle greens and a soft
    // slate background for category chips. No alarm-bell reds.
    chipBg: '#eef2f6',
    chipText: '#334155',
    sectionHeader: '#64748b',
    accentPositive: '#0f766e',
    tabBarBg: '#ffffff',
    tabBarBorder: '#e2e6ec',
    tabActive: '#0f766e',
    tabInactive: '#94a3b8',
} as const;

export const spacing = {
    xs: 4,
    sm: 8,
    md: 16,
    lg: 24,
    xl: 32,
} as const;

export const typography = {
    /** The headline net-worth number on the Portfolio screen. */
    display: { fontSize: 40, fontWeight: '700' as const, color: colors.text, letterSpacing: -0.5 },
    h1: { fontSize: 28, fontWeight: '700' as const, color: colors.text },
    h2: { fontSize: 20, fontWeight: '600' as const, color: colors.text },
    body: { fontSize: 16, color: colors.text },
    label: { fontSize: 13, fontWeight: '600' as const, color: colors.textMuted },
    /** Section headers (small, all-caps, muted). */
    sectionHeader: {
        fontSize: 11,
        fontWeight: '600' as const,
        color: colors.sectionHeader,
        letterSpacing: 1,
    },
    caption: { fontSize: 12, color: colors.textSubtle },
    mono: { fontSize: 12, color: colors.textMuted, fontFamily: 'Menlo' },
} as const;

export const radius = {
    sm: 6,
    md: 10,
    lg: 14,
} as const;
