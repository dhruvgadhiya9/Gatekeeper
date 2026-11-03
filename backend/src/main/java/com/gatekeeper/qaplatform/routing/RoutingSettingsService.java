package com.gatekeeper.qaplatform.routing;

import com.gatekeeper.qaplatform.audit.Audited;
import com.gatekeeper.qaplatform.common.error.ConflictException;
import com.gatekeeper.qaplatform.common.error.ResourceNotFoundException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin settings that decide how findings reach Zoho. */
@Service
public class RoutingSettingsService {

    public record FieldOptions(Map<String, String> severity, Map<String, String> classification) {}

    private final UserMappingRepository userMappings;
    private final ZohoFieldMappingRepository fieldMappings;

    public RoutingSettingsService(UserMappingRepository userMappings, ZohoFieldMappingRepository fieldMappings) {
        this.userMappings = userMappings;
        this.fieldMappings = fieldMappings;
    }

    @Transactional(readOnly = true)
    public List<UserMapping> listUserMappings() {
        return userMappings.findAllByOrderByGithubUsernameAsc();
    }

    /** GitHub usernames aren't case sensitive, so they're stored in lower case. */
    @Audited(action = "USER_MAPPING_CREATE", target = "#githubUsername")
    public UserMapping createUserMapping(String githubUsername, String zohoUserId) {
        try {
            return userMappings.saveAndFlush(new UserMapping(githubUsername.toLowerCase(Locale.ROOT), zohoUserId));
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("That GitHub username is already mapped.");
        }
    }

    @Audited(action = "USER_MAPPING_UPDATE", target = "#id")
    @Transactional
    public UserMapping updateUserMapping(UUID id, String zohoUserId) {
        UserMapping mapping = userMappings.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User mapping not found"));
        mapping.setZohoUserId(zohoUserId);
        return mapping;
    }

    @Audited(action = "USER_MAPPING_DELETE", target = "#id")
    @Transactional
    public void deleteUserMapping(UUID id) {
        UserMapping mapping = userMappings.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User mapping not found"));
        userMappings.delete(mapping);
    }

    @Transactional(readOnly = true)
    public FieldOptions getFieldOptions(String portalId) {
        Map<String, String> severity = new TreeMap<>();
        Map<String, String> classification = new TreeMap<>();
        for (ZohoFieldMapping mapping : fieldMappings.findByPortalId(portalId)) {
            (mapping.getKind() == ZohoFieldMapping.Kind.SEVERITY ? severity : classification)
                    .put(mapping.getGatekeeperKey(), mapping.getZohoOptionId());
        }
        return new FieldOptions(severity, classification);
    }

    /** Replaces the portal's mappings. A value left out is no longer mapped. */
    @Audited(action = "ZOHO_FIELD_MAPPING_UPDATE", target = "#portalId")
    @Transactional
    public FieldOptions replaceFieldOptions(String portalId, FieldOptions options) {
        fieldMappings.deleteAllForPortal(portalId);
        options.severity().forEach((key, id) ->
                fieldMappings.save(new ZohoFieldMapping(portalId, ZohoFieldMapping.Kind.SEVERITY, key, id)));
        options.classification().forEach((key, id) ->
                fieldMappings.save(new ZohoFieldMapping(portalId, ZohoFieldMapping.Kind.CLASSIFICATION, key, id)));
        return new FieldOptions(new TreeMap<>(options.severity()), new TreeMap<>(options.classification()));
    }
}
