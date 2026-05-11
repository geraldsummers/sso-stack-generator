package org.webservices.keycloak.onboarding;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OnboardingMarkerPolicyTest {
    @Test
    void markerIsRequiredForAnyRequiredAction() {
        assertTrue(OnboardingMarkerPolicy.markerRequired(Set.of("UPDATE_PASSWORD")));
        assertTrue(OnboardingMarkerPolicy.markerRequired(Set.of("CONFIGURE_TOTP")));
        assertFalse(OnboardingMarkerPolicy.markerRequired(Set.of()));
    }

    @Test
    void adminUserIdIsParsedFromUserResourcePaths() {
        assertEquals("abc-123", OnboardingMarkerPolicy.userIdFromAdminResourcePath("users/abc-123"));
        assertEquals("abc-123", OnboardingMarkerPolicy.userIdFromAdminResourcePath("users/abc-123/groups/def"));
        assertNull(OnboardingMarkerPolicy.userIdFromAdminResourcePath("groups/def"));
        assertNull(OnboardingMarkerPolicy.userIdFromAdminResourcePath(""));
    }
}
