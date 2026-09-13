-- Backstop for the pessimistic lock in BookingService: at most one CONFIRMED booking
-- per availability slot. Partial on purpose -- CANCELLED rows are ignored, so a cancelled
-- slot can be rebooked. A plain UNIQUE(availability_id) would block rebooking forever.
-- Runs after Hibernate creates the tables (spring.jpa.defer-datasource-initialization=true).
CREATE UNIQUE INDEX IF NOT EXISTS uq_bookings_active_slot
    ON bookings (availability_id)
    WHERE status = 'CONFIRMED';
