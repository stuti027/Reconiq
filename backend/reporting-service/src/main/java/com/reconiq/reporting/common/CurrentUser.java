package com.reconiq.reporting.common;

/**
 * Who is calling the current request. Endpoints depend only on this interface.
 *
 * AUTH_MODE=dev      -> DevHeaderCurrentUser (reads the X-Dev-User header).
 * AUTH_MODE=keycloak -> implemented in part 8.8 (reads the Keycloak token). Until then the service refuses to
 *                       start in keycloak mode, rather than silently letting everyone in.
 */
public interface CurrentUser {

    ReportingUser get();
}
