/// <reference types="jest" />

import { api } from '@/api/lock';

import { allVaultsMap } from '../mapData';
import { deviceUnlocked, openVault, restoreDeviceKeys } from '../sealedVault';

// jest.mock calls are hoisted above these imports by babel-jest.
jest.mock('@/api/lock', () => ({
  api: { vaults: jest.fn(), me: jest.fn(), keyBundle: jest.fn(), timeline: jest.fn() },
}));
jest.mock('../sealedVault', () => ({ deviceUnlocked: jest.fn(), openVault: jest.fn(), restoreDeviceKeys: jest.fn() }));

const memory = (id: string, vaultId: string) => ({ id, vaultId, title: id, coordinates: { latitude: 1, longitude: 2 } });

/** Roadmap 5.2: one map across vaults; sealed vaults only when this device can open them. */
describe('all-vaults map data', () => {
  beforeEach(() => {
    (api.vaults as jest.Mock).mockResolvedValue([
      { id: 'home', name: 'Personal', encryption: 'SERVER' },
      { id: 'family', name: 'Family', encryption: 'SEALED' },
    ]);
    (api.me as jest.Mock).mockResolvedValue({ id: 'me' });
    (api.keyBundle as jest.Mock).mockResolvedValue({ publicKey: 'pk' });
    // Two pages for the server vault, one for the sealed one.
    (api.timeline as jest.Mock).mockImplementation(async (vaultId: string, p: { cursor?: string }) =>
      vaultId === 'home'
        ? p.cursor
          ? { memories: [memory('h2', 'home')], nextCursor: null }
          : { memories: [memory('h1', 'home')], nextCursor: 'c1' }
        : { memories: [memory('f1', 'family')], nextCursor: null },
    );
  });

  test('includes a sealed vault when its keys open on this device, paging through every vault', async () => {
    (deviceUnlocked as jest.Mock).mockReturnValue(true);
    const data = await allVaultsMap();
    expect(data.memories.map((m) => m.id)).toEqual(['h1', 'h2', 'f1']);
    expect(data.vaultNames).toEqual({ home: 'Personal', family: 'Family' });
    expect(data.skipped).toEqual([]);
    expect(openVault).toHaveBeenCalledWith(expect.objectContaining({ id: 'family' }), 'me');
  });

  test('skips a sealed vault whose keys are locked, without fetching its memories', async () => {
    (deviceUnlocked as jest.Mock).mockReturnValue(false);
    (restoreDeviceKeys as jest.Mock).mockResolvedValue(false);
    (api.timeline as jest.Mock).mockClear();
    const data = await allVaultsMap();
    expect(data.memories.map((m) => m.id)).toEqual(['h1', 'h2']);
    expect(data.skipped.map((v) => v.id)).toEqual(['family']);
    expect((api.timeline as jest.Mock).mock.calls.every(([vaultId]) => vaultId === 'home')).toBe(true);
  });
});
