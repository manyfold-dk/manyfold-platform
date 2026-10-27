/**
 * The Manyfold design system's tokens, as ManyStage uses them.
 *
 * Each colour has a light and a dark value. `styles.css` beside this file repeats
 * them for the Backstage UI components; change a colour in both files.
 */
export type ColorTokens = {
  /** Manyfold Blue: primary buttons, links, the logo mark. */
  brand: string;
  /** Deep Blue: pressed states and dark bands such as the page header. */
  brandDeep: string;
  /** Light Blue: hover states and secondary marks; never body text on white. */
  brandLight: string;
  /** Sky Blue: highlights and focus rings on dark grounds; never text on light grounds. */
  brandSky: string;
  /** Blue tint: callout and selected-row backgrounds, with ink text. */
  brandTint: string;
  /** Primary text. */
  ink: string;
  /** Secondary text. */
  ink2: string;
  /** Captions and metadata, 14 px or larger. */
  muted: string;
  /** Dividers, card borders, table rules. */
  line: string;
  /** Page background. */
  surface: string;
  /** Cards, header bars, code blocks. */
  surfaceRaised: string;
  /** Text and icons on brand and brandDeep. */
  onBrand: string;
  /** Platform state only: operational. */
  success: string;
  /** Platform state only: degraded. */
  warning: string;
  /** Platform state only: down, and destructive actions. */
  error: string;
};

export const tokens: Record<'light' | 'dark', ColorTokens> = {
  light: {
    brand: '#2563EB',
    brandDeep: '#1E40AF',
    brandLight: '#3B82F6',
    brandSky: '#60A5FA',
    brandTint: '#EFF6FF',
    ink: '#0F172A',
    ink2: '#334155',
    muted: '#64748B',
    line: '#E2E8F0',
    surface: '#F8FAFC',
    surfaceRaised: '#FFFFFF',
    onBrand: '#FFFFFF',
    success: '#047857',
    warning: '#B45309',
    error: '#DC2626',
  },
  dark: {
    brand: '#3B82F6',
    brandDeep: '#1E40AF',
    brandLight: '#60A5FA',
    brandSky: '#93C5FD',
    brandTint: '#172554',
    ink: '#F1F5F9',
    ink2: '#CBD5E1',
    muted: '#94A3B8',
    line: '#1E293B',
    surface: '#0B1120',
    surfaceRaised: '#0F172A',
    onBrand: '#FFFFFF',
    success: '#34D399',
    warning: '#FBBF24',
    error: '#F87171',
  },
};

/**
 * The sidebar is dark in both themes: the light theme's ink as the ground, the dark
 * theme's ink as the text, and the light theme's sky blue for the selected item.
 */
export const nav = {
  background: tokens.light.ink,
  text: tokens.dark.ink,
  accent: tokens.light.brandSky,
  hover: tokens.dark.line,
};

/** Inter for everything, JetBrains Mono for code. Both are bundled, see themeModule.tsx. */
export const fonts = {
  sans: '"Inter", -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
  mono: '"JetBrains Mono", "Fira Code", Consolas, monospace',
};

/** Corner radius of buttons, inputs and cards (radius-md). */
export const radiusMd = 8;
