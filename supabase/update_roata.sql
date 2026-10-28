-- DBX Pulse 3.2: Roata norocului + invită un prieten
alter table public.members add column if not exists spins int not null default 0;
alter table public.members add column if not exists friend_code text;
alter table public.members add column if not exists referred_by uuid;
alter table public.members add column if not exists referral_rewarded boolean not null default false;
create unique index if not exists members_friend_code_key on public.members(friend_code);

create table if not exists public.spin_log (
  id bigserial primary key,
  user_id uuid not null,
  ts timestamptz not null default now(),
  prize_days int not null,
  is_test boolean not null default false
);
alter table public.spin_log enable row level security;  -- fără politici: doar funcțiile de server o ating

-- un singur bonus de bun venit pentru conturile existente (non-admin, non-nelimitat); admin primește 5 învârtiri de test
update public.members set spins = 1 where spins = 0 and not unlimited and role <> 'admin';
update public.members set spins = 5 where role = 'admin' and spins = 0;

create or replace function public.ensure_friend_code() returns text
language plpgsql security definer set search_path to 'public' as $$
declare c text; i int := 0;
begin
  select friend_code into c from public.members where user_id = auth.uid();
  if c is not null then return c; end if;
  loop
    c := 'DBX-' || upper(substr(md5(random()::text || clock_timestamp()::text), 1, 6));
    begin
      update public.members set friend_code = c where user_id = auth.uid() and friend_code is null;
      exit;
    exception when unique_violation then
      i := i + 1; if i > 5 then raise; end if;
    end;
  end loop;
  return (select friend_code from public.members where user_id = auth.uid());
end $$;

create or replace function public.my_wheel() returns json
language plpgsql security definer set search_path to 'public' as $$
declare m public.members%rowtype; fc text; lst json; cnt int; paid int;
begin
  if auth.uid() is null then raise exception 'not authenticated'; end if;
  select * into m from public.members where user_id = auth.uid();
  if not found then raise exception 'not member'; end if;
  fc := public.ensure_friend_code();
  select coalesce(json_agg(json_build_object(
           'name', f.name,
           'status', case when f.referral_rewarded then 'paid'
                          when f.unlimited or f.access_until > now() then 'trial' else 'inactive' end,
           'days_left', case when f.access_until > now() then ceil(extract(epoch from (f.access_until - now())) / 86400)::int else 0 end)
         order by f.created_at desc), '[]'::json)
    into lst from public.members f where f.referred_by = auth.uid();
  select count(*), count(*) filter (where referral_rewarded) into cnt, paid from public.members where referred_by = auth.uid();
  return json_build_object('spins', m.spins, 'friend_code', fc, 'friends', lst, 'friends_total', cnt, 'friends_paid', paid);
end $$;

create or replace function public.spin() returns json
language plpgsql security definer set search_path to 'public' as $$
declare m public.members%rowtype; r int; d int; big int; before timestamptz; after timestamptz; test boolean;
begin
  if auth.uid() is null then raise exception 'not authenticated'; end if;
  select * into m from public.members where user_id = auth.uid() for update;
  if not found then raise exception 'not member'; end if;
  if m.spins <= 0 then raise exception 'no spins'; end if;
  test := m.unlimited or m.role = 'admin';
  r := floor(random() * 100)::int;               -- 0..99
  d := case when r < 60 then 0 when r < 85 then 1 when r < 95 then 3 when r < 98 then 7 else 30 end;
  if d = 30 and not test then
    select count(*) into big from public.spin_log
      where prize_days = 30 and not is_test and ts >= date_trunc('month', now());
    if big >= 2 then d := 7; end if;              -- plafon: maxim 2 premii de 30 de zile pe lună
  end if;
  before := m.access_until;
  after := before;
  if d > 0 and not test then
    after := greatest(coalesce(m.access_until, now()), now()) + make_interval(days => d);
  end if;
  update public.members set spins = spins - 1, access_until = after where user_id = auth.uid();
  insert into public.spin_log(user_id, prize_days, is_test) values (auth.uid(), d, test);
  return json_build_object('prize_days', d, 'before', before, 'after', after, 'spins_left', m.spins - 1, 'test', test);
end $$;

-- plata aprobată: +1 învârtire pentru plătitor; +1 pentru cel care l-a invitat (o singură dată, la prima plată)
create or replace function public.approve_payment(p_user uuid, p_days integer default 30) returns void
language plpgsql security definer set search_path to 'public' as $$
declare ref uuid; rew boolean;
begin
  if not public.is_admin() then raise exception 'not admin'; end if;
  update public.members
    set access_until = greatest(coalesce(access_until, now()), now()) + make_interval(days => p_days),
        spins = spins + 1
    where user_id = p_user and not unlimited;
  update public.payment_requests set status = 'approved' where user_id = p_user and status = 'pending';
  select referred_by, referral_rewarded into ref, rew from public.members where user_id = p_user;
  if ref is not null and not coalesce(rew, false) then
    update public.members set spins = spins + 1 where user_id = ref;
    update public.members set referral_rewarded = true where user_id = p_user;
  end if;
end $$;

-- înscriere: codul poate fi cod de invitație SAU cod de prieten; contul nou primește 1 învârtire de bun venit
create or replace function public.redeem_invite(p_code text, p_name text) returns text
language plpgsql security definer set search_path to 'public' as $$
declare inv public.invites%rowtype; c text := upper(trim(p_code)); fr uuid;
begin
  if auth.uid() is null then raise exception 'not authenticated'; end if;
  if exists (select 1 from public.members where user_id = auth.uid()) then
    return (select role from public.members where user_id = auth.uid());
  end if;
  select * into inv from public.invites where code = c for update;
  if found and inv.uses_left > 0 then
    update public.invites set uses_left = uses_left - 1 where code = inv.code;
    insert into public.members(user_id, name, role, unlimited, access_until, spins)
      values (auth.uid(), left(trim(p_name), 40), inv.role, inv.unlimited,
              case when inv.unlimited then null else now() + make_interval(days => inv.grant_days) end,
              case when inv.unlimited or inv.role = 'admin' then 0 else 1 end);
    return inv.role;
  end if;
  select user_id into fr from public.members where friend_code = c;
  if fr is null then raise exception 'invalid invite'; end if;
  insert into public.members(user_id, name, role, access_until, spins, referred_by)
    values (auth.uid(), left(trim(p_name), 40), 'member', now() + interval '7 days', 1, fr);
  return 'member';
end $$;

revoke all on function public.spin(), public.my_wheel(), public.ensure_friend_code() from public, anon;
grant execute on function public.spin(), public.my_wheel(), public.ensure_friend_code() to authenticated;
