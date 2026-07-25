create table public.shared_collections (
    id uuid primary key default gen_random_uuid(),
    library_id uuid not null references public.shared_libraries(id) on delete cascade,
    name text not null,
    kind text not null default 'manual',
    description text,
    created_by uuid not null references public.profiles(id) on delete restrict,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    constraint shared_collections_name_length check (char_length(trim(name)) between 1 and 80),
    constraint shared_collections_kind check (kind in ('manual', 'series', 'author', 'theme')),
    constraint shared_collections_description_length check (
        description is null or char_length(description) <= 280
    )
);

create unique index shared_collections_live_name
    on public.shared_collections(library_id, lower(trim(name)))
    where deleted_at is null;
create index shared_collections_library_updated
    on public.shared_collections(library_id, updated_at desc)
    where deleted_at is null;

create table public.shared_collection_publications (
    collection_id uuid not null references public.shared_collections(id) on delete cascade,
    publication_id uuid not null references public.publications(id) on delete cascade,
    added_by uuid not null references public.profiles(id) on delete restrict,
    added_at timestamptz not null default now(),
    primary key (collection_id, publication_id)
);

create index shared_collection_publications_publication
    on public.shared_collection_publications(publication_id, collection_id);

create trigger shared_collections_touch_updated_at
before update on public.shared_collections
for each row execute function public.touch_updated_at();

alter table public.shared_collections enable row level security;
alter table public.shared_collection_publications enable row level security;

create policy shared_collections_read_members
on public.shared_collections
for select
to authenticated
using (
    (
        deleted_at is null
        and (select public.is_library_member(library_id))
    )
    or (
        deleted_at is not null
        and (select public.has_library_role(library_id, array['owner', 'librarian']))
    )
);

create policy shared_collections_insert_curators
on public.shared_collections
for insert
to authenticated
with check (
    created_by = (select auth.uid())
    and (select public.has_library_role(library_id, array['owner', 'librarian']))
);

create policy shared_collections_update_curators
on public.shared_collections
for update
to authenticated
using ((select public.has_library_role(library_id, array['owner', 'librarian'])))
with check ((select public.has_library_role(library_id, array['owner', 'librarian'])));

create policy shared_collections_delete_owner
on public.shared_collections
for delete
to authenticated
using ((select public.has_library_role(library_id, array['owner'])));

create policy shared_collection_publications_read_members
on public.shared_collection_publications
for select
to authenticated
using (
    exists (
        select 1
        from public.shared_collections c
        where c.id = collection_id
          and c.deleted_at is null
          and (select public.is_library_member(c.library_id))
    )
);

create policy shared_collection_publications_insert_curators
on public.shared_collection_publications
for insert
to authenticated
with check (
    added_by = (select auth.uid())
    and exists (
        select 1
        from public.shared_collections c
        where c.id = collection_id
          and c.deleted_at is null
          and (select public.has_library_role(c.library_id, array['owner', 'librarian']))
    )
);

create policy shared_collection_publications_delete_curators
on public.shared_collection_publications
for delete
to authenticated
using (
    exists (
        select 1
        from public.shared_collections c
        where c.id = collection_id
          and (select public.has_library_role(c.library_id, array['owner', 'librarian']))
    )
);

drop policy publications_read_members on public.publications;
create policy publications_read_members
on public.publications
for select
to authenticated
using (
    (
        deleted_at is null
        and (select public.is_library_member(library_id))
    )
    or (
        deleted_at is not null
        and (select public.has_library_role(library_id, array['owner', 'librarian']))
    )
);

drop function public.list_library_publications(uuid, text);
create function public.list_library_publications(
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
    updated_at timestamptz,
    status text,
    collection_names text[]
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
        p.updated_at,
        p.status,
        coalesce(
            (
                select array_agg(c.name order by lower(c.name))
                from public.shared_collection_publications cp
                join public.shared_collections c on c.id = cp.collection_id
                where cp.publication_id = p.id
                  and c.deleted_at is null
            ),
            '{}'::text[]
        )
    from public.publications p
    where p.library_id = p_library_id
      and p.deleted_at is null
      and p.status = 'ready'
      and (
          trim(coalesce(p_query, '')) = ''
          or p.title ilike '%' || trim(p_query) || '%'
          or p.author ilike '%' || trim(p_query) || '%'
          or p.category ilike '%' || trim(p_query) || '%'
          or p.series_name ilike '%' || trim(p_query) || '%'
          or exists (
              select 1
              from unnest(p.genres) genre
              where genre ilike '%' || trim(p_query) || '%'
          )
          or exists (
              select 1
              from public.shared_collection_publications cp
              join public.shared_collections c on c.id = cp.collection_id
              where cp.publication_id = p.id
                and c.deleted_at is null
                and c.name ilike '%' || trim(p_query) || '%'
          )
      )
    order by p.created_at desc;
$$;

create function public.list_archived_library_publications(p_library_id uuid)
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
    updated_at timestamptz,
    status text,
    collection_names text[]
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
        p.updated_at,
        p.status,
        '{}'::text[]
    from public.publications p
    where p.library_id = p_library_id
      and p.deleted_at is not null
      and (select public.has_library_role(p_library_id, array['owner', 'librarian']))
    order by p.deleted_at desc;
$$;

create function public.list_library_collections(p_library_id uuid)
returns table (
    collection_uuid uuid,
    library_uuid uuid,
    name text,
    kind text,
    description text,
    publication_uuids uuid[],
    book_count integer,
    created_at timestamptz,
    updated_at timestamptz
)
language sql
stable
security invoker
set search_path = ''
as $$
    select
        c.id,
        c.library_id,
        c.name,
        c.kind,
        c.description,
        coalesce(
            array_agg(p.id order by lower(p.title)) filter (
                where p.id is not null and p.deleted_at is null and p.status = 'ready'
            ),
            '{}'::uuid[]
        ),
        count(p.id) filter (
            where p.id is not null and p.deleted_at is null and p.status = 'ready'
        )::integer,
        c.created_at,
        c.updated_at
    from public.shared_collections c
    left join public.shared_collection_publications cp on cp.collection_id = c.id
    left join public.publications p on p.id = cp.publication_id
    where c.library_id = p_library_id
      and c.deleted_at is null
    group by c.id
    order by lower(c.name);
$$;

create function public.update_library_publication(
    p_publication_id uuid,
    p_title text,
    p_author text,
    p_category text,
    p_genres text[],
    p_series_name text,
    p_series_index real
)
returns table (publication_uuid uuid)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := (select auth.uid());
    v_library_id uuid;
begin
    select p.library_id into v_library_id
    from public.publications p
    where p.id = p_publication_id and p.deleted_at is null;

    if v_library_id is null
       or not public.has_library_role(v_library_id, array['owner', 'librarian']) then
        raise exception 'You do not have permission to edit this publication.';
    end if;
    if char_length(trim(coalesce(p_title, ''))) not between 1 and 300 then
        raise exception 'Every book needs a title.';
    end if;
    if char_length(trim(coalesce(p_author, ''))) not between 1 and 300 then
        raise exception 'Every book needs an author.';
    end if;
    if p_category is not null
       and trim(p_category) not in ('Fiction', 'Non-fiction', 'Comics & Manga', 'Essays & Poetry') then
        raise exception 'Choose a recognised category.';
    end if;
    if cardinality(coalesce(p_genres, '{}')) > 12 then
        raise exception 'A publication can have at most 12 genres.';
    end if;
    if p_series_index is not null and p_series_index < 0 then
        raise exception 'The series position cannot be negative.';
    end if;

    update public.publications
    set title = trim(p_title),
        author = trim(p_author),
        category = nullif(trim(coalesce(p_category, '')), ''),
        genres = coalesce(
            (
                select array_agg(distinct trim(g) order by trim(g))
                from unnest(coalesce(p_genres, '{}')) g
                where char_length(trim(g)) between 1 and 80
            ),
            '{}'
        ),
        series_name = nullif(trim(coalesce(p_series_name, '')), ''),
        series_index = p_series_index
    where id = p_publication_id;

    insert into public.audit_events(library_id, actor_user_id, event_type, subject_id)
    values (v_library_id, v_user_id, 'publication.updated', p_publication_id);

    return query select p_publication_id;
end;
$$;

create function public.set_library_publications_archived(
    p_library_id uuid,
    p_publication_ids uuid[],
    p_archived boolean
)
returns table (changed_count integer)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := (select auth.uid());
    v_changed integer := 0;
begin
    if not public.has_library_role(p_library_id, array['owner', 'librarian']) then
        raise exception 'You do not have permission to manage this library.';
    end if;
    if cardinality(coalesce(p_publication_ids, '{}')) < 1
       or cardinality(p_publication_ids) > 100 then
        raise exception 'Choose between 1 and 100 publications.';
    end if;

    if not p_archived and exists (
        select 1
        from public.publications archived
        join public.publications live
          on live.library_id = archived.library_id
         and live.sha256 = archived.sha256
         and live.deleted_at is null
         and live.id <> archived.id
        where archived.library_id = p_library_id
          and archived.id = any(p_publication_ids)
          and archived.deleted_at is not null
    ) then
        raise exception 'An active copy of one of these books already exists.';
    end if;

    with changed as (
        update public.publications p
        set deleted_at = case when p_archived then now() else null end
        where p.library_id = p_library_id
          and p.id = any(p_publication_ids)
          and (
              (p_archived and p.deleted_at is null)
              or (not p_archived and p.deleted_at is not null)
          )
        returning p.id
    ),
    audited as (
        insert into public.audit_events(library_id, actor_user_id, event_type, subject_id)
        select
            p_library_id,
            v_user_id,
            case when p_archived then 'publication.archived' else 'publication.restored' end,
            changed.id
        from changed
        returning 1
    )
    select count(*)::integer into v_changed from audited;

    return query select v_changed;
end;
$$;

create function public.upsert_library_collection(
    p_library_id uuid,
    p_collection_id uuid,
    p_name text,
    p_kind text,
    p_description text,
    p_publication_ids uuid[]
)
returns table (collection_uuid uuid)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := (select auth.uid());
    v_collection_id uuid := coalesce(p_collection_id, gen_random_uuid());
    v_kind text := lower(trim(coalesce(p_kind, 'manual')));
begin
    if not public.has_library_role(p_library_id, array['owner', 'librarian']) then
        raise exception 'You do not have permission to organise this library.';
    end if;
    if char_length(trim(coalesce(p_name, ''))) not between 1 and 80 then
        raise exception 'Every collection needs a short name.';
    end if;
    if v_kind not in ('manual', 'series', 'author', 'theme') then
        raise exception 'That collection type is not recognised.';
    end if;
    if char_length(coalesce(p_description, '')) > 280 then
        raise exception 'Collection descriptions must be 280 characters or shorter.';
    end if;
    if cardinality(coalesce(p_publication_ids, '{}')) > 300 then
        raise exception 'That collection contains too many books.';
    end if;
    if exists (
        select 1
        from unnest(coalesce(p_publication_ids, '{}')) publication_id
        where not exists (
            select 1
            from public.publications p
            where p.id = publication_id
              and p.library_id = p_library_id
              and p.deleted_at is null
              and p.status = 'ready'
        )
    ) then
        raise exception 'A collection contains a publication outside this library.';
    end if;

    if p_collection_id is null then
        insert into public.shared_collections(
            id, library_id, name, kind, description, created_by
        )
        values (
            v_collection_id,
            p_library_id,
            trim(p_name),
            v_kind,
            nullif(trim(coalesce(p_description, '')), ''),
            v_user_id
        );
    else
        update public.shared_collections
        set name = trim(p_name),
            kind = v_kind,
            description = nullif(trim(coalesce(p_description, '')), ''),
            deleted_at = null
        where id = p_collection_id and library_id = p_library_id;
        if not found then
            raise exception 'That collection is no longer available.';
        end if;
    end if;

    delete from public.shared_collection_publications
    where collection_id = v_collection_id;

    insert into public.shared_collection_publications(collection_id, publication_id, added_by)
    select v_collection_id, publication_id, v_user_id
    from (
        select distinct unnest(coalesce(p_publication_ids, '{}')) as publication_id
    ) selected;

    insert into public.audit_events(library_id, actor_user_id, event_type, subject_id)
    values (
        p_library_id,
        v_user_id,
        case when p_collection_id is null then 'collection.created' else 'collection.updated' end,
        v_collection_id
    );

    return query select v_collection_id;
end;
$$;

create function public.archive_library_collection(
    p_library_id uuid,
    p_collection_id uuid
)
returns table (collection_uuid uuid)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := (select auth.uid());
begin
    if not public.has_library_role(p_library_id, array['owner', 'librarian']) then
        raise exception 'You do not have permission to organise this library.';
    end if;

    update public.shared_collections
    set deleted_at = now()
    where id = p_collection_id
      and library_id = p_library_id
      and deleted_at is null;
    if not found then
        raise exception 'That collection is no longer available.';
    end if;

    insert into public.audit_events(library_id, actor_user_id, event_type, subject_id)
    values (p_library_id, v_user_id, 'collection.archived', p_collection_id);

    return query select p_collection_id;
end;
$$;

revoke all on table public.shared_collections from public, anon;
revoke all on table public.shared_collection_publications from public, anon;
grant select, insert, update, delete on public.shared_collections to authenticated;
grant select, insert, delete on public.shared_collection_publications to authenticated;

revoke all on function public.list_library_publications(uuid, text) from public, anon;
revoke all on function public.list_archived_library_publications(uuid) from public, anon;
revoke all on function public.list_library_collections(uuid) from public, anon;
revoke all on function public.update_library_publication(
    uuid, text, text, text, text[], text, real
) from public, anon;
revoke all on function public.set_library_publications_archived(uuid, uuid[], boolean) from public, anon;
revoke all on function public.upsert_library_collection(
    uuid, uuid, text, text, text, uuid[]
) from public, anon;
revoke all on function public.archive_library_collection(uuid, uuid) from public, anon;

grant execute on function public.list_library_publications(uuid, text) to authenticated;
grant execute on function public.list_archived_library_publications(uuid) to authenticated;
grant execute on function public.list_library_collections(uuid) to authenticated;
grant execute on function public.update_library_publication(
    uuid, text, text, text, text[], text, real
) to authenticated;
grant execute on function public.set_library_publications_archived(uuid, uuid[], boolean) to authenticated;
grant execute on function public.upsert_library_collection(
    uuid, uuid, text, text, text, uuid[]
) to authenticated;
grant execute on function public.archive_library_collection(uuid, uuid) to authenticated;
