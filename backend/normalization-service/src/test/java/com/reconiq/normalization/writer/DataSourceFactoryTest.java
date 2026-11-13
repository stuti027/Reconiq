package com.reconiq.normalization.writer;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class DataSourceFactoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Test
    void flywayCreatesSchemaAndTable() throws Exception {
        try (HikariDataSource ds = DataSourceFactory.create(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            DataSourceFactory.migrate(ds);

            try (Connection conn = ds.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT count(*) FROM information_schema.tables "
                                 + "WHERE table_schema='normalization' AND table_name='canonical_transactions'")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }
}