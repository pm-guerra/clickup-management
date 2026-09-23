package io.chronohealth.clickup.webhook;

import io.chronohealth.clickup.security.SecretCipher;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WebhookRegistrationRepository {

    private static final String COLUMNS = "webhook_id, workspace_id, endpoint, events, encrypted_secret, created_at";

    private final JdbcClient jdbc;
    private final SecretCipher cipher;

    public WebhookRegistrationRepository(JdbcClient jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    public void save(WebhookRegistration registration) {
        jdbc.sql("insert into webhook_registration (" + COLUMNS + ") "
                        + "values (:webhookId, :workspaceId, :endpoint, :events, :secret, :createdAt)")
                .param("webhookId", registration.webhookId())
                .param("workspaceId", registration.workspaceId())
                .param("endpoint", registration.endpoint())
                .param("events", String.join(",", registration.events()))
                .param("secret", cipher.encrypt(registration.secret()))
                .param("createdAt", registration.createdAt())
                .update();
    }

    public Optional<WebhookRegistration> findById(String webhookId) {
        return jdbc.sql("select " + COLUMNS + " from webhook_registration where webhook_id = :id")
                .param("id", webhookId)
                .query(this::map)
                .optional();
    }

    public List<WebhookRegistration> findByWorkspace(String workspaceId) {
        return jdbc.sql("select " + COLUMNS + " from webhook_registration where workspace_id = :id order by created_at")
                .param("id", workspaceId)
                .query(this::map)
                .list();
    }

    public void delete(String webhookId) {
        jdbc.sql("delete from webhook_registration where webhook_id = :id").param("id", webhookId).update();
    }

    private WebhookRegistration map(ResultSet rs, int rowNum) throws SQLException {
        return new WebhookRegistration(
                rs.getString("webhook_id"),
                rs.getString("workspace_id"),
                rs.getString("endpoint"),
                Arrays.asList(rs.getString("events").split(",")),
                cipher.decrypt(rs.getString("encrypted_secret")),
                rs.getObject("created_at", java.time.OffsetDateTime.class));
    }
}
