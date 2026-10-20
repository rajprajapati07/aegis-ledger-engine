package com.aegis.ledger.store;

import org.junit.jupiter.api.Assumptions;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Real Postgres for integration tests. Mocks can't tell you whether your row
 * locks actually serialise anything, so these run against the real thing.
 *
 * Defaults match docker-compose (`docker compose up -d postgres`). Override
 * with AEGIS_TEST_DB_URL / _USER / _PASSWORD. If nothing is listening, the
 * tests are skipped rather than failed, so `mvn verify` on a laptop without
 * Docker running still gives a clean unit-test run.
 */
final class TestDatabase {

    private TestDatabase() {
    }

    static DataSource freshSchema() {
        String url = env("AEGIS_TEST_DB_URL", "jdbc:postgresql://localhost:5432/aegis_test");
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(url);
        ds.setUser(env("AEGIS_TEST_DB_USER", "aegis"));
        ds.setPassword(env("AEGIS_TEST_DB_PASSWORD", "aegis"));

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS public CASCADE");
            st.execute("CREATE SCHEMA public");
            st.execute(migration());
        } catch (SQLException e) {
            Assumptions.assumeTrue(false,
                    "Postgres not reachable at " + url + " (" + e.getMessage() + "). "
                            + "Start it with: docker compose up -d postgres");
        }
        return ds;
    }

    private static String migration() {
        try (InputStream in = TestDatabase.class.getResourceAsStream("/db/migration/V1__ledger_schema.sql")) {
            if (in == null) {
                throw new IllegalStateException("migration script not on the test classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
