-- Zone din comunitate mai de încredere: o celulă (~550 m) apare doar dacă are date de la cel puțin 2 șoferi și minimum 5 evenimente.
create or replace function public.community_cells(p_since bigint)
returns table(clat double precision, clon double precision, hr int, kind smallint, n int, avg_npk double precision)
language sql stable security definer set search_path = public as $$
  with c as (
    select e.user_id,
           round(e.lat / 0.005) * 0.005 as la,
           round(e.lon / 0.005) * 0.005 as lo,
           extract(hour from (to_timestamp(e.ts / 1000.0) at time zone 'Europe/Bucharest'))::int as h,
           e.kind, e.net_per_km
    from public.events e
    where public.is_member() and e.user_id <> auth.uid() and e.ts >= p_since
  ),
  ok as (
    select la, lo from c group by la, lo
    having count(distinct user_id) >= 2 and count(*) >= 5
  )
  select c.la, c.lo, c.h, c.kind, count(*)::int, avg(c.net_per_km)
  from c join ok using (la, lo)
  group by c.la, c.lo, c.h, c.kind
  order by 1, 2, 3, 4
$$;
