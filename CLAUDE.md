# Auth BFF (Backend for Frontend) プロジェクト

# 重要

基本的なやりとりは日本語でおこなってください。

## Claude Code作業ルール

**コード修正前の確認必須**
- ファイルの修正・変更を行う前に、必ずユーザーに修正内容を提示して許可を取る
- 勝手にコードを変更してはいけない
- 修正案を説明し、ユーザーの承認を得てから実行する

## 概要

OIDC準拠の認証プロバイダー（Keycloak、Auth0、Okta等）とのOAuth2認証フローを処理する**必要最小限のSpring Boot BFF (Backend for Frontend)** アプリケーションです。

### 🎯 特徴

1. **BFFパターン実装**: フロントエンドはトークンを一切扱わず、BFFがすべてのAPI呼び出しをプロキシ
   - XSS攻撃からトークンを完全に保護
   - セッションCookieのみでAPIアクセス可能

2. **PKCE対応**: Authorization Code with PKCEによるセキュアなOAuth2認証

3. **トークン自動リフレッシュ**: OAuth2AuthorizedClientManagerによるシームレスなトークン管理
   - アクセストークン期限切れ時、自動的にリフレッシュトークンで更新
   - ユーザーに再ログインを強制せず、透過的にセッションを維持
   - Spring Security公式推奨のベストプラクティス実装

4. **認証後リダイレクト機能**: `return_to` パラメータで認証後の復帰先を指定可能
   - オープンリダイレクト脆弱性対策実装済み
   - セッション管理による安全なリダイレクト先保存

5. **最小構成**: 20ファイルで構成された、保守しやすいシンプルな設計
   - 未使用のクラス・メソッドは一切なし
   - Spring Boot自動設定を最大限活用
   - 型安全な設定管理（@ConfigurationProperties）

6. **完全なCSRF保護**: CookieベースのCSRFトークンで状態変更操作を保護

7. **OpenID Connect準拠**: RP-Initiated Logoutによる確実なIDプロバイダーセッション無効化

8. **権限制御の委譲**: BFFは認証とトークン管理に専念、権限制御はリソースサーバーで実施

9. **レート制限**: Bucket4j + Redisによる分散レート制限でブルートフォース攻撃やDDoSを軽減

## アーキテクチャ

### 認証フロー（PKCE対応）- 集約型BFF
```
フロントエンド (SPA)
    ↓ Cookie: BFFSESSIONID + XSRF-TOKEN
   BFF (APIゲートウェイ)
    ├─ 認証管理 (/bff/auth/*)
    ├─ APIプロキシ (/api/**)  ← パスベースで複数のリソースサーバーにルーティング
    └─ トークン管理（Redisセッション）
    ↓ Authorization: Bearer <access_token>
    ├─ /api/my-books/** → My Books API
    └─ /api/my-musics/** → My Musics API
         ↓
複数のリソースサーバー
    ├─ ビジネスロジック
    ├─ 権限制御  ← 権限チェックはここで実施
    └─ データ処理
```

### 主要コンポーネント（最小構成・20ファイル）

#### アプリケーション
- **ApiGatewayBffApplication**: メインクラス

#### 設定 (config/)
- **ResourceServerProperties**: リソースサーバー設定プロパティ（複数サーバー対応）
- **WebClientConfig**: サービスごとのWebClient設定（コネクションプール最適化）
- **CsrfCookieFilter**: CSRF Cookie自動設定フィルター
- **RedisConfig**: Spring Session Data Redis設定
- **SecurityConfig**: Spring Security設定（PKCE、CSRF保護、CORS、フィルターチェーン例外処理、認証後リダイレクト）
- **CustomAuthorizationRequestResolver**: カスタムOAuth2認可リクエストリゾルバー（PKCE + return_to保存 + マルチアプリ対応）
- **RateLimitConfig**: レート制限設定（Bucket4j + Redis）

#### フィルター (filter/)
- **FilterChainExceptionHandler**: フィルターチェーン例外ハンドラー（統一エラーレスポンス）
- **RateLimitFilter**: レート制限フィルター（認証エンドポイント・APIプロキシ）

#### コントローラー (controller/)
- **ApiProxyController**: すべてのAPIリクエストをプロキシ（214行、1メソッドのみ）
- **AuthController**: 認証エンドポイント（ログイン・ログアウト）

#### クライアント (client/)
- **OidcMetadataClient**: OIDC Discovery（メタデータ取得）

#### DTO (dto/)
- **ErrorResponse**: 統一エラーレスポンス（タイムスタンプ自動生成）
- **LogoutResponse**: ログアウトレスポンス
- **OidcConfiguration**: OIDC設定情報

#### 例外 (exception/)
- **GlobalExceptionHandler**: 統一エラーハンドリング（WebClient例外対応）
- **UnauthorizedException**: 認証エラー例外
- **RateLimitExceededException**: レート制限超過例外

#### サービス (service/)
- **AuthService**: 認証ビジネスロジック（ログアウト、IDプロバイダー連携）

## エンドポイント

### 認証エンドポイント

| メソッド | パス | 説明 | レスポンス |
|---------|------|------|-----------|
| GET | `/bff/auth/login` | 認証状態確認・OAuth2フロー開始 | リダイレクト |
| GET | `/bff/auth/login?return_to=/my-reviews` | 認証後に指定URLへリダイレクト | リダイレクト |
| POST | `/bff/auth/logout` | 通常ログアウト（BFFセッションのみクリア） | `LogoutResponse` |
| POST | `/bff/auth/logout?complete=true` | 完全ログアウト（IDプロバイダーセッションも無効化） | `LogoutResponse` |
| GET | `/actuator/health` | ヘルスチェック | Spring Boot Actuator標準レスポンス |

#### `return_to` パラメータ（認証後リダイレクト機能 + マルチアプリ対応）

**概要:**
- フロントエンドから `return_to` パラメータで認証後の復帰先URL（例: `/my-reviews`）を指定できます
- **マルチアプリ対応**: Refererヘッダーから各アプリのベースパス（例: `/my-books`）を自動抽出
- OAuth2認証完了後、BFFがフロントエンドに `/my-books/auth-callback?return_to=/my-reviews` の形式でリダイレクトします

**動作フロー:**

1. **未認証ユーザーの場合（マルチアプリ対応）:**
```
フロントエンド(my-books) → /bff/auth/login?return_to=/my-reviews
Referer: https://localhost/my-books
    ↓
Spring Security → CustomAuthorizationRequestResolver
    ↓ (Refererから/my-booksを抽出 → original_frontend_url = "https://localhost/my-books")
    ↓ (return_toもセッションに保存)
OAuth2認証フロー → IdPログイン画面
    ↓
認証成功 → authenticationSuccessHandler
    ↓ (セッションからoriginal_frontend_urlとreturn_toを取得)
フロントエンド ← /my-books/auth-callback?return_to=/my-reviews
```

2. **認証済みユーザーの場合（マルチアプリ対応）:**
```
フロントエンド(my-books) → /bff/auth/login?return_to=/my-reviews
Referer: https://localhost/my-books
    ↓
AuthController.login()
    ↓ (Refererから/my-booksを抽出)
フロントエンド ← /my-books/auth-callback?return_to=/my-reviews (即座にリダイレクト)
```

**セキュリティ:**
- オープンリダイレクト脆弱性対策実装済み
- 許可されたホスト（localhost、フロントエンドホスト）または相対パスのみ許可
- 不正なURLは自動的にブロックされ、デフォルトの `/auth-callback` にリダイレクト

### APIプロキシエンドポイント（パスベースルーティング）

| メソッド | パス | 説明 | 転送先 |
|---------|------|------|--------|
| GET/POST/PUT/DELETE/PATCH | `/api/my-books/**` | My Books APIへのリクエスト | `${MY_BOOKS_SERVICE_URL}/**` |
| GET/POST/PUT/DELETE/PATCH | `/api/my-musics/**` | My Musics APIへのリクエスト | `${MY_MUSICS_SERVICE_URL}/**` |

**重要な設計方針**:
- BFFは `/api/**` 配下のリクエストをパスプレフィックスに基づいて適切なリソースサーバーにルーティング
- 認証済みユーザーのアクセストークンを自動的に付与
- **権限制御はリソースサーバー側で実施**（BFFは権限チェックなし）
- 新しいリソースサーバーの追加は `application.yml` の設定のみで可能

**プロキシの動作例**:
```
フロントエンド                    BFF                          リソースサーバー
GET /api/my-books/list     →  GET /list          →  My Books API  (トークン付与)
POST /api/my-musics/search →  POST /search       →  My Musics API (トークン付与)
GET /api/my-books/1        →  GET /1             →  My Books API  (トークン付与、権限チェックはリソースサーバー側)
```

**パスプレフィックスの自動削除**:
- `/api/my-books/list` → My Books APIに `/list` として転送
- `/api/my-musics/search` → My Musics APIに `/search` として転送

## DTOクラス

### LogoutResponse
```json
{
  "message": "success"
}
```

**ログアウトの種類:**
- **通常ログアウト** (`complete=false` または省略): BFFセッションのみクリア
- **完全ログアウト** (`complete=true`): BFFセッション + IDプロバイダーセッションをクリア

### ErrorResponse
統一的なエラーレスポンス形式。

```json
{
  "error": "UNAUTHORIZED",
  "message": "認証が必要です",
  "status": 401,
  "path": "/bff/auth/login",
  "timestamp": "2025-10-12 14:30:45"
}
```

#### WebClient/IDプロバイダー通信特有のエラーコード
| エラーコード | HTTPステータス | 説明 |
|-------------|---------------|------|
| `IDP_CLIENT_ERROR` | 400 | IDプロバイダー通信でクライアントエラー |
| `IDP_SERVER_ERROR` | 503 | IDプロバイダー サーバーエラー |
| `IDP_CONNECTION_ERROR` | 503 | IDプロバイダー接続エラー |

## セキュリティ設定

### CSRF保護
- **有効化**: ✅ CookieCsrfTokenRepository使用
- **CSRFトークンCookie**: `XSRF-TOKEN` (HttpOnly=false)
- **CSRFトークンヘッダー**: `X-XSRF-TOKEN`
- **保護対象**: POST, PUT, DELETE, PATCH

**フロントエンド実装例:**
```javascript
// CSRFトークンをCookieから取得してヘッダーに設定
const csrfToken = document.cookie
  .split('; ')
  .find(row => row.startsWith('XSRF-TOKEN='))
  ?.split('=')[1];

fetch('/api/books', {
  method: 'POST',
  credentials: 'include',
  headers: {
    'Content-Type': 'application/json',
    'X-XSRF-TOKEN': csrfToken
  },
  body: JSON.stringify({ title: '新しい本' })
});
```

### セッション管理
- **ストレージ**: Redis
- **タイムアウト**: 30分
- **Cookie名**: BFFSESSIONID
- **属性**: HttpOnly, Secure, SameSite=lax

### CORS設定
- **許可オリジン**: 環境変数 `${CORS_ALLOWED_ORIGINS}` から読み込み
- **許可メソッド**: GET, POST, PUT, DELETE, OPTIONS
- **許可ヘッダー**: Authorization, Content-Type, X-XSRF-TOKEN
- **資格情報**: 許可

### OAuth2設定
- **プロバイダー**: OIDC準拠の認証サーバー（Keycloak、Auth0、Okta等）
- **フロー**: Authorization Code with PKCE
- **スコープ**: openid, profile, email

## レート制限

### 概要
Bucket4j + Redisによる分散レート制限を実装し、ブルートフォース攻撃やDDoS攻撃を軽減します。

### レート制限ルール

| エンドポイント | 制限 | 識別方法 | 目的 |
|--------------|------|---------|------|
| `/bff/auth/login` | 30リクエスト/分 | IPアドレス | ブルートフォース攻撃防止 |
| `/api/**` (認証済み) | 200リクエスト/分 | セッションID | API乱用防止 |
| `/api/**` (未認証) | 100リクエスト/分 | IPアドレス | DoS攻撃防止（書籍検索等の公開API保護） |

### 除外エンドポイント（レート制限なし）
- `/actuator/health` - 監視システムからのヘルスチェック
- `/bff/login/oauth2/code/**` - IDプロバイダーからのコールバック
- `/oauth2/authorization/**` - OAuth2認証開始
- `/bff/auth/logout` - ログアウト（セッション無効化済み）

### レート制限超過時のレスポンス
```json
{
  "error": "TOO_MANY_REQUESTS",
  "message": "リクエスト数が制限を超えました。しばらく待ってから再試行してください。",
  "status": 429,
  "path": "/bff/auth/login",
  "timestamp": "2025-10-18 15:30:45"
}
```

### 環境変数
```bash
# レート制限機能の有効/無効（デフォルト: true）
RATE_LIMIT_ENABLED=true

# 認証エンドポイントのレート制限（デフォルト: 30リクエスト/分）
RATE_LIMIT_AUTH_RPM=30

# APIプロキシのレート制限
# 認証済みユーザー（デフォルト: 200リクエスト/分）
RATE_LIMIT_API_AUTHENTICATED_RPM=200

# 未認証ユーザー（デフォルト: 100リクエスト/分）
# 書籍検索など、未認証でもアクセス可能なエンドポイントを保護
RATE_LIMIT_API_ANONYMOUS_RPM=100
```

### 技術詳細
- **ライブラリ**: Bucket4j 8.7.0
- **バックエンド**: Redis（Lettuce CAS方式）
- **分散対応**: 複数BFFインスタンス間でレート制限状態を共有
- **アルゴリズム**: Token Bucket（トークンバケット）
- **補充方式**: Intervally Refill（1分ごとに全トークン補充）

## APIプロキシの詳細実装

### 主要機能

1. **UriBuilderによる安全なURI構築**
   - クエリパラメータの自動エンコード
   - パスの重複スラッシュ問題を解消

2. **Content-Type転送**
   - フロントエンドのContent-Typeをリソースサーバーに正しく転送
   - POST/PUTリクエストで自動適用

3. **レスポンスヘッダーフィルタリング**
   - Springが自動設定するヘッダーを除外
   - 除外対象: `transfer-encoding`, `connection`, `keep-alive`, `upgrade`, `server`, `content-length`

4. **タイムアウト設定**
   - 30秒タイムアウト
   - リソースサーバーの応答遅延時に自動でエラー返却

5. **透過的なレスポンス転送**
   - ステータスコード保持（404, 500等）
   - カスタムヘッダー転送（`X-Total-Count`等）
   - Content-Type保持

## 環境変数

### 基本設定
```bash
# ============================================
# Identity Provider Configuration
# ============================================
IDP_CLIENT_ID=api-gateway-bff-client
IDP_CLIENT_SECRET=your-client-secret
IDP_REDIRECT_URI=http://localhost:8888/bff/login/oauth2/code/idp

# ISSUER_URIで統一（Keycloak、Auth0、Okta等のOIDC準拠プロバイダー）
IDP_ISSUER_URI=http://auth.localhost:8444/realms/sample-realm
IDP_POST_LOGOUT_REDIRECT_URI=http://localhost:5173/logout-complete

# ============================================
# Redis Configuration
# ============================================
REDIS_HOST=redis
REDIS_PORT=6379

# ============================================
# Application Configuration
# ============================================
# デフォルトフロントエンドURL（フォールバック用）
DEFAULT_FRONTEND_URL=http://localhost:5173

# 複数のリソースサーバー設定
# My Books Service
MY_BOOKS_SERVICE_URL=http://my-books-api:8080
MY_BOOKS_SERVICE_TIMEOUT=30

# My Musics Service
MY_MUSICS_SERVICE_URL=http://my-musics-api:8081
MY_MUSICS_SERVICE_TIMEOUT=30

# CORS許可オリジン（カンマ区切り）
CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:*
```

**重要**: `IDP_ISSUER_URI`を使用することで、Spring Securityが自動的に各エンドポイント（authorize, token, jwk, logout）を検出します。個別エンドポイント指定は不要です。

### IDプロバイダー別 ISSUER_URI 設定例

このBFFは **OIDC準拠のすべてのIDプロバイダーに対応** しています。`IDP_ISSUER_URI` の値のみを変更すれば、異なるプロバイダーに切り替え可能です。

#### Keycloak
```bash
IDP_ISSUER_URI=http://auth.localhost:8444/realms/sample-realm
```
- パス構造: `/realms/{realm-name}`
- Keycloak特有の「レルム」概念を使用

#### Auth0
```bash
IDP_ISSUER_URI=https://your-tenant.auth0.com
```
- パス構造: テナント名ベースのサブドメイン
- 追加パスは不要

#### Okta
```bash
IDP_ISSUER_URI=https://dev-12345678.okta.com/oauth2/default
```
- パス構造: `/oauth2/{authorization-server-id}`
- `default` は認可サーバーID（カスタム可能）

#### Azure AD (Microsoft Entra ID)
```bash
IDP_ISSUER_URI=https://login.microsoftonline.com/{tenant-id}/v2.0
```
- パス構造: `/{tenant-id}/v2.0`
- テナントIDまたはテナント名を指定

#### Google Identity Platform
```bash
IDP_ISSUER_URI=https://accounts.google.com
```
- 非常にシンプルな構造
- マルチテナント不要

#### AWS Cognito
```bash
IDP_ISSUER_URI=https://cognito-idp.{region}.amazonaws.com/{user-pool-id}
```
- パス構造: リージョン + ユーザープールID
- 例: `https://cognito-idp.ap-northeast-1.amazonaws.com/ap-northeast-1_abc123def`

**注意**: 上記の例はすべて OIDC Discovery（`/.well-known/openid-configuration`）をサポートしており、Spring Securityが自動的に各エンドポイントを検出します。BFFのコード変更は一切不要です。

## 開発環境

### 環境概要
このプロジェクトは **WSL2上のUbuntu** で **VSCode DevContainer** + **Docker Compose** を使用して開発しています。

```
WSL2 (Ubuntu) → VSCode DevContainer → Docker Compose
                                          ├── api-gateway-bff (開発コンテナ)
                                          └── redis (セッションストレージ)
```

**注意**: このプロジェクトは外部の認証サーバー（`http://auth.localhost:8444`）を使用します。Keycloak、Auth0、Okta等のOIDC準拠プロバイダーに対応しています。

### DevContainer構成

#### 基本情報
- **コンテナ名**: `api-gateway-bff`
- **ベースイメージ**: `eclipse-temurin:17-jdk-jammy`
- **実行ユーザー**: `vscode`
- **作業ディレクトリ**: `/workspace`

#### インストール済みツール
| ツール | バージョン | 用途 |
|--------|-----------|------|
| Java (Eclipse Temurin) | 17 | アプリケーション実行環境 |
| Gradle | ラッパー経由 | ビルドツール |
| Git | 最新 | バージョン管理 |

#### 永続化ボリューム
| ボリューム名 | マウント先 | 用途 |
|------------|-----------|------|
| (プロジェクトディレクトリ) | `/workspace` | ソースコード |
| `gradle-cache` | `/home/vscode/.gradle` | Gradleキャッシュ |
| `claude-config` | `/home/vscode/.claude` | Claude Code設定・認証情報 |

### Docker Compose サービス構成

#### 1. api-gateway-bff (開発コンテナ)
```yaml
ports: 8888:8080
networks: shared-network
depends_on: [redis]
```

#### 2. redis (セッションストレージ)
```yaml
image: redis:8.2
ports: 6379:6379
networks: shared-network
```

### ネットワーク構成
```
外部ブラウザ
    ↓ http://localhost:8888
api-gateway-bff:8080 ←→ redis:6379
    ↓ http://auth.localhost:8444 (外部IDプロバイダー)
IDプロバイダー (外部認証サーバー: Keycloak/Auth0/Okta等)
```

**重要**: 外部の認証サーバーを使用します。`IDP_ISSUER_URI`環境変数でOIDC準拠プロバイダーの接続先を指定してください。

### 開発環境起動

```bash
# Docker Compose環境起動
docker compose up -d

# VSCodeでDevContainer接続
code .
# VSCodeコマンドパレット: "Dev Containers: Reopen in Container"

# アプリケーション実行
./gradlew bootRun
```

## ビルド・実行

```bash
# ビルド
./gradlew build

# テスト実行
./gradlew test

# アプリケーション実行
./gradlew bootRun

# Docker Compose実行（完全環境）
docker compose up -d
```

## プロジェクト構成

### 📁 ソースコード構成（19ファイル）

```
src/main/java/com/example/auth_bff/
├── ApiGatewayBffApplication.java              # メインクラス
│
├── config/                              # 設定（6ファイル）
│   ├── ResourceServerProperties.java    # リソースサーバー設定プロパティ（複数サーバー対応）
│   ├── WebClientConfig.java             # サービスごとのWebClient設定（コネクションプール最適化）
│   ├── CsrfCookieFilter.java           # CSRF Cookie自動設定フィルター
│   ├── RedisConfig.java                 # Redis/Spring Session設定
│   ├── SecurityConfig.java              # Spring Security + PKCE + CORS + フィルターチェーン例外処理
│   └── RateLimitConfig.java             # レート制限設定（Bucket4j + Redis）
│
├── filter/                              # フィルター（2ファイル）
│   ├── FilterChainExceptionHandler.java # フィルターチェーン例外ハンドラー
│   └── RateLimitFilter.java             # レート制限フィルター
│
├── controller/                          # コントローラー（2ファイル）
│   ├── ApiProxyController.java          # APIプロキシ（パスベースルーティング、複数リソースサーバー対応）
│   └── AuthController.java              # 認証エンドポイント（/bff/auth/*）
│
├── client/                              # クライアント（1ファイル）
│   └── OidcMetadataClient.java          # OIDC Discoveryクライアント
│
├── dto/                                 # DTO（3ファイル）
│   ├── ErrorResponse.java               # 統一エラーレスポンス
│   ├── LogoutResponse.java              # ログアウトレスポンス
│   └── OidcConfiguration.java           # OIDC設定情報
│
├── exception/                           # 例外（3ファイル）
│   ├── GlobalExceptionHandler.java      # 統一エラーハンドラー
│   ├── UnauthorizedException.java       # 認証エラー例外
│   └── RateLimitExceededException.java  # レート制限超過例外
│
└── service/                             # サービス（1ファイル）
    └── AuthService.java                 # 認証ビジネスロジック
```

### 🎯 設計原則

1. **必要最小限の構成**: すべてのクラスとメソッドが実際に使用されている
2. **BFFパターン（集約型）**: フロントエンドはトークンを一切扱わず、複数のリソースサーバーを集約
3. **権限制御の委譲**: BFFは認証に専念、権限はリソースサーバーが管理
4. **Spring Boot自動設定の活用**: カスタムBean最小限
5. **統一されたエラーハンドリング**: FilterChainExceptionHandlerとGlobalExceptionHandlerで一貫したエラーレスポンス
6. **パスベースルーティング**: ApiProxyControllerはパスプレフィックスで適切なサービスを選択
7. **型安全な設定管理**: @ConfigurationPropertiesで複数サービスの設定を一元管理
8. **サービスごとのWebClient**: サービスごとに最適化されたコネクションプールとタイムアウト設定
9. **分散レート制限**: Redis + Bucket4jで複数インスタンス間でレート制限を共有
10. **フィルターチェーン例外処理**: Spring Securityフィルターチェーン内の例外も統一されたErrorResponse形式で返却

## 開発時の注意点

### コーディングスタイル
- **早期例外**: null チェック後即座に例外をスロー
- **型安全**: 具体的なDTOクラスを使用
- **単一責任**: Controller/Service の明確な分離
- **必要最小限**: 未使用のクラス・メソッドは作らない
- **アノテーション活用**: `@NonNull` で明示的なnull制約

### エラーハンドリング

#### コントローラー内の例外
- 認証エラー: `UnauthorizedException`
- IDプロバイダー通信エラー: `WebClientException`, `WebClientResponseException`
- その他一般エラー: `Exception`
- すべて`GlobalExceptionHandler`で統一処理

#### フィルターチェーン内の例外
- レート制限超過: `RateLimitExceededException`
- その他フィルター例外: `Exception`
- すべて`FilterChainExceptionHandler`で統一処理

#### 統一されたエラーレスポンス形式
両方のハンドラーが同じ`ErrorResponse` DTOを使用し、一貫したエラーレスポンスを返却します。

### テスト

#### テストファイル構成
```
src/test/java/com/example/auth_bff/
├── config/
│   ├── SecurityConfigTest.java          # セキュリティ設定の統合テスト
│   └── TestConfig.java                  # テスト用設定クラス
│
├── controller/
│   └── ApiProxyControllerTest.java      # APIプロキシの単体テスト
│
├── filter/
│   └── RateLimitIntegrationTest.java    # レート制限の統合テスト
│
└── service/
    └── AuthServiceTest.java             # 認証サービスの単体テスト
```

#### テストカバレッジ
- **全24テスト**: AuthServiceTest (3テスト)、SecurityConfigTest (7テスト)、ApiProxyControllerTest (5テスト)、RateLimitIntegrationTest (7テスト)、ApiGatewayBffApplicationTests (1テスト)、TestConfig (1テスト)
- **単体テスト**: サービス層、コントローラー層
- **統合テスト**: セキュリティ設定、レート制限（Redis連携）

#### テスト実行
```bash
# すべてのテスト実行
./gradlew test

# 特定のテストクラスのみ実行
./gradlew test --tests RateLimitIntegrationTest

# Redis起動が必要な統合テスト
docker compose up redis -d
./gradlew test
```

#### レート制限テストの特徴
- **実際のRedisと連携**: テスト用のRedisを使用して、実環境に近い統合テスト
- **IPアドレスベースの制限**: 認証エンドポイントはIPアドレスで識別
- **除外エンドポイントの検証**: ヘルスチェック、OAuth2関連のエンドポイントは制限なし
- **429エラーレスポンスの検証**: ErrorResponse形式のJSONレスポンスを確認

## トラブルシューティング

### よくある問題

1. **CORS エラー**
   - `SecurityConfig.corsConfigurationSource()` で許可オリジンを確認

2. **認証ループ**
   - IDプロバイダー設定のリダイレクトURI確認
   - `application.yml` の OAuth2設定確認

3. **セッション問題**
   - Redis接続確認
   - Cookie設定（Secure属性）確認

4. **IDプロバイダー接続問題**
   - `IDP_ISSUER_URI`が正しく設定されているか確認
   - 外部認証サーバーにアクセス可能か確認

## API使用例（BFFパターン）

### 認証フロー

```javascript
// 1. ログイン開始
window.location.href = '/bff/auth/login';

// 2. ログイン完了後、リソースサーバーからユーザー情報取得
fetch('/api/users/me', {
  credentials: 'include'
})
  .then(response => response.json())
  .then(user => {
    // { displayName: "田中太郎", avatarPath: "/avatars/123.jpg", email: "..." }
    console.log(user.displayName);
  });

// 3. 完全ログアウト
fetch('/bff/auth/logout?complete=true', {
  method: 'POST',
  credentials: 'include'
})
  .then(() => window.location.href = '/');
```

### APIプロキシの使用

```javascript
// CSRFトークン取得
function getCsrfToken() {
  return document.cookie
    .split('; ')
    .find(row => row.startsWith('XSRF-TOKEN='))
    ?.split('=')[1];
}

// GET リクエスト
fetch('/api/books/list', {
  credentials: 'include'
})
  .then(response => response.json())
  .then(books => console.log(books));

// POST リクエスト
fetch('/api/books', {
  method: 'POST',
  credentials: 'include',
  headers: {
    'Content-Type': 'application/json',
    'X-XSRF-TOKEN': getCsrfToken()
  },
  body: JSON.stringify({ title: 'Spring Security実践ガイド' })
})
  .then(response => response.json())
  .then(book => console.log('作成:', book));
```

**重要**: BFFは `/api/**` 配下のすべてのリクエストを自動的にプロキシします。新しいエンドポイント追加時、BFF側のコード変更は不要です。

---

## 📈 リファクタリングによる改善点

このプロジェクトは大幅なリファクタリングを経て、以下の改善が実施されました。

### ✅ IDプロバイダー設定のシンプル化

#### 導入前（Keycloak - 個別エンドポイント指定方式）
```yaml
# Keycloak特有のパス構造で個別エンドポイントを指定
IDP_AUTHORIZE_URI=http://localhost:8180/realms/sample-realm/protocol/openid-connect/auth
IDP_TOKEN_URI=http://idp:8080/realms/sample-realm/protocol/openid-connect/token
IDP_JWK_URI=http://idp:8080/realms/sample-realm/protocol/openid-connect/certs
IDP_LOGOUT_URI=http://idp:8080/realms/sample-realm/protocol/openid-connect/logout
```
**注**: `/realms/` と `/protocol/openid-connect/` はKeycloak固有のパス構造

#### 導入後（OIDC Discovery - すべてのプロバイダー対応）
```yaml
# Keycloakの例（他のプロバイダーでも同じ仕組み）
IDP_ISSUER_URI=http://auth.localhost:8444/realms/sample-realm
```

**改善効果:**
- 設定がシンプルに（5つのエンドポイント設定→1つのissuer-uri）
- Spring Securityが自動的にエンドポイントを検出
- 本番環境での設定ミスを削減

### ✅ WebClientのパフォーマンス最適化

#### 導入前（旧実装）
```java
// リクエストごとにWebClientを作成（非効率）
WebClient client = WebClient.builder()
    .baseUrl(resourceServerUrl)
    .build();
```

**問題点:**
- リクエストごとにコネクションを作成・破棄
- コネクションプールが使えない
- リソースリーク発生の可能性

#### 導入後（現在）
```java
@Bean
public WebClient webClient() {
    // シングルトンBeanとして1つだけ作成
    return WebClient.builder()
        .clientConnector(new ReactorClientHttpConnector(httpClient))
        .build();
}
```

**改善効果:**
- コネクションプールの再利用
- パフォーマンス向上（Keep-Alive接続）
- リソース効率の大幅改善

### ✅ エラーハンドリングの充実と統一化

#### 追加されたエラー処理
- `WebClientResponseException`: IDプロバイダー通信のHTTPエラー
- `WebClientException`: IDプロバイダー接続エラー
- `RateLimitExceededException`: レート制限超過エラー
- バリデーションエラーの詳細メッセージ

#### フィルターチェーン例外ハンドラーの導入
**設計の課題:**
- Spring Securityフィルターチェーン内の例外は`@RestControllerAdvice`では捕捉できない
- レート制限フィルター内で直接JSONレスポンスを生成すると、GlobalExceptionHandlerと重複

**解決策:**
```java
FilterChainExceptionHandler（フィルター内例外）
     ↓
ErrorResponse（統一形式）
     ↓
JSONレスポンス

GlobalExceptionHandler（コントローラー内例外）
     ↓
ErrorResponse（統一形式）
     ↓
JSONレスポンス
```

**改善効果:**
- ✅ すべての例外で統一されたErrorResponse形式
- ✅ エラーレスポンス生成ロジックの一元化
- ✅ ObjectMapperによる安全なJSON生成
- ✅ 保守性・拡張性の向上

#### エラーレスポンスの統一化
```json
{
  "error": "TOO_MANY_REQUESTS",
  "message": "リクエスト数が制限を超えました。しばらく待ってから再試行してください。",
  "status": 429,
  "path": "/bff/auth/login",
  "timestamp": "2025-10-18 14:30:45"
}
```

### ✅ ドキュメンテーションの充実

すべてのクラスに以下の情報を含む詳細なJavadocを追加：
- **設計方針**: なぜこの設計にしたのか
- **処理フロー**: どのように動作するのか
- **使用箇所**: どこから呼ばれるのか
- **注意点**: 気をつけるべきポイント

### ✅ レート制限機能の追加

#### 追加されたコンポーネント
- **RateLimitConfig**: Bucket4j + Redis設定
- **RateLimitFilter**: レート制限フィルター（認証エンドポイント・APIプロキシ）
- **FilterChainExceptionHandler**: フィルターチェーン例外ハンドラー
- **RateLimitExceededException**: レート制限超過例外
- **RateLimitIntegrationTest**: レート制限の統合テスト（7テストケース）

#### 機能
- 認証エンドポイント: 30リクエスト/分（IPアドレスベース）
- APIプロキシ（認証済み）: 200リクエスト/分（セッションIDベース）
- APIプロキシ（未認証）: 100リクエスト/分（IPアドレスベース）
- ブルートフォース攻撃・DDoS攻撃の軽減
- Redis + Bucket4jによる分散レート制限

### ✅ 未認証ユーザーへのレート制限追加（2025-01-26）

#### 背景・課題
未認証でもアクセス可能なエンドポイント（書籍検索等）が、DoS攻撃に対して脆弱でした。

**修正前の問題:**
```java
// セッションがない場合はレート制限をスキップ（❌ 脆弱性）
if (session == null) {
    return null; // レート制限なし
}
```

**攻撃シナリオ:**
```bash
# 未認証で無制限にリクエスト可能
for i in {1..100000}; do
  curl "http://localhost:8888/api/books/search?q=test"
done
```

#### 解決策
未認証ユーザーにもIPアドレスベースのレート制限を追加しました。

**修正後:**
```java
// 未認証ユーザー: IPアドレスベースのレート制限
if (session == null) {
    return "rate_limit:api:anonymous:" + request.getRemoteAddr();
}
// 認証済みユーザー: セッションIDベースのレート制限
return "rate_limit:api:authenticated:" + session.getId();
```

#### 新しいレート制限ルール

| ユーザー種別 | 制限 | 識別方法 | Redis キー |
|------------|------|---------|-----------|
| 認証エンドポイント | 30req/分 | IPアドレス | `rate_limit:auth:{IP}` |
| API（認証済み） | 200req/分 | セッションID | `rate_limit:api:authenticated:{SESSION_ID}` |
| API（未認証）✨NEW | 100req/分 | IPアドレス | `rate_limit:api:anonymous:{IP}` |

#### 修正ファイル
- [RateLimitFilter.java](src/main/java/com/example/auth_bff/filter/RateLimitFilter.java): 未認証ユーザーのレート制限ロジック追加
- [application.yml](src/main/resources/application.yml): 認証済み/未認証の個別設定
- [.env](.env), [.env.example](.env.example): 環境変数の更新
- [RateLimitIntegrationTest.java](src/test/java/com/example/auth_bff/filter/RateLimitIntegrationTest.java): テストプロパティの修正

#### 効果
- ✅ 未認証でアクセス可能な公開APIエンドポイントもDoS攻撃から保護
- ✅ 認証済みユーザーは引き続き高い制限値（200req/分）で快適に利用可能
- ✅ 書籍検索サイト等、未認証ユーザー向けコンテンツがあるサービスに対応

#### 環境変数
```bash
# 認証済みユーザー（セッションIDベース）
RATE_LIMIT_API_AUTHENTICATED_RPM=200

# 未認証ユーザー（IPアドレスベース）✨NEW
RATE_LIMIT_API_ANONYMOUS_RPM=100
```

### 📊 改善サマリー

| 項目 | 改善前 | 改善後 | 効果 |
|------|--------|--------|------|
| ファイル数 | 12ファイル | 18ファイル | 必要最小限に最適化 |
| IDプロバイダー設定 | 個別エンドポイント指定 | issuer-uri統一 | 設定がシンプルに |
| 設定管理 | 複雑な設定クラス | @Value統一 | シンプルで直感的 |
| WebClient | リクエストごと作成 | シングルトン | 性能向上 |
| エラー処理 | 基本的な処理のみ | 統一されたエラーハンドリング | 障害対応力向上、保守性向上 |
| レート制限（認証済み） | なし | Bucket4j + Redis（200req/分） | セキュリティ強化 |
| レート制限（未認証）✨NEW | なし（脆弱性） | IPベース（100req/分） | DoS攻撃対策、公開API保護 |
| OIDC Discovery | なし | OidcMetadataClient | 動的メタデータ取得 |
| ログ出力 | デバッグログ多数 | エラー/警告のみ | 本番環境最適化 |
| テスト | 基本的なテストのみ | 24テスト（統合テスト含む） | 品質保証 |
| ドキュメント | 簡易コメント | 詳細Javadoc | 保守性向上 |

---

🚀 **Ready for Production!** このBFFは本番環境でのOAuth2認証フローに対応しています。
