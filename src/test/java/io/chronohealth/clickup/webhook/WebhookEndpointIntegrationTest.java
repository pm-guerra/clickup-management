package io.chronohealth.clickup.webhook;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.chronohealth.clickup.security.AdminApiKeyFilter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
class WebhookEndpointIntegrationTest {

    private static final String SECRET = "whsec";
    private static final String BODY = """
            {"webhook_id":"wh-1","event":"taskCreated","task_id":"t-1",
             "history_items":[{"id":"hist-1","type":1,"date":"1700000000000","field":"task_creation","user":{"id":42}}]}
            """;

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private AdminApiKeyFilter adminApiKeyFilter;
    @Autowired
    private WebhookRegistrationStore registrations;
    @Autowired
    private WebhookSignatureVerifier signer;
    @MockitoBean
    private EventDispatcher dispatcher;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(adminApiKeyFilter).build();
        registrations.save(new WebhookRegistration("wh-1", "9001", "https://example.test/clickup/webhook",
                List.of("taskCreated"), SECRET, Instant.now()));
    }

    @Test
    void processesSignedEventOnlyOnce() throws Exception {
        byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
        String signature = signer.sign(body, SECRET);

        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/clickup/webhook").contentType(MediaType.APPLICATION_JSON)
                            .header("X-Signature", signature).content(body))
                    .andExpect(status().isOk());
        }

        verify(dispatcher, times(1)).dispatch(any());
    }

    @Test
    void rejectsBadSignatureAndUnknownWebhook() throws Exception {
        byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
        mvc.perform(post("/clickup/webhook").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature", "deadbeef").content(body))
                .andExpect(status().isUnauthorized());

        byte[] unknown = BODY.replace("wh-1", "wh-2").getBytes(StandardCharsets.UTF_8);
        mvc.perform(post("/clickup/webhook").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature", signer.sign(unknown, SECRET)).content(unknown))
                .andExpect(status().isUnauthorized());

        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    void adminEndpointsRequireApiKey() throws Exception {
        mvc.perform(get("/admin/clickup/webhook")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/clickup/webhook").header("X-Admin-Key", "test-admin-key"))
                .andExpect(status().isOk());
        mvc.perform(get("/admin/clickup/webhook").header("X-Admin-Key", "wrong"))
                .andExpect(status().isUnauthorized());
    }
}
