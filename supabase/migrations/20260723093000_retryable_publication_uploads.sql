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
    v_existing public.publications;
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

    select p.* into v_existing
    from public.publications p
    where p.library_id = p_library_id
      and p.sha256 = lower(p_sha256)
      and p.deleted_at is null
    for update;

    if v_existing.id is not null then
        if v_existing.status = 'ready' then
            raise exception 'That exact edition is already in this shared library.';
        end if;

        update public.publications
        set title = trim(p_title),
            author = trim(p_author),
            format = v_extension,
            category = nullif(trim(coalesce(p_category, '')), ''),
            genres = coalesce(p_genres, '{}'),
            series_name = nullif(trim(coalesce(p_series_name, '')), ''),
            series_index = p_series_index,
            original_file_name = left(trim(p_original_file_name), 255),
            size_bytes = p_size_bytes,
            status = 'uploading',
            created_by = v_user_id
        where id = v_existing.id;

        insert into public.audit_events(library_id, actor_user_id, event_type, subject_id)
        values (p_library_id, v_user_id, 'publication.retried', v_existing.id);
        return query select v_existing.id;
        return;
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

revoke execute on function public.begin_library_publication(
    uuid, text, text, text, text, text[], text, real, text, text, bigint
) from public, anon;
grant execute on function public.begin_library_publication(
    uuid, text, text, text, text, text[], text, real, text, text, bigint
) to authenticated;
