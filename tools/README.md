# Arabic edition extras

Files added for the Arabic-only build of Kotatsu-Redo:

- `generate_icons.py` — decodes `icon-source.b64` and regenerates all
  launcher icons (legacy, foreground and round) as PNG files in
  `app/src/main/res/mipmap-*`, replacing the stock WebP icons.
  Run by the "Build APK (Arabic edition)" GitHub Actions workflow.
- `icon-source.b64` — base64 copy of the custom launcher icon artwork
  (white book cover with gold Arabic calligraphy), kept as text so it
  can travel through the GitHub API.

Also note: `app/schemas/org.koitharu.kotatsu.reader.data.erase.HiddenImagesDatabase/`
holds Room schema files 1.json-6.json. Upstream never committed them,
but Room needs them to generate the database auto-migrations, so a
clean checkout cannot build without them.
