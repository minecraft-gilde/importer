package de.gilde.statsimporter.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import de.gilde.statsimporter.config.PluginSettings;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

public final class DatabaseManager implements AutoCloseable {

    private final HikariDataSource dataSource;

    public DatabaseManager(PluginSettings.DatabaseSettings settings) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("stats-importer-pool");
        // In Bukkit/Paper plugin classloaders, JDBC ServiceLoader auto-discovery is not always visible
        // to DriverManager. Explicitly set the driver to avoid "No suitable driver" at startup.
        config.setDriverClassName("org.mariadb.jdbc.Driver");
        config.setJdbcUrl("jdbc:mariadb://" + settings.host() + ":" + settings.port() + "/" + settings.name());
        config.setUsername(settings.user());
        config.setPassword(settings.password());
        config.setMaximumPoolSize(settings.poolMaxSize());
        config.setConnectionTimeout(settings.connectionTimeoutMs());
        config.setAutoCommit(false);
        config.addDataSourceProperty("useUnicode", "true");
        config.addDataSourceProperty("characterEncoding", "utf8");
        config.addDataSourceProperty("tcpKeepAlive", "true");
        this.dataSource = new HikariDataSource(config);
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public DataSource dataSource() {
        return dataSource;
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
