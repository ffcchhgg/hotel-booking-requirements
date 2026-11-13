DO $$ BEGIN
  IF current_database() NOT LIKE 'hotel_%_test' THEN
    RAISE EXCEPTION 'Checkout fixture requires an isolated test database';
  END IF;
END $$;
WITH selected_room AS (
  SELECT p.* FROM physical_room p WHERE p.hotel_id=1 AND p.state='READY'
  AND NOT EXISTS(SELECT 1 FROM stay s WHERE s.room_id=p.id AND s.ended_at IS NULL)
  ORDER BY p.id DESC LIMIT 1
), inserted AS (
  INSERT INTO booking(hotel_id,room_type_id,channel,contact,phone,check_in,check_out,rooms,guest_count,status,
    amount,created_at,paid_at,deadline,reason)
  SELECT hotel_id,room_type_id,'OFFLINE','退房界面验收','13800000000',CURRENT_DATE-1,CURRENT_DATE,1,1,
    'CHECKED_IN',50000,now()-interval '1 day',now()-interval '1 day',now()-interval '23 hours','CHECKOUT_FIXTURE'
  FROM selected_room RETURNING *
), nights AS (
  INSERT INTO booking_night(booking_id,stay_date,unit_price,rooms,allocated)
  SELECT id,check_in,amount,1,TRUE FROM inserted
), money AS (
  INSERT INTO money_entry(booking_id,kind,amount,reason,created_at)
  SELECT id,'PAYMENT',amount,'INITIAL',paid_at FROM inserted
), occupancy AS (
  INSERT INTO stay(booking_id,room_id,started_at,hourly_cents)
  SELECT b.id,p.id,now()-interval '1 day',5000 FROM inserted b CROSS JOIN selected_room p RETURNING *
), guests AS (
  INSERT INTO guest_registration(stay_id,name,phone,document_number)
  SELECT id,'退房测试住客','13800000000','TESTCHECKOUT001' FROM occupancy
)
UPDATE physical_room SET state='OCCUPIED' WHERE id IN(SELECT room_id FROM occupancy);
SELECT id,status FROM booking WHERE reason='CHECKOUT_FIXTURE' ORDER BY id DESC LIMIT 1;
