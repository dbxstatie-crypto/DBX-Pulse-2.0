-- DBX Pulse 2.5 — ziua săptămânii în zonele comunității + doar cursele luate (kind = 1).
-- Rulează o singură dată în Supabase → SQL Editor → New query → Run.
-- Fără acest script aplicația merge la fel, doar că zilele bune apar din cursele tale, nu și ale celorlalți.
drop function if exists public.community_cells(bigint);

create or replace function public.community_cells(p_since bigint)
returns table(clat double precision, clon double precision, hr int, kind smallint, n int, avg_npk double precision, dow int)
language sql stable security definer set search_path = public as $$
  select round(e.lat / 0.005) * 0.005,
         round(e.lon / 0.005) * 0.005,
         extract(hour  from (to_timestamp(e.ts / 1000.0) at time zone 'Europe/Bucharest'))::int,
         e.kind,
         count(*)::int,
         avg(e.net_per_km),
         extract(isodow from (to_timestamp(e.ts / 1000.0) at time zone 'Europe/Bucharest'))::int
  from public.events e
  where public.is_member() and e.user_id <> auth.uid() and e.kind = 1 and e.ts >= p_since
  group by 1, 2, 3, 4, 7
  order by 1, 2, 3, 7, 4
$$;

revoke execute on function public.community_cells(bigint) from public, anon;
grant  execute on function public.community_cells(bigint) to authenticated;
