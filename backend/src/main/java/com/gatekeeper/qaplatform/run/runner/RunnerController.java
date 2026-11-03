package com.gatekeeper.qaplatform.run.runner;

import com.gatekeeper.qaplatform.run.runner.RunnerDtos.CallbackRequest;
import com.gatekeeper.qaplatform.run.runner.RunnerDtos.UploadRequest;
import com.gatekeeper.qaplatform.run.runner.RunnerDtos.UploadResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The run always comes from the job token, so one sandbox can't touch another run. */
@RestController
@RequestMapping("/internal/runner")
public class RunnerController {

    private final RunnerService runner;

    public RunnerController(RunnerService runner) {
        this.runner = runner;
    }

    @GetMapping("/job")
    public JobSpec job(@AuthenticationPrincipal RunnerPrincipal principal) {
        return runner.jobSpec(principal.runId());
    }

    @PostMapping("/uploads")
    public UploadResponse uploads(
            @AuthenticationPrincipal RunnerPrincipal principal, @Valid @RequestBody UploadRequest request) {
        return new UploadResponse(runner.uploadTargets(principal.runId(), request.files()));
    }

    @PostMapping("/callback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void callback(
            @AuthenticationPrincipal RunnerPrincipal principal, @Valid @RequestBody CallbackRequest request) {
        runner.finish(principal.runId(), request);
    }
}
