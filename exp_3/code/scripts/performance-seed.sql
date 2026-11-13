DO $$ BEGIN
  IF current_database() NOT LIKE 'hotel_%_test' THEN
    RAISE EXCEPTION 'Performance seed is restricted to an isolated hotel_*_test database';
  END IF;
END $$;
-- Historical no-show orders: consistent initial payments and released night records.
-- They increase query volume without filling the demo's current booking dates.
WITH inserted AS (
  INSERT INTO booking(hotel_id,room_type_id,customer_id,channel,contact,phone,check_in,check_out,
    rooms,guest_count,status,amount,created_at,paid_at,deadline,reason)
  SELECT h.id,r.id,u.id,'ONLINE','性能数据-'||n,'13800000000',
    CURRENT_DATE-((n%180)+2)::int,CURRENT_DATE-((n%180)+1)::int,1,1,'NO_SHOW',45000,
    now()-interval '200 days'+n*interval '1 second',now()-interval '200 days'+n*interval '1 second',
    now()-interval '200 days'+n*interval '1 second'+interval '30 minutes','PERFORMANCE_FIXTURE'
  FROM generate_series(1,10000) n
  CROSS JOIN LATERAL (SELECT id FROM hotel ORDER BY id LIMIT 1) h
  CROSS JOIN LATERAL (SELECT id FROM room_type WHERE hotel_id=h.id ORDER BY id LIMIT 1) r
  CROSS JOIN LATERAL (SELECT id FROM app_user WHERE username='guest') u
  WHERE NOT EXISTS(SELECT 1 FROM booking WHERE reason='PERFORMANCE_FIXTURE')
  RETURNING *
), nights AS (
  INSERT INTO booking_night(booking_id,stay_date,unit_price,rooms,allocated)
  SELECT id,check_in,45000,1,FALSE FROM inserted
)
INSERT INTO money_entry(booking_id,kind,amount,reason,created_at)
SELECT id,'PAYMENT',45000,'INITIAL',paid_at FROM inserted;
ANALYZE booking;
ANALYZE booking_night;
SELECT COUNT(*) AS total_orders FROM booking;
