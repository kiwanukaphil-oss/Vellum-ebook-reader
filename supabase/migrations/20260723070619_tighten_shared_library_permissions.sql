-- Hosted projects grant direct function execution to API roles when routines
-- are created. Revoke those direct grants explicitly; revoking PUBLIC alone is
-- not sufficient on the managed platform.
revoke execute on all functions in schema public from public, anon, authenticated;

-- These are the only RPCs and RLS helpers the signed-in client may invoke.
grant execute on function public.is_library_member(uuid) to authenticated;
grant execute on function public.has_library_role(uuid, text[]) to authenticated;
grant execute on function public.create_shared_library(text, text) to authenticated;
grant execute on function public.list_my_libraries() to authenticated;
grant execute on function public.create_library_invitation(uuid, text, text) to authenticated;
grant execute on function public.accept_library_invitation(text) to authenticated;
grant execute on function public.begin_library_publication(
    uuid, text, text, text, text, text[], text, real, text, text, bigint
) to authenticated;
grant execute on function public.list_library_publications(uuid, text) to authenticated;

-- Cover every foreign key used during deletion or role/catalogue lookups.
create index if not exists shared_libraries_owner_user
    on public.shared_libraries(owner_user_id);
create index if not exists invitations_created_by
    on public.invitations(created_by);
create index if not exists invitations_accepted_by
    on public.invitations(accepted_by)
    where accepted_by is not null;
create index if not exists publications_created_by
    on public.publications(created_by);
create index if not exists audit_events_library
    on public.audit_events(library_id, created_at desc);
create index if not exists audit_events_actor
    on public.audit_events(actor_user_id)
    where actor_user_id is not null;
