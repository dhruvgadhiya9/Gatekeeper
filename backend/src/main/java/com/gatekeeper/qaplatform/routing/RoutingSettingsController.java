package com.gatekeeper.qaplatform.routing;

import com.gatekeeper.qaplatform.common.error.FieldViolation;
import com.gatekeeper.qaplatform.common.error.RequestValidationException;
import com.gatekeeper.qaplatform.finding.Finding;
import com.gatekeeper.qaplatform.routing.RoutingSettingsService.FieldOptions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@PreAuthorize("hasRole('ADMIN')")
public class RoutingSettingsController {

    private static final String ZOHO_ID = "^$|[A-Za-z0-9_-]{1,64}";
    private static final java.util.regex.Pattern ZOHO_ID_PATTERN = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,64}");

    public record UserMappingResponse(UUID id, String githubUsername, String zohoUserId) {

        static UserMappingResponse from(UserMapping mapping) {
            return new UserMappingResponse(mapping.getId(), mapping.getGithubUsername(), mapping.getZohoUserId());
        }
    }

    public record CreateUserMapping(
            @NotBlank
                    @Pattern(regexp = "^$|[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})", message = "isn't a valid GitHub username")
                    String githubUsername,
            @NotBlank @Pattern(regexp = ZOHO_ID, message = "isn't a valid Zoho user ID") String zohoUserId) {}

    public record UpdateUserMapping(
            @NotBlank @Pattern(regexp = ZOHO_ID, message = "isn't a valid Zoho user ID") String zohoUserId) {}

    public record FieldOptionsRequest(@NotNull Map<String, String> severity, @NotNull Map<String, String> classification) {}

    private final RoutingSettingsService settings;

    public RoutingSettingsController(RoutingSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping("/user-mappings")
    public List<UserMappingResponse> listUserMappings() {
        return settings.listUserMappings().stream().map(UserMappingResponse::from).toList();
    }

    @PostMapping("/user-mappings")
    @ResponseStatus(HttpStatus.CREATED)
    public UserMappingResponse createUserMapping(@Valid @RequestBody CreateUserMapping request) {
        return UserMappingResponse.from(settings.createUserMapping(request.githubUsername(), request.zohoUserId()));
    }

    @PutMapping("/user-mappings/{id}")
    public UserMappingResponse updateUserMapping(@PathVariable UUID id, @Valid @RequestBody UpdateUserMapping request) {
        return UserMappingResponse.from(settings.updateUserMapping(id, request.zohoUserId()));
    }

    @DeleteMapping("/user-mappings/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUserMapping(@PathVariable UUID id) {
        settings.deleteUserMapping(id);
    }

    @GetMapping("/integrations/zoho/portals/{portalId}/field-options")
    public FieldOptions getFieldOptions(@PathVariable String portalId) {
        checkId("portalId", portalId, new ArrayList<>(), true);
        return settings.getFieldOptions(portalId);
    }

    @PutMapping("/integrations/zoho/portals/{portalId}/field-options")
    public FieldOptions replaceFieldOptions(
            @PathVariable String portalId, @Valid @RequestBody FieldOptionsRequest request) {
        List<FieldViolation> violations = new ArrayList<>();
        checkId("portalId", portalId, violations, false);
        checkOptions("severity", request.severity(), names(Finding.Severity.values()), violations);
        checkOptions("classification", request.classification(), names(Finding.Category.values()), violations);
        if (!violations.isEmpty()) {
            throw new RequestValidationException(violations);
        }
        return settings.replaceFieldOptions(portalId, new FieldOptions(request.severity(), request.classification()));
    }

    private static void checkOptions(
            String field, Map<String, String> options, Set<String> allowedKeys, List<FieldViolation> violations) {
        options.forEach((key, id) -> {
            if (!allowedKeys.contains(key)) {
                violations.add(new FieldViolation(field + "." + key, "isn't a known value"));
            }
            checkId(field + "." + key, id, violations, false);
        });
    }

    private static void checkId(String field, String id, List<FieldViolation> violations, boolean throwNow) {
        if (id == null || !ZOHO_ID_PATTERN.matcher(id).matches()) {
            violations.add(new FieldViolation(field, "isn't a valid Zoho ID"));
            if (throwNow) {
                throw new RequestValidationException(violations);
            }
        }
    }

    private static Set<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toSet());
    }
}
