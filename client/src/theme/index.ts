import { Platform, useColorScheme } from 'react-native';

/**
 * Minimal palette: warm paper neutrals, ink for primary actions, one restrained amber accent
 * ("preserved") used for links, focus and highlights only.
 */
const light = {
  background: '#FBFAF7',
  surface: '#FFFFFF',
  surfaceMuted: '#F3F1EC',
  text: '#16130F',
  textMuted: '#857D72',
  textFaint: '#B5AEA3',
  border: '#ECE8E1',
  ink: '#16130F', // primary buttons
  inkText: '#FBFAF7',
  accent: '#A0672E',
  accentSoft: '#F5EBDF',
  accentText: '#FFFFFF',
  draft: '#9A9287',
  sealing: '#C2882B',
  locked: '#3C7A5A',
  danger: '#C0362C',
  dangerSoft: '#FBECEA',
};

const dark: typeof light = {
  background: '#0F0E0C',
  surface: '#181614',
  surfaceMuted: '#211E1B',
  text: '#F4F0E9',
  textMuted: '#9A9288',
  textFaint: '#5E5850',
  border: '#2A2622',
  ink: '#F4F0E9',
  inkText: '#0F0E0C',
  accent: '#E0AE6E',
  accentSoft: '#2B2219',
  accentText: '#0F0E0C',
  draft: '#9A9288',
  sealing: '#E5B25A',
  locked: '#7CC59E',
  danger: '#F47A6E',
  dangerSoft: '#2E1A17',
};

export type Theme = typeof light;

export function useTheme(): Theme {
  return useColorScheme() === 'dark' ? dark : light;
}

export const space = { xs: 4, sm: 8, md: 12, lg: 16, xl: 24, xxl: 32, xxxl: 48 };
export const radius = { sm: 8, md: 12, lg: 18, pill: 999 };

export const mono = Platform.select({ ios: 'Menlo', default: 'monospace' });
