import { describe, expect, it } from 'vitest';
import { heroIconCdnUrl, heroIconUrl } from './heroes';

const CDN = 'https://cdn.cloudflare.steamstatic.com/apps/dota2/images/dota_react/heroes/';

describe('heroIconCdnUrl', () => {
  it('builds the Valve CDN URL from a full GSI hero name (npc_dota_hero_ prefix stripped)', () => {
    expect(heroIconCdnUrl('npc_dota_hero_drow_ranger')).toBe(`${CDN}drow_ranger.png`);
  });

  it('lowercases the slug and also accepts a bare slug without the prefix', () => {
    expect(heroIconCdnUrl('npc_dota_hero_Pudge')).toBe(`${CDN}pudge.png`);
    expect(heroIconCdnUrl('pudge')).toBe(`${CDN}pudge.png`);
  });

  it('returns null when there is no hero to show', () => {
    expect(heroIconCdnUrl(null)).toBeNull();
    expect(heroIconCdnUrl(undefined)).toBeNull();
    expect(heroIconCdnUrl('')).toBeNull();
    expect(heroIconCdnUrl('   ')).toBeNull();
    expect(heroIconCdnUrl('npc_dota_hero_')).toBeNull(); // prefix only -> empty slug -> null
  });
});

describe('heroIconUrl', () => {
  it('returns null when there is no hero to show', () => {
    expect(heroIconUrl(null)).toBeNull();
    expect(heroIconUrl(undefined)).toBeNull();
    expect(heroIconUrl('')).toBeNull();
  });

  it('never yields a remote CDN URL (that path is heroIconCdnUrl) — a bundled-local URL or null', () => {
    // The local bundle may be empty (CI, or before `npm run fetch:hero-icons`), so we cannot assert
    // a specific asset URL; the invariant under test is that heroIconUrl only ever resolves to a
    // bundled (same-origin/relative) URL or null — it must not fall back to the http(s) CDN itself.
    const url = heroIconUrl('npc_dota_hero_axe');
    expect(url === null || !/^https?:/i.test(url)).toBe(true);
  });
});
