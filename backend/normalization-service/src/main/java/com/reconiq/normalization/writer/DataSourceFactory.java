package com.reconiq.normalization.writer;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/** Builds the Postgres connection pool and applies migrations on it. No database name is hard-coded. */
public final class DataSourceFactory {

    private DataSourceFactory() {
    }

    public static HikariDataSource create(String jdbcUrl, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(1);
        config.setPoolName("normalization-writer");
        return new HikariDataSource(config);
    }

    public static void migrate(DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .schemas("normalization")
                .createSchemas(true)
                .load()
                .migrate();
    }
}