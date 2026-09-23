package io.chronohealth.clickup.client;

/**
 * A non-successful ClickUp API response. The message only carries the status and ClickUp's error code,
 * never the response body, so it's safe to log.
 */
public class ClickUpApiException extends RuntimeException {

    private final int statusCode;
    private final String errorCode;

    public ClickUpApiException(int statusCode, String errorCode) {
        super("ClickUp API error: status=" + statusCode + ", ecode=" + errorCode);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }

    public int statusCode() {
        return statusCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
