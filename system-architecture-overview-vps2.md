# 💻 Web アプリケーション構成概要 (VPS 2 台構成)

本システムは、機能分離とセキュリティ強化のため、役割の異なる **2 台の仮想プライベートサーバー (VPS)** を用いて構築されています。VPS1 は認証とイメージ管理、VPS2 はアプリケーション本体を実行します。

## 1. サーバー役割概要

| サーバー | ホスト名                     | 主な役割                                          | 接続方式               |
| :------- | :--------------------------- | :------------------------------------------------ | :--------------------- |
| **VPS1** | `vsv-crystal.skygroup.local` | **認証プロバイダー** および **Docker レジストリ** | **インターネット経由** |
| **VPS2** | `vsv-emerald.skygroup.local` | **Web アプリケーション本体** (実行環境)           | **インターネット経由** |

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

    %% ユーザーアクセス (VPS1へ - 認証・レジストリ)
    User -- OIDC認証 --> Nginx_VPS1
    Nginx_VPS1 -- /auth --> Keycloak
    Developer -. HTTPS (Push/Pull) .-> Nginx_VPS1
    Nginx_VPS1 -- /v2 --> Registry

    %% ユーザーアクセス (VPS2へ - 2つの経路)
    User -- HTTPS (REST API) --> Nginx_VPS2
    Nginx_VPS2 -- /api/** --> BFF
    User -- HTTPS (静的ファイル) --> Nginx_VPS2
    Nginx_VPS2 -- ルーティング --> APP

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

複数の独立したアプリケーション（例: my-books、my-music）を同一のVPS2上で稼働させる場合の構成図です。各アプリケーションは専用のバックエンドとDBを持ちますが、認証機能（BFF）とRedisは共通で利用します。

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

    %% 開発者アクセス (VPS1へ - レジストリ)
    Developer -. HTTPS (Push/Pull) .-> Nginx_VPS1
    Nginx_VPS1 -- /v2 --> Registry

    %% ユーザーアクセス (VPS2へ - REST API)
    User -- HTTPS (REST API) --> Nginx_VPS2
    Nginx_VPS2 -- /api/my-books/** --> BFF
    Nginx_VPS2 -- /api/my-musics/** --> BFF

    %% ユーザーアクセス (VPS2へ - 静的ファイル)
    User -- HTTPS (静的ファイル) --> Nginx_VPS2
    Nginx_VPS2 -- /my-books --> APP_Books
    Nginx_VPS2 -- /my-music --> APP_Music

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

- **認証基盤の統一**: 1つのBFFで複数アプリの認証を一元管理
- **リソースの効率化**: Redis や BFF を共有することでリソース消費を削減
- **アプリケーションの独立性**: 各アプリは専用のバックエンドとDBを持つため、データとロジックが分離
- **スケーラビリティ**: アプリケーション単位での個別のスケーリングが可能

### 2-2. 実装上のプロジェクト名とコンテナ名

上記のアーキテクチャ図で示されたコンポーネントは、実際には以下のプロジェクト名とコンテナ名で実装されています。

#### VPS1 (vsv-crystal.skygroup.local)

| プロジェクト名 | 図中の表記 | コンテナ名 | 役割 |
|-----------|--------------|-----------|------|
| `vsv-crystal` | Nginx | `nginx-edge` | エッジリバースプロキシ（HTTPS終端、認証・レジストリルーティング） |
|  | Keycloak | `keycloak` | OIDC認証プロバイダー |
|  | DB | `keycloak-db` | Keycloak専用データベース |
|  | Registry | `registry` | Dockerイメージレジストリ |

#### VPS2 (vsv-emerald.skygroup.local) - シングルアプリケーション構成

| プロジェクト名 | 図中の表記 | コンテナ名 | 役割 |
|-----------|--------------|-----------|------|
| `vsv-emerald` | Nginx | `nginx-edge` | エッジリバースプロキシ（HTTPS終端、アプリケーションルーティング） |
| `my-books-frontend` | APP | `my-books-frontend` | フロントエンド（SPA） |
| `my-books-api` | API | `my-books-api` | リソースサーバー（REST API） |
|  | DB | `my-books-db` | アプリケーションデータベース |
| `api-gateway-bff` | BFF | `api-gateway-bff` | 認証ゲートウェイ・APIプロキシ |
|  | Redis | `redis` | セッションストレージ（BFFトークン管理） |

## 3. VPS1: 認証・レジストリサーバー (`vsv-crystal.skygroup.local`)

インフラのコア機能、特に**認証認可**と**デプロイに必要なイメージ管理**を担います。

- **コンテナ構成**
  - **`nginx-edge`**: **エッジリバースプロキシ**。外部からのHTTPS/HTTPトラフィックを受け付ける**最前線の通信窓口**（ポート80/443を公開）。SSL終端とルーティングを担当し、以下のエンドポイントを提供：
    - `/auth` → Keycloak（OIDC認証）
    - `/v2` → Registry（Dockerイメージのpush/pull）
  - **`keycloak`**: **認証プロバイダー**。OpenID Connect (OIDC) プロトコルを提供。
  - **`keycloak-db`**: **Keycloak 専用のデータベース**。Keycloak が管理するユーザー情報、レルム設定、クライアント定義、セッション情報などを永続化するために利用されます。
  - **`registry`**: **Docker イメージレジストリ**。アプリケーションイメージの保管と配布。nginx-edge経由でのみアクセス可能（内部ポート5000）。

## 4. VPS2: Web アプリケーションサーバー (`vsv-emerald.skygroup.local`)

ユーザーに直接サービスを提供する、アプリケーションの実行環境です。

- **コンテナ構成**
  - **`nginx-edge`**: **エッジリバースプロキシ**。外部からのHTTPS/HTTPトラフィックを受け付ける**最前線の通信窓口**（ポート80/443を公開）。SSL終端とルーティングを担当し、リクエストを `frontend` やその他の内部サービスへ転送します。
  - **`xxx-frontend`**: **ユーザーインターフェース (UI)** を提供する内部サービス。クライアント側での**セッション管理**を担当。
  - **`xxx-api`**: アプリケーションの**メインビジネスロジック**を実行する API サービス。BFF からの有効なアクセストークンでのみアクセスを許可します。
  - **`xxx-db`**: アプリケーションデータの**永続化**を行うデータベース。
  - **`api-gateway-bff` (Backend For Frontend)**:
    - **認証ゲートウェイ**。VPS1 Keycloak とのトークン交換を行い、**アクセストークンとリフレッシュトークンを管理**します。
    - Frontend からのリクエストを検証し、Backend へ転送する際の**Bearer トークン付与**を担当します。
  - **`redis`**: **BFF**が利用する**キャッシュ/データストア**。**アクセストークンとリフレッシュトークン**の保存・管理に使用されます。

## 5. 認証・データアクセスフロー（Keycloak と BFF 連携）

本システムは、OIDC 認可コードフローと BFF を必須とするデータアクセスにより、機密性の高いトークンをクライアントに露出させないセキュアな設計を採用しています。

### 5-1. ユーザー認証フロー (OIDC Code Flow)

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

    Browser->>Keycloak: 3. 認可リクエスト (code, redirect_uri=BFF)
    Keycloak-->>Browser: 4. ユーザー認証 (ID/PW入力)
    Keycloak->>Browser: 5. 認可コード付与 & コールバックURLへリダイレクト

    Browser->>Nginx_V2: 6. 認可コード付きコールバック (redirect_uri)
    Nginx_V2->>BFF: コールバックURLへルーティング

    activate BFF
    BFF->>Keycloak: 7. トークン交換要求 (認可コード, Client Secret)
    Keycloak-->>BFF: 8. トークン発行 (Access/Refresh/ID Token)

    BFF->>Redis: 9. Access/Refresh TokenをセッションIDと紐づけて保存
    activate Redis
    Redis-->>BFF: 保存完了
    deactivate Redis

    BFF->>Browser: 10. セキュアなセッションクッキー発行
    deactivate BFF

    Browser->>Nginx_V2: 11. 認証完了後のリクエスト (セッションクッキー付与)
```

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

Access Tokenの有効期限が切れた際、BFFが自動的にRefresh Tokenを使用して新しいAccess Tokenを取得する流れをシーケンス図で示します。このプロセスはSpring Security OAuth2 Clientが自動的に処理します。

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

- **自動処理**: Spring Security OAuth2 Clientが`OAuth2AuthorizedClientManager.authorize()`実行時に自動的にトークン期限をチェックし、必要に応じてリフレッシュを実行
- **VPS間通信の最小化**: Access Tokenが有効な間はVPS1（Keycloak）への通信は発生せず、期限切れ時のみVPS間通信が発生
- **透過的な処理**: フロントエンドはトークンリフレッシュを意識する必要がなく、通常のAPIリクエストと同様に処理される
- **セキュリティ**: Refresh Tokenは常にBFFのRedis内に保持され、フロントエンドには一切公開されない
- **トークンローテーション**: Keycloak設定（`refreshTokenMaxReuse: 0`）により、リフレッシュ時に新しいRefresh Tokenが発行され、古いトークンは即座に無効化される。これによりトークン漏洩時のリスクを最小化し、Replay攻撃を防止
- **実装詳細**: `OAuth2AuthorizedClientManager`は`OAuth2AuthorizedClientProviderBuilder`で`refreshToken()`プロバイダーを設定することで、トークンリフレッシュ機能が有効化される（[SecurityConfig.java](src/main/java/com/example/api_gateway_bff/config/SecurityConfig.java)参照）
