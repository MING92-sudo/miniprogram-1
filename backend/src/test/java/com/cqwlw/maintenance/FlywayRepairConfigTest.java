package com.cqwlw.maintenance;

import com.cqwlw.maintenance.config.FlywayRepairConfig;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 这里保证启动前只清掉失败行、不做 checksum 对齐。 */
class FlywayRepairConfigTest {

    @AfterEach
    void clearFlag() {
        System.clearProperty("flyway.repair");
    }

    @Test
    void clearsFailedRowsByPrimaryKeyThenMigrates() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement select = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        PreparedStatement delete = mock(PreparedStatement.class);

        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(contains("SELECT installed_rank"))).thenReturn(select);
        when(conn.prepareStatement(contains("DELETE FROM flyway_schema_history"))).thenReturn(delete);
        when(select.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getLong(1)).thenReturn(22L);
        when(rs.getString(2)).thenReturn("22");
        when(rs.getString(3)).thenReturn("elevator project name");
        when(delete.executeUpdate()).thenReturn(1);

        Flyway flyway = mock(Flyway.class);
        new FlywayRepairConfig().flywayMigrationStrategy(dataSource).migrate(flyway);

        verify(delete).setLong(1, 22L);
        verify(delete).executeUpdate();
        verify(flyway).migrate();
        verify(flyway, never()).repair();
    }

    @Test
    void keepsHistoryUntouchedWhenNoMigrationFailed() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement select = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);

        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(select);
        when(select.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        Flyway flyway = mock(Flyway.class);
        new FlywayRepairConfig().flywayMigrationStrategy(dataSource).migrate(flyway);

        verify(conn, never()).prepareStatement(contains("DELETE FROM flyway_schema_history"));
        verify(flyway).migrate();
    }

    @Test
    void toleratesMissingHistoryTableOnFirstDeploy() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection conn = mock(Connection.class);

        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenThrow(
                new SQLException("Table 'maintenance.flyway_schema_history' doesn't exist", "42S02", 1146));

        Flyway flyway = mock(Flyway.class);
        new FlywayRepairConfig().flywayMigrationStrategy(dataSource).migrate(flyway);

        verify(flyway).migrate();
    }

    @Test
    void repairFlagStillTakesPrecedence() throws Exception {
        System.setProperty("flyway.repair", "true");
        DataSource dataSource = mock(DataSource.class);

        Flyway flyway = mock(Flyway.class);
        new FlywayRepairConfig().flywayMigrationStrategy(dataSource).migrate(flyway);

        verify(flyway).repair();
        verify(flyway).migrate();
        verifyNoInteractions(dataSource);
    }
}
