package io.chronohealth.clickup.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.chronohealth.clickup.client.dto.CustomFieldValue;
import io.chronohealth.clickup.client.dto.CustomTaskType;
import io.chronohealth.clickup.client.dto.NewSubtask;
import io.chronohealth.clickup.client.dto.Task;
import io.chronohealth.clickup.client.dto.TaskStatus;
import io.chronohealth.clickup.client.dto.TaskUpdate;
import io.chronohealth.clickup.client.dto.User;
import io.chronohealth.clickup.client.dto.Webhook;
import io.chronohealth.clickup.client.dto.Workspace;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * ClickUp API v2 client bound to a single OAuth access token. Obtain one via {@link ClickUpClientFactory}.
 * <p>
 * Note: ClickUp calls Workspaces "team" in its API paths.
 */
public class ClickUpClient {

    private final ClickUpHttp http;
    private final String accessToken;

    ClickUpClient(ClickUpHttp http, String accessToken) {
        this.http = http;
        this.accessToken = accessToken;
    }

    public User getAuthorizedUser() {
        return http.execute("getAuthorizedUser", () -> api().get().uri("/user")
                .retrieve().body(UserResponse.class).user());
    }

    public List<Workspace> getAuthorizedWorkspaces() {
        return http.execute("getAuthorizedWorkspaces", () -> api().get().uri("/team")
                .retrieve().body(TeamsResponse.class).teams());
    }

    /**
     * Custom task types of the Workspace ("Bug", "Epic", ...). The built-in "Task" type isn't listed.
     */
    public List<CustomTaskType> getCustomTaskTypes(String workspaceId) {
        return http.execute("getCustomTaskTypes", () -> api().get().uri("/team/{workspaceId}/custom_item", workspaceId)
                .retrieve().body(CustomTaskTypesResponse.class).customItems());
    }

    /**
     * The list's statuses in board order ({@code orderindex}).
     */
    public List<TaskStatus> getListStatuses(String listId) {
        return http.execute("getListStatuses", () -> api().get().uri("/list/{listId}", listId)
                .retrieve().body(ListResponse.class).statuses());
    }

    public Task getTask(String taskId) {
        return getTask(taskId, false);
    }

    /**
     * @param includeSubtasks also return the task's direct subtasks in {@link Task#subtasks()}
     */
    public Task getTask(String taskId, boolean includeSubtasks) {
        return http.execute("getTask", () -> api().get()
                .uri(b -> b.path("/task/{taskId}").queryParam("include_subtasks", includeSubtasks).build(taskId))
                .retrieve().body(Task.class));
    }

    /**
     * Creates a subtask in the same list as its parent.
     */
    public Task createSubtask(String parentTaskId, NewSubtask subtask) {
        return createSubtask(getTask(parentTaskId), subtask);
    }

    /**
     * Creates a subtask in the same list as {@code parent}, which the caller already fetched.
     */
    public Task createSubtask(Task parent, NewSubtask subtask) {
        CreateTaskBody body = new CreateTaskBody(subtask.name(), subtask.description(), subtask.assignees(),
                subtask.status(), subtask.priority(), subtask.dueDate(), subtask.tags(), subtask.customFields(),
                parent.id());
        return http.execute("createSubtask", () -> api().post().uri("/list/{listId}/task", parent.list().id())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().body(Task.class));
    }

    public Task updateTask(String taskId, TaskUpdate update) {
        return http.execute("updateTask", () -> api().put().uri("/task/{taskId}", taskId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(update)
                .retrieve().body(Task.class));
    }

    /**
     * Sets a custom field. The {@code value} shape depends on the field type; see ClickUp's "Set Custom Field Value" docs.
     */
    public void setCustomField(String taskId, String fieldId, Object value) {
        http.run("setCustomField", () -> api().post().uri("/task/{taskId}/field/{fieldId}", taskId, fieldId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new SetCustomFieldBody(value))
                .retrieve().toBodilessEntity());
    }

    public void removeCustomField(String taskId, String fieldId) {
        http.run("removeCustomField", () -> api().delete().uri("/task/{taskId}/field/{fieldId}", taskId, fieldId)
                .retrieve().toBodilessEntity());
    }

    public Webhook createWebhook(String workspaceId, String endpoint, List<String> events) {
        return http.execute("createWebhook", () -> api().post().uri("/team/{workspaceId}/webhook", workspaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateWebhookBody(endpoint, events))
                .retrieve().body(CreateWebhookResponse.class).webhook());
    }

    public List<Webhook> getWebhooks(String workspaceId) {
        return http.execute("getWebhooks", () -> api().get().uri("/team/{workspaceId}/webhook", workspaceId)
                .retrieve().body(WebhooksResponse.class).webhooks());
    }

    public void deleteWebhook(String webhookId) {
        http.run("deleteWebhook", () -> api().delete().uri("/webhook/{webhookId}", webhookId)
                .retrieve().toBodilessEntity());
    }

    private RestClient api() {
        return http.restClient().mutate()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .build();
    }

    private record UserResponse(User user) {
    }

    private record CustomTaskTypesResponse(List<CustomTaskType> customItems) {
    }

    private record ListResponse(String id, List<TaskStatus> statuses) {
    }

    private record TeamsResponse(List<Workspace> teams) {
    }

    private record WebhooksResponse(List<Webhook> webhooks) {
    }

    private record CreateWebhookBody(String endpoint, List<String> events) {
    }

    private record CreateWebhookResponse(String id, Webhook webhook) {
    }

    private record SetCustomFieldBody(Object value) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record CreateTaskBody(String name, String description, List<Long> assignees, String status,
                                  Integer priority, Long dueDate, List<String> tags,
                                  List<CustomFieldValue> customFields, String parent) {
    }
}
