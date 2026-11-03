package com.gatekeeper.qaplatform.run.runner;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Requests from sandboxes. Sandboxes run untrusted code, so these are validated like public input. */
public final class RunnerDtos {

    private static final long MAX_FILE_BYTES = 200L * 1024 * 1024;

    private RunnerDtos() {}

    public record UploadRequest(@NotEmpty @Size(max = 500) List<@NotNull @Valid UploadFile> files) {}

    /** @param path relative to the workspace and under {@code results/}, with no way to climb out of it */
    public record UploadFile(
            @NotBlank
                    @Size(max = 500)
                    @Pattern(regexp = "^$|results/(?!.*\\.\\.)[A-Za-z0-9._/-]+", message = "must be a file under results/")
                    String path,
            @Positive @Max(MAX_FILE_BYTES) long sizeBytes,
            @NotBlank
                    @Size(max = 100)
                    @Pattern(regexp = "^$|[a-z]+/[a-z0-9.+-]+", message = "isn't a valid content type")
                    String contentType) {}

    public record UploadResponse(List<UploadTarget> uploads) {}

    public record UploadTarget(String path, String url) {

        @Override
        public String toString() {
            return "UploadTarget[path=" + path + ", url=***]";
        }
    }

    /**
     * @param outcome how the agent run ended
     * @param files what was uploaded
     * @param agentSummary Claude Code's result metadata, kept as reported and never trusted
     */
    public record CallbackRequest(
            @NotNull Outcome outcome,
            @NotNull @PositiveOrZero Integer exitCode,
            @NotNull @Size(max = 500) List<@NotBlank @Size(max = 500) String> files,
            @Size(max = 20_000) String agentSummary) {}

    public enum Outcome {
        COMPLETED,
        FAILED,
        TIMED_OUT
    }
}
