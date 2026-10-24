-- Intrare automată în comunitate, fără cod. Primul cont care intră devine admin (tu).
create or replace function public.join_community(p_name text)
returns text language plpgsql security definer set search_path = public as $$
declare r text;
begin
  if auth.uid() is null then raise exception 'not authenticated'; end if;
  if exists (select 1 from public.members where user_id = auth.uid()) then
    return (select role from public.members where user_id = auth.uid());
  end if;
  r := case when exists (select 1 from public.members) then 'member' else 'admin' end;
  insert into public.members(user_id, name, role)
    values (auth.uid(), left(coalesce(nullif(trim(p_name), ''), 'Utilizator'), 40), r);
  return r;
end $$;

revoke execute on function public.join_community(text) from public, anon;
grant  execute on function public.join_community(text) to authenticated;
