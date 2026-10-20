-- Separate database for the integration tests, so `mvn verify` never wipes
-- the data you're poking at through the API.
CREATE DATABASE aegis_test OWNER aegis;
