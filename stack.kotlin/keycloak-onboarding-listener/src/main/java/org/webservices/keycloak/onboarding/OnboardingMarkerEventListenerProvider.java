package org.webservices.keycloak.onboarding;

import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import java.util.Set;
import java.util.stream.Collectors;

public final class OnboardingMarkerEventListenerProvider implements EventListenerProvider {
    private final KeycloakSession session;

    public OnboardingMarkerEventListenerProvider(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public void onEvent(Event event) {
        if (event != null) {
            syncUser(event.getRealmId(), event.getUserId(), "user:" + event.getType());
        }
    }

    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
        if (event != null) {
            syncUser(
                event.getRealmId(),
                OnboardingMarkerPolicy.userIdFromAdminResourcePath(event.getResourcePath()),
                "admin:" + event.getOperationType()
            );
        }
    }

    private void syncUser(String realmId, String userId, String reason) {
        if (realmId == null || userId == null || userId.isBlank()) {
            return;
        }

        RealmModel realm = session.realms().getRealm(realmId);
        if (realm == null) {
            log("realm-missing", realmId, userId, reason, Set.of());
            return;
        }

        GroupModel marker = realm.getTopLevelGroupsStream()
            .filter(group -> OnboardingMarkerPolicy.MARKER_GROUP.equals(group.getName()))
            .findFirst()
            .orElse(null);
        if (marker == null) {
            log("marker-group-missing", realm.getName(), userId, reason, Set.of());
            return;
        }

        UserModel user = session.users().getUserById(realm, userId);
        if (user == null) {
            log("user-missing", realm.getName(), userId, reason, Set.of());
            return;
        }

        Set<String> requiredActions = user.getRequiredActionsStream().collect(Collectors.toUnmodifiableSet());
        boolean shouldHaveMarker = OnboardingMarkerPolicy.markerRequired(requiredActions);
        boolean hasMarker = user.isMemberOf(marker);

        if (shouldHaveMarker && !hasMarker) {
            user.joinGroup(marker);
            log("marker-added", realm.getName(), user.getUsername(), reason, requiredActions);
            return;
        }

        if (!shouldHaveMarker && hasMarker) {
            user.leaveGroup(marker);
            log("marker-removed", realm.getName(), user.getUsername(), reason, requiredActions);
        }
    }

    private void log(String action, String realm, String user, String reason, Set<String> requiredActions) {
        System.out.printf(
            "[onboarding-marker] action=%s realm=%s user=%s reason=%s requiredActions=%s%n",
            action,
            realm,
            user,
            reason,
            requiredActions
        );
    }

    @Override
    public void close() {
    }
}
