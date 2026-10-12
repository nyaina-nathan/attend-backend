DO $$ 
BEGIN
    -- 1. Check and add chk_events_coordinates
    IF NOT EXISTS (
        SELECT 1 
        FROM pg_constraint 
        WHERE conname = 'chk_events_coordinates'
    ) THEN
        ALTER TABLE events
            ADD CONSTRAINT chk_events_coordinates
                CHECK ((latitude is null) = (longitude is null));
    END IF;

    -- 2. Check and add chk_events_latitude
    IF NOT EXISTS (
        SELECT 1 
        FROM pg_constraint 
        WHERE conname = 'chk_events_latitude'
    ) THEN
        ALTER TABLE events
            ADD CONSTRAINT chk_events_latitude
                CHECK (latitude is null or latitude between -90 and 90);
    END IF;

    -- 3. Check and add chk_events_longitude
    IF NOT EXISTS (
        SELECT 1 
        FROM pg_constraint 
        WHERE conname = 'chk_events_longitude'
    ) THEN
        ALTER TABLE events
            ADD CONSTRAINT chk_events_longitude
                CHECK (longitude is null or longitude between -180 and 180);
    END IF;
END $$;