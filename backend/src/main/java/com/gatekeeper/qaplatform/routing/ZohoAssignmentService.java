package com.gatekeeper.qaplatform.routing;

import com.gatekeeper.qaplatform.audit.Audited;
import com.gatekeeper.qaplatform.common.error.ConflictException;
import com.gatekeeper.qaplatform.common.error.RequestValidationException;
import com.gatekeeper.qaplatform.common.error.ResourceNotFoundException;
import com.gatekeeper.qaplatform.config.ZohoProperties;
import com.gatekeeper.qaplatform.finding.Finding;
import com.gatekeeper.qaplatform.integration.zoho.ZohoClient;
import com.gatekeeper.qaplatform.integration.zoho.ZohoModels.IssueChanges;
import com.gatekeeper.qaplatform.integration.zoho.ZohoModels.NewIssue;
import com.gatekeeper.qaplatform.integration.zoho.ZohoModels.User;
import com.gatekeeper.qaplatform.project.Project;
import com.gatekeeper.qaplatform.project.ProjectRepository;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

/**
 * The only way to write to Zoho. Each write is audited. Issue creation is idempotent by fingerprint.
 * This class has no transaction on purpose: the fingerprint must be committed before Zoho is called.
 */
@Service
public class ZohoAssignmentService {

    /**
     * @param fingerprint identifies this defect; filing it again returns the existing issue
     * @param assigneeZpuid the owner's Zoho {@code zpuid}, or null to leave unassigned
     */
    public record FileIssue(
            UUID projectId,
            String fingerprint,
            String title,
            String description,
            String assigneeZpuid,
            Finding.Severity severity,
            Finding.Category category) {}

    /** @param created false if the issue already existed */
    public record FiledIssue(String zohoIssueId, boolean created) {}

    private record Claim(ZohoIssueLink link, boolean mayAlreadyExist) {}

    private static final Pattern FINGERPRINT = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    private static final String MARKER_PREFIX = "Gatekeeper-Fingerprint: ";
    /** A PENDING link younger than this means another attempt is probably still running. */
    private static final Duration IN_PROGRESS_WINDOW = Duration.ofMinutes(2);

    private final ZohoClient zoho;
    private final ProjectRepository projects;
    private final ZohoIssueLinkRepository links;
    private final ZohoFieldMappingRepository fieldMappings;
    private final ZohoProperties properties;
    private final RetryRegistry retries;
    private final Clock clock;

    public ZohoAssignmentService(
            ZohoClient zoho,
            ProjectRepository projects,
            ZohoIssueLinkRepository links,
            ZohoFieldMappingRepository fieldMappings,
            ZohoProperties properties,
            RetryRegistry retries,
            Clock clock) {
        this.zoho = zoho;
        this.projects = projects;
        this.links = links;
        this.fieldMappings = fieldMappings;
        this.properties = properties;
        this.retries = retries;
        this.clock = clock;
    }

    /**
     * Creates the issue once. If an earlier attempt's outcome is unknown, Zoho is searched for the
     * fingerprint marker before anything is created.
     */
    @Audited(
            action = "ZOHO_CREATE_ISSUE",
            target = "#result?.zohoIssueId()",
            details = "{projectId: #command.projectId(), fingerprint: #command.fingerprint(), created: #result?.created()}")
    public FiledIssue fileIssue(FileIssue command) {
        checkCommand(command);
        Project project = project(command.projectId());
        Claim claim = claim(command.projectId(), command.fingerprint());
        ZohoIssueLink link = claim.link();
        if (link.getStatus() == ZohoIssueLink.Status.CREATED) {
            return new FiledIssue(link.getZohoIssueId(), false);
        }

        String marker = MARKER_PREFIX + command.fingerprint();
        NewIssue issue = toNewIssue(project.getZohoPortalId(), command, marker);
        AtomicBoolean checkFirst = new AtomicBoolean(claim.mayAlreadyExist());
        AtomicBoolean foundExisting = new AtomicBoolean(false);
        AtomicInteger attempts = new AtomicInteger();
        try {
            String issueId = retries.retry("zoho").executeSupplier(() -> {
                attempts.incrementAndGet();
                if (checkFirst.getAndSet(true)) {
                    Optional<String> existing =
                            zoho.findIssue(project.getZohoPortalId(), project.getZohoProjectId(), command.title(), marker);
                    if (existing.isPresent()) {
                        foundExisting.set(true);
                        return existing.get();
                    }
                }
                return zoho.createIssue(project.getZohoPortalId(), project.getZohoProjectId(), issue);
            });
            link.markCreated(issueId);
            links.save(link);
            return new FiledIssue(issueId, !foundExisting.get());
        } catch (RuntimeException failure) {
            boolean onlyAttemptEver = !claim.mayAlreadyExist() && attempts.get() == 1;
            recordFailure(link, failure, onlyAttemptEver);
            throw failure;
        }
    }

    @Audited(action = "ZOHO_UPDATE_ISSUE", target = "#issueId", details = "{projectId: #projectId}")
    public void updateIssue(UUID projectId, String issueId, IssueChanges changes) {
        Project project = project(projectId);
        zoho.updateIssue(project.getZohoPortalId(), project.getZohoProjectId(), issueId, changes);
    }

    @Audited(action = "ZOHO_COMMENT", target = "#issueId", details = "{projectId: #projectId}")
    public void addComment(UUID projectId, String issueId, String comment) {
        Project project = project(projectId);
        zoho.addComment(project.getZohoPortalId(), project.getZohoProjectId(), issueId, comment);
    }

    /** Gatekeeper stores each user's {@code zpuid}, but Zoho's follower API takes their {@code id}. */
    @Audited(action = "ZOHO_ADD_FOLLOWERS", target = "#issueId", details = "{projectId: #projectId, followers: #followerZpuids}")
    public void addFollowers(UUID projectId, String issueId, List<String> followerZpuids) {
        Project project = project(projectId);
        Map<String, String> idsByZpuid = zoho.listProjectUsers(project.getZohoPortalId(), project.getZohoProjectId()).stream()
                .collect(Collectors.toMap(User::zpuid, User::id, (first, second) -> first));
        List<String> ids = followerZpuids.stream()
                .map(zpuid -> Optional.ofNullable(idsByZpuid.get(zpuid)).orElseThrow(() ->
                        new RequestValidationException("followers", "includes someone who isn't on this Zoho project")))
                .toList();
        zoho.addFollowers(project.getZohoPortalId(), project.getZohoProjectId(), issueId, ids);
    }

    /** Returns false without calling Zoho if attachments are turned off. */
    @Audited(action = "ZOHO_ATTACH_FILE", target = "#issueId", details = "{projectId: #projectId, fileName: #fileName, attached: #result}")
    public boolean attachFile(UUID projectId, String issueId, String fileName, String contentType, byte[] content) {
        if (!properties.attachmentsEnabled()) {
            return false;
        }
        Project project = project(projectId);
        String attachmentId = zoho.uploadAttachment(project.getZohoPortalId(), fileName, contentType, content);
        zoho.associateAttachments(project.getZohoPortalId(), project.getZohoProjectId(), issueId, List.of(attachmentId));
        return true;
    }

    /** The unique constraint makes this safe when two attempts start together. */
    private Claim claim(UUID projectId, String fingerprint) {
        Optional<ZohoIssueLink> saved = links.findByProjectIdAndFingerprint(projectId, fingerprint);
        if (saved.isEmpty()) {
            try {
                return new Claim(links.saveAndFlush(new ZohoIssueLink(projectId, fingerprint)), false);
            } catch (DataIntegrityViolationException lostTheRace) {
                saved = links.findByProjectIdAndFingerprint(projectId, fingerprint);
            }
        }
        ZohoIssueLink link = saved.orElseThrow();
        if (link.getStatus() == ZohoIssueLink.Status.PENDING
                && link.getUpdatedAt().isAfter(clock.instant().minus(IN_PROGRESS_WINDOW))) {
            throw new ConflictException("This issue is already being filed in Zoho.");
        }
        return new Claim(link, link.getStatus() != ZohoIssueLink.Status.CREATED);
    }

    /**
     * The claim is released only if the sole attempt ever made was rejected, so nothing can exist.
     * A 4xx after an unclear attempt came from the search, and says nothing about the earlier create.
     */
    private void recordFailure(ZohoIssueLink link, RuntimeException failure, boolean onlyAttemptEver) {
        boolean rejected = failure instanceof HttpClientErrorException
                && !(failure instanceof HttpClientErrorException.TooManyRequests);
        if (rejected && onlyAttemptEver) {
            links.delete(link);
        } else {
            link.setStatus(ZohoIssueLink.Status.UNCERTAIN);
            links.save(link);
        }
    }

    /**
     * Zoho has no documented way to list option IDs, so they're mapped by an admin. Unmapped values
     * are left out of the request and written into the description, so nothing is lost.
     */
    private NewIssue toNewIssue(String portalId, FileIssue command, String marker) {
        Optional<String> severityId = optionId(portalId, ZohoFieldMapping.Kind.SEVERITY, command.severity());
        Optional<String> classificationId = optionId(portalId, ZohoFieldMapping.Kind.CLASSIFICATION, command.category());

        StringBuilder description = new StringBuilder(command.description() == null ? "" : command.description());
        if (severityId.isEmpty() && command.severity() != null) {
            description.append("\n\nSeverity: ").append(command.severity());
        }
        if (classificationId.isEmpty() && command.category() != null) {
            description.append("\n\nCategory: ").append(command.category());
        }
        description.append("\n\n").append(marker);
        return new NewIssue(
                command.title(),
                description.toString(),
                command.assigneeZpuid(),
                severityId.orElse(null),
                classificationId.orElse(null),
                null);
    }

    private Optional<String> optionId(String portalId, ZohoFieldMapping.Kind kind, Enum<?> value) {
        return value == null
                ? Optional.empty()
                : fieldMappings.findByPortalIdAndKindAndGatekeeperKey(portalId, kind, value.name())
                        .map(ZohoFieldMapping::getZohoOptionId);
    }

    private Project project(UUID id) {
        return projects.findById(id).orElseThrow(() -> new ResourceNotFoundException("Project not found"));
    }

    private static void checkCommand(FileIssue command) {
        if (command.fingerprint() == null || !FINGERPRINT.matcher(command.fingerprint()).matches()) {
            throw new RequestValidationException("fingerprint", "must be 8 to 64 letters, digits, hyphens or underscores");
        }
        if (command.title() == null || command.title().isBlank()) {
            throw new RequestValidationException("title", "must not be blank");
        }
    }
}
