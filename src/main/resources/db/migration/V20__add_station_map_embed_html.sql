ALTER TABLE backend.stations
    ADD COLUMN IF NOT EXISTS map_embed_html TEXT;
