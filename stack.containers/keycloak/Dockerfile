FROM quay.io/keycloak/keycloak:26.5.7

COPY stack.kotlin/keycloak-onboarding-listener/build/libs/keycloak-onboarding-listener-1.0-SNAPSHOT-all.jar /opt/keycloak/providers/keycloak-onboarding-listener.jar

RUN /opt/keycloak/bin/kc.sh build
