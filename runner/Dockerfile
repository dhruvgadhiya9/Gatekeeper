# The disposable sandbox for one test run. It holds tools but no secrets. The backend gives it a
# short-lived job token at start, and the real credentials never enter it.
#
# The base image brings the Playwright browsers (Chromium, Firefox, WebKit) and Node.
FROM mcr.microsoft.com/playwright:v1.64.1-noble@sha256:2f05f8a734509a7b3f26a912639488ee08f58a7b292d14d62271bcff9038768f

ARG MAVEN_VERSION=3.9.16
ARG MAVEN_SHA512=831a8591fe20c8243b1dbe7d71e3244f31d1665b0804b2e825e38cbbe5ce0cafb8338851f90780735568773e0a6cd07bbec107cda0b896b008b861075358b6f6
ARG GRADLE_VERSION=9.8.2
ARG GRADLE_SHA256=21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607
ARG GO_VERSION=1.27.3
ARG GO_SHA256=65efbc80fdc1fa2cd2631cad5b481777a68e8725f34e89f50fc6ac714e379b40
ARG GITLEAKS_VERSION=8.30.1
ARG GITLEAKS_SHA256=551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb
ARG OSV_VERSION=2.7.0
ARG OSV_SHA256=160c6253e2922e9e0a6a9771c5c4c40a4b3c6090b34a3cdd5e80c96cc2e4218b
ARG ZAP_VERSION=2.17.0
ARG ZAP_SHA256=94c8f767b1c2e94f0db66b3ae56514d5e3f5a728ee1b6c798e0c8fe2d61fbff0

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates curl git jq unzip openjdk-21-jdk-headless python3 python3-venv \
    && rm -rf /var/lib/apt/lists/*

# Each download is checked against a pinned checksum before it's unpacked.
RUN set -eu; \
    curl -fsSL "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/${MAVEN_VERSION}/apache-maven-${MAVEN_VERSION}-bin.tar.gz" -o /tmp/maven.tgz; \
    echo "${MAVEN_SHA512}  /tmp/maven.tgz" | sha512sum -c -; \
    tar -xzf /tmp/maven.tgz -C /opt; ln -s "/opt/apache-maven-${MAVEN_VERSION}/bin/mvn" /usr/local/bin/mvn; \
    curl -fsSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o /tmp/gradle.zip; \
    echo "${GRADLE_SHA256}  /tmp/gradle.zip" | sha256sum -c -; \
    unzip -q /tmp/gradle.zip -d /opt; ln -s "/opt/gradle-${GRADLE_VERSION}/bin/gradle" /usr/local/bin/gradle; \
    curl -fsSL "https://go.dev/dl/go${GO_VERSION}.linux-amd64.tar.gz" -o /tmp/go.tgz; \
    echo "${GO_SHA256}  /tmp/go.tgz" | sha256sum -c -; \
    tar -xzf /tmp/go.tgz -C /usr/local; ln -s /usr/local/go/bin/go /usr/local/bin/go; \
    curl -fsSL "https://github.com/gitleaks/gitleaks/releases/download/v${GITLEAKS_VERSION}/gitleaks_${GITLEAKS_VERSION}_linux_x64.tar.gz" -o /tmp/gitleaks.tgz; \
    echo "${GITLEAKS_SHA256}  /tmp/gitleaks.tgz" | sha256sum -c -; \
    tar -xzf /tmp/gitleaks.tgz -C /usr/local/bin gitleaks; \
    curl -fsSL "https://github.com/google/osv-scanner/releases/download/v${OSV_VERSION}/osv-scanner_linux_amd64" -o /usr/local/bin/osv-scanner; \
    echo "${OSV_SHA256}  /usr/local/bin/osv-scanner" | sha256sum -c -; \
    chmod 0755 /usr/local/bin/osv-scanner; \
    curl -fsSL "https://github.com/zaproxy/zaproxy/releases/download/v${ZAP_VERSION}/ZAP_${ZAP_VERSION}_Crossplatform.zip" -o /tmp/zap.zip; \
    echo "${ZAP_SHA256}  /tmp/zap.zip" | sha256sum -c -; \
    unzip -q /tmp/zap.zip -d /opt; ln -s "/opt/ZAP_${ZAP_VERSION}/zap.sh" /usr/local/bin/zap.sh; \
    rm -f /tmp/maven.tgz /tmp/gradle.zip /tmp/go.tgz /tmp/gitleaks.tgz /tmp/zap.zip

RUN python3 -m venv /opt/venv \
    && /opt/venv/bin/pip install --no-cache-dir semgrep==1.183.0 pytest==9.2.0 \
    && ln -s /opt/venv/bin/semgrep /usr/local/bin/semgrep \
    && ln -s /opt/venv/bin/pytest /usr/local/bin/pytest

RUN npm install --global --omit=dev \
        @anthropic-ai/claude-code@2.1.317 \
        @playwright/test@1.64.1 \
        @axe-core/playwright@4.14.0 \
        axe-core@4.14.0

RUN useradd --uid 10001 --no-create-home --home-dir /workspace/home --shell /bin/bash runner \
    && mkdir -p /workspace /opt/runner/prompts \
    && chown runner:runner /workspace

COPY prompts/ /opt/runner/prompts/
COPY entrypoint.sh /opt/runner/entrypoint.sh
COPY tools/zap-baseline /usr/local/bin/zap-baseline
RUN chmod 0555 /opt/runner/entrypoint.sh /usr/local/bin/zap-baseline \
    && chmod -R a+rX /opt/runner

ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright \
    NODE_PATH=/usr/lib/node_modules \
    HOME=/workspace/home \
    TMPDIR=/tmp \
    GIT_TERMINAL_PROMPT=0

VOLUME ["/workspace"]
WORKDIR /workspace
USER 10001:10001
ENTRYPOINT ["/opt/runner/entrypoint.sh"]
