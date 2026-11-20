# سجل التغييرات | Changelog

## Manga Epic (this fork)

- **Sources:** exactly 24 working Arabic sources — 15 manga, 6 novels, 3 anime.
  Non-Arabic and adult sources are fenced out of browsing, search, library,
  history, sync and external-link resolution.
- **Accounts:** Supabase sign-in with Google or email/password, profile with
  cover photo and reading stats, cloud sync of favourites/progress/history,
  and Google Drive backup (appDataFolder, newest 3 kept).
- **Ads (AdMob):** native cards in browse feeds and on details pages, an
  interstitial at the in-reader chapter separator, and a pause banner in the
  anime player — all behind the `ads_enabled` Remote Config kill-switch.
- **Anime:** Media3 player with automatic server fallback (bounded stream
  refresh + per-server watchdog) and Cloudflare challenge solving.
- **Novels:** dedicated reader with bundled Arabic fonts (Cairo, Tajawal,
  Amiri, Noto Kufi/Naskh/Sans Arabic).
- **Identity:** independent name, icon, splash and purple "Epic" theme;
  full Arabic UI translation.

Earlier history belongs to the upstream Kotatsu / Kotatsu-Redo projects.
