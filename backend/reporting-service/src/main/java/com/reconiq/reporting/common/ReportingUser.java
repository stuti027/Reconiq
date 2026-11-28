package com.reconiq.reporting.common;

/** The person calling an endpoint, whatever the login mode. */
public record ReportingUser(String name, Role role) {
}
