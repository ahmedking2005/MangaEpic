-- Manga Epic — Supabase schema (Phase 3)
-- Project: ahmedking2005's Project (ref lfdefhhldvdevcrkzmgf)
-- Rules baked in from day one:
--   * RLS enabled on EVERY table, all four policies, auth.uid() = user_id.
--   * Tables store identifiers only (source / manga / chapter keys) — never manga content or images.
--   * The app ships only the publishable key; service_role never leaves the dashboard.
-- Run in: Supabase Dashboard → SQL Editor. Safe to re-run (idempotent guards where practical).

-- ---------- helper: updated_at ----------
create or replace function public.set_updated_at()
returns trigger
language plpgsql
as $$
begin
  -- Preserve a client-provided updated_at (the app syncs with last-write-wins);
  -- only stamp now() when an update did not change it (e.g. dashboard edits).
  if new.updated_at is not distinct from old.updated_at then
    new.updated_at = now();
  end if;
  return new;
end;
$$;

-- ---------- profiles ----------
create table if not exists public.profiles (
  user_id      uuid primary key references auth.users (id) on delete cascade,
  display_name text not null default '',
  avatar_url   text,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);

-- ---------- favorites (identifiers only) ----------
create table if not exists public.favorites (
  user_id    uuid not null references auth.users (id) on delete cascade,
  source     text not null,
  manga_key  text not null,
  title      text not null default '',
  cover_url  text,
  added_at   timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (user_id, source, manga_key)
);

-- ---------- reading progress (last-write-wins by updated_at) ----------
create table if not exists public.reading_progress (
  user_id    uuid not null references auth.users (id) on delete cascade,
  source     text not null,
  manga_key  text not null,
  chapter_key text not null default '',
  page       int  not null default 0,
  total_pages int not null default 0,
  updated_at timestamptz not null default now(),
  primary key (user_id, source, manga_key)
);

-- ---------- reading history ----------
create table if not exists public.reading_history (
  user_id     uuid not null references auth.users (id) on delete cascade,
  source      text not null,
  manga_key   text not null,
  chapter_key text not null,
  read_at     timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  primary key (user_id, source, manga_key, chapter_key)
);

-- ---------- user devices (FCM tokens for announcements + new-chapter alerts) ----------
create table if not exists public.user_devices (
  user_id    uuid not null references auth.users (id) on delete cascade,
  fcm_token  text not null,
  platform   text not null default 'android',
  app_build  int,
  updated_at timestamptz not null default now(),
  primary key (user_id, fcm_token)
);

-- ---------- updated_at triggers ----------
do $$
declare t text;
begin
  foreach t in array array['profiles','favorites','reading_progress','reading_history','user_devices'] loop
    execute format('drop trigger if exists trg_set_updated_at on public.%I', t);
    execute format('create trigger trg_set_updated_at before update on public.%I
                    for each row execute function public.set_updated_at()', t);
  end loop;
end;
$$;

-- ---------- Row Level Security: ON everywhere, owner-only policies ----------
do $$
declare t text;
begin
  foreach t in array array['profiles','favorites','reading_progress','reading_history','user_devices'] loop
    execute format('alter table public.%I enable row level security', t);
    execute format('drop policy if exists %I on public.%I', t || '_select_own', t);
    execute format('drop policy if exists %I on public.%I', t || '_insert_own', t);
    execute format('drop policy if exists %I on public.%I', t || '_update_own', t);
    execute format('drop policy if exists %I on public.%I', t || '_delete_own', t);
    execute format('create policy %I on public.%I for select using (auth.uid() = user_id)', t || '_select_own', t);
    execute format('create policy %I on public.%I for insert with check (auth.uid() = user_id)', t || '_insert_own', t);
    execute format('create policy %I on public.%I for update using (auth.uid() = user_id) with check (auth.uid() = user_id)', t || '_update_own', t);
    execute format('create policy %I on public.%I for delete using (auth.uid() = user_id)', t || '_delete_own', t);
  end loop;
end;
$$;

-- ---------- phase 4 addition: reading percent (local history stores percent, not total pages) ----------
alter table public.reading_progress add column if not exists percent real not null default 0;

-- ---------- indexes for the sync queries ----------
create index if not exists idx_favorites_user_updated  on public.favorites (user_id, updated_at);
create index if not exists idx_progress_user_updated   on public.reading_progress (user_id, updated_at);
create index if not exists idx_history_user_read_at    on public.reading_history (user_id, read_at desc);
create index if not exists idx_devices_token           on public.user_devices (fcm_token);
