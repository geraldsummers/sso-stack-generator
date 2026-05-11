package org.webservices.keycloak.onboarding;

import java.util.Set;

public final class OnboardingMarkerPolicy {
    public static final String MARKER_GROUP = "onboarding_required";

    private OnboardingMarkerPolicy() {
    }

    public static boolean markerRequired(Set<String> requiredActions) {
        return requiredActions != null && !requiredActions.isEmpty();
    }

    public static String userIdFromAdminResourcePath(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return null;
        }
        String[] parts = resourcePath.split("/");
        for (int i = 0; i < parts.length - 1; i += 1) {
            if ("users".equals(parts[i]) && !parts[i + 1].isBlank()) {
                return parts[i + 1];
            }
        }
        return null;
    }
}
