# トークンリフレッシュ動作検証ガイド

## 📋 概要

このドキュメントでは、BFF（Backend for Frontend）のトークンリフレッシュ機能が正常に動作していることを検証するための手順を説明します。

## 🎯 検証目的

以下の動作が正しく実装されていることを確認します：

1. **アクセストークンの自動リフレッシュ**: OAuth2AuthorizedClientManagerによる透過的なトークン更新
2. **トークンローテーション**: リフレッシュ時に古いRefresh Tokenが無効化されること
3. **BFFセッション期限切れ時の挙動**: 再認証フローの確認
4. **Keycloakセッション期限切れ時の挙動**: 完全な再ログインの確認

## ⏱️ タイムアウト設定の関係

### 本番環境の設定

| コンポーネント | タイムアウト | 用途 |
|--------------|------------|------|
| アクセストークン | 15分 | API呼び出し用の短命トークン |
| BFFセッション | 30分 | フロントエンドのセッション管理 |
| Keycloakセッション（アイドル） | 60分 | IdPでのSSO状態維持 |
| Keycloakセッション（最大） | 10時間 | IdPでの最大セッション期間 |

### 検証環境の設定（推奨）

| コンポーネント | 本番環境 | 検証環境 | 理由 |
|--------------|---------|---------|------|
| アクセストークン | 15分 | **1分** | 自動リフレッシュを短時間で確認 |
| BFFセッション | 30分 | **2分** | セッション期限切れの挙動を確認 |
| Keycloakセッション（アイドル） | 60分 | **3分** | Keycloak再認証フローを確認 |
| Keycloakセッション（最大） | 10時間 | **10分** | 最大期間制限の確認 |

## 🛠️ 検証用設定の変更

### 1. Keycloak設定の変更

**ファイル**: Keycloakレルム設定（JSON Export/Import または Admin UI）

```json
{
  "realm": "sample-realm",
  "accessTokenLifespan": 60,          // 15分 → 1分（検証用）
  "ssoSessionIdleTimeout": 180,       // 60分 → 3分（検証用）
  "ssoSessionMaxLifespan": 600,       // 10時間 → 10分（検証用）
  "revokeRefreshToken": true,         // トークンローテーション有効
  "refreshTokenMaxReuse": 0           // Replay攻撃対策
}
```

**Admin UIでの変更手順**:
1. Keycloak Admin Console にログイン
2. `sample-realm` を選択
3. **Realm Settings** → **Tokens** タブ
4. 以下の値を変更：
   - Access Token Lifespan: `1 Minutes`
   - SSO Session Idle: `3 Minutes`
   - SSO Session Max: `10 Minutes`
5. **Save** をクリック

### 2. BFF設定の変更

**ファイル**: `src/main/resources/application.yml`

```yaml
spring:
  session:
    timeout: 2m  # 30分 → 2分（検証用）

# ログレベルを詳細化（検証用）
logging:
  level:
    root: INFO
    org.springframework.security: DEBUG              # Spring Securityの詳細ログ
    org.springframework.security.oauth2: DEBUG       # OAuth2処理の詳細ログ
    org.springframework.session: DEBUG               # セッション管理ログ
    com.example.api_gateway_bff: DEBUG               # BFFアプリケーションログ
```

### 3. BFFの再起動

```bash
# Docker Composeの場合
docker compose restart api-gateway-bff

# Gradleの場合
./gradlew bootRun
```

## 📋 検証シナリオ

### シナリオ1: アクセストークン期限切れ → 自動リフレッシュ

#### 目的
OAuth2AuthorizedClientManagerによる透過的なトークンリフレッシュ機能を確認

#### 前提条件
- アクセストークン有効期限: **1分**
- BFFセッション有効期限: **2分**
- Keycloakセッション有効期限: **3分**

#### 手順

1. **ログイン**
   ```
   ブラウザ → https://localhost/bff/auth/login
   ```
   - Keycloakログイン画面でID/PWを入力
   - 認証成功後、フロントエンドにリダイレクト

2. **即座にAPIリクエスト**
   ```
   ブラウザ → https://localhost/api/my-books/list
   ```
   - ✅ 期待結果: 正常にデータ取得

3. **1分30秒待機**
   - アクセストークンが期限切れになるまで待機

4. **再度APIリクエスト**
   ```
   ブラウザ → https://localhost/api/my-books/list
   ```
   - ✅ 期待結果: 自動リフレッシュ後、正常にデータ取得

#### タイムライン

```
00:00 ログイン成功
      → Access Token #1 発行（有効期限: 00:01）
      → Refresh Token #1 発行
      → BFFセッション作成（有効期限: 02:00）

00:05 API呼び出し
      → ✅ Access Token #1 有効
      → API呼び出し成功

01:30 API呼び出し
      → ⚠️ Access Token #1 期限切れ検出
      → 自動リフレッシュ開始
      → Refresh Token #1 を使用してトークン交換
      → ✅ Access Token #2 発行（有効期限: 02:30）
      → ✅ Refresh Token #2 発行（#1は無効化）
      → ✅ API呼び出し成功（ユーザーは中断なし）
```

#### 確認ポイント

**BFFログ**:
```log
DEBUG o.s.s.oauth2.client.RefreshTokenOAuth2AuthorizedClientProvider :
  Refreshed access token for client 'idp'

DEBUG c.e.a.controller.ApiProxyController :
  Using access token for API request
```

**Redis**:
```bash
# セッションに新しいトークンが保存されていることを確認
redis-cli
> KEYS spring:session:sessions:*
> HGETALL spring:session:sessions:<session-id>
# OAuth2AuthorizedClient の accessToken フィールドに新しいトークン
```

**ユーザー体験**:
- ✅ 再ログイン不要
- ✅ API呼び出しが透過的に成功
- ✅ エラーメッセージなし

---

### シナリオ2: BFFセッション期限切れ → 再認証

#### 目的
BFFセッションが期限切れになった際、Keycloakセッションが有効な場合はスムーズに再認証できることを確認

#### 前提条件
- アクセストークン有効期限: **1分**
- BFFセッション有効期限: **2分**
- Keycloakセッション有効期限: **3分**

#### 手順

1. **ログイン**
   ```
   ブラウザ → https://localhost/bff/auth/login
   ```

2. **2分30秒待機**
   - BFFセッションが期限切れになるまで待機

3. **APIリクエスト**
   ```
   ブラウザ → https://localhost/api/my-books/list
   ```
   - ❌ 期待結果: 401 Unauthorized

4. **再認証**
   ```
   ブラウザ → https://localhost/bff/auth/login
   ```
   - ✅ 期待結果: パスワード入力なしで即座にリダイレクト（Keycloakセッション有効）

#### タイムライン

```
00:00 ログイン成功
      → BFFセッション作成（有効期限: 02:00）
      → Keycloakセッション作成（有効期限: 03:00）

02:30 API呼び出し
      → ❌ BFFセッション期限切れ
      → Redisにセッションが存在しない
      → 401 Unauthorized

02:31 /bff/auth/login にアクセス
      → Keycloak認証フロー開始
      → ✅ Keycloakセッションが有効
      → パスワード入力画面をスキップ
      → ✅ 即座に新しいBFFセッション作成
      → フロントエンドにリダイレクト
```

#### 確認ポイント

**BFFログ**:
```log
DEBUG o.s.session.data.redis.RedisIndexedSessionRepository :
  Session not found: <session-id>

INFO c.e.a.config.SecurityConfig :
  === OAuth2 Authentication Success ===
  Using default frontend URL: https://localhost
```

**Redis**:
```bash
# 古いセッションが削除され、新しいセッションが作成される
redis-cli
> KEYS spring:session:sessions:*
# 新しいセッションIDが表示される
```

**ユーザー体験**:
- ❌ 一時的に401エラー（フロントエンドがキャッチして再認証にリダイレクト）
- ✅ パスワード再入力は不要
- ✅ スムーズに再認証完了

---

### シナリオ3: Keycloakセッション期限切れ → 完全再ログイン

#### 目的
Keycloakセッションも期限切れになった場合、完全な再ログインが必要になることを確認

#### 前提条件
- アクセストークン有効期限: **1分**
- BFFセッション有効期限: **2分**
- Keycloakセッション有効期限: **3分**

#### 手順

1. **ログイン**
   ```
   ブラウザ → https://localhost/bff/auth/login
   ```

2. **3分30秒待機**
   - Keycloakセッションが期限切れになるまで待機

3. **再認証**
   ```
   ブラウザ → https://localhost/bff/auth/login
   ```
   - ❌ 期待結果: Keycloakログイン画面が表示される

4. **ID/パスワード入力**
   - ✅ 期待結果: 認証成功後、フロントエンドにリダイレクト

#### タイムライン

```
00:00 ログイン成功
      → BFFセッション作成（有効期限: 02:00）
      → Keycloakセッション作成（有効期限: 03:00）

03:30 /bff/auth/login にアクセス
      → ⚠️ BFFセッション期限切れ
      → ⚠️ Keycloakセッション期限切れ
      → ❌ Keycloakログイン画面表示
      → ユーザーがID/PWを入力
      → ✅ 認証成功
      → 新しいBFFセッション作成
      → 新しいKeycloakセッション作成
```

#### 確認ポイント

**ブラウザ**:
- Keycloakのログイン画面が表示される
- ID/パスワードの入力フィールドが表示される

**BFFログ**:
```log
INFO c.e.a.config.SecurityConfig :
  === OAuth2 Authentication Success ===
```

**ユーザー体験**:
- ❌ ID/パスワードの再入力が必要
- ✅ 認証後は正常にサービス利用可能

---

### シナリオ4: 複数回のトークンリフレッシュ → トークンローテーション確認

#### 目的
`refreshTokenMaxReuse: 0` 設定により、リフレッシュ時に古いRefresh Tokenが無効化されることを確認

#### 前提条件
- アクセストークン有効期限: **30秒**（さらに短縮）
- BFFセッション有効期限: **3分**
- Keycloakセッション有効期限: **5分**

#### 手順

1. **ログイン**
   ```
   ブラウザ → https://localhost/bff/auth/login
   ```

2. **30秒ごとにAPIリクエスト × 5回**
   ```
   00:00 ログイン
   00:30 API呼び出し（リフレッシュ発生）
   01:00 API呼び出し（リフレッシュ発生）
   01:30 API呼び出し（リフレッシュ発生）
   02:00 API呼び出し（リフレッシュ発生）
   02:30 API呼び出し（リフレッシュ発生）
   ```

#### タイムライン

```
00:00 ログイン成功
      → Access Token #1 発行
      → Refresh Token #1 発行

00:30 API呼び出し
      → Access Token #1 期限切れ
      → Refresh Token #1 で自動リフレッシュ
      → ✅ Access Token #2 発行
      → ✅ Refresh Token #2 発行
      → ❌ Refresh Token #1 無効化（Keycloak）

01:00 API呼び出し
      → Access Token #2 期限切れ
      → Refresh Token #2 で自動リフレッシュ
      → ✅ Access Token #3 発行
      → ✅ Refresh Token #3 発行
      → ❌ Refresh Token #2 無効化（Keycloak）

（以下同様に続く）
```

#### 確認ポイント

**BFFログ**（5回のリフレッシュが記録される）:
```log
DEBUG o.s.s.oauth2.client.RefreshTokenOAuth2AuthorizedClientProvider :
  Refreshed access token for client 'idp'
DEBUG o.s.s.oauth2.client.RefreshTokenOAuth2AuthorizedClientProvider :
  Refreshed access token for client 'idp'
DEBUG o.s.s.oauth2.client.RefreshTokenOAuth2AuthorizedClientProvider :
  Refreshed access token for client 'idp'
...
```

**Keycloakログ**（古いRefresh Tokenが無効化される）:
```log
WARN  [org.keycloak.events] (executor-thread-X)
  type=REFRESH_TOKEN_ERROR, realmId=sample-realm,
  error=invalid_token, reason=Token reuse detected
```

**Redis**（トークンが更新される）:
```bash
# セッション内のトークンが定期的に更新されることを確認
redis-cli
> WATCH spring:session:sessions:<session-id>
# 30秒ごとにトークンフィールドが変化
```

**ユーザー体験**:
- ✅ すべてのAPI呼び出しが成功
- ✅ 再ログイン不要
- ✅ エラーなし

---

## 🔍 検証時のログ確認ポイント

### BFFログで確認すべき内容

#### トークンリフレッシュ成功
```log
DEBUG o.s.s.oauth2.client.RefreshTokenOAuth2AuthorizedClientProvider :
  Refreshed access token for client 'idp'
```

#### セッション期限切れ
```log
DEBUG o.s.session.data.redis.RedisIndexedSessionRepository :
  Session not found: <session-id>
```

#### 認証成功
```log
INFO c.e.a.config.SecurityConfig :
  === OAuth2 Authentication Success ===
  Using saved frontend URL: https://localhost
```

#### API呼び出し
```log
DEBUG c.e.a.controller.ApiProxyController :
  Routing request: path=/my-books/list, service=my-books, targetPath=/list
```

### Redisで確認すべき内容

```bash
# Redisコンテナに接続
docker exec -it <redis-container-name> redis-cli

# セッション一覧
KEYS spring:session:sessions:*

# 特定セッションの内容確認
HGETALL spring:session:sessions:<session-id>

# セッションの有効期限確認（秒）
TTL spring:session:sessions:<session-id>

# セッション削除（手動テスト用）
DEL spring:session:sessions:<session-id>
```

### Keycloak Admin Consoleで確認すべき内容

1. **セッション確認**
   - Realm Settings → Sessions → Active Sessions
   - ユーザーのセッション数を確認

2. **イベントログ確認**
   - Realm Settings → Events → Login Events
   - `LOGIN`, `REFRESH_TOKEN`, `LOGOUT` イベントを確認

3. **トークン再利用エラー確認**（シナリオ4）
   - Realm Settings → Events → Login Events
   - `REFRESH_TOKEN_ERROR` イベントを確認
   - Error: `Token reuse detected`

## 📊 検証結果の記録テンプレート

### シナリオ1: アクセストークン自動リフレッシュ

| 項目 | 期待結果 | 実際の結果 | 備考 |
|------|---------|-----------|------|
| 初回API呼び出し（00:05） | ✅ 成功 | | |
| 2回目API呼び出し（01:30） | ✅ 成功（自動リフレッシュ） | | |
| BFFログにリフレッシュログ | ✅ 出力あり | | |
| Redisに新トークン保存 | ✅ 保存あり | | |

### シナリオ2: BFFセッション期限切れ

| 項目 | 期待結果 | 実際の結果 | 備考 |
|------|---------|-----------|------|
| API呼び出し（02:30） | ❌ 401 Unauthorized | | |
| 再認証時のログイン画面 | ✅ スキップ（Keycloakセッション有効） | | |
| 新BFFセッション作成 | ✅ 作成あり | | |

### シナリオ3: Keycloakセッション期限切れ

| 項目 | 期待結果 | 実際の結果 | 備考 |
|------|---------|-----------|------|
| 再認証時のログイン画面 | ❌ 表示あり | | |
| ID/PW入力 | ✅ 必要 | | |
| 認証後のリダイレクト | ✅ 成功 | | |

### シナリオ4: トークンローテーション

| 項目 | 期待結果 | 実際の結果 | 備考 |
|------|---------|-----------|------|
| リフレッシュ回数 | ✅ 5回 | | |
| すべてのAPI呼び出し成功 | ✅ 成功 | | |
| Keycloakログにトークン再利用エラー | ✅ あり（古いトークン使用時） | | |

## ⚠️ 注意事項

### 検証環境での作業時

1. **必ず検証専用環境で実施**
   - 本番環境では絶対に短い時間設定を使用しない
   - 開発環境またはローカル環境で実施

2. **検証後は設定を元に戻す**
   - Keycloak設定を本番用の値に戻す
   - `application.yml` を元に戻す
   - BFFを再起動する

3. **ログレベルの戻し忘れに注意**
   - DEBUG レベルのログは大量に出力される
   - 検証後は INFO または WARN に戻す

### 検証を中断する場合

```bash
# BFF再起動
docker compose restart api-gateway-bff

# Redisセッションクリア
docker exec -it <redis-container-name> redis-cli FLUSHDB

# Keycloak設定をエクスポート（バックアップ）
# Admin Console → Realm Settings → Partial Export
```

## 🎯 検証完了後のチェックリスト

- [ ] すべてのシナリオで期待通りの動作を確認
- [ ] Keycloak設定を本番用の値に戻した
- [ ] `application.yml` の `session.timeout` を `30m` に戻した
- [ ] ログレベルを `INFO` または `WARN` に戻した
- [ ] BFFを再起動した
- [ ] 検証結果を記録した
- [ ] 問題が見つかった場合は Issue を作成した

## 📚 関連ドキュメント

- [system-architecture-overview-vps2.md](../system-architecture-overview-vps2.md) - システム全体のアーキテクチャ
- [CLAUDE.md](../CLAUDE.md) - BFFプロジェクトの概要
- [SecurityConfig.java](../src/main/java/com/example/api_gateway_bff/config/SecurityConfig.java) - OAuth2設定の実装
- [ApiProxyController.java](../src/main/java/com/example/api_gateway_bff/controller/ApiProxyController.java) - トークン使用箇所

## 🔗 参考資料

- [Spring Security OAuth2 Client - Refresh Token](https://docs.spring.io/spring-security/reference/servlet/oauth2/client/authorization-grants.html#oauth2Client-refresh-token-grant)
- [Keycloak Token Settings](https://www.keycloak.org/docs/latest/server_admin/#_timeouts)
- [Spring Session with Redis](https://docs.spring.io/spring-session/reference/guides/boot-redis.html)
