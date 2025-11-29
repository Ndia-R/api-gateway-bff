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
    subgraph External 外部
        User(🧑 ユーザー)
        Developer(💻 開発者 / CI/CD)
    end

    %% VPS2のタイトルに全角スペースを挿入して右寄せに見せる
    subgraph VPS1 [🔑 VPS1:</br>vsv-crystal.skygroup.local]
        direction LR
        Nginx_Crystal(Nginx-Edge)
        Keycloak(Keycloak)
        Keycloak_DB(Keycloak DB)
        Registry(Registry)
    end

    subgraph VPS2 [🌐 VPS2:<br/>vsv-emerald.skygroup.local]
        direction LR
        Nginx_Emerald(Nginx-Edge)
        Frontend(Frontend)
        BFF(BFF)
        Backend(Backend)
        Redis(Redis)
        DB(DB)
    end

    %% ユーザーアクセス (VPS1へ - 認証・レジストリ)
    User -- OIDC認証 --> Nginx_Crystal
    Nginx_Crystal -- /auth --> Keycloak
    Developer -- HTTPS (Push/Pull) --> Nginx_Crystal
    Nginx_Crystal -- /v2 --> Registry

    %% ユーザーアクセス (VPS2へ - 2つの経路)
    User -- HTTPS (静的ファイル) --> Nginx_Emerald
    Nginx_Emerald -- ルーティング --> Frontend
    User -- HTTPS (REST API) --> Nginx_Emerald
    Nginx_Emerald -- /api/** --> BFF

    %% 認証フロー (VPS間連携)
    BFF -- OIDC(トークン交換/HTTPS) --> Nginx_Crystal
    Nginx_Crystal -- トークン交換 --> Keycloak

    %% VPS1 内部通信
    Keycloak -- ユーザー/設定データ管理 --> Keycloak_DB

    %% アプリケーション内通信 (VPS2内部)
    Frontend -- セッションCookie --> BFF
    BFF -- Bearer Token --> Backend
    BFF -- トークン管理 --> Redis
    Backend -- DB接続 --> DB

    %% その他依存関係
    %% VPS1とVPS2のコンテナはRegistryからPullされる
    Registry --> VPS1 & VPS2
```

### 2-1. マルチアプリケーション構成例

複数の独立したアプリケーション（例: my-books、my-music）を同一のVPS2上で稼働させる場合の構成図です。各アプリケーションは専用のバックエンドとDBを持ちますが、認証機能（BFF）とRedisは共通で利用します。

```mermaid
graph LR
    subgraph External [外部]
        User(🧑 ユーザー)
        Developer(💻 開発者 / CI/CD)
    end

    subgraph VPS1 [🔑 VPS1:<br/>vsv-crystal.skygroup.local]
        direction LR
        Nginx_Crystal_Multi(Nginx-Edge)
        Keycloak_Multi(Keycloak)
        Keycloak_DB_Multi(Keycloak DB)
        Registry_Multi(Registry)
    end

    subgraph VPS2 [🌐 VPS2:<br/>vsv-emerald.skygroup.local]
        direction TB
        Nginx_Emerald_Multi(Nginx-Edge)

        subgraph Apps [アプリケーション群]
            direction LR
            Frontend_Books(Frontend<br/>my-books)
            Frontend_Music(Frontend<br/>my-music)
        end

        BFF(BFF<br/>共通認証)
        Redis(Redis<br/>共通)

        subgraph Backends [バックエンド群]
            direction LR
            Backend_Books(Backend<br/>my-books)
            Backend_Music(Backend<br/>my-music)
        end

        subgraph Databases [データベース群]
            direction LR
            DB_Books(DB<br/>my-books)
            DB_Music(DB<br/>my-music)
        end
    end

    %% ユーザーアクセス (VPS1へ - 認証)
    User -- OIDC認証 --> Nginx_Crystal_Multi
    Nginx_Crystal_Multi -- /auth --> Keycloak_Multi

    %% 開発者アクセス (VPS1へ - レジストリ)
    Developer -- Push/Pull --> Nginx_Crystal_Multi
    Nginx_Crystal_Multi -- /v2 --> Registry_Multi

    %% ユーザーアクセス (VPS2へ - 静的ファイル)
    User -- HTTPS (静的ファイル) --> Nginx_Emerald_Multi
    Nginx_Emerald_Multi -- /books --> Frontend_Books
    Nginx_Emerald_Multi -- /music --> Frontend_Music

    %% ユーザーアクセス (VPS2へ - REST API)
    User -- HTTPS (REST API) --> Nginx_Emerald_Multi
    Nginx_Emerald_Multi -- /api/my-books/** --> BFF
    Nginx_Emerald_Multi -- /api/my-musics/** --> BFF

    %% 認証フロー (VPS間連携)
    BFF -- トークン交換 --> Nginx_Crystal_Multi
    Nginx_Crystal_Multi -- トークン交換 --> Keycloak_Multi
    Keycloak_Multi -- データ管理 --> Keycloak_DB_Multi

    %% アプリケーション内通信
    Frontend_Books -- セッションCookie --> BFF
    Frontend_Music -- セッションCookie --> BFF

    BFF -- トークン管理 --> Redis

    BFF -- Bearer Token --> Backend_Books
    BFF -- Bearer Token --> Backend_Music

    Backend_Books -- DB接続 --> DB_Books
    Backend_Music -- DB接続 --> DB_Music

    %% イメージ配布
    Registry_Multi --> VPS1 & VPS2
```

この構成により、以下のメリットが得られます：

- **認証基盤の統一**: 1つのBFFで複数アプリの認証を一元管理
- **リソースの効率化**: Redis や BFF を共有することでリソース消費を削減
- **アプリケーションの独立性**: 各アプリは専用のバックエンドとDBを持つため、データとロジックが分離
- **スケーラビリティ**: アプリケーション単位での個別のスケーリングが可能

## 3. VPS1: 認証・レジストリサーバー (`vsv-crystal.skygroup.local`)

インフラのコア機能、特に**認証認可**と**デプロイに必要なイメージ管理**を担います。

- **コンテナ構成**
  - **`nginx-edge`**: **エッジリバースプロキシ**。外部からのHTTPS/HTTPトラフィックを受け付ける**最前線の通信窓口**（ポート80/443を公開）。SSL終端とルーティングを担当し、以下のエンドポイントを提供：
    - `/auth` → Keycloak（OIDC認証）
    - `/v2` → Registry（Dockerイメージのpush/pull）
  - **`keycloak`**: **認証プロバイダー**。OpenID Connect (OIDC) プロトコルを提供。
  - **`keycloak_db`**: **Keycloak 専用のデータベース**。Keycloak が管理するユーザー情報、レルム設定、クライアント定義、セッション情報などを永続化するために利用されます。
  - **`registry`**: **Docker イメージレジストリ**。アプリケーションイメージの保管と配布。nginx-edge経由でのみアクセス可能（内部ポート5000）。

## 4. VPS2: Web アプリケーションサーバー (`vsv-emerald.skygroup.local`)

ユーザーに直接サービスを提供する、アプリケーションの実行環境です。

- **コンテナ構成**
  - **`nginx-edge`**: **エッジリバースプロキシ**。外部からのHTTPS/HTTPトラフィックを受け付ける**最前線の通信窓口**（ポート80/443を公開）。SSL終端とルーティングを担当し、リクエストを `frontend` やその他の内部サービスへ転送します。
  - **`frontend`**: **ユーザーインターフェース (UI)** を提供する内部サービス。クライアント側での**セッション管理**を担当。
  - **`bff` (Backend For Frontend)**:
    - **認証ゲートウェイ**。VPS1 Keycloak とのトークン交換を行い、**アクセストークンとリフレッシュトークンを管理**します。
    - Frontend からのリクエストを検証し、Backend へ転送する際の**Bearer トークン付与**を担当します。
  - **`backend`**: アプリケーションの**メインビジネスロジック**を実行する API サービス。BFF からの有効なアクセストークンでのみアクセスを許可します。
  - **`redis`**: **BFF**が利用する**キャッシュ/データストア**。**アクセストークンとリフレッシュトークン**の保存・管理に使用されます。
  - **`db`**: アプリケーションデータの**永続化**を行うデータベース。

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
    participant Backend as ⚙️ Backend (vsv-emerald)

    title データアクセスフロー (認証後)

    Browser->>Nginx_V2: 1. データ取得リクエスト (/api/data)
    Nginx_V2->>BFF: リクエストルーティング (セッションクッキー付き)
    activate BFF
    BFF->>Redis: 2. セッションIDからAccess Tokenを取得
    activate Redis
    Redis-->>BFF: Access Tokenを返却
    deactivate Redis

    BFF->>Backend: 3. APIアクセス (Authorization: Bearer <Token> 付与)
    activate Backend
    Backend-->>BFF: 4. データ応答
    deactivate Backend

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
    participant Backend as ⚙️ Backend (vsv-emerald)

    title トークンリフレッシュフロー (Access Token期限切れ時)

    Browser->>Nginx_V2: 1. データ取得リクエスト (/api/data)
    Nginx_V2->>BFF: リクエストルーティング (セッションクッキー付き)
    activate BFF
    BFF->>Redis: 2. セッションIDからAccess Tokenを取得
    activate Redis
    Redis-->>BFF: Access Token返却 (期限切れ)
    deactivate Redis

    Note over BFF: 3. BFFがトークン期限切れを検出<br/>Refresh Tokenを使用して自動更新

    BFF->>Nginx_V1: 4. トークンリフレッシュ要求<br/>(Refresh Token + Client Credentials)
    Nginx_V1->>Keycloak: トークンリフレッシュ要求
    activate Keycloak
    Note over Keycloak: Refresh Tokenを検証し、<br/>新しいAccess Token + Refresh Tokenを発行<br/>古いRefresh Tokenは即座に無効化
    Keycloak-->>Nginx_V1: 5. 新しいAccess Token + Refresh Token発行
    Nginx_V1-->>BFF: 新しいトークンセット返却
    deactivate Keycloak

    BFF->>Redis: 6. 新しいAccess Token + Refresh Tokenをセッションに保存
    activate Redis
    Redis-->>BFF: 保存完了
    deactivate Redis

    BFF->>Backend: 7. APIアクセス (Authorization: Bearer <新Token> 付与)
    activate Backend
    Backend-->>BFF: 8. データ応答
    deactivate Backend

    BFF->>Browser: 9. 応答データ返却
    deactivate BFF

    Note over Browser,BFF: ユーザーは中断なくサービスを利用可能<br/>(トークンリフレッシュは透過的に処理)
```

**重要なポイント:**

- **自動処理**: Spring Security OAuth2 Clientが`OAuth2AuthorizedClientRepository.loadAuthorizedClient()`実行時に自動的にトークン期限をチェックし、必要に応じてリフレッシュを実行
- **VPS間通信の最小化**: Access Tokenが有効な間はVPS1（Keycloak）への通信は発生せず、期限切れ時のみVPS間通信が発生
- **透過的な処理**: フロントエンドはトークンリフレッシュを意識する必要がなく、通常のAPIリクエストと同様に処理される
- **セキュリティ**: Refresh Tokenは常にBFFのRedis内に保持され、フロントエンドには一切公開されない
- **トークンローテーション**: Keycloak設定（`refreshTokenMaxReuse: 0`）により、リフレッシュ時に新しいRefresh Tokenが発行され、古いトークンは即座に無効化される。これによりトークン漏洩時のリスクを最小化し、Replay攻撃を防止
