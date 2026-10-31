package com.erezer.admin.notification;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class NotificationDtos {

    private NotificationDtos() {
    }

    public record NotificationView(long id, String action, String section, String title, String body, String link,
            String actorName, String actorRole, Instant createdAt, boolean read) {
    }

    /** An action as the menu offers it. */
    public record ActionView(String action, String section, String label) {
    }

    /** How one action behaves. */
    public record Rule(@NotNull String action, boolean enabled, boolean push, boolean fromModerator, boolean fromEmployee,
            boolean fromAdmin) {
    }

    /** @param recipients how many people currently see it */
    public record RuleView(String action, String section, String label, boolean enabled, boolean push, boolean fromModerator,
            boolean fromEmployee, boolean fromAdmin, long recipients) {
    }

    public record PersonView(long id, String fullName, String role, long actions, long devices) {
    }

    /** What one person gets for one action. */
    public record Subscription(@NotNull String action, boolean see, boolean push) {
    }

    public record SettingsView(boolean configured, String projectId, String clientEmail, String apiKey, String appId,
            String messagingSenderId, String vapidKey) {
    }

    /** @param serviceAccount the Firebase key file's text; empty keeps the saved one */
    public record SettingsRequest(
            @Size(max = 10000) String serviceAccount,
            @NotBlank @Size(max = 3000) String webConfig,
            @NotBlank @Size(max = 200) String vapidKey) {
    }

    /** What a browser needs to ask for push. None of it is the private key. */
    public record PushConfig(boolean configured, Map<String, String> firebase, String vapidKey) {
    }

    public record DeviceRequest(@NotBlank @Size(max = 512) String token) {
    }

    public record TestResult(int sent, int failed) {
    }

    public record Unread(long count) {
    }

    public record Catalog(List<ActionView> actions) {
    }
}
