create index shared_collections_created_by
    on public.shared_collections(created_by);

create index shared_collection_publications_added_by
    on public.shared_collection_publications(added_by);
