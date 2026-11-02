package com.gatekeeper.qaplatform.integration.figma;

import com.gatekeeper.qaplatform.integration.figma.FigmaClient.Frame;
import com.gatekeeper.qaplatform.integration.figma.FigmaDesignService.ExportResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/figma")
@PreAuthorize("hasAnyRole('ADMIN', 'QA_LEAD')")
public class FigmaController {

    public record ExportRequest(@NotNull List<String> frameIds) {}

    private final FigmaDesignService figmaDesigns;

    public FigmaController(FigmaDesignService figmaDesigns) {
        this.figmaDesigns = figmaDesigns;
    }

    @GetMapping("/frames")
    public List<Frame> frames(@PathVariable UUID projectId) {
        return figmaDesigns.listFrames(projectId);
    }

    @PostMapping("/exports")
    public ExportResult export(@PathVariable UUID projectId, @Valid @RequestBody ExportRequest request) {
        return figmaDesigns.export(projectId, request.frameIds());
    }
}
