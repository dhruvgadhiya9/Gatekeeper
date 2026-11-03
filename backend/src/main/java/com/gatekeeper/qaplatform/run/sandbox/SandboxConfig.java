package com.gatekeeper.qaplatform.run.sandbox;

import com.gatekeeper.qaplatform.config.SandboxProperties;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** The Docker daemon is only contacted when sandboxes are turned on. */
@Configuration
public class SandboxConfig {

    @Bean
    @ConditionalOnProperty(name = "app.sandbox.enabled", havingValue = "false", matchIfMissing = true)
    SandboxLauncher disabledSandboxLauncher() {
        return new DisabledSandboxLauncher();
    }

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "app.sandbox.enabled", havingValue = "true")
    static class Enabled {

        private static final Logger log = LoggerFactory.getLogger(Enabled.class);

        @Bean(destroyMethod = "close")
        DockerClient dockerClient() {
            var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
            var http = new ApacheDockerHttpClient.Builder()
                    .dockerHost(config.getDockerHost())
                    .sslConfig(config.getSSLConfig())
                    .connectionTimeout(Duration.ofSeconds(10))
                    .responseTimeout(Duration.ofSeconds(60))
                    .build();
            return DockerClientImpl.getInstance(config, http);
        }

        @Bean
        SandboxLauncher dockerSandboxLauncher(DockerClient docker, SandboxProperties properties, Clock clock) {
            return new DockerSandboxLauncher(docker, properties, clock);
        }

        @Bean
        SandboxReaper sandboxReaper(SandboxLauncher launcher) {
            return new SandboxReaper(launcher);
        }

        /** Enforces time limits from outside, so a sandbox can't outlive its deadline by ignoring it. */
        static class SandboxReaper {

            private final SandboxLauncher launcher;

            SandboxReaper(SandboxLauncher launcher) {
                this.launcher = launcher;
            }

            @Scheduled(fixedDelayString = "PT30S")
            void reap() {
                try {
                    launcher.cleanUp();
                } catch (RuntimeException e) {
                    log.warn("Sandbox clean-up failed: {}", e.getClass().getSimpleName());
                }
            }
        }
    }
}
