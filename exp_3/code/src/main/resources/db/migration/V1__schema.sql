CREATE TABLE hotel (
  id BIGSERIAL PRIMARY KEY, name VARCHAR(100) NOT NULL, campus VARCHAR(40) NOT NULL,
  description TEXT NOT NULL DEFAULT '', image VARCHAR(200) NOT NULL DEFAULT '/assets/garden.jpg',
  version BIGINT NOT NULL DEFAULT 1
);
CREATE TABLE app_user (
  id BIGSERIAL PRIMARY KEY, username VARCHAR(40) NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL, display_name VARCHAR(60) NOT NULL,
  role VARCHAR(12) NOT NULL CHECK (role IN ('CUSTOMER','STAFF','ADMIN')),
  hotel_id BIGINT REFERENCES hotel, enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK ((role='STAFF' AND hotel_id IS NOT NULL) OR (role<>'STAFF' AND hotel_id IS NULL))
);
CREATE TABLE room_type (
  id BIGSERIAL PRIMARY KEY, hotel_id BIGINT NOT NULL REFERENCES hotel,
  name VARCHAR(60) NOT NULL, capacity INT NOT NULL CHECK(capacity BETWEEN 1 AND 10),
  description TEXT NOT NULL DEFAULT '', image VARCHAR(200) NOT NULL DEFAULT '/assets/room.jpg',
  active BOOLEAN NOT NULL DEFAULT TRUE, version BIGINT NOT NULL DEFAULT 1
);
CREATE TABLE inventory_day (
  room_type_id BIGINT NOT NULL REFERENCES room_type, stay_date DATE NOT NULL,
  capacity INT NOT NULL CHECK(capacity BETWEEN 0 AND 500),
  public_price BIGINT NOT NULL CHECK(public_price BETWEEN 0 AND 100000000),
  online_price BIGINT NOT NULL CHECK(online_price BETWEEN 0 AND 100000000),
  stop_sale BOOLEAN NOT NULL DEFAULT FALSE, emergency_stop BOOLEAN NOT NULL DEFAULT FALSE,
  version BIGINT NOT NULL DEFAULT 1, PRIMARY KEY(room_type_id,stay_date)
);
CREATE TABLE booking (
  id BIGSERIAL PRIMARY KEY, hotel_id BIGINT NOT NULL REFERENCES hotel,
  room_type_id BIGINT NOT NULL REFERENCES room_type, customer_id BIGINT REFERENCES app_user,
  channel VARCHAR(12) NOT NULL CHECK(channel IN ('ONLINE','OFFLINE')),
  contact VARCHAR(60) NOT NULL, phone VARCHAR(30) NOT NULL,
  check_in DATE NOT NULL, check_out DATE NOT NULL, rooms INT NOT NULL CHECK(rooms BETWEEN 1 AND 30),
  guest_count INT NOT NULL CHECK(guest_count BETWEEN 1 AND 30),
  status VARCHAR(20) NOT NULL CHECK(status IN ('PENDING','CONFIRMED','CHECKED_IN','CHECKED_OUT','CANCELLED','NO_SHOW')),
  amount BIGINT NOT NULL CHECK(amount>=0), created_at TIMESTAMPTZ NOT NULL,
  paid_at TIMESTAMPTZ NOT NULL, deadline TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 1, reason VARCHAR(40),
  parent_id BIGINT REFERENCES booking, student_discount BOOLEAN NOT NULL DEFAULT FALSE,
  CHECK(check_out>check_in), CHECK(channel<>'ONLINE' OR customer_id IS NOT NULL)
);
CREATE INDEX booking_scope ON booking(hotel_id,status,check_in,check_out);
CREATE INDEX booking_customer ON booking(customer_id,created_at DESC);
CREATE INDEX booking_deadline ON booking(deadline) WHERE status='PENDING';
CREATE TABLE booking_night (
  booking_id BIGINT NOT NULL REFERENCES booking, stay_date DATE NOT NULL,
  unit_price BIGINT NOT NULL CHECK(unit_price>=0), rooms INT NOT NULL CHECK(rooms>0),
  allocated BOOLEAN NOT NULL DEFAULT FALSE, PRIMARY KEY(booking_id,stay_date)
);
CREATE INDEX booking_night_active ON booking_night(stay_date,booking_id) WHERE allocated;
CREATE TABLE money_entry (
  id BIGSERIAL PRIMARY KEY, booking_id BIGINT NOT NULL REFERENCES booking,
  kind VARCHAR(16) NOT NULL CHECK(kind IN ('PAYMENT','REFUND','LATE_FEE','EXTENSION')),
  amount BIGINT NOT NULL CHECK(amount>=0), original_id BIGINT REFERENCES money_entry,
  reason VARCHAR(60) NOT NULL, created_at TIMESTAMPTZ NOT NULL,
  UNIQUE(booking_id,kind,reason), CHECK((kind='REFUND')=(original_id IS NOT NULL))
);
CREATE TABLE request_record (
  actor_id BIGINT NOT NULL REFERENCES app_user, action VARCHAR(50) NOT NULL,
  request_key VARCHAR(80) NOT NULL, digest VARCHAR(64) NOT NULL, response JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(actor_id,action,request_key)
);
CREATE TABLE operation_log (
  id BIGSERIAL PRIMARY KEY, actor_id BIGINT REFERENCES app_user, hotel_id BIGINT REFERENCES hotel,
  action VARCHAR(60) NOT NULL, object_type VARCHAR(30) NOT NULL, object_id BIGINT,
  summary VARCHAR(300) NOT NULL, created_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE physical_room (
  id BIGSERIAL PRIMARY KEY, hotel_id BIGINT NOT NULL REFERENCES hotel,
  room_type_id BIGINT NOT NULL REFERENCES room_type, number VARCHAR(20) NOT NULL,
  state VARCHAR(16) NOT NULL CHECK(state IN ('READY','OCCUPIED','DIRTY','DISABLED')),
  UNIQUE(hotel_id,number)
);
CREATE TABLE fee_policy (
  id BIGSERIAL PRIMARY KEY, hotel_id BIGINT NOT NULL REFERENCES hotel,
  hourly_cents BIGINT NOT NULL CHECK(hourly_cents BETWEEN 0 AND 1000000),
  created_by BIGINT NOT NULL REFERENCES app_user, created_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE stay (
  id BIGSERIAL PRIMARY KEY, booking_id BIGINT NOT NULL REFERENCES booking,
  room_id BIGINT NOT NULL REFERENCES physical_room, started_at TIMESTAMPTZ NOT NULL,
  ended_at TIMESTAMPTZ, hourly_cents BIGINT NOT NULL CHECK(hourly_cents>=0),
  UNIQUE(booking_id,room_id)
);
CREATE UNIQUE INDEX one_active_stay ON stay(room_id) WHERE ended_at IS NULL;
CREATE TABLE guest_registration (
  id BIGSERIAL PRIMARY KEY, stay_id BIGINT NOT NULL REFERENCES stay,
  name VARCHAR(60) NOT NULL, phone VARCHAR(30) NOT NULL, document_number VARCHAR(40) NOT NULL
);
CREATE TABLE change_request (
  id BIGSERIAL PRIMARY KEY, hotel_id BIGINT NOT NULL REFERENCES hotel,
  author_id BIGINT NOT NULL REFERENCES app_user, kind VARCHAR(20) NOT NULL CHECK(kind IN ('HOTEL','ROOM_TYPE','INVENTORY')),
  target_id BIGINT NOT NULL, payload JSONB NOT NULL, baseline JSONB NOT NULL,
  status VARCHAR(12) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','APPROVED','REJECTED')),
  reviewer_id BIGINT REFERENCES app_user, note VARCHAR(300), created_at TIMESTAMPTZ NOT NULL,
  reviewed_at TIMESTAMPTZ
);
CREATE TABLE uploaded_image (
  id UUID PRIMARY KEY, hotel_id BIGINT NOT NULL REFERENCES hotel,
  owner_id BIGINT NOT NULL REFERENCES app_user, file_name VARCHAR(100) NOT NULL UNIQUE,
  content_type VARCHAR(30) NOT NULL, created_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE modification (
  id BIGSERIAL PRIMARY KEY, old_booking_id BIGINT NOT NULL UNIQUE REFERENCES booking,
  customer_id BIGINT NOT NULL REFERENCES app_user, payload JSONB NOT NULL,
  state VARCHAR(16) NOT NULL CHECK(state IN ('CANCELLED_OLD','CREATED_NEW','FAILED')),
  new_booking_id BIGINT UNIQUE REFERENCES booking, detail VARCHAR(200), created_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE incident (
  id BIGSERIAL PRIMARY KEY, hotel_id BIGINT NOT NULL REFERENCES hotel,
  room_type_id BIGINT NOT NULL REFERENCES room_type, start_date DATE NOT NULL, end_date DATE NOT NULL,
  reason VARCHAR(300) NOT NULL, status VARCHAR(12) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','CLOSED')),
  actor_id BIGINT NOT NULL REFERENCES app_user, created_at TIMESTAMPTZ NOT NULL, closed_at TIMESTAMPTZ,
  CHECK(end_date>start_date)
);
CREATE TABLE incident_case (
  incident_id BIGINT NOT NULL REFERENCES incident, booking_id BIGINT NOT NULL REFERENCES booking,
  state VARCHAR(20) NOT NULL CHECK(state IN ('REFUNDED','MANUAL','RESOLVED')),
  note VARCHAR(300) NOT NULL DEFAULT '', PRIMARY KEY(incident_id,booking_id)
);
CREATE TABLE reception_request (
  id BIGSERIAL PRIMARY KEY, booking_id BIGINT NOT NULL REFERENCES booking,
  kind VARCHAR(30) NOT NULL, reason VARCHAR(300) NOT NULL,
  status VARCHAR(12) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','RESOLVED')),
  resolution VARCHAR(300), actor_id BIGINT NOT NULL REFERENCES app_user, created_at TIMESTAMPTZ NOT NULL,
  resolved_at TIMESTAMPTZ
);
