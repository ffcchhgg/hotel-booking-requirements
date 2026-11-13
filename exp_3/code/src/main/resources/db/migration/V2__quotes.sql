CREATE TABLE quote (
  id UUID PRIMARY KEY, actor_id BIGINT NOT NULL REFERENCES app_user,
  hotel_id BIGINT NOT NULL REFERENCES hotel, purpose VARCHAR(12) NOT NULL,
  payload JSONB NOT NULL, price_hash VARCHAR(64) NOT NULL, amount BIGINT NOT NULL CHECK(amount>=0),
  created_at TIMESTAMPTZ NOT NULL, expires_at TIMESTAMPTZ NOT NULL,
  used_booking_id BIGINT UNIQUE REFERENCES booking
);
CREATE INDEX quote_expiration ON quote(expires_at);
