-- touch_shared_key() was defined but never called from any client code, so
-- last_used_at never actually updated despite the UI displaying it. Fold the
-- update into set_shared_token() itself — the one RPC every shared-link read
-- actually calls — so it fires exactly when a token is used, with no second
-- call site to forget.
-- $1 (positional) is used instead of the bare parameter name because the
-- parameter is named `token`, same as the column being updated below —
-- referencing it by name would be ambiguous with shared_access_keys.token.
create or replace function set_shared_token(token text)
returns void language sql security definer as $$
  select set_config('app.shared_token', $1, false);
  update shared_access_keys
  set last_used_at = now()
  where shared_access_keys.token = $1
    and is_active = true;
$$;

drop function if exists touch_shared_key(text);

-- fetchSharedLibrary derived the link owner from the first returned title row
-- (data?.[0]?.user_id), which is null when the owner has zero titles. Look it
-- up directly from the key instead, independent of whether they own any titles.
create or replace function shared_key_owner(token_val text)
returns uuid language sql security definer stable as $$
  select user_id from shared_access_keys
  where token = token_val
    and is_active = true
    and (expires_at is null or expires_at > now())
  limit 1;
$$;
