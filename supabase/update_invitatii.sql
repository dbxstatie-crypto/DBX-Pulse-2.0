-- ============================================================
-- DBX Pulse: invitații (7 zile gratuite), abonament 19,9 lei/lună, cereri de plată.
-- Rulează O SINGURĂ DATĂ în Supabase → SQL Editor → New query → Run.
-- Rulează-l ÎNAINTE să instalezi APK-ul nou.
-- ============================================================

-- 1) Acces per membru: nelimitat sau până la o dată
alter table public.members add column if not exists unlimited boolean not null default false;
alter table public.members add column if not exists access_until timestamptz;
-- membrii de acum (tu și prietenii) rămân cu acces nelimitat; adminul mereu
update public.members set unlimited = true where access_until is null;
update public.members set unlimited = true where role = 'admin';

-- 2) Invitațiile spun ce acces dau (implicit 7 zile gratuite)
alter table public.invites add column if not exists grant_days int not null default 7;
alter table public.invites add column if not exists unlimited boolean not null default false;
alter table public.invites alter column grant_days set default 7;
-- codul vechi pentru prieteni (10 folosiri) se oprește; generezi coduri noi din aplicație
update public.invites set uses_left = 0 where code like 'PRI-%';

-- 3) „Are acces” = admin, nelimitat sau neexpirat. Serverul oprește zonele celor fără acces.
create or replace function public.has_access() returns boolean
language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from public.members
    where user_id = auth.uid()
      and (role = 'admin' or unlimited or (access_until is not null and access_until > now()))
  )
$$;

create or replace function public.is_member() returns boolean
language sql stable security definer set search_path = public as $$
  select public.has_access()
$$;

-- 4) Intrarea în comunitate doar cu cod valabil (o singură folosire pe cod)
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
  insert into public.members(user_id, name, role, unlimited, access_until)
    values (auth.uid(), left(trim(p_name), 40), inv.role, inv.unlimited,
            case when inv.unlimited then null else now() + make_interval(days => inv.grant_days) end);
  return inv.role;
end $$;

-- 5) Doar adminul își poate crea coduri (din aplicație: Setări → Invitații)
create or replace function public.create_invite(p_days int default 7, p_unlimited boolean default false)
returns text language plpgsql security definer set search_path = public as $$
declare c text;
begin
  if not public.is_admin() then raise exception 'not admin'; end if;
  c := 'DBX-' || upper(substr(md5(random()::text || clock_timestamp()::text), 1, 6));
  insert into public.invites(code, role, uses_left, grant_days, unlimited)
    values (c, 'member', 1, coalesce(p_days, 7), coalesce(p_unlimited, false));
  return c;
end $$;

-- 6) Cereri de plată: utilizatorul apasă „Am plătit”, adminul aprobă +30 zile
create table if not exists public.payment_requests (
  id bigserial primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  created_at timestamptz not null default now(),
  status text not null default 'pending'
);
alter table public.payment_requests enable row level security;   -- fără policy: se folosește doar prin funcțiile de mai jos

create or replace function public.request_payment() returns void
language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then raise exception 'not authenticated'; end if;
  if not exists (select 1 from public.members where user_id = auth.uid()) then raise exception 'not member'; end if;
  if not exists (select 1 from public.payment_requests where user_id = auth.uid() and status = 'pending') then
    insert into public.payment_requests(user_id) values (auth.uid());
  end if;
end $$;

create or replace function public.list_payment_requests()
returns table(user_id uuid, name text, email text, created_at timestamptz, access_until timestamptz)
language sql stable security definer set search_path = public as $$
  select r.user_id, m.name, u.email::text, r.created_at, m.access_until
  from public.payment_requests r
  join public.members m on m.user_id = r.user_id
  join auth.users u on u.id = r.user_id
  where r.status = 'pending' and public.is_admin()
  order by r.created_at
$$;

create or replace function public.approve_payment(p_user uuid, p_days int default 30) returns void
language plpgsql security definer set search_path = public as $$
begin
  if not public.is_admin() then raise exception 'not admin'; end if;
  update public.members
    set access_until = greatest(coalesce(access_until, now()), now()) + make_interval(days => p_days)
    where user_id = p_user and not unlimited;
  update public.payment_requests set status = 'approved' where user_id = p_user and status = 'pending';
end $$;

-- 7) Drepturi: fără cod nu se mai intră liber în comunitate
do $$ begin
  revoke execute on function public.join_community(text) from public, anon, authenticated;
exception when undefined_function then null;
end $$;
revoke execute on function public.create_invite(int, boolean) from public, anon;
revoke execute on function public.list_payment_requests() from public, anon;
revoke execute on function public.approve_payment(uuid, int) from public, anon;
revoke execute on function public.request_payment() from public, anon;
grant execute on function public.create_invite(int, boolean) to authenticated;
grant execute on function public.redeem_invite(text, text) to authenticated;
grant execute on function public.has_access() to authenticated;
grant execute on function public.request_payment() to authenticated;
grant execute on function public.list_payment_requests() to authenticated;
grant execute on function public.approve_payment(uuid, int) to authenticated;

-- Verificare: trebuie să vezi un singur rând cu rol admin și nelimitat = true
select name, role, unlimited, access_until from public.members order by role, name;
