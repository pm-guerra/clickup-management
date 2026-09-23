package io.chronohealth.clickup.oauth;

import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/clickup/oauth")
public class OAuthController {

    private final OAuthService oauthService;

    public OAuthController(OAuthService oauthService) {
        this.oauthService = oauthService;
    }

    @GetMapping("/start")
    public ResponseEntity<Void> start() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(oauthService.buildAuthorizationUrl()))
                .build();
    }

    @GetMapping("/callback")
    public Map<String, String> callback(@RequestParam String code, @RequestParam(required = false) String state) {
        String workspaceId = oauthService.completeAuthorization(code, state);
        return Map.of("status", "connected", "workspaceId", workspaceId);
    }

    @ExceptionHandler(OAuthService.InvalidOAuthStateException.class)
    public ResponseEntity<Map<String, String>> invalidState(OAuthService.InvalidOAuthStateException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(OAuthService.WorkspaceNotGrantedException.class)
    public ResponseEntity<Map<String, String>> workspaceNotGranted(OAuthService.WorkspaceNotGrantedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
    }
}
