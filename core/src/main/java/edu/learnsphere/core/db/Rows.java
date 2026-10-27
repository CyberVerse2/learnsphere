package edu.learnsphere.core.db;

import java.sql.ResultSet;
import java.sql.SQLException;

/** Helpers for reading nullable and boolean columns. */
final class Rows {

    private Rows() {
    }

    static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    static boolean bool(ResultSet rs, String column) throws SQLException {
        return rs.getInt(column) != 0;
    }

    static int flag(boolean value) {
        return value ? 1 : 0;
    }
}
