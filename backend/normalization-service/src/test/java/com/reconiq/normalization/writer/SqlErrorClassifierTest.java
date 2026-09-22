package com.reconiq.normalization.writer;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class SqlErrorClassifierTest {

    @Test
    void classifiesConnectionAndResourceErrorsAsEnvironment() {
        assertThat(SqlErrorClassifier.classify(new SQLException("x", "08001"))).isEqualTo(ErrorClass.ENVIRONMENT);
        assertThat(SqlErrorClassifier.classify(new SQLException("x", "53300"))).isEqualTo(ErrorClass.ENVIRONMENT);
        assertThat(SqlErrorClassifier.classify(new SQLException("x", "57P01"))).isEqualTo(ErrorClass.ENVIRONMENT);
        assertThat(SqlErrorClassifier.classify(new SQLException("x", "40001"))).isEqualTo(ErrorClass.ENVIRONMENT);
        assertThat(SqlErrorClassifier.classify(new SQLException("x", "40P01"))).isEqualTo(ErrorClass.ENVIRONMENT);
    }

    @Test
    void classifiesDataAndConstraintErrorsAsData() {
        assertThat(SqlErrorClassifier.classify(new SQLException("x", "22001"))).isEqualTo(ErrorClass.DATA);
        assertThat(SqlErrorClassifier.classify(new SQLException("x", "23505"))).isEqualTo(ErrorClass.DATA);
    }

    @Test
    void classifiesUnknownOrNullStateAsUnknown() {
        assertThat(SqlErrorClassifier.classify(new SQLException("x", "99999"))).isEqualTo(ErrorClass.UNKNOWN);
        assertThat(SqlErrorClassifier.classify(new SQLException("x"))).isEqualTo(ErrorClass.UNKNOWN);
    }
}