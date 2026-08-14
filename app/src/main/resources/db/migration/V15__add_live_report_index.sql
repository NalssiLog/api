CREATE INDEX idx_weather_report_live_status_created
    ON weather_report (moderation_status, created_at DESC, id DESC)
    INCLUDE (
        location_id,
        author_type,
        author_member_id,
        author_anonymous_key,
        temperature,
        precipitation,
        sunlight
    );
