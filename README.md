# Auth BFF (Backend for Frontend)

OIDC準拠の認証プロバイダー（Keycloak、Auth0、Okta等）とのOAuth2認証フローを処理する**必要最小限のSpring Boot BFF (Backend for Frontend)** アプリケーションです。

## 特徴

- **BFFパターン実装**: フロントエンドはトークンを一切扱わず、BFFがすべてのAPI呼び出しをプロキシ
  - XSS攻撃からトークンを完全に保護
  - セッションCookieのみでAPIアクセス可能

- **PKCE対応**: Authorization Code with PKCEによるセキュアなOAuth2認証

- **トークン自動リフレッシュ**: OAuth2AuthorizedClientManagerによるシームレスなトークン管理
  - アクセストークン期限切れ時、自動的にリフレッシュトークンで更新
  - ユーザーに再ログインを強制せず、透過的にセッションを維持
  - Spring Security公式推奨のベストプラクティス実装

- **認証後リダイレクト機能**: `return_to` パラメータで認証後の復帰先を指定可能
  - オープンリダイレクト脆弱性対策実装済み
  - セッション管理による安全なリダイレクト先保存

- **最小構成**: 20ファイルで構成された、保守しやすいシンプルな設計
  - 未使用のクラス・メソッドは一切なし
  - Spring Boot自動設定を最大限活用

- **完全なCSRF保護**: CookieベースのCSRFトークンで状態変更操作を保護

- **レート制限**: Bucket4j + Redisによる分散レート制限でブルートフォース攻撃やDDoSを軽減

## アーキテクチャ

```
フロントエンド (SPA)
    ↓ Cookie: BFFSESSIONID + XSRF-TOKEN
   BFF (APIゲートウェイ)
    ├─ 認証管理 (/bff/auth/*)
    ├─ APIプロキシ (/api/**)
    └─ トークン管理（Redisセッション）
    ↓ Authorization: Bearer <access_token>
複数のリソースサーバー
    ├─ ビジネスロジック
    ├─ 権限制御
    └─ データ処理
```

## 技術スタック

- **言語**: Java 21
- **フレームワーク**: Spring Boot 3.5.6
- **認証**: Spring Security OAuth2 Client (OIDC準拠)
- **セッション管理**: Spring Session Data Redis
- **レート制限**: Bucket4j + Redis
- **ビルドツール**: Gradle

## クイックスタート

### 前提条件

- Java 21以上
- Docker & Docker Compose
- OIDC準拠の認証プロバイダー（Keycloak、Auth0、Okta等）

### 環境変数の設定

`.env.example` をコピーして `.env` を作成し、以下の環境変数を設定してください。

```bash
# Identity Provider Configuration
IDP_CLIENT_ID=your-client-id
IDP_CLIENT_SECRET=your-client-secret
IDP_ISSUER_URI=https://your-idp.com/realms/your-realm

# Application Configuration
FRONTEND_URL=https://localhost
SERVICE_01_URL=http://your-api:8080
SERVICE_01_PATH_PREFIX=/your-service
```

### Docker Composeで起動

```bash
# 環境を起動
docker compose up -d

# ログを確認
docker compose logs -f api-gateway-bff
```

### ローカル開発

```bash
# ビルド
./gradlew build

# アプリケーション実行
./gradlew bootRun

# テスト実行
./gradlew test
```

## エンドポイント

### 認証エンドポイント

| メソッド | パス | 説明 |
|---------|------|------|
| GET | `/bff/auth/login` | 認証状態確認・OAuth2フロー開始 |
| GET | `/bff/auth/login?return_to=/my-reviews` | 認証後に指定URLへリダイレクト |
| POST | `/bff/auth/logout` | 通常ログアウト（BFFセッションのみクリア） |
| POST | `/bff/auth/logout?complete=true` | 完全ログアウト（IDプロバイダーセッションも無効化） |

### APIプロキシエンドポイント

BFFは `/api/**` 配下のリクエストをパスプレフィックスに基づいて適切なリソースサーバーにルーティングします。

```
GET /api/my-books/list → My Books API: GET /list
POST /api/my-books/1   → My Books API: POST /1
```

## セキュリティ

### CSRF保護

- **CSRFトークンCookie**: `XSRF-TOKEN` (HttpOnly=false)
- **CSRFトークンヘッダー**: `X-XSRF-TOKEN`
- **保護対象**: POST, PUT, DELETE, PATCH

フロントエンドはCookieからCSRFトークンを取得し、リクエストヘッダーに設定する必要があります。

```javascript
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

### 認証後リダイレクト機能

`return_to` パラメータで認証後の復帰先を指定できます。

**未認証ユーザーの場合:**
```
1. フロントエンド → /bff/auth/login?return_to=/my-reviews
2. OAuth2認証フロー → IdPログイン画面
3. 認証成功 → /auth-callback?return_to=/my-reviews
```

**認証済みユーザーの場合:**
```
1. フロントエンド → /bff/auth/login?return_to=/my-reviews
2. 即座にリダイレクト → /auth-callback?return_to=/my-reviews
```

**セキュリティ:**
- オープンリダイレクト脆弱性対策実装済み
- 許可されたホスト（localhost、フロントエンドホスト）または相対パスのみ許可
- 不正なURLは自動的にブロック

### レート制限

| エンドポイント | 制限 | 識別方法 |
|--------------|------|---------|
| `/bff/auth/login` | 30リクエスト/分 | IPアドレス |
| `/api/**` (認証済み) | 200リクエスト/分 | セッションID |
| `/api/**` (未認証) | 100リクエスト/分 | IPアドレス |

## 環境変数

| 変数名 | 説明 | デフォルト値 |
|--------|------|-------------|
| `IDP_CLIENT_ID` | OAuth2クライアントID | - |
| `IDP_CLIENT_SECRET` | OAuth2クライアントシークレット | - |
| `IDP_ISSUER_URI` | IDプロバイダーのIssuer URI | - |
| `IDP_REDIRECT_URI` | OAuth2リダイレクトURI | - |
| `IDP_POST_LOGOUT_REDIRECT_URI` | ログアウト後のリダイレクトURI | - |
| `FRONTEND_URL` | フロントエンドURL | - |
| `SERVICE_01_URL` | リソースサーバー1のURL | - |
| `SERVICE_01_PATH_PREFIX` | リソースサーバー1のパスプレフィックス | `/service-01` |
| `REDIS_HOST` | Redisホスト | `redis` |
| `REDIS_PORT` | Redisポート | `6379` |
| `LOG_LEVEL` | ログレベル | `INFO` |
| `RATE_LIMIT_ENABLED` | レート制限の有効/無効 | `true` |
| `RATE_LIMIT_AUTH_RPM` | 認証エンドポイントのレート制限 | `30` |
| `RATE_LIMIT_API_AUTHENTICATED_RPM` | API（認証済み）のレート制限 | `200` |
| `RATE_LIMIT_API_ANONYMOUS_RPM` | API（未認証）のレート制限 | `100` |

## プロジェクト構成

```
src/main/java/com/example/api_gateway_bff/
├── ApiGatewayBffApplication.java          # メインクラス
├── config/                                 # 設定
│   ├── CustomAuthorizationRequestResolver.java
│   ├── CsrfCookieFilter.java
│   ├── RateLimitConfig.java
│   ├── RedisConfig.java
│   ├── ResourceServerProperties.java
│   ├── SecurityConfig.java
│   └── WebClientConfig.java
├── controller/                             # コントローラー
│   ├── ApiProxyController.java
│   └── AuthController.java
├── service/                                # サービス
│   └── AuthService.java
├── client/                                 # クライアント
│   └── OidcMetadataClient.java
├── filter/                                 # フィルター
│   ├── FilterChainExceptionHandler.java
│   └── RateLimitFilter.java
├── dto/                                    # DTO
│   ├── ErrorResponse.java
│   ├── LogoutResponse.java
│   └── OidcConfiguration.java
└── exception/                              # 例外
    ├── GlobalExceptionHandler.java
    ├── RateLimitExceededException.java
    └── UnauthorizedException.java
```

## 開発

### ビルド

```bash
./gradlew build
```

### テスト

```bash
# すべてのテスト実行
./gradlew test

# 特定のテストクラスのみ実行
./gradlew test --tests RateLimitIntegrationTest
```

### Docker環境

```bash
# 環境を起動
docker compose up -d

# ログを確認
docker compose logs -f api-gateway-bff

# 環境を停止
docker compose down

# 環境をクリーンアップ（ボリュームも削除）
docker compose down -v
```

## IDプロバイダー別設定例

### Keycloak
```bash
IDP_ISSUER_URI=http://auth.localhost:8444/realms/test-realm
```

### Auth0
```bash
IDP_ISSUER_URI=https://your-tenant.auth0.com
```

### Okta
```bash
IDP_ISSUER_URI=https://dev-12345678.okta.com/oauth2/default
```

### Azure AD
```bash
IDP_ISSUER_URI=https://login.microsoftonline.com/{tenant-id}/v2.0
```

## トラブルシューティング

### CORS エラー
`SecurityConfig.corsConfigurationSource()` で許可オリジンを確認してください。

### 認証ループ
- IDプロバイダー設定のリダイレクトURI確認
- `application.yml` の OAuth2設定確認

### セッション問題
- Redis接続確認
- Cookie設定（Secure属性）確認

## ライセンス

このプロジェクトはMITライセンスの下で公開されています。

## サポート

問題が発生した場合は、Issueを作成してください。
