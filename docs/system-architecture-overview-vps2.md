# 💻 Web アプリケーション構成概要 (VPS 2 台構成)

本システムは、機能分離とセキュリティ強化のため、役割の異なる **2 台の仮想プライベートサーバー (VPS)** を用いて構築されています。VPS1 は認証とイメージ管理、VPS2 はアプリケーション本体を実行します。

## 1. サーバー役割概要

| サーバー | ホスト名                     | 主な役割                                                                 | 接続方式               |
| :------- | :--------------------------- | :----------------------------------------------------------------------- | :--------------------- |
| **VPS1** | `vsv-crystal.skygroup.local` | **認証プロバイダー**、**Docker レジストリ**、および **静的アセット配信** | **インターネット経由** |
| **VPS2** | `vsv-emerald.skygroup.local` | **Web アプリケーション本体** (実行環境)                                  | **インターネット経由** |

## 2. システムコンポーネント関係図

以下の図は、VPS1 と VPS2 の役割、内部コンポーネント、そして外部との依存関係を視覚的に示しています。

```mermaid
graph LR
    subgraph 外部
        User(🧑 ユーザー)
        Developer(💻 開発者 / CI/CD)
    end

    subgraph VPS1 [🔑 VPS1<br>vsv-crystal.skygroup.local]
        direction LR
        Nginx_VPS1(Nginx<br>Edge Proxy)
        Keycloak(Keycloak)
        Keycloak_DB(DB<br>Keycloak)
        Registry(Registry)
        Nginx_Assets(Nginx<br>Assets)
    end

    subgraph VPS2 [🌐 VPS2<br>vsv-emerald.skygroup.local]
        direction LR
        Nginx_VPS2(Nginx<br>Edge Proxy)
        subgraph Apps [フロントエンド]
            APP(APP)
        end
        BFF(BFF)
        subgraph Apis [バックエンド]
            API(API)
        end
        Redis(Redis)
        subgraph Databases [データベース]
            DB(DB)
        end
    end

    %% ユーザーアクセス (VPS1へ - 認証)
    User -- OIDC認証 --> Nginx_VPS1
    Nginx_VPS1 -- /auth --> Keycloak

    %% ユーザーアクセス (VPS1へ - 画像・フォント等)
    User -- HTTPS (画像・フォント等) --> Nginx_VPS1
    Nginx_VPS1 -- /assets/** --> Nginx_Assets

    %% ユーザーアクセス (VPS2へ - REST API)
    User -- HTTPS (REST API) --> Nginx_VPS2
    Nginx_VPS2 -- /api/** --> BFF

    %% ユーザーアクセス (VPS2へ - 静的ファイル)
    User -- HTTPS (静的ファイル) --> Nginx_VPS2
    Nginx_VPS2 -- ルーティング --> APP

    %% 開発者アクセス (VPS1へ - レジストリ)
    Developer -. HTTPS (Push/Pull) .-> Nginx_VPS1
    Nginx_VPS1 -- /v2 --> Registry

    %% 認証フロー (VPS間連携)
    BFF -. OIDC(トークン交換/HTTPS) .-> Nginx_VPS1
    Nginx_VPS1 -. トークン交換 .-> Keycloak

    %% VPS1 内部通信
    Keycloak -- データ管理 --> Keycloak_DB

    %% アプリケーション内通信 (VPS2内部)
    APP -- セッションCookie --> BFF
    BFF -- Bearer Token --> API
    BFF -- トークン管理 --> Redis
    API -- DB接続 --> DB

    %% その他依存関係
    %% VPS2のコンテナはRegistryからPullされる
    Registry -. イメージ pull .-> VPS2
```

### 2-1. マルチアプリケーション構成例

複数の独立したアプリケーション（例: my-books、my-music）を同一の VPS2 上で稼働させる場合の構成図です。各アプリケーションは専用のバックエンドと DB を持ちますが、認証機能（BFF）と Redis は共通で利用します。

```mermaid
graph LR
    subgraph External [外部]
        User(🧑 ユーザー)
        Developer(💻 開発者 / CI/CD)
    end

    subgraph VPS1 [🔑 VPS1<br>vsv-crystal.skygroup.local]
        direction LR
        Nginx_VPS1(Nginx<br>Edge Proxy)
        Keycloak(Keycloak)
        Keycloak_DB(DB<br>Keycloak)
        Registry(Registry)
        Nginx_Assets(Nginx<br>Assets)
    end

    subgraph VPS2 [🌐 VPS2<br>vsv-emerald.skygroup.local]
        direction LR
        Nginx_VPS2(Nginx<br>Edge Proxy)

        subgraph Apps [フロントエンド]
            direction TB
            APP_Books(APP<br>my-books)
            APP_Music(APP<br>my-music)
        end

        BFF(BFF<br>共通認証)
        Redis(Redis)

        subgraph Apis [バックエンド]
            direction TB
            API_Books(API<br>my-books)
            API_Music(API<br>my-music)
        end

        subgraph Databases [データベース]
            direction TB
            DB_Books(DB<br>my-books)
            DB_Music(DB<br>my-music)
        end
    end

    %% ユーザーアクセス (VPS1へ - 認証)
    User -- OIDC認証 --> Nginx_VPS1
    Nginx_VPS1 -- /auth --> Keycloak

    %% ユーザーアクセス (VPS1へ - 画像・フォント等)
    User -- HTTPS (画像・フォント等) --> Nginx_VPS1
    Nginx_VPS1 -- /assets/** --> Nginx_Assets

    %% ユーザーアクセス (VPS2へ - REST API)
    User -- HTTPS (REST API) --> Nginx_VPS2
    Nginx_VPS2 -- /api/my-books/** --> BFF
    Nginx_VPS2 -- /api/my-musics/** --> BFF

    %% ユーザーアクセス (VPS2へ - 静的ファイル)
    User -- HTTPS (静的ファイル) --> Nginx_VPS2
    Nginx_VPS2 -- /my-books --> APP_Books
    Nginx_VPS2 -- /my-music --> APP_Music

    %% 開発者アクセス (VPS1へ - レジストリ)
    Developer -. HTTPS (Push/Pull) .-> Nginx_VPS1
    Nginx_VPS1 -- /v2 --> Registry

    %% 認証フロー (VPS間連携)
    BFF -. OIDC(トークン交換/HTTPS) .-> Nginx_VPS1
    Nginx_VPS1 -. トークン交換 .-> Keycloak
    Keycloak -- データ管理 --> Keycloak_DB

    %% アプリケーション内通信
    APP_Books -- セッションCookie --> BFF
    APP_Music -- セッションCookie --> BFF

    BFF -- トークン管理 --> Redis

    BFF -- Bearer Token --> API_Books
    BFF -- Bearer Token --> API_Music

    API_Books -- DB接続 --> DB_Books
    API_Music -- DB接続 --> DB_Music

    %% VPS2のコンテナはRegistryからPullされる
    Registry -. イメージ pull .-> VPS2
```

この構成により、以下のメリットが得られます：

- **認証基盤の統一**: 1 つの BFF で複数アプリの認証を一元管理
- **リソースの効率化**: Redis や BFF を共有することでリソース消費を削減
- **アプリケーションの独立性**: 各アプリは専用のバックエンドと DB を持つため、データとロジックが分離
- **スケーラビリティ**: アプリケーション単位での個別のスケーリングが可能

### 2-2. 実装上のプロジェクト名とコンテナ名

上記のアーキテクチャ図で示されたコンポーネントは、実際には以下のプロジェクト名とコンテナ名で実装されています。

#### VPS1 (vsv-crystal.skygroup.local)

| プロジェクト名 | 図中の表記 | コンテナ名     | 役割                                                               |
| -------------- | ---------- | -------------- | ------------------------------------------------------------------ |
| `vsv-crystal`  | Nginx      | `nginx-edge`   | エッジリバースプロキシ（HTTPS 終端、認証・レジストリルーティング） |
|                | Keycloak   | `keycloak`     | OIDC 認証プロバイダー                                              |
|                | DB         | `keycloak-db`  | Keycloak 専用データベース                                          |
|                | Registry   | `registry`     | Docker イメージレジストリ                                          |
|                | Nginx      | `nginx-assets` | 静的アセット配信（画像、フォント、CSS、JS 等）                     |

#### VPS2 (vsv-emerald.skygroup.local) - シングルアプリケーション構成

| プロジェクト名      | 図中の表記 | コンテナ名          | 役割                                                               |
| ------------------- | ---------- | ------------------- | ------------------------------------------------------------------ |
| `vsv-emerald`       | Nginx      | `nginx-edge`        | エッジリバースプロキシ（HTTPS 終端、アプリケーションルーティング） |
| `my-books-frontend` | APP        | `my-books-frontend` | フロントエンド（SPA）                                              |
| `my-books-api`      | API        | `my-books-api`      | リソースサーバー（REST API）                                       |
|                     | DB         | `my-books-db`       | アプリケーションデータベース                                       |
| `api-gateway-bff`   | BFF        | `api-gateway-bff`   | 認証ゲートウェイ・API プロキシ                                     |
|                     | Redis      | `redis`             | セッションストレージ（BFF トークン管理）                           |

## 3. VPS1: 認証・レジストリサーバー (`vsv-crystal.skygroup.local`)

インフラのコア機能、特に**認証認可**と**デプロイに必要なイメージ管理**を担います。

- **コンテナ構成**
  - **`nginx-edge`**: **エッジリバースプロキシ**。外部からの HTTPS/HTTP トラフィックを受け付ける**最前線の通信窓口**（ポート 80/443 を公開）。SSL 終端とルーティングを担当し、以下のエンドポイントを提供：
    - `/auth` → Keycloak（OIDC 認証）
    - `/v2` → Registry（Docker イメージの push/pull）
    - `/assets` → nginx-assets（静的アセット配信）
  - **`keycloak`**: **認証プロバイダー**。OpenID Connect (OIDC) プロトコルを提供。
  - **`keycloak-db`**: **Keycloak 専用のデータベース**。Keycloak が管理するユーザー情報、レルム設定、クライアント定義、セッション情報などを永続化するために利用されます。
  - **`registry`**: **Docker イメージレジストリ**。アプリケーションイメージの保管と配布。nginx-edge 経由でのみアクセス可能（内部ポート 5000）。
  - **`nginx-assets`**: **静的アセット配信サーバー**。画像ファイル、フォント、CSS、JavaScript などの静的リソースを高速に配信します。nginx-edge からのリクエスト（`/assets/**`）を受け取り、効率的なキャッシュと gzip 圧縮を適用して配信します。内部ポート 80 で稼働し、外部からは nginx-edge 経由でのみアクセス可能です。

## 4. VPS2: Web アプリケーションサーバー (`vsv-emerald.skygroup.local`)

ユーザーに直接サービスを提供する、アプリケーションの実行環境です。

- **コンテナ構成**
  - **`nginx-edge`**: **エッジリバースプロキシ**。外部からの HTTPS/HTTP トラフィックを受け付ける**最前線の通信窓口**（ポート 80/443 を公開）。SSL 終端とルーティングを担当し、リクエストを `frontend` やその他の内部サービスへ転送します。
  - **`xxx-frontend`**: **ユーザーインターフェース (UI)** を提供する内部サービス。クライアント側での**セッション管理**を担当。
  - **`xxx-api`**: アプリケーションの**メインビジネスロジック**を実行する API サービス。BFF からの有効なアクセストークンでのみアクセスを許可します。
  - **`xxx-db`**: アプリケーションデータの**永続化**を行うデータベース。
  - **`api-gateway-bff` (Backend For Frontend)**:
    - **認証ゲートウェイ**。VPS1 Keycloak とのトークン交換を行い、**アクセストークンとリフレッシュトークンを管理**します。
    - Frontend からのリクエストを検証し、Backend へ転送する際の**Bearer トークン付与**を担当します。
  - **`redis`**: **BFF**が利用する**キャッシュ/データストア**。**アクセストークンとリフレッシュトークン**の保存・管理に使用されます。

## 4-1. BFF のセキュリティ機能と主要機能

BFF（Backend for Frontend）は、単なる API プロキシではなく、以下の重要なセキュリティ機能とユーザビリティ向上機能を提供します。

### セキュリティ機能

#### 🔐 PKCE (Proof Key for Code Exchange) 対応

Authorization Code Flow のセキュリティを強化する仕組み。認可コードの盗聴攻撃を防止します。

**動作原理:**

1. BFF が`code_verifier`（ランダムな文字列 43-128 文字）を生成
2. `code_challenge`を計算: `BASE64URL(SHA256(code_verifier))`
3. 認可リクエスト時に`code_challenge`を Keycloak に送信
4. トークン交換時に`code_verifier`を Keycloak に送信して検証

**実装:** Spring Security 標準の`OAuth2AuthorizationRequestCustomizers.withPkce()`を使用

**参照:** [CustomAuthorizationRequestResolver.java](src/main/java/com/example/api_gateway_bff/config/CustomAuthorizationRequestResolver.java)

---

#### 🛡️ CSRF 保護 (Cookie ベース CSRF トークン)

POST/PUT/DELETE 等の状態変更操作を保護し、クロスサイトリクエストフォージェリ攻撃を防止します。

**仕組み:**

- **CSRF トークン Cookie**: `XSRF-TOKEN`（HttpOnly=false、JavaScript から読み取り可能）
- **CSRF トークンヘッダー**: フロントエンドはリクエスト時に`X-XSRF-TOKEN`ヘッダーにトークンを設定
- **自動検証**: Spring Security が自動的にトークンを検証

**フロントエンド実装例:**

```javascript
const csrfToken = document.cookie
  .split("; ")
  .find((row) => row.startsWith("XSRF-TOKEN="))
  ?.split("=")[1];

fetch("/api/books", {
  method: "POST",
  credentials: "include",
  headers: {
    "Content-Type": "application/json",
    "X-XSRF-TOKEN": csrfToken,
  },
  body: JSON.stringify({ title: "新しい本" }),
});
```

**参照:** [SecurityConfig.java](src/main/java/com/example/api_gateway_bff/config/SecurityConfig.java), [CsrfCookieFilter.java](src/main/java/com/example/api_gateway_bff/config/CsrfCookieFilter.java)

---

#### ⏱️ レート制限 (Bucket4j + Redis)

ブルートフォース攻撃や DDoS 攻撃を軽減する分散レート制限機能。

**レート制限ルール:**

| エンドポイント       | 制限              | 識別方法      | 目的                                      |
| -------------------- | ----------------- | ------------- | ----------------------------------------- |
| `/bff/auth/login`    | 30 リクエスト/分  | IP アドレス   | ブルートフォース攻撃防止                  |
| `/api/**` (認証済み) | 200 リクエスト/分 | セッション ID | API 乱用防止                              |
| `/api/**` (未認証)   | 100 リクエスト/分 | IP アドレス   | DoS 攻撃防止（書籍検索等の公開 API 保護） |

**除外エンドポイント（レート制限なし）:**

- `/actuator/health` - 監視システムからのヘルスチェック
- `/bff/login/oauth2/code/**` - Keycloak からのコールバック
- `/oauth2/authorization/**` - OAuth2 認証開始
- `/bff/auth/logout` - ログアウト（セッション無効化済み）

**技術詳細:**

- **ライブラリ**: Bucket4j 8.7.0
- **バックエンド**: Redis（Lettuce CAS 方式）
- **分散対応**: 複数 BFF インスタンス間でレート制限状態を共有
- **アルゴリズム**: Token Bucket（トークンバケット）

**レート制限超過時のレスポンス:**

```json
{
  "error": "TOO_MANY_REQUESTS",
  "message": "リクエスト数が制限を超えました。しばらく待ってから再試行してください。",
  "status": 429,
  "path": "/bff/auth/login",
  "timestamp": "2025-10-18 15:30:45"
}
```

**参照:** [RateLimitConfig.java](src/main/java/com/example/api_gateway_bff/config/RateLimitConfig.java), [RateLimitFilter.java](src/main/java/com/example/api_gateway_bff/filter/RateLimitFilter.java)

---

### ユーザビリティ向上機能

#### 🔄 認証後リダイレクト機能 (`return_to`パラメータ)

未認証ユーザーが特定のページにアクセスした際、認証完了後に元のページに自動的に復帰する機能。

**動作フロー:**

**1. 未認証ユーザーの場合:**

```
フロントエンド → /bff/auth/login?return_to=/my-reviews
    ↓
Spring Security → CustomAuthorizationRequestResolver
    ↓ (return_toをセッションに保存)
OAuth2認証フロー → Keycloakログイン画面
    ↓
認証成功 → authenticationSuccessHandler
    ↓ (セッションからreturn_toを取得)
フロントエンド ← /auth-callback?return_to=/my-reviews
```

**2. 認証済みユーザーの場合:**

```
フロントエンド → /bff/auth/login?return_to=/my-reviews
    ↓
AuthController.login()
    ↓
フロントエンド ← /auth-callback?return_to=/my-reviews (即座にリダイレクト)
```

**セキュリティ対策:**

- **オープンリダイレクト脆弱性対策**: `return_to`が安全な URL（相対パスまたは許可されたホスト）であるかを検証
- **許可されたホスト**: localhost、フロントエンドホスト
- 不正な URL は自動的にブロックされ、デフォルトの`/auth-callback`にリダイレクト

**参照:** [CustomAuthorizationRequestResolver.java](src/main/java/com/example/api_gateway_bff/config/CustomAuthorizationRequestResolver.java), [SecurityConfig.java](src/main/java/com/example/api_gateway_bff/config/SecurityConfig.java), [AuthController.java](src/main/java/com/example/api_gateway_bff/controller/AuthController.java)

---

### インフラ機能

#### 🔍 OIDC Discovery (自動メタデータ取得)

`IDP_ISSUER_URI`から`/.well-known/openid-configuration`を自動取得し、複数の OIDC 準拠 ID プロバイダーに対応します。

**対応 ID プロバイダー:**

| プロバイダー    | ISSUER_URI 例                                                                                                                                            |
| --------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Keycloak**    | `https://vsv-crystal.skygroup.local/auth/realms/sample-realm` (開発環境)<br>`https://vsv-crystal.skygroup.local/auth/realms/production-realm` (本番環境) |
| **Auth0**       | `https://your-tenant.auth0.com`                                                                                                                          |
| **Okta**        | `https://dev-12345678.okta.com/oauth2/default`                                                                                                           |
| **Azure AD**    | `https://login.microsoftonline.com/{tenant-id}/v2.0`                                                                                                     |
| **Google**      | `https://accounts.google.com`                                                                                                                            |
| **AWS Cognito** | `https://cognito-idp.{region}.amazonaws.com/{user-pool-id}`                                                                                              |

**利点:**

- ID プロバイダーの切り替えが環境変数の変更のみで可能
- エンドポイント（authorize, token, jwk, logout 等）の個別指定が不要
- Spring Security が自動的にメタデータを検出・設定

**参照:** [OidcMetadataClient.java](src/main/java/com/example/api_gateway_bff/client/OidcMetadataClient.java)

---

#### 🚦 パスベースルーティング (複数リソースサーバー対応)

`/api/**`配下のリクエストをパスプレフィックスに基づいて複数のバックエンドサービスにルーティングします。

**ルーティング例:**

| フロントエンドリクエスト     | パスプレフィックス | 転送先                             |
| ---------------------------- | ------------------ | ---------------------------------- |
| `GET /api/my-books/list`     | `/my-books`        | `http://my-books-api:8080/list`    |
| `POST /api/my-musics/search` | `/my-musics`       | `http://my-musics-api:8081/search` |

**動作:**

1. BFF がリクエストパスからプレフィックスを抽出
2. `ResourceServerProperties`から対応するサービスを選択
3. パスプレフィックスを削除してターゲットパスを生成（`/my-books/list` → `/list`）
4. 認証済みの場合、アクセストークンを`Authorization: Bearer <token>`ヘッダーに付与
5. 選択したリソースサーバーにリクエストを転送
6. レスポンスを透過的にフロントエンドに返却

**設定例 (application.yml):**

```yaml
resource-servers:
  my-books:
    url: http://my-books-api:8080
    path-prefix: /my-books
    timeout: 30
  my-musics:
    url: http://my-musics-api:8081
    path-prefix: /my-musics
    timeout: 30
```

**利点:**

- 新しいリソースサーバーの追加が設定ファイルの変更のみで可能
- BFF コードの変更不要
- 権限制御はリソースサーバー側で実施（BFF は認証のみ担当）

**参照:** [ApiProxyController.java](src/main/java/com/example/api_gateway_bff/controller/ApiProxyController.java), [ResourceServerProperties.java](src/main/java/com/example/api_gateway_bff/config/ResourceServerProperties.java)

---

## 5. 認証・データアクセスフロー（Keycloak と BFF 連携）

本システムは、OIDC 認可コードフローと BFF を必須とするデータアクセスにより、機密性の高いトークンをクライアントに露出させないセキュアな設計を採用しています。

### 5-1. ユーザー認証フロー (OIDC Code Flow with PKCE)

Keycloak と BFF が連携し、BFF 内の Redis にトークンを保存してセッションを確立するまでの流れをシーケンス図で示します。

```mermaid
sequenceDiagram
    participant Browser as 🧑 ブラウザ (Frontend)
    participant Nginx_V2 as 🌐 Nginx (vsv-emerald)
    participant BFF as 💻 BFF (vsv-emerald)
    participant Redis as 💾 Redis (vsv-emerald)
    participant Keycloak as 🔑 Keycloak (vsv-crystal)

    title OpenID Connect (OIDC) 認可コードフロー

    Browser->>Nginx_V2: 1. /login アクセス (セッションなし)
    Nginx_V2->>BFF: /login へルーティング
    activate BFF
    BFF->>Browser: 2. 認証画面へリダイレクト
    deactivate BFF

    Browser->>Keycloak: 3. 認可リクエスト (code_challenge, redirect_uri=BFF)
    Note over Browser,Keycloak: PKCE: code_challengeをKeycloakに送信
    Keycloak-->>Browser: 4. ユーザー認証 (ID/PW入力)
    Keycloak->>Browser: 5. 認可コード付与 & コールバックURLへリダイレクト

    Browser->>Nginx_V2: 6. 認可コード付きコールバック (redirect_uri)
    Nginx_V2->>BFF: コールバックURLへルーティング

    activate BFF
    BFF->>Keycloak: 7. トークン交換要求 (認可コード, Client Secret, code_verifier)
    Note over BFF,Keycloak: PKCE: code_verifierで検証
    Keycloak-->>BFF: 8. トークン発行 (Access/Refresh/ID Token)

    BFF->>Redis: 9. Access/Refresh TokenをセッションIDと紐づけて保存
    activate Redis
    Redis-->>BFF: 保存完了
    deactivate Redis

    BFF->>Browser: 10. セキュアなセッションクッキー発行
    deactivate BFF

    Browser->>Nginx_V2: 11. 認証完了後のリクエスト (セッションクッキー付与)
```

**PKCE (Proof Key for Code Exchange) の役割:**

このフローでは、認可コードの盗聴攻撃を防止するため、PKCE が適用されています：

1. **Step 2**: BFF が`code_verifier`（ランダムな文字列）を生成し、`code_challenge`を計算
2. **Step 3**: `code_challenge`を Keycloak に送信（認可リクエスト）
3. **Step 7**: `code_verifier`を Keycloak に送信（トークン交換）
4. Keycloak が`code_verifier`から`code_challenge`を再計算し、Step 3 で受け取った値と一致するかを検証

これにより、認可コードが盗聴されても、`code_verifier`を持たない攻撃者はトークンを取得できません。

---

### 5-2. データアクセスフロー

認証完了後、Frontend からのデータ取得リクエストが BFF を経由し、Redis に保存されたトークンを用いて Backend へセキュアにアクセスする流れをシーケンス図で示します。

```mermaid
sequenceDiagram
    participant Browser as 🧑 ブラウザ (Frontend)
    participant Nginx_V2 as 🌐 Nginx (vsv-emerald)
    participant BFF as 💻 BFF (vsv-emerald)
    participant Redis as 💾 Redis (vsv-emerald)
    participant API as ⚙️ API (vsv-emerald)

    title データアクセスフロー (認証後)

    Browser->>Nginx_V2: 1. データ取得リクエスト (/api/data)
    Nginx_V2->>BFF: リクエストルーティング (セッションクッキー付き)
    activate BFF
    BFF->>Redis: 2. セッションIDからAccess Tokenを取得
    activate Redis
    Redis-->>BFF: Access Tokenを返却
    deactivate Redis

    BFF->>API: 3. APIアクセス (Authorization: Bearer <Token> 付与)
    activate API
    API-->>BFF: 4. データ応答
    deactivate API

    BFF->>Browser: 5. 応答データ返却
    deactivate BFF
```

---

### 5-3. トークンリフレッシュフロー

Access Token の有効期限が切れた際、BFF が自動的に Refresh Token を使用して新しい Access Token を取得する流れをシーケンス図で示します。このプロセスは Spring Security OAuth2 Client が自動的に処理します。

```mermaid
sequenceDiagram
    participant Browser as 🧑 ブラウザ (Frontend)
    participant Nginx_V2 as 🌐 Nginx (vsv-emerald)
    participant BFF as 💻 BFF (vsv-emerald)
    participant Redis as 💾 Redis (vsv-emerald)
    participant Nginx_V1 as 🔑 Nginx (vsv-crystal)
    participant Keycloak as 🔑 Keycloak (vsv-crystal)
    participant API as ⚙️ API (vsv-emerald)

    title トークンリフレッシュフロー (Access Token期限切れ時)

    Browser->>Nginx_V2: 1. データ取得リクエスト (/api/data)
    Nginx_V2->>BFF: リクエストルーティング (セッションクッキー付き)
    activate BFF
    BFF->>Redis: 2. セッションIDからAccess Tokenを取得
    activate Redis
    Redis-->>BFF: Access Token返却 (期限切れ)
    deactivate Redis

    Note over BFF: 3. BFFがトークン期限切れを検出<br>Refresh Tokenを使用して自動更新

    BFF->>Nginx_V1: 4. トークンリフレッシュ要求<br>(Refresh Token + Client Credentials)
    Nginx_V1->>Keycloak: トークンリフレッシュ要求
    activate Keycloak
    Note over Keycloak: Refresh Tokenを検証し、<br>新しいAccess Token + Refresh Tokenを発行<br>古いRefresh Tokenは即座に無効化
    Keycloak-->>Nginx_V1: 5. 新しいAccess Token + Refresh Token発行
    Nginx_V1-->>BFF: 新しいトークンセット返却
    deactivate Keycloak

    BFF->>Redis: 6. 新しいAccess Token + Refresh Tokenをセッションに保存
    activate Redis
    Redis-->>BFF: 保存完了
    deactivate Redis

    BFF->>API: 7. APIアクセス (Authorization: Bearer <新Token> 付与)
    activate API
    API-->>BFF: 8. データ応答
    deactivate API

    BFF->>Browser: 9. 応答データ返却
    deactivate BFF

    Note over Browser,BFF: ユーザーは中断なくサービスを利用可能<br>(トークンリフレッシュは透過的に処理)
```

**重要なポイント:**

- **自動処理**: Spring Security OAuth2 Client が`OAuth2AuthorizedClientManager.authorize()`実行時に自動的にトークン期限をチェックし、必要に応じてリフレッシュを実行
- **VPS 間通信の最小化**: Access Token が有効な間は VPS1（Keycloak）への通信は発生せず、期限切れ時のみ VPS 間通信が発生
- **透過的な処理**: フロントエンドはトークンリフレッシュを意識する必要がなく、通常の API リクエストと同様に処理される
- **セキュリティ**: Refresh Token は常に BFF の Redis 内に保持され、フロントエンドには一切公開されない
- **トークンローテーション**: Keycloak 設定（`refreshTokenMaxReuse: 0`）により、リフレッシュ時に新しい Refresh Token が発行され、古いトークンは即座に無効化される。これによりトークン漏洩時のリスクを最小化し、Replay 攻撃を防止
- **PKCE 対応**: 認証フロー全体で PKCE が適用され、認可コードの盗聴攻撃を防止（詳細は「4-1. BFF のセキュリティ機能と主要機能」参照）
- **CSRF 保護**: すべての状態変更操作（POST/PUT/DELETE）は CSRF トークンで保護（詳細は「4-1. BFF のセキュリティ機能と主要機能」参照）
- **レート制限**: 認証エンドポイント・API プロキシにレート制限が適用され、ブルートフォース攻撃や DDoS 攻撃を軽減（詳細は「4-1. BFF のセキュリティ機能と主要機能」参照）
- **実装詳細**: `OAuth2AuthorizedClientManager`は`OAuth2AuthorizedClientProviderBuilder`で`refreshToken()`プロバイダーを設定することで、トークンリフレッシュ機能が有効化される（[SecurityConfig.java](src/main/java/com/example/api_gateway_bff/config/SecurityConfig.java)参照）
