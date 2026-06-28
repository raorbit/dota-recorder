// Hero display helpers. The core stores the raw GSI hero name (e.g.
// "npc_dota_hero_drow_ranger"); the UI should show a proper name ("Drow Ranger") and a
// portrait. The slug (the name minus the "npc_dota_hero_" prefix) doubles as the Valve
// CDN image filename, so it drives both the name lookup and the icon URL.
//
// Names sourced from OpenDota's hero list (localized_name). Heroes not in the map fall
// back to a title-cased slug, so a brand-new hero still renders cleanly (just maybe not
// its special name, e.g. a "nevermore"-style internal name) until this map is refreshed.

const HERO_PREFIX = 'npc_dota_hero_';

// Valve's dota_react hero portraits (landscape). Same CDN OpenDota links to. Allowed in
// the renderer CSP img-src; if it's unreachable (offline) the <img> onError degrades to
// the placeholder chip.
const HERO_IMG_BASE =
  'https://cdn.cloudflare.steamstatic.com/apps/dota2/images/dota_react/heroes/';

// slug -> localized name. Only slugs whose pretty name differs from a naive title-case
// MUST be here; the rest are included for completeness/offline-correctness.
const HERO_NAMES: Readonly<Record<string, string>> = {
  antimage: 'Anti-Mage',
  axe: 'Axe',
  bane: 'Bane',
  bloodseeker: 'Bloodseeker',
  crystal_maiden: 'Crystal Maiden',
  drow_ranger: 'Drow Ranger',
  earthshaker: 'Earthshaker',
  juggernaut: 'Juggernaut',
  mirana: 'Mirana',
  morphling: 'Morphling',
  nevermore: 'Shadow Fiend',
  phantom_lancer: 'Phantom Lancer',
  puck: 'Puck',
  pudge: 'Pudge',
  razor: 'Razor',
  sand_king: 'Sand King',
  storm_spirit: 'Storm Spirit',
  sven: 'Sven',
  tiny: 'Tiny',
  vengefulspirit: 'Vengeful Spirit',
  windrunner: 'Windranger',
  zuus: 'Zeus',
  kunkka: 'Kunkka',
  lina: 'Lina',
  lion: 'Lion',
  shadow_shaman: 'Shadow Shaman',
  slardar: 'Slardar',
  tidehunter: 'Tidehunter',
  witch_doctor: 'Witch Doctor',
  lich: 'Lich',
  riki: 'Riki',
  enigma: 'Enigma',
  tinker: 'Tinker',
  sniper: 'Sniper',
  necrolyte: 'Necrophos',
  warlock: 'Warlock',
  beastmaster: 'Beastmaster',
  queenofpain: 'Queen of Pain',
  venomancer: 'Venomancer',
  faceless_void: 'Faceless Void',
  skeleton_king: 'Wraith King',
  death_prophet: 'Death Prophet',
  phantom_assassin: 'Phantom Assassin',
  pugna: 'Pugna',
  templar_assassin: 'Templar Assassin',
  viper: 'Viper',
  luna: 'Luna',
  dragon_knight: 'Dragon Knight',
  dazzle: 'Dazzle',
  rattletrap: 'Clockwerk',
  leshrac: 'Leshrac',
  furion: "Nature's Prophet",
  life_stealer: 'Lifestealer',
  dark_seer: 'Dark Seer',
  clinkz: 'Clinkz',
  omniknight: 'Omniknight',
  enchantress: 'Enchantress',
  huskar: 'Huskar',
  night_stalker: 'Night Stalker',
  broodmother: 'Broodmother',
  bounty_hunter: 'Bounty Hunter',
  weaver: 'Weaver',
  jakiro: 'Jakiro',
  batrider: 'Batrider',
  chen: 'Chen',
  spectre: 'Spectre',
  ancient_apparition: 'Ancient Apparition',
  doom_bringer: 'Doom',
  ursa: 'Ursa',
  spirit_breaker: 'Spirit Breaker',
  gyrocopter: 'Gyrocopter',
  alchemist: 'Alchemist',
  invoker: 'Invoker',
  silencer: 'Silencer',
  obsidian_destroyer: 'Outworld Destroyer',
  lycan: 'Lycan',
  brewmaster: 'Brewmaster',
  shadow_demon: 'Shadow Demon',
  lone_druid: 'Lone Druid',
  chaos_knight: 'Chaos Knight',
  meepo: 'Meepo',
  treant: 'Treant Protector',
  ogre_magi: 'Ogre Magi',
  undying: 'Undying',
  rubick: 'Rubick',
  disruptor: 'Disruptor',
  nyx_assassin: 'Nyx Assassin',
  naga_siren: 'Naga Siren',
  keeper_of_the_light: 'Keeper of the Light',
  wisp: 'Io',
  visage: 'Visage',
  slark: 'Slark',
  medusa: 'Medusa',
  troll_warlord: 'Troll Warlord',
  centaur: 'Centaur Warrunner',
  magnataur: 'Magnus',
  shredder: 'Timbersaw',
  bristleback: 'Bristleback',
  tusk: 'Tusk',
  skywrath_mage: 'Skywrath Mage',
  abaddon: 'Abaddon',
  elder_titan: 'Elder Titan',
  legion_commander: 'Legion Commander',
  techies: 'Techies',
  ember_spirit: 'Ember Spirit',
  earth_spirit: 'Earth Spirit',
  abyssal_underlord: 'Underlord',
  terrorblade: 'Terrorblade',
  phoenix: 'Phoenix',
  oracle: 'Oracle',
  winter_wyvern: 'Winter Wyvern',
  arc_warden: 'Arc Warden',
  monkey_king: 'Monkey King',
  dark_willow: 'Dark Willow',
  pangolier: 'Pangolier',
  grimstroke: 'Grimstroke',
  hoodwink: 'Hoodwink',
  void_spirit: 'Void Spirit',
  snapfire: 'Snapfire',
  mars: 'Mars',
  ringmaster: 'Ringmaster',
  dawnbreaker: 'Dawnbreaker',
  marci: 'Marci',
  primal_beast: 'Primal Beast',
  muerta: 'Muerta',
  kez: 'Kez',
  largo: 'Largo',
};

/** The slug ("drow_ranger") from a raw GSI hero name, or null when there's no hero. */
function heroSlug(hero: string | null | undefined): string | null {
  if (!hero) return null;
  const slug = hero.startsWith(HERO_PREFIX) ? hero.slice(HERO_PREFIX.length) : hero;
  return slug.trim() === '' ? null : slug.toLowerCase();
}

/** Title-cases a slug ("drow_ranger" -> "Drow Ranger") as a last-resort name. */
function titleCase(slug: string): string {
  return slug
    .split('_')
    .filter((p) => p !== '')
    .map((p) => p.charAt(0).toUpperCase() + p.slice(1))
    .join(' ');
}

/** Proper display name for a raw GSI hero name; "Unknown hero" when absent. */
export function heroDisplayName(hero: string | null | undefined): string {
  const slug = heroSlug(hero);
  if (slug === null) return 'Unknown hero';
  return HERO_NAMES[slug] ?? titleCase(slug);
}

/** Portrait URL for a raw GSI hero name, or null when there's no hero to show. */
export function heroIconUrl(hero: string | null | undefined): string | null {
  const slug = heroSlug(hero);
  return slug === null ? null : `${HERO_IMG_BASE}${slug}.png`;
}
