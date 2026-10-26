-- Zonele se contorizează pentru toți: fiecare membru vede zonele agregate ale celorlalți (fără nume sau ID).
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
