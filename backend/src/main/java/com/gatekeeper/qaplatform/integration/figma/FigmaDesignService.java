package com.gatekeeper.qaplatform.integration.figma;

import com.gatekeeper.qaplatform.audit.Audited;
import com.gatekeeper.qaplatform.common.error.ConflictException;
import com.gatekeeper.qaplatform.common.error.RequestValidationException;
import com.gatekeeper.qaplatform.common.error.ResourceNotFoundException;
import com.gatekeeper.qaplatform.common.storage.ObjectStorage;
import com.gatekeeper.qaplatform.integration.figma.FigmaClient.Frame;
import com.gatekeeper.qaplatform.project.DesignSource;
import com.gatekeeper.qaplatform.project.DesignSourceRepository;
import com.gatekeeper.qaplatform.project.DesignUpload;
import com.gatekeeper.qaplatform.project.DesignUploadRepository;
import com.gatekeeper.qaplatform.project.DesignUploadService;
import com.gatekeeper.qaplatform.project.ProjectRepository;
import com.gatekeeper.qaplatform.project.dto.ProjectDetailResponse.DesignUploadResponse;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

/** Turns frames from a project's Figma file into stored PNG images for design comparison. */
@Service
public class FigmaDesignService {

    public record FailedFrame(String id, String name) {}

    public record ExportResult(List<DesignUploadResponse> exported, List<FailedFrame> failed) {}

    private static final int MAX_FRAMES_PER_EXPORT = 50;
    private static final Pattern FRAME_ID = Pattern.compile("[0-9]+[:-][0-9]+");
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private final FigmaClient figma;
    private final ProjectRepository projects;
    private final DesignSourceRepository designSources;
    private final DesignUploadRepository uploads;
    private final ObjectStorage storage;

    public FigmaDesignService(
            FigmaClient figma,
            ProjectRepository projects,
            DesignSourceRepository designSources,
            DesignUploadRepository uploads,
            ObjectStorage storage) {
        this.figma = figma;
        this.projects = projects;
        this.designSources = designSources;
        this.uploads = uploads;
        this.storage = storage;
    }

    @Audited(action = "FIGMA_LIST_FRAMES", target = "#projectId")
    public List<Frame> listFrames(UUID projectId) {
        DesignSource source = figmaSource(projectId);
        return explainingFigmaErrors(() -> figma.listFrames(source.getToken(), source.getFigmaFileKey()));
    }

    /** One frame failing doesn't stop the others. Exporting a frame again replaces its old image. */
    @Audited(action = "FIGMA_EXPORT", target = "#projectId", details = "{frames: #frameIds.size()}")
    public ExportResult export(UUID projectId, List<String> frameIds) {
        checkIds(frameIds);
        DesignSource source = figmaSource(projectId);
        String token = source.getToken();
        String fileKey = source.getFigmaFileKey();

        Map<String, Frame> frames = explainingFigmaErrors(() -> figma.listFrames(token, fileKey)).stream()
                .collect(Collectors.toMap(Frame::id, Function.identity(), (first, second) -> first));
        if (!frames.keySet().containsAll(frameIds)) {
            throw new RequestValidationException("frameIds", "must be frames in this project's Figma file");
        }
        List<String> distinct = List.copyOf(new HashSet<>(frameIds));
        checkLimit(source.getId(), distinct);

        Map<String, String> urls = explainingFigmaErrors(() -> figma.renderFrames(token, fileKey, distinct));
        List<DesignUploadResponse> exported = new ArrayList<>();
        List<FailedFrame> failed = new ArrayList<>();
        for (String id : distinct) {
            Frame frame = frames.get(id);
            Optional<DesignUploadResponse> stored =
                    Optional.ofNullable(urls.get(id)).flatMap(url -> store(projectId, source.getId(), frame, url));
            stored.ifPresentOrElse(exported::add, () -> failed.add(new FailedFrame(id, frame.name())));
        }
        return new ExportResult(exported, failed);
    }

    private Optional<DesignUploadResponse> store(UUID projectId, UUID sourceId, Frame frame, String url) {
        byte[] image;
        try {
            image = figma.downloadImage(url);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (image.length < PNG_SIGNATURE.length
                || !Arrays.equals(Arrays.copyOf(image, PNG_SIGNATURE.length), PNG_SIGNATURE)) {
            return Optional.empty();
        }

        String key = "projects/" + projectId + "/design/" + UUID.randomUUID() + ".png";
        storage.put(key, new ByteArrayInputStream(image), image.length, "image/png");
        uploads.findByDesignSourceIdAndFigmaNodeId(sourceId, frame.id()).ifPresent(old -> {
            uploads.delete(old);
            uploads.flush();
            storage.delete(old.getStorageKey());
        });
        String name = DesignUploadService.cleanFileName(
                (frame.page() + " - " + frame.name()).replace('/', '-').replace('\\', '-') + ".png");
        DesignUpload saved = uploads.save(DesignUpload.fromFigma(sourceId, name, key, image.length, frame.id()));
        return Optional.of(new DesignUploadResponse(
                saved.getId(), saved.getFileName(), saved.getContentType(), saved.getSizeBytes()));
    }

    private static void checkIds(List<String> frameIds) {
        if (frameIds == null || frameIds.isEmpty() || frameIds.size() > MAX_FRAMES_PER_EXPORT) {
            throw new RequestValidationException(
                    "frameIds", "must list between 1 and " + MAX_FRAMES_PER_EXPORT + " frames");
        }
        if (!frameIds.stream().allMatch(id -> id != null && FRAME_ID.matcher(id).matches())) {
            throw new RequestValidationException("frameIds", "contains an invalid frame ID");
        }
    }

    private void checkLimit(UUID sourceId, List<String> frameIds) {
        long replaced = frameIds.stream()
                .filter(id -> uploads.findByDesignSourceIdAndFigmaNodeId(sourceId, id).isPresent())
                .count();
        long total = uploads.countByDesignSourceId(sourceId) + frameIds.size() - replaced;
        if (total > DesignUploadService.MAX_FILES_PER_PROJECT) {
            throw new RequestValidationException(
                    "frameIds", "would exceed " + DesignUploadService.MAX_FILES_PER_PROJECT + " design images for this project");
        }
    }

    private DesignSource figmaSource(UUID projectId) {
        if (!projects.existsById(projectId)) {
            throw new ResourceNotFoundException("Project not found");
        }
        return designSources.findByProjectId(projectId).stream()
                .filter(source -> source.getType() == DesignSource.Type.FIGMA)
                .findFirst()
                .orElseThrow(() -> new ConflictException("This project doesn't use a Figma design."));
    }

    private static <T> T explainingFigmaErrors(Supplier<T> call) {
        try {
            return call.get();
        } catch (HttpClientErrorException.Forbidden e) {
            throw new ConflictException("Figma rejected the stored token. Edit the project and enter a new one.");
        } catch (HttpClientErrorException.NotFound e) {
            throw new ConflictException("Figma can't find this file. Check the link, and that the token can open it.");
        }
    }
}
