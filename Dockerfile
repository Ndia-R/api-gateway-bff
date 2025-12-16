# ====================================
# 開発環境ステージ
# ====================================
FROM eclipse-temurin:21-jdk-jammy AS development

RUN apt-get update && \
    apt-get install -y git curl sudo bash python3 && \
    curl -fsSL https://deb.nodesource.com/setup_22.x | bash - && \
    apt-get install -y nodejs && \
    rm -rf /var/lib/apt/lists/*

# vscodeユーザーを作成
RUN useradd -m vscode

# vscodeユーザーがパスワードなしでsudoを使えるように設定
# /etc/sudoers.d/vscodeファイルを作成し、NOPASSWD: ALL を設定
RUN echo "vscode ALL=(ALL) NOPASSWD: ALL" > /etc/sudoers.d/vscode && \
    chmod 0440 /etc/sudoers.d/vscode

# CA証明書をコピー
COPY ./certs/rootCA.pem /tmp/rootCA.pem

# CA証明書をJavaトラストストアに追加
RUN keytool -import -trustcacerts -noprompt \
    -alias mkcert-ca \
    -file /tmp/rootCA.pem \
    -keystore $JAVA_HOME/lib/security/cacerts \
    -storepass changeit && \
    rm /tmp/rootCA.pem

# vscodeユーザーに切り替え
USER vscode
WORKDIR /workspace

# Gradleキャッシュ用ディレクトリを作成（volume用）
RUN mkdir -p /home/vscode/.gradle

# Python uv（Serena MCP用）をインストール
RUN curl -LsSf https://astral.sh/uv/install.sh | sh

# uvをPATHに追加
ENV PATH="/home/vscode/.local/bin:$PATH"

# Gemini CLIをグローバルインストール
USER root
RUN npm install -g @google/gemini-cli
# 元のユーザーに戻す
USER vscode

# ====================================
# 本番環境: ビルドステージ
# ====================================
FROM eclipse-temurin:21-jdk-jammy AS production-builder

WORKDIR /build

# Gradleラッパーとビルドファイルをコピー
COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./

# 依存関係を事前ダウンロード（キャッシュ効率化）
RUN ./gradlew dependencies --no-daemon || true

# ソースコードをコピーしてビルド
COPY src src
RUN ./gradlew bootJar --no-daemon

# ====================================
# 本番環境: 実行ステージ
# ====================================
FROM eclipse-temurin:21-jre-alpine AS production

RUN apk add --update curl

# CA証明書をコピー
COPY ./certs/rootCA.pem /tmp/rootCA.pem

# CA証明書をJavaトラストストアに追加
RUN keytool -import -trustcacerts -noprompt \
    -alias mkcert-ca \
    -file /tmp/rootCA.pem \
    -keystore $JAVA_HOME/lib/security/cacerts \
    -storepass changeit && \
    rm /tmp/rootCA.pem

# セキュリティ: 非rootユーザーで実行
RUN addgroup -S appuser && adduser -S -G appuser appuser

WORKDIR /app

# ビルドステージからJARファイルのみコピー
COPY --from=production-builder /build/build/libs/*.jar app.jar

# 所有権を変更
RUN chown appuser:appuser /app/app.jar

# ヘルスチェック設定
# docker-compose の depends_on で condition: service_healthy を使用するために必要
HEALTHCHECK --interval=30s --timeout=5s --retries=3 --start-period=60s \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

USER appuser

ENTRYPOINT ["java", "-jar", "/app/app.jar"]