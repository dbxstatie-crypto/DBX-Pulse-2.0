-- DBX Pulse 3.1: zone de ~500 m (celule 0.0045° lat x 0.0064° lon), centrul celulei = (floor + 0.5).
-- Compatibil cu 3.0 (aceleași coloane). Rulează-l la lansarea 3.1.
create or replace function public.community_cells(p_since bigint)
returns table(clat double precision, clon double precision, hr integer, kind smallint, n integer, avg_npk double precision)
language sql stable security definer set search_path to 'public'
as $function$
  with c as (
    select e.user_id,
           (floor(e.lat / 0.0045) + 0.5) * 0.0045 as la,
           (floor(e.lon / 0.0064) + 0.5) * 0.0064 as lo,
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
$function$;
