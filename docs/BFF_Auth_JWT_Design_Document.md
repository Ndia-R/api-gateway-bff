---
# BFF向け 認可・JWT設計ドキュメント

## 1. 目的

本ドキュメントは、書籍アプリにおける

* ロール設計方針
* プラン設計方針
* JWT設計
* BFFの責務

を明確化し、BFF実装時の判断基準を統一することを目的とする。
---

# 2. 全体アーキテクチャ

```
[ Client ]
     ↓
[ Keycloak ]  ← 認証
     ↓
[ BFF ]       ← ビジネス文脈付与（plan追加）
     ↓
[ Resource Server(s) ]
```

認証は
Keycloak
が担当する。

BFFは **認証後のビジネス文脈を付与する層** である。

---

# 3. 設計原則

## 3.1 責務分離

| レイヤ          | 責務                            |
| --------------- | ------------------------------- |
| Keycloak        | 認証・ユーザー識別              |
| BFF             | ビジネス情報付与・内部JWT再発行 |
| Resource Server | 認可判定のみ                    |

---

## 3.2 ロール設計思想

ロールは「何者か」を表す。

### 現在のロール

- USER
- CONTENT_MANAGER
- MODERATOR
- ADMIN

### 重要方針

- リソース単位でロールを作らない
- 「本を読めるロール」などは作らない
- ロールは責務単位

---

# 4. プラン設計

## 4.1 プランはロールではない

プランは契約種別であり、責務ではない。

例：

- FREE
- PREMIUM
- 将来追加の可能性あり

---

## 4.2 プランで制御する内容

| FREE           | PREMIUM            |
| -------------- | ------------------ |
| 試し読み       | 全文閲覧           |
| レビュー閲覧   | レビュー投稿・編集 |
| お気に入り追加 | ブックマーク機能   |

---

## 4.3 なぜKeycloakにplanを持たせないのか

planは：

- 契約情報
- 課金状態に依存
- 変更頻度が高い

よって **アプリケーションDBで管理する**

---

# 5. JWT設計

## 5.1 外部JWT（Keycloak発行）

例：

```json
{
  "sub": "user-id",
  "realm_access": {
    "roles": ["USER"]
  }
}
```

planは含まれない。

---

## 5.2 内部JWT（BFF発行）

BFFは以下を行う：

1. Keycloak JWT検証
2. subを用いてアプリDBからplan取得
3. 内部JWTを再発行

例：

```json
{
  "sub": "user-id",
  "roles": ["USER"],
  "plan": "PREMIUM"
}
```

---

# 6. Resource Serverでの認可方針

## 6.1 ロール判定

責務に関する操作は roles で判断

例：

- 管理画面
- コンテンツ修正

---

## 6.2 プラン判定

利用範囲は plan で判断

例：

```
if (plan == PREMIUM) {
    allowFullBookAccess();
}
```

---

# 7. 将来拡張への備え

## 7.1 プラン増加

planは文字列またはenumで管理。

追加時は：

- DB更新
- BFF側マッピング追加

で対応可能。

---

## 7.2 書籍単位制御

書籍に以下を持たせる：

```
requiredPlan
または
requiredFeature
```

判定：

```
user.plan >= requiredPlan
```

---

## 7.3 JWT肥大化防止方針

JWTには以下のみ含める：

- sub
- roles
- plan

permissions や features は含めない。

---

# 8. セキュリティ方針

- 内部JWTはBFFが署名
- 有効期限は短め（5〜15分推奨）
- リフレッシュはBFF経由

---

# 9. 設計まとめ

### 採用モデル

- RBAC（責務制御）
- Planベース制御（利用範囲制御）
- JWT最小化設計
- BFFでビジネス文脈付与

---

# 10. 実装者への重要ポイント

1. Resource ServerはDBを参照しない
2. Resource ServerはKeycloakに依存しない
3. 認可判定はJWTのみで完結させる
4. planはロールではない

---
