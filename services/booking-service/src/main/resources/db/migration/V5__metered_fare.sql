-- Metered fares: a completed trip records what was actually driven and how its final fare was decided.

ALTER TABLE bookings
    ADD COLUMN actual_distance_km DECIMAL(8,2) NULL,   -- distance really driven between start and complete (NULL = not metered)
    ADD COLUMN actual_minutes     INT          NULL,   -- minutes between start and complete
    ADD COLUMN fare_source        VARCHAR(10)  NULL;   -- ESTIMATE or METERED, set when the trip completes

-- trips completed before this change were all priced from the estimate
UPDATE bookings SET fare_source = 'ESTIMATE' WHERE final_fare IS NOT NULL;
