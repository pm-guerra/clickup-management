package io.chronohealth.clickup.client;

import io.chronohealth.clickup.config.ClickUpProperties;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared HTTP plumbing for ClickUp: base URL, timeouts, error mapping, logging and 429 retries.
 * Everything that talks to ClickUp goes through here.
 */
@Component
public class ClickUpHttp {

    private static final Logger log = LoggerFactory.getLogger(ClickUpHttp.class);

    private final RestClient restClient;
    private final JsonMapper jsonMapper;
    private final ClickUpProperties.RateLimit rateLimit;
    private final Clock clock;

    public ClickUpHttp(RestClient.Builder builder, JsonMapper jsonMapper, ClickUpProperties properties, Clock clock) {
        this.jsonMapper = jsonMapper;
        this.rateLimit = properties.rateLimit();
        this.clock = clock;

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        this.restClient = builder.clone()
                .baseUrl(properties.apiBaseUrl())
                .requestFactory(requestFactory)
                .requestInterceptor((request, body, execution) -> {
                    long start = System.nanoTime();
                    ClientHttpResponse response = execution.execute(request, body);
                    // Path only: ids are fine, but never log query strings or bodies.
                    log.debug("ClickUp {} {} -> {} ({} ms)", request.getMethod(), request.getURI().getPath(),
                            response.getStatusCode().value(), (System.nanoTime() - start) / 1_000_000);
                    return response;
                })
                .defaultStatusHandler(HttpStatusCode::isError, (_, response) -> {
                    throw toException(response);
                })
                .build();
    }

    public RestClient restClient() {
        return restClient;
    }

    /**
     * Runs a ClickUp call, waiting and retrying when rate limited (HTTP 429).
     */
    public <T> T execute(String operation, Supplier<T> call) {
        int attempt = 0;
        while (true) {
            try {
                return call.get();
            } catch (ClickUpRateLimitedException e) {
                attempt++;
                if (attempt > rateLimit.maxRetries()) {
                    log.warn("ClickUp rate limit: giving up on {} after {} retries", operation, rateLimit.maxRetries());
                    throw e;
                }
                Duration wait = waitFor(e.resetAt());
                log.warn("ClickUp rate limit on {}; retry {}/{} in {} ms", operation, attempt, rateLimit.maxRetries(), wait.toMillis());
                sleep(wait);
            } catch (ClickUpApiException e) {
                log.warn("ClickUp call {} failed: status={}, ecode={}", operation, e.statusCode(), e.errorCode());
                throw e;
            }
        }
    }

    public void run(String operation, Runnable call) {
        execute(operation, () -> {
            call.run();
            return null;
        });
    }

    private ClickUpApiException toException(ClientHttpResponse response) throws IOException {
        if (response.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
            String reset = response.getHeaders().getFirst("X-RateLimit-Reset");
            Instant resetAt = null;
            if (reset != null) {
                try {
                    resetAt = Instant.ofEpochSecond(Long.parseLong(reset.trim()));
                } catch (NumberFormatException _) {
                    // fall back to the default back-off
                }
            }
            return new ClickUpRateLimitedException(resetAt);
        }
        return new ClickUpApiException(response.getStatusCode().value(), readErrorCode(response));
    }

    private String readErrorCode(ClientHttpResponse response) {
        // Only extract ClickUp's ECODE; the rest of the body may echo task data.
        try {
            JsonNode body = jsonMapper.readTree(response.getBody());
            JsonNode ecode = body.path("ECODE");
            return ecode.isMissingNode() ? null : ecode.asString();
        } catch (Exception _) {
            return null;
        }
    }

    private Duration waitFor(Instant resetAt) {
        Duration wait = resetAt == null ? Duration.ofSeconds(5) : Duration.between(clock.instant(), resetAt).plusMillis(250);
        if (wait.isNegative() || wait.isZero()) {
            wait = Duration.ofMillis(500);
        }
        return wait.compareTo(rateLimit.maxWait()) > 0 ? rateLimit.maxWait() : wait;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for ClickUp rate limit", e);
        }
    }
}
