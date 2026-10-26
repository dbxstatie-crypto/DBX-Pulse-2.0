-- Actualizare din aplicație: aici ții minte care e ultima versiune și unde e fișierul APK.
create table if not exists public.app_release (
  id int primary key default 1 check (id = 1),
  version_code int not null default 6,
  version_name text not null default '2.4',
  apk_url text not null default '',
  min_version_code int not null default 0,
  notes text not null default ''
);
alter table public.app_release enable row level security;
drop policy if exists app_release_read on public.app_release;
create policy app_release_read on public.app_release for select to anon, authenticated using (true);
insert into public.app_release(id) values (1) on conflict do nothing;
update public.app_release
  set apk_url = 'https://odbwfbjznpwvqygcxfqq.supabase.co/storage/v1/object/public/apk/dbx-pulse.apk'
  where id = 1 and apk_url = '';
select * from public.app_release;
