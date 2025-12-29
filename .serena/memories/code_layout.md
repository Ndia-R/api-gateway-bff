# コードレイアウト

## プロジェクト構造

### パッケージ構成

```
src/main/java/com/example/api_gateway_bff/
├── ApiGatewayBffApplication.java              # メインクラス
│
├── config/                                     # 設定（7ファイル）
│   ├── ResourceServerProperties.java          # リソースサーバー設定プロパティ（複数サーバー対応）
│   ├── WebClientConfig.java                   # サービスごとのWebClient設定（コネクションプール最適化）
│   ├── CsrfCookieFilter.java                  # CSRF Cookie自動設定フィルター
│   ├── RedisConfig.java                       # Redis/Spring Session設定
│   ├── SecurityConfig.java                    # Spring Security + PKCE + CORS + フィルターチェーン例外処理
│   ├── RateLimitConfig.java                   # レート制限設定（Bucket4j + Redis）
│   └── CustomAuthorizationRequestResolver.java # カスタムOAuth2認可リクエストリゾルバー（PKCE + return_to保存）
│
├── filter/                                     # フィルター（2ファイル）
│   ├── FilterChainExceptionHandler.java       # フィルターチェーン例外ハンドラー
│   └── RateLimitFilter.java                   # レート制限フィルター
│
├── controller/                                 # コントローラー（2ファイル）
│   ├── ApiProxyController.java                # APIプロキシ（パスベースルーティング、複数リソースサーバー対応）
│   └── AuthController.java                    # 認証エンドポイント（/bff/auth/*）
│
├── client/                                     # クライアント（1ファイル）
│   └── OidcMetadataClient.java                # OIDC Discoveryクライアント
│
├── dto/                                        # DTO（3ファイル）
│   ├── ErrorResponse.java                     # 統一エラーレスポンス
│   ├── LogoutResponse.java                    # ログアウトレスポンス
│   └── OidcConfiguration.java                 # OIDC設定情報
│
├── exception/                                  # 例外（3ファイル）
│   ├── GlobalExceptionHandler.java            # 統一エラーハンドラー
│   ├── UnauthorizedException.java             # 認証エラー例外
│   └── RateLimitExceededException.java        # レート制限超過例外
│
└── service/                                    # サービス（1ファイル）
    └── AuthService.java                       # 認証ビジネスロジック
```

## 主要コンポーネントの役割

### 1. 設定（config/）

#### CustomAuthorizationRequestResolver.java (新規追加: 2025-12-27)
- **役割**: PKCE対応のカスタムOAuth2認可リクエストリゾルバー
- **機能**:
  - PKCE (Proof Key for Code Exchange) のcode_challenge/code_verifierを自動生成
  - 認証後のリダイレクト先（return_to）をセッションに保存
  - Spring SecurityのOAuth2AuthorizationRequestResolverインターフェースを実装
- **使用箇所**: SecurityConfig.javaから参照され、OAuth2ログインフローに組み込まれる

#### SecurityConfig.java
- **役割**: Spring Securityの中核設定
- **機能**:
  - OAuth2AuthorizedClientManagerを使用したトークン自動リフレッシュ機能 (追加: 2025-12-27)
  - CustomAuthorizationRequestResolverの統合 (追加: 2025-12-27)
  - 認証成功ハンドラーにreturn_toパラメータのリダイレクト処理 (追加: 2025-12-27)
  - CSRF保護、CORS設定、フィルターチェーン例外処理
- **使用箇所**: Spring Bootアプリケーション起動時に自動設定される

### 2. コントローラー（controller/）

#### ApiProxyController.java
- **役割**: すべてのAPIリクエストをリソースサーバーにプロキシ
- **主要な変更** (2025-12-27):
  - OAuth2AuthorizedClientRepositoryからOAuth2AuthorizedClientManagerに変更
  - アクセストークン期限切れ時の自動リフレッシュ機能を実装
  - リフレッシュトークンを使用して新しいアクセストークンを自動取得
- **機能**:
  - パスベースルーティング（/api/my-books/**, /api/my-musics/** 等）
  - 認証済みユーザーのアクセストークンを自動付与
  - 未認証ユーザーのリクエストはトークンなしで転送

#### AuthController.java
- **役割**: 認証関連のエンドポイント
- **主要な変更** (2025-12-27):
  - return_toパラメータのバリデーションとセッション保存機能を追加
  - セキュリティ検証により、安全なURLのみリダイレクト先として許可
- **エンドポイント**:
  - GET /bff/auth/login?return_to=/my-reviews - 認証後のリダイレクト先を指定
  - POST /bff/auth/logout?complete=true - 完全ログアウト

## アーキテクチャ上の重要なポイント

### トークン管理とリフレッシュ (追加: 2025-12-27)

```
フロントエンド
    ↓ Cookie: BFFSESSIONID
   BFF
    ├─ OAuth2AuthorizedClientManager (トークン管理)
    │   ├─ アクセストークン有効期限チェック
    │   ├─ 期限切れ時、リフレッシュトークンで自動更新
    │   └─ 新しいトークンをRedisセッションに保存
    └─ APIプロキシ
    ↓ Authorization: Bearer <access_token>
リソースサーバー
```

### 認証後リダイレクト機能 (追加: 2025-12-27)

```
1. フロントエンド → /bff/auth/login?return_to=/my-reviews
2. AuthController → return_toをバリデーション・セッション保存
3. CustomAuthorizationRequestResolver → OAuth2認証フロー開始
4. IdP → 認証完了後、BFFにコールバック
5. SecurityConfig.authenticationSuccessHandler → セッションからreturn_toを取得
6. BFF → フロントエンドに /auth-callback?return_to=/my-reviews でリダイレクト
```

### BFFパターンの設計原則

1. **必要最小限の構成**: すべてのクラスとメソッドが実際に使用されている（20ファイル）
2. **BFFパターン（集約型）**: フロントエンドはトークンを一切扱わず、複数のリソースサーバーを集約
3. **権限制御の委譲**: BFFは認証に専念、権限はリソースサーバーが管理
4. **Spring Boot自動設定の活用**: カスタムBean最小限
5. **統一されたエラーハンドリング**: FilterChainExceptionHandlerとGlobalExceptionHandlerで一貫したエラーレスポンス
