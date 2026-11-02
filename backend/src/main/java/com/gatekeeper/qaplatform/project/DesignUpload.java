package com.gatekeeper.qaplatform.project;

import com.gatekeeper.qaplatform.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** One approved design image uploaded for a project. */
@Entity
@Table(name = "design_upload")
public class DesignUpload extends BaseEntity {

    private UUID designSourceId;
    private String fileName;
    private String storageKey;
    private String contentType;
    private long sizeBytes;
    private String figmaNodeId;

    protected DesignUpload() {}

    public static DesignUpload fromFigma(
            UUID designSourceId, String fileName, String storageKey, long sizeBytes, String figmaNodeId) {
        DesignUpload upload = new DesignUpload(designSourceId, fileName, storageKey, "image/png", sizeBytes);
        upload.figmaNodeId = figmaNodeId;
        return upload;
    }

    public DesignUpload(UUID designSourceId, String fileName, String storageKey, String contentType, long sizeBytes) {
        this.designSourceId = designSourceId;
        this.fileName = fileName;
        this.storageKey = storageKey;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
    }

    public UUID getDesignSourceId() {
        return designSourceId;
    }

    public String getFileName() {
        return fileName;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }
}
