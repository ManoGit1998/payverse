package com.payverse.common.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * One reusable PostgreSQL container shared by every module's tests (enable reuse with
 * {@code make testcontainers-reuse}). Each module gets its own database, named after
 * {@code spring.application.name} (or {@code payverse.test.database}), so Flyway histories never collide.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainer {

    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withReuse(true)
            .withLabel("com.payverse.testcontainer", "postgres");

    @Bean
    DynamicPropertyRegistrar postgresProperties(Environment environment) {
        return registry -> {
            String database = databaseName(environment);
            ensureStarted();
            createDatabaseIfMissing(database);
            String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
        };
    }

    public static synchronized void ensureStarted() {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
    }

    static String databaseName(Environment environment) {
        String name = environment.getProperty("payverse.test.database",
                environment.getProperty("spring.application.name", "payverse"));
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_") + "_test";
    }

    private static void createDatabaseIfMissing(String database) {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + database + "'")) {
                if (!rs.next()) {
                    statement.execute("CREATE DATABASE " + database);
                }
            }
        } catch (SQLException e) {
            if (!"42P04".equals(e.getSQLState())) { // duplicate_database: created concurrently
                throw new IllegalStateException("Could not create test database " + database, e);
            }
        }
    }
}
