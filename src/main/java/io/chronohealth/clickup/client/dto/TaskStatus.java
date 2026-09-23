package io.chronohealth.clickup.client.dto;

/**
 * @param orderindex position of the status in its list's board order
 */
public record TaskStatus(String status, String type, Integer orderindex) {
}
