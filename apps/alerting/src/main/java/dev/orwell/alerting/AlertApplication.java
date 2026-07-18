package dev.orwell.alerting;

import dev.orwell.undertow.ServerRuntime;

/**
 * Alert-relay server. Exposes {@code POST /alerts} which records the alert and, when email is
 * configured, forwards it over SMTP.
 */
public final class AlertApplication {
    public static void main(String[] args) {
        ServerRuntime.runOrExit(
                args,
                AlertEnvs.ENV.schema(),
                AlertSpringApplication::start,
                AlertUndertowApplication::start
        );
    }
}
