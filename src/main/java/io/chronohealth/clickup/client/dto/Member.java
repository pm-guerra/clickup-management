package io.chronohealth.clickup.client.dto;

/**
 * A Workspace member's id and username. Personal data: use it only to hand on, never store or log the username.
 */
public record Member(Long id, String username) {

    @Override
    public String toString() {
        return "Member[id=" + id + "]";
    }
}
