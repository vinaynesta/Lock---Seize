import { useColorScheme } from 'react-native';

const light = {
  background: '#FAF7F2',
  surface: '#FFFFFF',
  surfaceMuted: '#F1ECE3',
  text: '#1F1B16',
  textMuted: '#6B6358',
  border: '#E3DCD0',
  accent: '#8A5A2B', // warm amber: "preserved"
  accentText: '#FFFFFF',
  draft: '#6B6358',
  sealing: '#B7791F',
  locked: '#2F6B4F',
  danger: '#B42318',
};

const dark: typeof light = {
  background: '#14110E',
  surface: '#1E1A16',
  surfaceMuted: '#2A241E',
  text: '#F3EDE4',
  textMuted: '#A89F92',
  border: '#3A322A',
  accent: '#D9A35F',
  accentText: '#14110E',
  draft: '#A89F92',
  sealing: '#E0A84A',
  locked: '#6FBF95',
  danger: '#F97066',
};

export type Theme = typeof light;

export function useTheme(): Theme {
  return useColorScheme() === 'dark' ? dark : light;
}

export const space = { xs: 4, sm: 8, md: 12, lg: 16, xl: 24, xxl: 32 };
export const radius = { sm: 8, md: 12, lg: 16 };
