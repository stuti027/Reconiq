package com.reconiq.reporting.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.reconiq.reporting.common.BadRequestException;
import com.reconiq.reporting.common.ReportingUser;
import com.reconiq.reporting.common.Role;

class DevHeaderCurrentUserTest {

    private static ReportingUser parse(String header) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (header != null) {
            request.addHeader(DevHeaderCurrentUser.HEADER, header);
        }
        return DevHeaderCurrentUser.parse(request);
    }

    @Test
    void readsNameAndRole() {
        assertThat(parse("asha|REVIEWER")).isEqualTo(new ReportingUser("asha", Role.REVIEWER));
    }

    @Test
    void roleIsCaseInsensitive() {
        assertThat(parse("ravi|admin")).isEqualTo(new ReportingUser("ravi", Role.ADMIN));
    }

    @Test
    void noHeaderMeansDevViewer() {
        assertThat(parse(null)).isEqualTo(new ReportingUser("dev", Role.VIEWER));
    }

    @Test
    void malformedHeaderIsRejected() {
        assertThatThrownBy(() -> parse("asha")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> parse("|ADMIN")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> parse("asha|BOSS")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> parse("a|ADMIN|x")).isInstanceOf(BadRequestException.class);
    }
}
