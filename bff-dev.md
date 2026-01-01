# 🔑 BFF 開発環境ガイド (api-gateway-bff)

本ガイドは、認証ゲートウェイとトークン管理を担う BFF サービス（`api-gateway-bff`）をローカルで開発するための環境設定と利用方法を説明します。BFF はトークン管理のために Redis に依存します。

## 🚀 1. 環境構成の概要

本環境は、以下の 2 つのコンテナで構成されます。

| サービス名     | 役割                            | Dockerfile ステージ | 公開ポート                              | 依存関係 |
| :------------- | :------------------------------ | :------------------ | :-------------------------------------- | :------- |
| **`api-gateway-bff`** | 認証ゲートウェイ（Spring Boot） | `development`       | ホスト 8888 $\rightarrow$ コンテナ 8080 | `redis`  |
| **`redis`**    | セッショントークンストア        | -                   | なし（内部ネットワークのみ）            | -        |

> 💡 **ポイント**: ローカル環境では、Frontend からのアクセスを容易にするため、BFF はホストの **8888 ポート**で公開されます。

---

## 🛠️ 2. 開発環境の特長と依存関係

### 2-1. Redis との連携

- **自動起動と依存性**: `docker-compose.yml` により、`api-gateway-bff` の起動前に **`redis`** コンテナが完全に準備完了（`service_healthy`）になるのを待機します。
- **トークン管理**: BFF は OIDC 認可コードフローで Keycloak から取得した **Access Token** および **Refresh Token** を、クライアントに露出させることなく、この **`redis`** に保存し、セッションと紐づけて管理します。

### 2-2. 高速な開発とツールチェーン

- **ソースコード即時反映**: ホスト側のソースコードと Gradle キャッシュを Volume マウント (`gradle-cache`) しているため、Backend と同様に**高速な再ビルド**と開発サイクルを実現します。
- **多機能なイメージ**: `Dockerfile` の `development` ステージは **JDK 21** ベースで、**Git**、**Node.js**、**Python (uv)**、**Gemini CLI** などのツールを含み、統合的な開発環境を提供します。

---

## 🔑 3. 認証・ルーティング設定

BFF は、Keycloak (VPS1) との連携および、Backend サービスへのルーティングを担うため、以下の環境変数が必須です。

| 環境変数                   | 役割                                   | 設定値（例）                                                |
| :------------------------- | :------------------------------------- | :---------------------------------------------------------- |
| **`IDP_CLIENT_ID`**        | Keycloak で登録したクライアント ID     | `api-gateway-bff-client`                                    |
| **`IDP_CLIENT_SECRET`**    | Keycloak のクライアントシークレット    | `xxxxx-xxxxx-secret`                                        |
| **`IDP_ISSUER_URI`**       | **Keycloak の認証サーバー URL**        | ローカル Keycloak の URL を設定してください。               |
| **`DEFAULT_FRONTEND_URL`** | デフォルトフロントエンドURL（フォールバック用） | `https://localhost:5173`                                    |
| **`MY_BOOKS_SERVICE_URL`** | **Backend サービスへのルーティング先** | `http://my-books-api:8080` (ローカル開発環境でのコンテナ名) |

> 💡 **OIDC フロー**: 開発用の Keycloak との HTTPS 接続を可能にするため、`Dockerfile` の `development` ステージには、**`mkcert` CA 証明書**が一時的に追加されています。

---

## 🏗️ 4. 本番デプロイ用イメージの作成

開発完了後、`Dockerfile` のマルチステージビルドを利用して、VPS2 にデプロイするためのイメージを作成します。

- **ビルド・実行ステージ**: Backend と同じく、`production-builder` でビルドされた JAR ファイルが、軽量な **Alpine JRE** ベースの `production` イメージにコピーされ、非 Root ユーザー (`appuser`) で実行されます。
- **ヘルスチェック**: `production` ステージには、コンテナの健全性を確認するための標準的な **`HEALTHCHECK`** が定義されています。

**ビルドコマンド例:**

```bash
# production ステージをターゲットに指定し、本番環境用のイメージをビルド
docker image build --target production -t <レジストリURL>/api-gateway-bff:<タグ> .
```

```bash
# 例
docker image build --target production -t vsv-crystal.skygroup.local/api-gateway-bff:latest .
```
