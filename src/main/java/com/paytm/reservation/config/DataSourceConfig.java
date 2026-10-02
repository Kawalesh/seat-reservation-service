package com.paytm.reservation.config;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.net.URI;

@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties properties) {
        String url = properties.getUrl();
        String username = properties.getUsername();
        String password = properties.getPassword();

        // Support cloud platforms (Render, Railway, Fly.io, Heroku) passing URI strings in DB_URL or DATABASE_URL
        String envDbUrl = System.getenv("DB_URL");
        if (envDbUrl == null || envDbUrl.isBlank()) {
            envDbUrl = System.getenv("DATABASE_URL");
        }

        if (envDbUrl != null && !envDbUrl.isBlank()) {
            if (envDbUrl.startsWith("postgres://") || envDbUrl.startsWith("postgresql://")) {
                try {
                    URI uri = new URI(envDbUrl);
                    String host = uri.getHost();
                    int port = uri.getPort() > 0 ? uri.getPort() : 5432;
                    String path = uri.getPath(); // e.g. "/seat_reservation"
                    url = "jdbc:postgresql://" + host + ":" + port + path;

                    if (uri.getUserInfo() != null) {
                        String[] userInfo = uri.getUserInfo().split(":", 2);
                        username = userInfo[0];
                        if (userInfo.length > 1) {
                            password = userInfo[1];
                        }
                    }
                    log.info("Normalized cloud database URI to JDBC URL: jdbc:postgresql://{}:{}{}", host, port, path);
                } catch (Exception e) {
                    if (!envDbUrl.startsWith("jdbc:")) {
                        url = "jdbc:" + envDbUrl;
                    } else {
                        url = envDbUrl;
                    }
                }
            } else {
                url = envDbUrl;
            }
        } else if (System.getenv("DB_HOST") != null) {
            String host = System.getenv("DB_HOST");
            String port = System.getenv().getOrDefault("DB_PORT", "5432");
            String name = System.getenv().getOrDefault("DB_NAME", "seat_reservation");
            url = "jdbc:postgresql://" + host + ":" + port + "/" + name;
            log.info("Constructed JDBC URL from DB_HOST/PORT/NAME: {}", url);
        }

        if (System.getenv("DB_USERNAME") != null && !System.getenv("DB_USERNAME").isBlank()) {
            username = System.getenv("DB_USERNAME");
        }
        if (System.getenv("DB_PASSWORD") != null && !System.getenv("DB_PASSWORD").isBlank()) {
            password = System.getenv("DB_PASSWORD");
        }

        HikariDataSource ds = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .url(url)
                .username(username)
                .password(password)
                .build();

        return ds;
    }
}
