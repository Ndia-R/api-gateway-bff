# Auth BFF プロジェクト - Claude Code 作業ガイド

## 重要：作業ルール

**基本的なやりとりは日本語でおこなってください。**

**コード修正前の確認必須**
- ファイルの修正・変更を行う前に、必ずユーザーに修正内容を提示して許可を取る
- 勝手にコードを変更してはいけない
- 修正案を説明し、ユーザーの承認を得てから実行する

## プロジェクト概要

OIDC準拠の認証プロバイダー（Keycloak、Auth0、Okta等）とのOAuth2認証フローを処理する **Spring Boot BFF (Backend for Frontend)** アプリケーションです。

### 技術スタック
- **言語**: Java 17
- **フレームワーク**: Spring Boot 3.5.6
- **認証**: Spring Security OAuth2 Client (PKCE対応)
- **セッション**: Spring Session Data Redis
- **レート制限**: Bucket4j + Redis
- **ビルド**: Gradle

### BFFパターンの役割
```
フロントエンド (SPA)
    ↓ Cookie: BFFSESSIONID + XSRF-TOKEN
   BFF (このアプリ)
    ├─ 認証管理 (/bff/auth/*)
    ├─ APIプロキシ (/api/**)
    └─ トークン管理（Redisセッション）
    ↓ Authorization: Bearer <access_token>
リソースサーバー
    ├─ ビジネスロジック
    ├─ 権限制御
    └─ データ処理
```

**重要**: BFFは認証とトークン管理に専念し、権限制御はリソースサーバーに委譲します。

## プロジェクト構成

```
src/main/java/com/example/api_gateway_bff/
├── ApiGatewayBffApplication.java      # メインクラス
├── config/                             # 設定（7ファイル）
│   ├── CustomAuthorizationRequestResolver.java  # PKCE + return_to保存
│   ├── ResourceServerProperties.java            # リソースサーバー設定
│   ├── WebClientConfig.java                     # WebClient設定
│   ├── CsrfCookieFilter.java                    # CSRF Cookie自動設定
│   ├── RedisConfig.java                         # Redis/Spring Session設定
│   ├── SecurityConfig.java                      # Spring Security設定
│   └── RateLimitConfig.java                     # レート制限設定
├── filter/                             # フィルター（2ファイル）
│   ├── FilterChainExceptionHandler.java         # フィルターチェーン例外処理
│   └── RateLimitFilter.java                     # レート制限フィルター
├── controller/                         # コントローラー（2ファイル）
│   ├── ApiProxyController.java                  # APIプロキシ
│   └── AuthController.java                      # 認証エンドポイント
├── service/                            # サービス（1ファイル）
│   └── AuthService.java                         # 認証ビジネスロジック
├── client/                             # クライアント（1ファイル）
│   └── OidcMetadataClient.java                  # OIDC Discovery
├── dto/                                # DTO（3ファイル）
│   ├── ErrorResponse.java
│   ├── LogoutResponse.java
│   └── OidcConfiguration.java
└── exception/                          # 例外（3ファイル）
    ├── GlobalExceptionHandler.java
    ├── UnauthorizedException.java
    └── RateLimitExceededException.java
```

## 設計原則

1. **必要最小限の構成**: すべてのクラスとメソッドが実際に使用されている
2. **BFFパターン（集約型）**: フロントエンドはトークンを一切扱わず、複数のリソースサーバーを集約
3. **権限制御の委譲**: BFFは認証に専念、権限はリソースサーバーが管理
4. **Spring Boot自動設定の活用**: カスタムBean最小限
5. **統一されたエラーハンドリング**: FilterChainExceptionHandlerとGlobalExceptionHandlerで一貫したエラーレスポンス
6. **パスベースルーティング**: `/api/my-books/**` → My Books API、`/api/my-musics/**` → My Musics API
7. **型安全な設定管理**: @ConfigurationPropertiesで複数サービスの設定を一元管理
8. **分散レート制限**: Redis + Bucket4jで複数インスタンス間でレート制限を共有

## コーディングスタイル

### 基本方針
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

#### 統一エラーレスポンス
両方のハンドラーが同じ`ErrorResponse` DTOを使用し、一貫したエラーレスポンスを返却します。

```json
{
  "error": "UNAUTHORIZED",
  "message": "認証が必要です",
  "status": 401,
  "path": "/bff/auth/login",
  "timestamp": "2025-10-12 14:30:45"
}
```

## 重要な技術的注意点

### CSRF保護
- **CSRFトークンCookie**: `XSRF-TOKEN` (HttpOnly=false)
- **CSRFトークンヘッダー**: `X-XSRF-TOKEN`
- **保護対象**: POST, PUT, DELETE, PATCH

フロントエンドはCookieからCSRFトークンを取得し、リクエストヘッダーに設定する必要があります。

### セッション管理
- **ストレージ**: Redis
- **Cookie名**: BFFSESSIONID
- **属性**: HttpOnly, Secure, SameSite=lax
- **タイムアウト**: 環境変数 `SESSION_TIMEOUT` で設定（デフォルト: 12時間）

### レート制限
- **認証エンドポイント** (`/bff/auth/login`): 30リクエスト/分（IPアドレスベース）
- **APIプロキシ（認証済み）** (`/api/**`): 200リクエスト/分（セッションIDベース）
- **APIプロキシ（未認証）** (`/api/**`): 100リクエスト/分（IPアドレスベース）

### APIプロキシの実装
- **パスプレフィックスの自動削除**: `/api/my-books/list` → My Books APIに `/list` として転送
- **Content-Type転送**: フロントエンドのContent-Typeをリソースサーバーに正しく転送
- **レスポンスヘッダーフィルタリング**: Springが自動設定するヘッダーを除外
- **タイムアウト**: 30秒
- **透過的なレスポンス転送**: ステータスコード、カスタムヘッダー、Content-Typeを保持

## 開発コマンド

### ビルド・テスト・実行
```bash
# ビルド
./gradlew build

# テスト実行
./gradlew test

# 特定のテストのみ実行
./gradlew test --tests RateLimitIntegrationTest

# アプリケーション実行
./gradlew bootRun
```

### Docker Compose
```bash
# 環境起動
docker compose up -d

# ログ確認
docker compose logs -f api-gateway-bff

# 環境停止
docker compose down

# 環境クリーンアップ（ボリュームも削除）
docker compose down -v
```

## 参考情報

詳細な情報は [README.md](README.md) を参照してください：
- エンドポイントの詳細
- 環境変数の完全なリスト
- IDプロバイダー別設定例
- 開発環境のセットアップ
- トラブルシューティング
- API使用例
- 改善履歴
