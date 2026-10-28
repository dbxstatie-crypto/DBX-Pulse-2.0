-- DBX Pulse 3.1: fiecare utilizator își poate schimba doar propriul nume (rulat deja pe proiect).
create or replace function public.rename_me(p_name text)
returns void language plpgsql security definer set search_path to 'public'
as $function$
begin
  if auth.uid() is null then raise exception 'not authenticated'; end if;
  if length(trim(coalesce(p_name, ''))) < 2 then raise exception 'name too short'; end if;
  update public.members set name = left(trim(p_name), 40) where user_id = auth.uid();
end $function$;
revoke all on function public.rename_me(text) from public, anon;
grant execute on function public.rename_me(text) to authenticated;
