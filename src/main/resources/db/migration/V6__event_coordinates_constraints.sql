alter table events
    add constraint chk_events_coordinates
        check ((latitude is null) = (longitude is null));

alter table events
    add constraint chk_events_latitude
        check (latitude is null or latitude between -90 and 90);

alter table events
    add constraint chk_events_longitude
        check (longitude is null or longitude between -180 and 180);
