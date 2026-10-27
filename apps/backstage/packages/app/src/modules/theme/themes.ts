import {
  BackstageTypography,
  PageTheme,
  UnifiedThemeOptions,
  createUnifiedTheme,
  defaultComponentThemes,
  palettes,
} from '@backstage/theme';
import { ColorTokens, fonts, nav, radiusMd, tokens } from './tokens';

/**
 * Every page header is one flat band of brand-deep with on-brand text. Backstage's
 * header paints only `background-image`, so a gradient from the colour to itself
 * carries the flat colour: no shape, no colour ramp. Pages ask for a page theme by
 * id (`home`, `tool`, `service` and so on); every id falls back to this one.
 */
const header: PageTheme = {
  colors: [tokens.light.brandDeep],
  shape: 'none',
  backgroundImage: `linear-gradient(${tokens.light.brandDeep}, ${tokens.light.brandDeep})`,
  fontColor: tokens.light.onBrand,
};

/**
 * Headings are SemiBold, never Bold, on the design system's scale: h1 48, h2 36,
 * h3 24, h4 20. h5 (card titles) and h6 are not on the scale; they take 20 and 16.
 * Material UI's defaults already match the text sizes: body1 16, body2 14,
 * caption 12.
 */
const typography: BackstageTypography = {
  htmlFontSize: 16,
  fontFamily: fonts.sans,
  h1: { fontSize: 48, fontWeight: 600, marginBottom: 10 },
  h2: { fontSize: 36, fontWeight: 600, marginBottom: 8 },
  h3: { fontSize: 24, fontWeight: 600, marginBottom: 6 },
  h4: { fontSize: 20, fontWeight: 600, marginBottom: 6 },
  h5: { fontSize: 20, fontWeight: 600, marginBottom: 4 },
  h6: { fontSize: 16, fontWeight: 600, marginBottom: 2 },
};

/**
 * Solid controls of the dark theme carry ink text: white on the dark theme's brand
 * blue is 3.7:1, the light theme's ink on it 4.85:1. Material UI hovers a contained
 * button to `primary.dark`; brand-light keeps ink at 7.0:1 there, where brand-deep
 * would drop it to 2.1:1.
 */
const darkPrimary = {
  main: tokens.dark.brand,
  dark: tokens.dark.brandLight,
  contrastText: tokens.light.ink,
};

function palette(variant: 'light' | 'dark') {
  const t = tokens[variant];
  const base = palettes[variant];
  return {
    ...base,
    primary: variant === 'light' ? { main: t.brand } : darkPrimary,
    secondary: { main: variant === 'light' ? t.brandDeep : t.brandLight },
    info: { main: t.brandLight },
    success: { main: t.success },
    warning: { main: t.warning },
    error: { main: t.error },
    background: { default: t.surface, paper: t.surfaceRaised },
    text: { primary: t.ink, secondary: t.ink2 },
    divider: t.line,
    border: t.line,
    textContrast: t.ink,
    textSubtle: t.muted,
    link: t.brand,
    // Hover moves away from the ground: darker on light, lighter on dark.
    linkHover: variant === 'light' ? t.brandDeep : t.brandLight,
    infoBackground: t.brandTint,
    infoText: t.ink,
    status: {
      ...base.status,
      ok: t.success,
      warning: t.warning,
      error: t.error,
      running: t.brand,
      aborted: t.muted,
    },
    navigation: {
      background: nav.background,
      indicator: nav.accent,
      color: nav.text,
      selectedColor: nav.accent,
      navItem: { hoverBackground: nav.hover },
      submenu: { background: nav.hover },
    },
    tabbar: { indicator: t.brand },
  };
}

/**
 * A few overrides, not a rewrite: radius-md on buttons, inputs and cards, and
 * borders instead of shadows on cards and the page header. `createUnifiedTheme`
 * replaces Backstage's defaults per component, so an override restates or spreads
 * them.
 */
function components(t: ColorTokens): UnifiedThemeOptions['components'] {
  return {
    BackstageHeader: {
      styleOverrides: { header: { boxShadow: 'none' } },
    },
    BackstageInfoCard: {
      styleOverrides: { headerTitle: { fontWeight: 600 } },
    },
    MuiButton: {
      styleOverrides: {
        ...defaultComponentThemes?.MuiButton?.styleOverrides,
        // Sentence case: labels render as written.
        root: { borderRadius: radiusMd, textTransform: 'none' },
      },
    },
    MuiCard: {
      styleOverrides: {
        // Backstage's default flex column keeps card actions at the bottom.
        root: {
          display: 'flex',
          flexDirection: 'column',
          borderRadius: radiusMd,
          border: `1px solid ${t.line}`,
          boxShadow: 'none',
        },
      },
    },
    MuiOutlinedInput: {
      styleOverrides: { root: { borderRadius: radiusMd } },
    },
  };
}

function createManyStageTheme(variant: 'light' | 'dark') {
  return createUnifiedTheme({
    palette: palette(variant),
    typography,
    fontFamily: fonts.sans,
    defaultPageTheme: 'home',
    pageTheme: { home: header },
    components: components(tokens[variant]),
  });
}

export const manyStageLight = createManyStageTheme('light');
export const manyStageDark = createManyStageTheme('dark');
