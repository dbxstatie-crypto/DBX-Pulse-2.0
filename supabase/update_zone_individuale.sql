-- 1) Zonele comunității le vede doar adminul; ceilalți își văd doar datele proprii.
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
  where public.is_admin() and e.user_id <> auth.uid() and e.ts >= p_since
  group by 1, 2, 3, 4
  order by 1, 2, 3, 4
$$;

-- 2) Adminul este contul alexstatie89@gmail.com; toți ceilalți sunt membri.
update public.members m
set role = case
  when m.user_id = (select u.id from auth.users u where lower(u.email) = 'alexstatie89@gmail.com') then 'admin'
  else 'member'
end;

-- Verificare: trebuie să apară un singur rând cu rol admin
select name, role from public.members order by role, name;
