package com.gatekeeper.qaplatform.project;

import com.gatekeeper.qaplatform.audit.Audited;
import com.gatekeeper.qaplatform.common.error.ConflictException;
import com.gatekeeper.qaplatform.common.error.FieldViolation;
import com.gatekeeper.qaplatform.common.error.RequestValidationException;
import com.gatekeeper.qaplatform.common.error.ResourceNotFoundException;
import com.gatekeeper.qaplatform.common.storage.ObjectStorage;
import com.gatekeeper.qaplatform.common.storage.StorageCleanup;
import com.gatekeeper.qaplatform.project.dto.ProjectDetailResponse.DesignUploadResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Stores uploaded design images. Everything about a file that the browser claims is treated as
 * untrusted: the type comes from the file's own bytes, and storage keys never use its name.
 */
@Service
public class DesignUploadService {

    public static final int MAX_FILES_PER_PROJECT = 50;
    private static final long MAX_FILE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_NAME_LENGTH = 255;

    private enum ImageType {
        PNG("image/png", "png", new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'}),
        JPEG("image/jpeg", "jpg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});

        final String contentType;
        final String extension;
        final byte[] signature;

        ImageType(String contentType, String extension, byte[] signature) {
            this.contentType = contentType;
            this.extension = extension;
            this.signature = signature;
        }

        boolean matches(byte[] header) {
            return header.length >= signature.length
                    && Arrays.equals(Arrays.copyOf(header, signature.length), signature);
        }
    }

    private final ProjectRepository projects;
    private final DesignSourceRepository designSources;
    private final DesignUploadRepository uploads;
    private final ObjectStorage storage;
    private final StorageCleanup storageCleanup;

    public DesignUploadService(
            ProjectRepository projects,
            DesignSourceRepository designSources,
            DesignUploadRepository uploads,
            ObjectStorage storage,
            StorageCleanup storageCleanup) {
        this.projects = projects;
        this.designSources = designSources;
        this.uploads = uploads;
        this.storage = storage;
        this.storageCleanup = storageCleanup;
    }

    /** Checks every file first, so one bad file means none are stored. */
    @Audited(action = "DESIGN_UPLOAD", target = "#projectId", details = "{files: #files.size()}")
    public List<DesignUploadResponse> upload(UUID projectId, List<MultipartFile> files) {
        if (!projects.existsById(projectId)) {
            throw new ResourceNotFoundException("Project not found");
        }
        DesignSource source = designSources.findByProjectId(projectId).stream()
                .filter(item -> item.getType() == DesignSource.Type.UPLOAD)
                .findFirst()
                .orElseThrow(() -> new ConflictException("This project doesn't use uploaded designs."));
        if (uploads.countByDesignSourceId(source.getId()) + files.size() > MAX_FILES_PER_PROJECT) {
            throw new RequestValidationException(
                    "files", "A project can have at most " + MAX_FILES_PER_PROJECT + " design images.");
        }

        List<ImageType> types = checkFiles(files);
        List<DesignUploadResponse> stored = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            stored.add(store(projectId, source.getId(), files.get(i), types.get(i)));
        }
        return stored;
    }

    @Audited(action = "DESIGN_UPLOAD_DELETE", target = "#projectId", details = "{uploadId: #uploadId}")
    @Transactional
    public void delete(UUID projectId, UUID uploadId) {
        DesignUpload upload = uploads.findById(uploadId)
                .filter(item -> designSources.findByProjectId(projectId).stream()
                        .anyMatch(source -> source.getId().equals(item.getDesignSourceId())))
                .orElseThrow(() -> new ResourceNotFoundException("Design image not found"));
        uploads.delete(upload);
        storageCleanup.deleteAfterCommit(List.of(upload.getStorageKey()));
    }

    private static List<ImageType> checkFiles(List<MultipartFile> files) {
        List<ImageType> types = new ArrayList<>();
        List<FieldViolation> violations = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            String field = "files[" + i + "]";
            if (file.isEmpty()) {
                violations.add(new FieldViolation(field, "is empty"));
            } else if (file.getSize() > MAX_FILE_BYTES) {
                violations.add(new FieldViolation(field, "must be 10 MB or smaller"));
            } else {
                ImageType type = detectType(file);
                if (type == null) {
                    violations.add(new FieldViolation(field, "must be a PNG or JPEG image"));
                }
                types.add(type);
            }
        }
        if (!violations.isEmpty()) {
            throw new RequestValidationException(violations);
        }
        return types;
    }

    private DesignUploadResponse store(UUID projectId, UUID sourceId, MultipartFile file, ImageType type) {
        String key = "projects/" + projectId + "/design/" + UUID.randomUUID() + "." + type.extension;
        try (InputStream content = file.getInputStream()) {
            storage.put(key, content, file.getSize(), type.contentType);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        DesignUpload upload = uploads.save(new DesignUpload(
                sourceId, cleanFileName(file.getOriginalFilename()), key, type.contentType, file.getSize()));
        return new DesignUploadResponse(
                upload.getId(), upload.getFileName(), upload.getContentType(), upload.getSizeBytes());
    }

    private static ImageType detectType(MultipartFile file) {
        try (InputStream content = file.getInputStream()) {
            byte[] header = content.readNBytes(8);
            return Arrays.stream(ImageType.values()).filter(type -> type.matches(header)).findFirst().orElse(null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Keeps only the last path segment and removes control characters, for display only. */
    public static String cleanFileName(String original) {
        String name = original == null ? "" : original;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.replaceAll("\\p{Cntrl}", "").trim();
        if (name.isEmpty()) {
            return "design";
        }
        return name.length() > MAX_NAME_LENGTH ? name.substring(name.length() - MAX_NAME_LENGTH) : name;
    }
}
