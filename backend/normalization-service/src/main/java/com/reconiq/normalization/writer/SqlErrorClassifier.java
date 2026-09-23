package com.reconiq.normalization.writer;

import java.sql.SQLException;

public final class SqlErrorClassifier {

    private SqlErrorClassifier() {
    }

    public static ErrorClass classify(SQLException e) {
        String state = e.getSQLState();
        if (state == null) {
            return ErrorClass.UNKNOWN;
        }
        if (state.startsWith("08") || state.startsWith("53") || state.equals("57P01")
                || state.equals("40001") || state.equals("40P01")) {
            return ErrorClass.ENVIRONMENT;
        }
        if (state.startsWith("22") || state.startsWith("23")) {
            return ErrorClass.DATA;
        }
        return ErrorClass.UNKNOWN;
    }
}