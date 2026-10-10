create table auth_delivery_outbox (
  delivery_id uuid primary key,
  account_id bigint references accounts(id) on delete cascade,
  delivery_type varchar(32) not null
    check (delivery_type in ('email_verification','password_reset','security_notice')),
  recipient varchar(320) not null,
  token_value text not null,
  expires_at timestamptz not null,
  state varchar(16) not null default 'pending'
    check (state in ('pending','leased','sent','dead')),
  attempts integer not null default 0 check (attempts >= 0),
  next_attempt_at timestamptz not null default current_timestamp,
  lease_until timestamptz,
  last_error varchar(500),
  created_at timestamptz not null default current_timestamp,
  sent_at timestamptz,
  check (expires_at > created_at)
);

create index idx_auth_delivery_outbox_due
  on auth_delivery_outbox(state,next_attempt_at,lease_until);

create index idx_auth_delivery_outbox_account
  on auth_delivery_outbox(account_id,created_at desc);
