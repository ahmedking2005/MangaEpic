# Security Policy

## Reporting a vulnerability

Please report security issues privately to the repository owner via a
GitHub private vulnerability report (Security → Report a vulnerability)
instead of opening a public issue.

## Notes for this fork

- The app signs every release with its own fixed signing key; verify
  downloaded APKs against the SHA256SUMS file attached to each release.
- The Supabase publishable (anon) key is embedded in the app by design;
  all tables are protected by owner-only Row Level Security policies.
  The service_role key is never shipped in the app or the repository.
- Signing keys and keystore passwords live only in GitHub Actions
  secrets — never in the repository.
