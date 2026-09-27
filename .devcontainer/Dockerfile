# Use Ubuntu as base for maximum compatibility
FROM ubuntu:24.04

# Avoid prompts from apt
ENV DEBIAN_FRONTEND=noninteractive

# Target architecture (amd64 or arm64). BuildKit and Buildah set TARGETARCH;
# other builders leave it empty, and each download step then falls back to
# the image's own architecture (dpkg --print-architecture).
ARG TARGETARCH

# Install basic dependencies including zsh
RUN apt-get update && apt-get install -y \
    curl \
    wget \
    git \
    build-essential \
    ca-certificates \
    gnupg \
    lsb-release \
    sudo \
    vim \
    unzip \
    zsh \
    fonts-powerline \
    python3 \
    python3-yaml \
    && rm -rf /var/lib/apt/lists/*

# Install Java 25 LTS (Eclipse Temurin)
# Released September 2025, update 25.0.1 available November 2025
RUN wget -O - https://packages.adoptium.net/artifactory/api/gpg/key/public | apt-key add - && \
    echo "deb https://packages.adoptium.net/artifactory/deb $(lsb_release -cs) main" | tee /etc/apt/sources.list.d/adoptium.list && \
    apt-get update && \
    apt-get install -y temurin-25-jdk && \
    rm -rf /var/lib/apt/lists/*

# Set Java environment variables. The package installs into an
# architecture-specific directory (temurin-25-jdk-amd64 or -arm64); a stable
# symlink lets JAVA_HOME stay the same on every architecture.
RUN JAVA_DIR=$(ls -d /usr/lib/jvm/temurin-25-jdk-* 2>/dev/null | head -n 1) && \
    test -n "${JAVA_DIR}" && \
    ln -s "${JAVA_DIR}" /usr/lib/jvm/temurin-25-jdk && \
    echo "export JAVA_HOME=/usr/lib/jvm/temurin-25-jdk" >> /etc/environment && \
    echo "export PATH=\"/usr/lib/jvm/temurin-25-jdk/bin:\$PATH\"" >> /etc/environment

ENV JAVA_HOME=/usr/lib/jvm/temurin-25-jdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# Install Maven 3.9+ (latest stable)
ARG MAVEN_VERSION=3.9.12
RUN wget https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz && \
    tar -xzf apache-maven-${MAVEN_VERSION}-bin.tar.gz -C /opt && \
    ln -s /opt/apache-maven-${MAVEN_VERSION} /opt/maven && \
    rm apache-maven-${MAVEN_VERSION}-bin.tar.gz

ENV MAVEN_HOME=/opt/maven
ENV PATH="${MAVEN_HOME}/bin:${PATH}"

# Install Node.js 24 LTS "Krypton" (Active LTS until April 2028)
RUN curl -fsSL https://deb.nodesource.com/setup_24.x | bash - && \
    apt-get install -y nodejs && \
    rm -rf /var/lib/apt/lists/*

# Install pnpm globally (latest stable)
RUN npm install -g pnpm@latest

# Install OpenAI Codex CLI
RUN npm install -g @openai/codex

# Install Quarkus CLI
RUN curl -Ls https://sh.jbang.dev | bash -s - trust add https://repo1.maven.org/maven2/io/quarkus/quarkus-cli/ && \
    curl -Ls https://sh.jbang.dev | bash -s - app install --fresh --force quarkus@quarkusio

ENV PATH="/root/.jbang/bin:${PATH}"

# Install Claude CLI (native binary)
RUN curl -fsSL https://claude.ai/install.sh | bash

ENV PATH="/root/.local/bin:${PATH}"

# Install kubectl
RUN ARCH="${TARGETARCH:-$(dpkg --print-architecture)}" && \
    curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/${ARCH}/kubectl" && \
    chmod +x kubectl && \
    mv kubectl /usr/local/bin/

# Install Helm
RUN curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash

# Install kustomize (an installation's render harness and the CI render run the standalone binary)
ARG KUSTOMIZE_VERSION=5.8.1
RUN ARCH="${TARGETARCH:-$(dpkg --print-architecture)}" && \
    curl -fsSL "https://github.com/kubernetes-sigs/kustomize/releases/download/kustomize%2Fv${KUSTOMIZE_VERSION}/kustomize_v${KUSTOMIZE_VERSION}_linux_${ARCH}.tar.gz" -o /tmp/kustomize.tar.gz && \
    tar -xzf /tmp/kustomize.tar.gz -C /usr/local/bin kustomize && \
    rm /tmp/kustomize.tar.gz

# Install ArgoCD CLI
ARG ARGOCD_VERSION=3.4.2
RUN ARCH="${TARGETARCH:-$(dpkg --print-architecture)}" && \
    curl -fsSL "https://github.com/argoproj/argo-cd/releases/download/v${ARGOCD_VERSION}/argocd-linux-${ARCH}" -o /usr/local/bin/argocd && \
    chmod +x /usr/local/bin/argocd

# Install SOPS (Secrets OPerationS) for encrypted secrets management
ARG SOPS_VERSION=3.13.1
RUN ARCH="${TARGETARCH:-$(dpkg --print-architecture)}" && \
    curl -fsSL "https://github.com/getsops/sops/releases/download/v${SOPS_VERSION}/sops-v${SOPS_VERSION}.linux.${ARCH}" -o /usr/local/bin/sops && \
    chmod +x /usr/local/bin/sops

# Install age encryption tool (used with SOPS)
ARG AGE_VERSION=1.3.1
RUN ARCH="${TARGETARCH:-$(dpkg --print-architecture)}" && \
    curl -fsSL "https://github.com/FiloSottile/age/releases/download/v${AGE_VERSION}/age-v${AGE_VERSION}-linux-${ARCH}.tar.gz" -o /tmp/age.tar.gz && \
    tar -xzf /tmp/age.tar.gz -C /tmp && \
    mv /tmp/age/age /usr/local/bin/age && \
    mv /tmp/age/age-keygen /usr/local/bin/age-keygen && \
    rm -rf /tmp/age.tar.gz /tmp/age

# Install hcloud-upload-image (for creating Hetzner Cloud snapshots from disk images)
ARG HCLOUD_UPLOAD_IMAGE_VERSION=1.3.0
# Note: the amd64 filename uses "x86_64"
RUN ARCH="${TARGETARCH:-$(dpkg --print-architecture)}" && \
    case "${ARCH}" in amd64) HUI_ARCH=x86_64 ;; arm64) HUI_ARCH=arm64 ;; *) echo "unsupported architecture: ${ARCH}" >&2; exit 1 ;; esac && \
    curl -fsSL "https://github.com/apricote/hcloud-upload-image/releases/download/v${HCLOUD_UPLOAD_IMAGE_VERSION}/hcloud-upload-image_Linux_${HUI_ARCH}.tar.gz" -o /tmp/hcloud-upload-image.tar.gz && \
    tar -xzf /tmp/hcloud-upload-image.tar.gz -C /usr/local/bin hcloud-upload-image && \
    rm /tmp/hcloud-upload-image.tar.gz

# Install talosctl (Talos Linux management CLI)
ARG TALOSCTL_VERSION=1.12.2
RUN ARCH="${TARGETARCH:-$(dpkg --print-architecture)}" && \
    curl -fsSL "https://github.com/siderolabs/talos/releases/download/v${TALOSCTL_VERSION}/talosctl-linux-${ARCH}" -o /usr/local/bin/talosctl && \
    chmod +x /usr/local/bin/talosctl

# Install OpenTofu (Infrastructure as Code)
ARG OPENTOFU_VERSION=1.11.4
RUN ARCH="${TARGETARCH:-$(dpkg --print-architecture)}" && \
    curl -fsSL "https://github.com/opentofu/opentofu/releases/download/v${OPENTOFU_VERSION}/tofu_${OPENTOFU_VERSION}_linux_${ARCH}.tar.gz" -o /tmp/tofu.tar.gz && \
    tar -xzf /tmp/tofu.tar.gz -C /usr/local/bin tofu && \
    rm /tmp/tofu.tar.gz

# Install Podman remote client (for connecting to host Podman via socket)
# The CONTAINER_HOST env var (set in devcontainer.json) tells it where to connect
RUN apt-get update && apt-get install -y podman-remote && \
    rm -rf /var/lib/apt/lists/* && \
    ln -sf /usr/bin/podman-remote /usr/bin/podman

# Install AWS CLI v2 (for S3-compatible storage management: Hetzner Object Storage, R2)
# Note: the filename uses "x86_64" and "aarch64", not "amd64" and "arm64"
RUN ARCH="${TARGETARCH:-$(dpkg --print-architecture)}" && \
    case "${ARCH}" in amd64) AWS_ARCH=x86_64 ;; arm64) AWS_ARCH=aarch64 ;; *) echo "unsupported architecture: ${ARCH}" >&2; exit 1 ;; esac && \
    curl -fsSL "https://awscli.amazonaws.com/awscli-exe-linux-${AWS_ARCH}.zip" -o /tmp/awscli.zip && \
    unzip -q /tmp/awscli.zip -d /tmp && \
    /tmp/aws/install && \
    rm -rf /tmp/awscli.zip /tmp/aws

# Create non-root user
ARG USERNAME=vscode
ARG USER_UID=1000
ARG USER_GID=$USER_UID

# Create group and user, handling the case where GID/UID already exist
RUN if ! getent group $USER_GID >/dev/null; then \
        groupadd --gid $USER_GID $USERNAME; \
    else \
        groupadd $USERNAME; \
    fi && \
    if ! getent passwd $USER_UID >/dev/null; then \
        useradd --uid $USER_UID --gid $USERNAME -m $USERNAME -s /bin/bash; \
    else \
        useradd --gid $USERNAME -m $USERNAME -s /bin/bash; \
    fi && \
    echo $USERNAME ALL=\(root\) NOPASSWD:ALL > /etc/sudoers.d/$USERNAME && \
    chmod 0440 /etc/sudoers.d/$USERNAME

# Copy jbang to vscode user
RUN cp -r /root/.jbang /home/$USERNAME/ && \
    chown -R $USERNAME:$USERNAME /home/$USERNAME/.jbang

# Create .m2 directory for Maven (repository will be mounted as volume)
RUN mkdir -p /home/$USERNAME/.m2 && \
    chown -R $USERNAME:$USERNAME /home/$USERNAME/.m2

# Create .local/share directory structure for pnpm store (volume) and Claude CLI
RUN mkdir -p /home/$USERNAME/.local/share/pnpm/store && \
    chown -R $USERNAME:$USERNAME /home/$USERNAME/.local

# Install Claude CLI for vscode user
RUN su $USERNAME -c 'export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && export HOME=/home/vscode && curl -fsSL https://claude.ai/install.sh | bash'

# Install oh-my-zsh for vscode user
RUN su $USERNAME -c 'PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /bin/sh -c "$(/usr/bin/curl -fsSL https://raw.githubusercontent.com/ohmyzsh/ohmyzsh/master/tools/install.sh)" "" --unattended'

# Install Powerlevel10k theme
RUN su $USERNAME -c 'PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /usr/bin/git clone --depth=1 https://github.com/romkatv/powerlevel10k.git ${ZSH_CUSTOM:-/home/vscode/.oh-my-zsh/custom}/themes/powerlevel10k'

# Install zsh-autosuggestions plugin
RUN su $USERNAME -c 'PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /usr/bin/git clone https://github.com/zsh-users/zsh-autosuggestions ${ZSH_CUSTOM:-/home/vscode/.oh-my-zsh/custom}/plugins/zsh-autosuggestions'

# Cache buster for dotfiles - changes when any dotfile is modified
ARG DOTFILES_HASH=""

# Record build timestamp in Copenhagen timezone (must be after cache buster to update on each build)
RUN echo "$(TZ='Europe/Copenhagen' date '+%Y-%m-%d %H:%M:%S %Z')" > /etc/devcontainer-build-timestamp

# Copy shell configuration files
COPY --chown=$USERNAME:$USERNAME dotfiles/zshrc.template /home/$USERNAME/.zshrc
COPY --chown=$USERNAME:$USERNAME dotfiles/zprofile.template /home/$USERNAME/.zprofile
COPY --chown=$USERNAME:$USERNAME dotfiles/p10k.zsh /home/$USERNAME/.p10k.zsh

# Set zsh as default shell for vscode user
RUN chsh -s /usr/bin/zsh $USERNAME

# Set up user environment
USER $USERNAME
ENV SHELL=/usr/bin/zsh
ENV JAVA_HOME=/usr/lib/jvm/temurin-25-jdk
ENV MAVEN_HOME=/opt/maven
ENV PATH="${JAVA_HOME}/bin:${MAVEN_HOME}/bin:/home/vscode/.jbang/bin:/home/vscode/.local/bin:${PATH}"

# Set working directory
WORKDIR /workspace

# Verify all installations
RUN java -version && \
    mvn --version && \
    node --version && \
    pnpm --version && \
    quarkus version && \
    claude --version && \
    codex --version && \
    kubectl version --client && \
    helm version && \
    kustomize version && \
    python3 -c 'import yaml' && \
    podman --version && \
    argocd version --client && \
    sops --version && \
    age --version && \
    hcloud-upload-image --help > /dev/null && \
    aws --version && \
    talosctl version --client && \
    tofu version

CMD ["/bin/bash"]
