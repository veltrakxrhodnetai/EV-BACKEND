INSERT INTO backend.stations (name, address, city, state, latitude, longitude, status)
SELECT seed.name, seed.address, seed.city, seed.state, seed.latitude, seed.longitude, seed.status
FROM (
    VALUES
    ('Veltrak EV Hub - Anna Nagar', 'Plot 12, 2nd Avenue, Anna Nagar', 'Chennai', 'Tamil Nadu', 13.0850, 80.2101, 'Active'),
    ('Veltrak EV Hub - OMR', '45 Sholinganallur Main Rd, OMR', 'Chennai', 'Tamil Nadu', 12.9010, 80.2279, 'Active')
) AS seed(name, address, city, state, latitude, longitude, status)
WHERE NOT EXISTS (SELECT 1 FROM backend.stations);

INSERT INTO backend.chargers (station_id, ocpp_identity, name, max_power_kw, status)
SELECT station.id, seed.ocpp_identity, seed.name, seed.max_power_kw, seed.status
FROM (
    VALUES
    ('Veltrak EV Hub - Anna Nagar', 'BELECTRIQ-001', 'DC Fast Charger 1', 120.0, 'Available'),
    ('Veltrak EV Hub - OMR', 'BELECTRIQ-002', 'DC Fast Charger 2', 120.0, 'Available')
) AS seed(station_name, ocpp_identity, name, max_power_kw, status)
JOIN backend.stations station ON station.name = seed.station_name
WHERE NOT EXISTS (SELECT 1 FROM backend.chargers);

INSERT INTO backend.connectors (charger_id, connector_no, type, max_power_kw, status)
SELECT charger.id, seed.connector_no, seed.type, seed.max_power_kw, seed.status
FROM (
    VALUES
    ('BELECTRIQ-001', 1, 'CCS2', 120.0, 'Available'),
    ('BELECTRIQ-001', 2, 'CCS2', 120.0, 'Available'),
    ('BELECTRIQ-002', 1, 'CCS2', 120.0, 'Available'),
    ('BELECTRIQ-002', 2, 'CCS2', 120.0, 'Available')
) AS seed(ocpp_identity, connector_no, type, max_power_kw, status)
JOIN backend.chargers charger ON charger.ocpp_identity = seed.ocpp_identity
WHERE NOT EXISTS (SELECT 1 FROM backend.connectors);

INSERT INTO backend.tariffs (station_id, price_per_kwh, gst_percent, session_fee, currency)
SELECT station.id, seed.price_per_kwh, seed.gst_percent, seed.session_fee, seed.currency
FROM (
    VALUES
    ('Veltrak EV Hub - Anna Nagar', 20.00, 18.0, 0.0, 'INR'),
    ('Veltrak EV Hub - OMR', 20.00, 18.0, 0.0, 'INR')
) AS seed(station_name, price_per_kwh, gst_percent, session_fee, currency)
JOIN backend.stations station ON station.name = seed.station_name
WHERE NOT EXISTS (SELECT 1 FROM backend.tariffs);
