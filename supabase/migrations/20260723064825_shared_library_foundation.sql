create extension if not exists pgcrypto with schema extensions;

create table public.profiles (
    id uuid primary key references auth.users(id) on delete cascade,
    display_name text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint profiles_display_name_length check (
        display_name is null or char_length(display_name) between 1 and 80
    )
);

create table public.shared_libraries (
    id uuid primary key default gen_random_uuid(),
    owner_user_id uuid not null references public.profiles(id) on delete restrict,
    name text not null,
    description text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    constraint shared_libraries_name_length check (char_length(trim(name)) between 1 and 80),
    constraint shared_libraries_description_length check (
        description is null or char_length(description) <= 280
    )
);

create table public.memberships (
    library_id uuid not null references public.shared_libraries(id) on delete cascade,
    user_id uuid not null references public.profiles(id) on delete cascade,
    role text not null,
    joined_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (library_id, user_id),
    constraint memberships_role check (role in ('owner', 'librarian', 'reader'))
);

create table public.invitations (
    id uuid primary key default gen_random_uuid(),
    library_id uuid not null references public.shared_libraries(id) on delete cascade,
    invitee_email text not null,
    invited_role text not null default 'reader',
    token_hash bytea not null unique,
    created_by uuid not null references public.profiles(id) on delete cascade,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    accepted_at timestamptz,
    accepted_by uuid references public.profiles(id) on delete set null,
    revoked_at timestamptz,
    constraint invitations_email_normalized check (
        invitee_email = lower(trim(invitee_email)) and char_length(invitee_email) between 3 and 254
    ),
    constraint invitations_role check (invited_role in ('librarian', 'reader')),
    constraint invitations_expiry check (expires_at > created_at)
);

create table public.publications (
    id uuid primary key default gen_random_uuid(),
    library_id uuid not null references public.shared_libraries(id) on delete cascade,
    title text not null,
    author text not null,
    format text not null,
    category text,
    genres text[] not null default '{}',
    series_name text,
    series_index real,
    original_file_name text not null,
    object_key text not null unique,
    cover_object_key text,
    sha256 text not null,
    size_bytes bigint not null,
    status text not null default 'uploading',
    created_by uuid not null references public.profiles(id) on delete restrict,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    constraint publications_title_length check (char_length(trim(title)) between 1 and 300),
    constraint publications_author_length check (char_length(trim(author)) between 1 and 300),
    constraint publications_format check (format in ('epub', 'pdf', 'cbz', 'cbr')),
    constraint publications_sha256 check (sha256 ~ '^[0-9a-f]{64}$'),
    constraint publications_size check (size_bytes > 0 and size_bytes <= 104857600),
    constraint publications_status check (status in ('uploading', 'ready', 'failed'))
);

create unique index publications_live_fingerprint
    on public.publications(library_id, sha256)
    where deleted_at is null;
create index publications_library_created
    on public.publications(library_id, created_at desc)
    where deleted_at is null;
create index memberships_user on public.memberships(user_id, library_id);
create index invitations_library on public.invitations(library_id, created_at desc);

create table public.audit_events (
    id bigint generated always as identity primary key,
    library_id uuid not null references public.shared_libraries(id) on delete cascade,
    actor_user_id uuid references public.profiles(id) on delete set null,
    event_type text not null,
    subject_id uuid,
    created_at timestamptz not null default now()
);

create or replace function public.touch_updated_at()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
    new.updated_at = now();
    return new;
end;
$$;

create trigger profiles_touch_updated_at
before update on public.profiles
for each row execute function public.touch_updated_at();
create trigger libraries_touch_updated_at
before update on public.shared_libraries
for each row execute function public.touch_updated_at();
create trigger memberships_touch_updated_at
before update on public.memberships
for each row execute function public.touch_updated_at();
create trigger publications_touch_updated_at
before update on public.publications
for each row execute function public.touch_updated_at();

create or replace function public.create_profile_for_auth_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    insert into public.profiles(id, display_name)
    values (
        new.id,
        nullif(trim(coalesce(new.raw_user_meta_data ->> 'display_name', '')), '')
    )
    on conflict (id) do nothing;
    return new;
end;
$$;

create trigger auth_user_created_profile
after insert on auth.users
for each row execute function public.create_profile_for_auth_user();

insert into public.profiles(id, display_name)
select id, nullif(trim(coalesce(raw_user_meta_data ->> 'display_name', '')), '')
from auth.users
on conflict (id) do nothing;

create or replace function public.is_library_member(p_library_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select (select auth.uid()) is not null
       and exists (
           select 1
           from public.memberships m
           where m.library_id = p_library_id
             and m.user_id = (select auth.uid())
       );
$$;

create or replace function public.has_library_role(p_library_id uuid, p_roles text[])
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select (select auth.uid()) is not null
       and exists (
           select 1
           from public.memberships m
           where m.library_id = p_library_id
             and m.user_id = (select auth.uid())
             and m.role = any(p_roles)
       );
$$;

alter table public.profiles enable row level security;
alter table public.shared_libraries enable row level security;
alter table public.memberships enable row level security;
alter table public.invitations enable row level security;
alter table public.publications enable row level security;
alter table public.audit_events enable row level security;

create policy profiles_read_self on public.profiles
for select to authenticated
using (id = (select auth.uid()));
create policy profiles_update_self on public.profiles
for update to authenticated
using (id = (select auth.uid()))
with check (id = (select auth.uid()));

create policy libraries_read_members on public.shared_libraries
for select to authenticated
using (deleted_at is null and public.is_library_member(id));
create policy libraries_update_owner on public.shared_libraries
for update to authenticated
using (public.has_library_role(id, array['owner']))
with check (public.has_library_role(id, array['owner']));

create policy memberships_read_members on public.memberships
for select to authenticated
using (public.is_library_member(library_id));
create policy memberships_insert_owner on public.memberships
for insert to authenticated
with check (public.has_library_role(library_id, array['owner']));
create policy memberships_update_owner on public.memberships
for update to authenticated
using (public.has_library_role(library_id, array['owner']))
with check (public.has_library_role(library_id, array['owner']));
create policy memberships_delete_owner on public.memberships
for delete to authenticated
using (public.has_library_role(library_id, array['owner']) and role <> 'owner');

create policy invitations_read_curators on public.invitations
for select to authenticated
using (public.has_library_role(library_id, array['owner', 'librarian']));
create policy invitations_insert_curators on public.invitations
for insert to authenticated
with check (public.has_library_role(library_id, array['owner', 'librarian']));
create policy invitations_update_curators on public.invitations
for update to authenticated
using (public.has_library_role(library_id, array['owner', 'librarian']))
with check (public.has_library_role(library_id, array['owner', 'librarian']));

create policy publications_read_members on public.publications
for select to authenticated
using (deleted_at is null and public.is_library_member(library_id));
create policy publications_insert_curators on public.publications
for insert to authenticated
with check (
    created_by = (select auth.uid())
    and public.has_library_role(library_id, array['owner', 'librarian'])
);
create policy publications_update_curators on public.publications
for update to authenticated
using (public.has_library_role(library_id, array['owner', 'librarian']))
with check (public.has_library_role(library_id, array['owner', 'librarian']));
create policy publications_delete_curators on public.publications
for delete to authenticated
using (public.has_library_role(library_id, array['owner', 'librarian']));

create policy audit_read_owner on public.audit_events
for select to authenticated
using (public.has_library_role(library_id, array['owner']));

create or replace function public.create_shared_library(
    p_name text,
    p_description text default null
)
returns table (
    library_uuid uuid,
    library_name text,
    library_description text
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := (select auth.uid());
    v_library public.shared_libraries;
begin
    if v_user_id is null then
        raise exception 'Sign in to create a shared library.';
    end if;
    if char_length(trim(coalesce(p_name, ''))) not between 1 and 80 then
        raise exception 'Choose a library name between 1 and 80 characters.';
    end if;

    insert into public.shared_libraries(owner_user_id, name, description)
    values (
        v_user_id,
        trim(p_name),
        nullif(trim(coalesce(p_description, '')), '')
    )
    returning * into v_library;

    insert into public.memberships(library_id, user_id, role)
    values (v_library.id, v_user_id, 'owner');
    insert into public.audit_events(library_id, actor_user_id, event_type, subject_id)
    values (v_library.id, v_user_id, 'library.created', v_library.id);

    return query select v_library.id, v_library.name, v_library.description;
end;
$$;

create or replace function public.list_my_libraries()
returns table (
    library_uuid uuid,
    library_name text,
    library_description text,
    member_role text,
    member_count integer,
    publication_count integer
)
language sql
stable
security invoker
set search_path = ''
as $$
    select
        l.id,
        l.name,
        l.description,
        mine.role,
        (select count(*)::integer from public.memberships all_members where all_members.library_id = l.id),
        (
            select count(*)::integer
            from public.publications p
            where p.library_id = l.id and p.deleted_at is null and p.status = 'ready'
        )
    from public.memberships mine
    join public.shared_libraries l on l.id = mine.library_id
    where mine.user_id = (select auth.uid())
      and l.deleted_at is null
    order by l.created_at;
$$;

create or replace function public.create_library_invitation(
    p_library_id uuid,
    p_invitee_email text,
    p_role text default 'reader'
)
returns table (
    invitation_code text,
    library_name text,
    invitee_email text,
    invited_role text,
    expires_at timestamptz
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := (select auth.uid());
    v_email text := lower(trim(coalesce(p_invitee_email, '')));
    v_code text;
    v_expiry timestamptz := now() + interval '7 days';
    v_library_name text;
begin
    if not public.has_library_role(p_library_id, array['owner', 'librarian']) then
        raise exception 'You do not have permission to invite people to this library.';
    end if;
    if p_role not in ('reader', 'librarian') then
        raise exception 'Choose either Reader or Librarian access.';
    end if;
    if p_role = 'librarian' and not public.has_library_role(p_library_id, array['owner']) then
        raise exception 'Only the owner can invite another librarian.';
    end if;
    if v_email !~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$' then
        raise exception 'Enter a valid email address.';
    end if;

    select l.name into v_library_name
    from public.shared_libraries l
    where l.id = p_library_id and l.deleted_at is null;
    if v_library_name is null then
        raise exception 'That shared library is no longer available.';
    end if;

    v_code := replace(
        translate(encode(extensions.gen_random_bytes(12), 'base64'), '+/', '-_'),
        '=',
        ''
    );
    insert into public.invitations(
        library_id, invitee_email, invited_role, token_hash, created_by, expires_at
    )
    values (
        p_library_id,
        v_email,
        p_role,
        extensions.digest(v_code, 'sha256'),
        v_user_id,
        v_expiry
    );
    insert into public.audit_events(library_id, actor_user_id, event_type)
    values (p_library_id, v_user_id, 'invitation.created');

    return query select v_code, v_library_name, v_email, p_role, v_expiry;
end;
$$;

create or replace function public.accept_library_invitation(p_invitation_code text)
returns table (
    library_uuid uuid,
    library_name text,
    library_description text,
    member_role text,
    member_count integer,
    publication_count integer
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := (select auth.uid());
    v_email text := lower(coalesce((select auth.jwt() ->> 'email'), ''));
    v_invitation public.invitations;
begin
    if v_user_id is null or v_email = '' then
        raise exception 'Sign in with the invited email address first.';
    end if;
    if char_length(trim(coalesce(p_invitation_code, ''))) < 12 then
        raise exception 'That invitation link is invalid.';
    end if;

    select i.* into v_invitation
    from public.invitations i
    where i.token_hash = extensions.digest(trim(p_invitation_code), 'sha256')
    for update;

    if v_invitation.id is null
       or v_invitation.revoked_at is not null
       or v_invitation.expires_at <= now() then
        raise exception 'That invitation has expired or was withdrawn.';
    end if;
    if v_invitation.invitee_email <> v_email then
        raise exception 'This invitation belongs to a different email address.';
    end if;

    insert into public.memberships(library_id, user_id, role)
    values (v_invitation.library_id, v_user_id, v_invitation.invited_role)
    on conflict (library_id, user_id) do nothing;

    update public.invitations
    set accepted_at = coalesce(accepted_at, now()),
        accepted_by = coalesce(accepted_by, v_user_id)
    where id = v_invitation.id;
    insert into public.audit_events(library_id, actor_user_id, event_type, subject_id)
    values (v_invitation.library_id, v_user_id, 'membership.joined', v_user_id);

    return query
    select
        l.id,
        l.name,
        l.description,
        m.role,
        (select count(*)::integer from public.memberships all_members where all_members.library_id = l.id),
        (
            select count(*)::integer
            from public.publications p
            where p.library_id = l.id and p.deleted_at is null and p.status = 'ready'
        )
    from public.shared_libraries l
    join public.memberships m
      on m.library_id = l.id and m.user_id = v_user_id
    where l.id = v_invitation.library_id and l.deleted_at is null;
end;
$$;

create or replace function public.begin_library_publication(
    p_library_id uuid,
    p_title text,
    p_author text,
    p_format text,
    p_category text,
    p_genres text[],
    p_series_name text,
    p_series_index real,
    p_original_file_name text,
    p_sha256 text,
    p_size_bytes bigint
)
returns table (publication_uuid uuid)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := (select auth.uid());
    v_publication_id uuid := gen_random_uuid();
    v_extension text := lower(trim(p_format));
begin
    if not public.has_library_role(p_library_id, array['owner', 'librarian']) then
        raise exception 'You do not have permission to publish to this library.';
    end if;
    if char_length(trim(coalesce(p_title, ''))) not between 1 and 300 then
        raise exception 'Every book needs a title.';
    end if;
    if char_length(trim(coalesce(p_author, ''))) not between 1 and 300 then
        raise exception 'Every book needs an author.';
    end if;
    if v_extension not in ('epub', 'pdf', 'cbz', 'cbr') then
        raise exception 'Vellum can publish EPUB, PDF, CBZ, and CBR files.';
    end if;
    if lower(coalesce(p_sha256, '')) !~ '^[0-9a-f]{64}$' then
        raise exception 'The book fingerprint is invalid.';
    end if;
    if p_size_bytes <= 0 or p_size_bytes > 104857600 then
        raise exception 'Books in this household library must be 100 MB or smaller.';
    end if;
    if exists (
        select 1 from public.publications p
        where p.library_id = p_library_id
          and p.sha256 = lower(p_sha256)
          and p.deleted_at is null
    ) then
        raise exception 'That exact edition is already in this shared library.';
    end if;

    insert into public.publications(
        id,
        library_id,
        title,
        author,
        format,
        category,
        genres,
        series_name,
        series_index,
        original_file_name,
        object_key,
        cover_object_key,
        sha256,
        size_bytes,
        status,
        created_by
    )
    values (
        v_publication_id,
        p_library_id,
        trim(p_title),
        trim(p_author),
        v_extension,
        nullif(trim(coalesce(p_category, '')), ''),
        coalesce(p_genres, '{}'),
        nullif(trim(coalesce(p_series_name, '')), ''),
        p_series_index,
        left(trim(p_original_file_name), 255),
        'libraries/' || p_library_id || '/publications/' || v_publication_id || '/original.' || v_extension,
        'libraries/' || p_library_id || '/publications/' || v_publication_id || '/cover.webp',
        lower(p_sha256),
        p_size_bytes,
        'uploading',
        v_user_id
    );
    insert into public.audit_events(library_id, actor_user_id, event_type, subject_id)
    values (p_library_id, v_user_id, 'publication.started', v_publication_id);

    return query select v_publication_id;
end;
$$;

create or replace function public.list_library_publications(
    p_library_id uuid,
    p_query text default ''
)
returns table (
    publication_uuid uuid,
    library_uuid uuid,
    title text,
    author text,
    format text,
    category text,
    genres text[],
    series_name text,
    series_index real,
    sha256 text,
    size_bytes bigint,
    created_at timestamptz,
    status text
)
language sql
stable
security invoker
set search_path = ''
as $$
    select
        p.id,
        p.library_id,
        p.title,
        p.author,
        p.format,
        p.category,
        p.genres,
        p.series_name,
        p.series_index,
        p.sha256,
        p.size_bytes,
        p.created_at,
        p.status
    from public.publications p
    where p.library_id = p_library_id
      and p.deleted_at is null
      and p.status = 'ready'
      and (
          trim(coalesce(p_query, '')) = ''
          or p.title ilike '%' || trim(p_query) || '%'
          or p.author ilike '%' || trim(p_query) || '%'
          or p.category ilike '%' || trim(p_query) || '%'
          or exists (
              select 1
              from unnest(p.genres) genre
              where genre ilike '%' || trim(p_query) || '%'
          )
      )
    order by p.created_at desc;
$$;

revoke all on function public.touch_updated_at() from public;
revoke all on function public.create_profile_for_auth_user() from public;
revoke all on function public.is_library_member(uuid) from public;
revoke all on function public.has_library_role(uuid, text[]) from public;
revoke all on function public.create_shared_library(text, text) from public;
revoke all on function public.list_my_libraries() from public;
revoke all on function public.create_library_invitation(uuid, text, text) from public;
revoke all on function public.accept_library_invitation(text) from public;
revoke all on function public.begin_library_publication(
    uuid, text, text, text, text, text[], text, real, text, text, bigint
) from public;
revoke all on function public.list_library_publications(uuid, text) from public;

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

grant usage on schema public to authenticated;
grant select, update on public.profiles to authenticated;
grant select, update on public.shared_libraries to authenticated;
grant select, insert, update, delete on public.memberships to authenticated;
grant select, insert, update on public.invitations to authenticated;
grant select, insert, update, delete on public.publications to authenticated;
grant select on public.audit_events to authenticated;
grant usage, select on sequence public.audit_events_id_seq to authenticated;
