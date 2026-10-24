-- ============================================================
-- DBX Pulse — schema Supabase. Rulează o singură dată în
-- Supabase → SQL Editor → New query → Run.
-- ============================================================

-- Membri (un rând per prieten, ID-ul = auth.users.id)
create table if not exists public.members (
  user_id    uuid primary key references auth.users(id) on delete cascade,
  name       text not null,
  role       text not null default 'member' check (role in ('member','admin')),
  created_at timestamptz not null default now()
);

-- Coduri de invitație (doar tu le vezi, din dashboard)
create table if not exists public.invites (
  code      text primary key,
  role      text not null default 'member' check (role in ('member','admin')),
  uses_left int  not null default 1
);

-- Oferte văzute (kind 0) și curse acceptate (kind 1), cu locație
create table if not exists public.events (
  id         bigint generated always as identity primary key,
  user_id    uuid not null default auth.uid() references auth.users(id) on delete cascade,
  ts         bigint not null,                    -- milisecunde (ora telefonului)
  kind       smallint not null check (kind in (0,1)),
  lat        double precision not null,
  lon        double precision not null,
  net_per_km double precision,
  price      double precision,
  pickup_km  double precision,
  trip_km    double precision,
  net        double precision,
  created_at timestamptz not null default now(),
  unique (user_id, ts, kind)
);
create index if not exists events_ts_idx on public.events (ts desc);

alter table public.members enable row level security;
alter table public.invites enable row level security;   -- fără policy = nimeni din aplicație
alter table public.events  enable row level security;

create or replace function public.is_member() returns boolean
language sql stable security definer set search_path = public as $$
  select exists (select 1 from public.members where user_id = auth.uid())
$$;

create or replace function public.is_admin() returns boolean
language sql stable security definer set search_path = public as $$
  select exists (select 1 from public.members where user_id = auth.uid() and role = 'admin')
$$;

drop policy if exists members_select on public.members;
create policy members_select on public.members for select
  using (user_id = auth.uid() or public.is_admin());

drop policy if exists events_insert on public.events;
create policy events_insert on public.events for insert
  with check (user_id = auth.uid() and public.is_member());

drop policy if exists events_select on public.events;
create policy events_select on public.events for select
  using (user_id = auth.uid() or public.is_admin());

drop policy if exists events_delete on public.events;
create policy events_delete on public.events for delete
  using (user_id = auth.uid() or public.is_admin());

-- Intrare în comunitate cu cod de invitație
create or replace function public.redeem_invite(p_code text, p_name text)
returns text language plpgsql security definer set search_path = public as $$
declare inv public.invites%rowtype;
begin
  if auth.uid() is null then raise exception 'not authenticated'; end if;
  if exists (select 1 from public.members where user_id = auth.uid()) then
    return (select role from public.members where user_id = auth.uid());
  end if;
  select * into inv from public.invites where code = upper(trim(p_code)) for update;
  if not found or inv.uses_left <= 0 then raise exception 'invalid invite'; end if;
  update public.invites set uses_left = uses_left - 1 where code = inv.code;
  insert into public.members(user_id, name, role)
    values (auth.uid(), left(trim(p_name), 40), inv.role);
  return inv.role;
end $$;

-- Zone agregate ale celorlalți: fără ID, fără nume, locația rotunjită la ~550 m, ora fără minute
create or replace function public.community_cells(p_since bigint)
returns table(clat double precision, clon double precision, hr int, kind smallint, n int, avg_npk double precision)
language sql stable security definer set search_path = public as $$
  select round(e.lat / 0.005) * 0.005,
         round(e.lon / 0.005) * 0.005,
         extract(hour from (to_timestamp(e.ts / 1000.0) at time zone 'Europe/Bucharest'))::int,
         e.kind,
         count(*)::int,
         avg(e.net_per_km)
  from public.events e
  where public.is_member() and e.user_id <> auth.uid() and e.ts >= p_since
  group by 1, 2, 3, 4
  order by 1, 2, 3, 4
$$;

-- Dreptul de a-ți șterge datele
create or replace function public.delete_my_data() returns void
language plpgsql security definer set search_path = public as $$
begin
  delete from public.events  where user_id = auth.uid();
  delete from public.members where user_id = auth.uid();
end $$;

revoke execute on function public.redeem_invite(text, text) from public, anon;
revoke execute on function public.community_cells(bigint)   from public, anon;
revoke execute on function public.delete_my_data()          from public, anon;
grant  execute on function public.redeem_invite(text, text) to authenticated;
grant  execute on function public.community_cells(bigint)   to authenticated;
grant  execute on function public.delete_my_data()          to authenticated;

-- Coduri de invitație generate aleator: 1 de admin (pentru tine) + 1 pentru prieteni (10 folosiri)
insert into public.invites(code, role, uses_left) values
  ('ADM-' || upper(substr(md5(random()::text), 1, 8)), 'admin', 1),
  ('PRI-' || upper(substr(md5(random()::text), 1, 8)), 'member', 10);

select * from public.invites;   -- aici vezi codurile
