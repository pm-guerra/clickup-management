package io.chronohealth.clickup.token;

import io.chronohealth.clickup.security.SecretCipher;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcAccessTokenStore implements AccessTokenStore {

    private final JdbcClient jdbc;
    private final SecretCipher cipher;
    private final Clock clock;

    public JdbcAccessTokenStore(JdbcClient jdbc, SecretCipher cipher, Clock clock) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void save(StoredAccessToken token) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        String encrypted = cipher.encrypt(token.accessToken());
        int updated = jdbc.sql("""
                        update clickup_token
                           set encrypted_access_token = :token, authorized_user_id = :userId, updated_at = :now
                         where workspace_id = :workspaceId
                        """)
                .param("token", encrypted)
                .param("userId", token.authorizedUserId())
                .param("now", now)
                .param("workspaceId", token.workspaceId())
                .update();
        if (updated == 0) {
            jdbc.sql("""
                            insert into clickup_token (workspace_id, encrypted_access_token, authorized_user_id, created_at, updated_at)
                            values (:workspaceId, :token, :userId, :now, :now)
                            """)
                    .param("workspaceId", token.workspaceId())
                    .param("token", encrypted)
                    .param("userId", token.authorizedUserId())
                    .param("now", now)
                    .update();
        }
    }

    @Override
    public Optional<StoredAccessToken> find(String workspaceId) {
        return jdbc.sql("select workspace_id, encrypted_access_token, authorized_user_id from clickup_token where workspace_id = :id")
                .param("id", workspaceId)
                .query((rs, _) -> new StoredAccessToken(
                        rs.getString("workspace_id"),
                        cipher.decrypt(rs.getString("encrypted_access_token")),
                        rs.getString("authorized_user_id")))
                .optional();
    }
}
