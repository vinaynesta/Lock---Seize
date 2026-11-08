/// <reference types="jest" />

import { cameraFor, clusterPoints, formatPin } from '../geo';
import { formatCoordinates, parseCoordinates } from '../sealedDecrypt';

jest.mock('expo-location', () => ({}));

/** Roadmap 5.1: map positions. */
describe('map positions', () => {
  test('pins read as degrees with hemispheres', () => {
    expect(formatPin({ latitude: 38.713889, longitude: -9.133333 })).toBe('38.7139° N, 9.1333° W');
    expect(formatPin({ latitude: -33.8568, longitude: 151.2153 })).toBe('33.8568° S, 151.2153° E');
  });

  test('the stored form round-trips at 6 decimals', () => {
    const c = { latitude: 46.558312, longitude: 7.835741 };
    expect(formatCoordinates(c)).toBe('46.558312,7.835741');
    expect(parseCoordinates(formatCoordinates(c))).toEqual(c);
    expect(parseCoordinates(null)).toBeNull();
    expect(parseCoordinates('garbage')).toBeNull();
  });

  test('the camera centres on the pins and zooms out with their spread', () => {
    const city = cameraFor([{ latitude: 38.70, longitude: -9.14 }, { latitude: 38.72, longitude: -9.12 }]);
    const world = cameraFor([{ latitude: 51.5, longitude: -0.1 }, { latitude: -33.9, longitude: 151.2 }]);
    expect(city.coordinates.latitude).toBeCloseTo(38.71);
    expect(city.zoom).toBeGreaterThan(world.zoom);
    expect(cameraFor([]).zoom).toBe(1);
  });

  test('nearby pins group when zoomed out and separate when zoomed in (5.2)', () => {
    const lisbon = [
      { id: 'a', coordinates: { latitude: 38.7139, longitude: -9.1394 } },
      { id: 'b', coordinates: { latitude: 38.7169, longitude: -9.1399 } },
      { id: 'c', coordinates: { latitude: 38.6916, longitude: -9.2160 } },
    ];
    const tokyo = { id: 't', coordinates: { latitude: 35.6762, longitude: 139.6503 } };
    const world = clusterPoints([...lisbon, tokyo], 2);
    expect(world).toHaveLength(2);
    const group = world.find((c) => c.points.length === 3)!;
    expect(group.id).toMatch(/^cluster:/);
    expect(group.coordinates.latitude).toBeCloseTo((38.7139 + 38.7169 + 38.6916) / 3);
    expect(world.find((c) => c.id === 't')!.points).toEqual([tokyo]); // a lone pin keeps its id

    expect(clusterPoints(lisbon, 16)).toHaveLength(3);
    expect(clusterPoints(lisbon, 2).map((c) => c.id)).toEqual(clusterPoints(lisbon, 2).map((c) => c.id)); // stable ids
    expect(clusterPoints([], 5)).toEqual([]);
  });
});
