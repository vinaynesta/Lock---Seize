import type { ConfigContext, ExpoConfig } from 'expo/config';

// Expo evaluates this file in Node at build time; the app's tsconfig has no Node globals.
declare const process: { env: Record<string, string | undefined> };

/**
 * Extends app.json. The Google Maps API key for Android (expo-maps, roadmap 5.1) comes from the
 * environment, never the repo: set GOOGLE_MAPS_ANDROID_API_KEY in .env.local or the EAS build secrets.
 * Restrict the key to the Maps SDK for Android, package app.lock.client and the signing SHA-1.
 * iOS uses Apple Maps and needs no key.
 */
export default ({ config }: ConfigContext): ExpoConfig => {
  const apiKey = process.env.GOOGLE_MAPS_ANDROID_API_KEY;
  return {
    ...(config as ExpoConfig),
    android: {
      ...config.android,
      ...(apiKey ? { config: { ...config.android?.config, googleMaps: { apiKey } } } : {}),
    },
  };
};
