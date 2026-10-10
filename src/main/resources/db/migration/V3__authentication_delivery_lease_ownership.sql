alter table auth_delivery_outbox add column lease_token uuid;

-- A deployment must not inherit work leased by a process running the previous protocol.
-- Returning those rows to pending lets a V3 worker claim them with an explicit owner token.
update auth_delivery_outbox
set state='pending', lease_until=null, lease_token=null, next_attempt_at=current_timestamp
where state='leased';

alter table auth_delivery_outbox
  add constraint chk_auth_delivery_outbox_lease_ownership
  check ((state='leased' and lease_until is not null and lease_token is not null)
      or (state<>'leased' and lease_until is null and lease_token is null));
