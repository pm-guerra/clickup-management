package io.chronohealth.clickup.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.chronohealth.clickup.client.ClickUpApiException;
import io.chronohealth.clickup.event.EventRepository;
import io.chronohealth.clickup.event.EventRetryJob;
import io.chronohealth.clickup.event.EventStatus;
import io.chronohealth.clickup.event.StoredEvent;
import io.chronohealth.clickup.security.AdminApiKeyFilter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
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
    @Autowired
    private EventRepository events;
    @Autowired
    private EventRetryJob retryJob;
    @Autowired
    private JdbcClient jdbc;
    @MockitoBean
    private EventDispatcher dispatcher;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(adminApiKeyFilter).build();
        jdbc.sql("delete from webhook_event").update();
        registrations.save(new WebhookRegistration("wh-1", "9001", "https://example.test/clickup/webhook",
                List.of("taskCreated"), SECRET, Instant.now()));
    }

    @Test
    void storesAndProcessesSignedEventOnlyOnce() throws Exception {
        deliver(BODY);
        deliver(BODY);

        verify(dispatcher, times(1)).dispatch(any());
        assertThat(onlyEvent().status()).isEqualTo(EventStatus.SUCCEEDED);
        assertThat(onlyEvent().attempts()).isEqualTo(1);
    }

    @Test
    void failedEventIsRetriedByTheRetryJob() throws Exception {
        doThrow(new ClickUpApiException(503, null)).doNothing().when(dispatcher).dispatch(any());

        deliver(BODY);
        StoredEvent failed = onlyEvent();
        assertThat(failed.status()).isEqualTo(EventStatus.FAILED);
        assertThat(failed.lastError()).contains("status=503");

        // Not due yet: backoff hasn't elapsed.
        assertThat(retryJob.run().due()).isZero();

        jdbc.sql("update webhook_event set next_attempt_at = next_attempt_at - interval '1' hour").update();
        EventRetryJob.RunResult result = retryJob.run();

        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(onlyEvent().status()).isEqualTo(EventStatus.SUCCEEDED);
        assertThat(onlyEvent().attempts()).isEqualTo(2);
    }

    @Test
    void nonRetryableFailureGoesDeadAndCanBeRetriedManually() throws Exception {
        doThrow(new ClickUpApiException(400, "ITEM_015")).when(dispatcher).dispatch(any());

        deliver(BODY);
        assertThat(onlyEvent().status()).isEqualTo(EventStatus.DEAD);

        doNothing().when(dispatcher).dispatch(any());
        mvc.perform(post("/admin/events/" + onlyEvent().id() + "/retry").header("X-Admin-Key", "test-admin-key"))
                .andExpect(status().isOk());
        assertThat(onlyEvent().status()).isEqualTo(EventStatus.SUCCEEDED);
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
        assertThat(events.findByStatus(EventStatus.PENDING, 10)).isEmpty();
    }

    @Test
    void adminEndpointsRequireApiKey() throws Exception {
        mvc.perform(get("/admin/clickup/webhook")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/events")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/clickup/webhook").header("X-Admin-Key", "wrong"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/clickup/webhook").header("X-Admin-Key", "test-admin-key"))
                .andExpect(status().isOk());
        mvc.perform(get("/admin/events").header("X-Admin-Key", "test-admin-key"))
                .andExpect(status().isOk());
    }

    private void deliver(String json) throws Exception {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        mvc.perform(post("/clickup/webhook").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature", signer.sign(body, SECRET)).content(body))
                .andExpect(status().isOk());
    }

    private StoredEvent onlyEvent() {
        long id = jdbc.sql("select id from webhook_event").query(Long.class).single();
        return events.findById(id).orElseThrow();
    }
}
