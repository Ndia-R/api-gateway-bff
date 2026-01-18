# アプリケーション ロール設計

## 1. 設計方針: 「権限」と「役割」との分離

本設計では、ロールを **「権限 (Role)」**と **「役割 (Composite Roles)」** の 2 層構造で管理します。

| 要素                       | 用途                                 | 例                                       |
| -------------------------- | ------------------------------------ | ---------------------------------------- |
| **権限 (Role)**            | 「何ができるか」という最小単位の権限 | `book-content:read`, `review:delete:own` |
| **役割 (Composite Roles)** | 職務や立場に応じた権限のセット       | `ui:premium-user`, `ui:moderator`        |

---

## 2. 権限 (Role) の命名規則

権限 (Role) の命名規則は `{リソース}:{アクション}:{対象/スコープ}` の形式を基本とします。

1. リソース (Resource): 操作の対象となるもの（名詞・単数形）

   例: book, review, user, favorite

2. アクション (Action): 何ができるのか（動詞）

   例: read, create, update, delete, manage

3. 対象/スコープ (Scope): ※必要な場合のみ。誰のデータを対象にするか。

   - own または self: 自分自身のデータのみ
   - any または all: 全員のデータを対象（管理者用）

### アクション (Action)

「何ができるのか」を定義します。CRUD 操作をベースに、より直感的な単語を選びます。

| アクション      | 意味・用途                     | サービス層のメソッド例     |
| --------------- | ------------------------------ | -------------------------- |
| `read/view`     | データの取得・詳細表示         | get..., find..., search... |
| `create/add`    | 新規データの作成・追加         | create..., register...     |
| `update/edit`   | 既存データの変更               | update..., patch...        |
| `delete/remove` | データの削除                   | delete..., remove...       |
| `manage`        | 上記すべて（フルコントロール） | （管理用サービス全般）     |
| `exec/run`      | 計算やバッチ、送金などの実行   | execute..., process...     |

### スコープ (Scope)

「誰の・どの範囲のデータに」を定義します。

| スコープ     | 意味                           | 判定ロジックの基準            |
| ------------ | ------------------------------ | ----------------------------- |
| `own/self`   | 実行者本人のデータのみ         | data.userId === user.id       |
| `any/all`    | 他人のものを含むすべてのデータ | ロール保持のみで OK           |
| `group/dept` | 所属組織内のデータのみ         | data.groupId === user.groupId |
| `public`     | 公開されているデータのみ       | data.isPublic === true        |

---

## 3. 権限 (Role)

| 対象             | 権限 (Role)                 | 説明                                                 |
| ---------------- | --------------------------- | ---------------------------------------------------- |
| **書籍**         | `book:manage`               | すべての書籍を閲覧・作成・編集・削除できる権限       |
|                  | `book-content:read:preview` | 試し読みの有料コンテンツを閲覧できる権限             |
|                  | `book-content:read`         | すべての有料コンテンツを閲覧できる権限               |
| **レビュー**     | `review:delete:any`         | すべてのレビューを削除できる権限                     |
|                  | `review:manage:own`         | 自身のレビューを閲覧・作成・編集・削除できる権限     |
| **お気に入り**   | `favorite:manage:own`       | 自身のお気に入りを閲覧・作成・編集・削除できる権限   |
| **ブックマーク** | `bookmark:manage:own`       | 自身のブックマークを閲覧・作成・編集・削除できる権限 |
| **ジャンル**     | `genre:manage`              | すべてのジャンルを閲覧・作成・編集・削除できる権限   |
| **ユーザー**     | `user:read:own`             | 自身のプロフィールを閲覧できる権限                   |
|                  | `user:update:own`           | 自身のプロフィールを編集できる権限                   |
|                  | `user:manage`               | すべてのユーザーを閲覧・作成・編集・削除できる権限   |

※バックエンドは、**「権限 (Role)」** を元にアクセス管理を行う。

---

## 4. 役割 (Composite Roles)

アプリケーションで利用する主な役割として、以下の 5 つを定義します。この役割 (Composite Roles) は権限の組み合わせで作成する。

| 役割 (Role)             | 説明                          | 想定ユーザー                                                                                   |
| ----------------------- | ----------------------------- | ---------------------------------------------------------------------------------------------- |
| なし                    | **未ログインユーザー**        | 未ログインユーザー。書籍の検索、概要や目次の閲覧、書籍へのレビュー一覧などは見れる。           |
| **`ui:general-user`**   | **一般ユーザー** (デフォルト) | 無料登録したすべてのユーザー。書籍の閲覧や自身のお気に入り管理が可能。                         |
| **`ui:premium-user`**   | **プレミアムユーザー**        | 月額課金ユーザー。有料コンテンツの閲覧、レビュー、ブックマークなど追加機能へのアクセスが可能。 |
| **`ui:content-editor`** | **コンテンツ編集者**          | 書籍のメタデータやジャンルを管理するスタッフ。                                                 |
| **`ui:moderator`**      | **コミュニティ管理者**        | 不適切なレビューの削除など、コミュニティの健全性を維持するスタッフ。                           |
| **`ui:admin`**          | **システム管理者**            | すべての権限を持つシステム管理者。ユーザー管理やシステム設定も可能。                           |

※フロントエンドは、**「役割 (Composite Roles)」** を元にアクセス管理を行う。

---

## 5. 権限マトリックス

各役割 (Composite Roles) にどの権限 (Role) が含まれるかを以下に示します。

| 権限                        | `ui:general-user` | `ui:premium-user` | `ui:content-editor` | `ui:moderator` | `ui:admin` |
| --------------------------- | :---------------: | :---------------: | :-----------------: | :------------: | :--------: |
| `book:manage`               |                   |                   |         ✅          |                |     ✅     |
| `book-content:read:preview` |        ✅         |        ✅         |                     |                |     ✅     |
| `book-content:read`         |                   |        ✅         |                     |                |     ✅     |
| `review:delete:any`         |                   |                   |                     |       ✅       |     ✅     |
| `review:manage:own`         |                   |        ✅         |                     |                |     ✅     |
| `favorite:manage:own`       |        ✅         |        ✅         |                     |                |     ✅     |
| `bookmark:manage:own`       |                   |        ✅         |                     |                |     ✅     |
| `genre:manage`              |                   |                   |         ✅          |                |     ✅     |
| `user:read:own`             |        ✅         |        ✅         |                     |                |     ✅     |
| `user:update:own`           |        ✅         |        ✅         |                     |                |     ✅     |
| `user:manage`               |                   |                   |                     |                |     ✅     |

- **`ui:general-user`** は、新規登録時のデフォルトロールとして設定します。
- **※補足:** `Content Editors` や `Moderators` などのスタッフ系グループには、管理機能の役割に加えて `ui:general-user` ロールも付与されます。これにより、スタッフは自身の担当業務に加え、お気に入り管理などの一般ユーザー向け機能も利用できます。

---

## 6. 実装ガイドライン

### バックエンド (Spring Security)

- API のエンドポイント保護には、役割 (Composite Role) ではなく、**権限 (Role)** を直接指定することを推奨します。これにより、API が必要とする権限が明確になります。
- Spring Security の `hasAuthority()` や `@PreAuthorize` を利用します。

```java
// SecurityConfig.java
.requestMatchers(HttpMethod.POST, "/api/books").hasAuthority("book:manage")
.requestMatchers(HttpMethod.DELETE, "/api/bookmarks/**").hasAuthority("bookmark:manage:own")
.requestMatchers(HttpMethod.GET, "/api/book-content/**").hasAuthority("book-content:read")

// BookmarkController.java
@DeleteMapping("/bookmarks/{id}")
@PreAuthorize("hasAuthority('bookmark:manage:own')")
public void deleteBookmark(@PathVariable Long id) { ... }
```

### フロントエンド (React)

- UI 要素（ボタン、メニューなど）の表示/非表示の制御に **役割 (Composite Roles)** のみを使用します。
- `useAuth` のようなカスタムフック（全体で使うプロバイダー）で、ユーザーが持つ役割を簡単に判定できるようにします。

```typescript
// roles.ts
export const RoleType = {
  GeneralUser: "ui:general-user",
  PremiumUser: "ui:premium-user",
  ContentEditor: "ui:content-editor",
  Moderator: "ui:moderator",
  Admin: "ui:admin",
} as const;

export type RoleType = (typeof RoleType)[keyof typeof RoleType];

// protected-router.tsx
// ログインユーザーが指定したロールを持っているかどうか
const hasRole = (role: RoleType) => {
  return !!userProfile?.roles.includes(role);
};

// role-guard.tsx
export default function RoleGuard({ role, children }: Props) {
  const { hasRole } = useAuth();

  if (!hasRole(role)) return null;

  return <>{children}</>;
}

// Component.tsx
{
  <RoleGuard role={RoleType.PremiumUser}>
    <Button>有料コンテンツを読む</Button>
  </RoleGuard>;
}
```

**重要**: フロントエンドでの表示制御はあくまで UI/UX 向上のためです。**最終的なアクセス可否の判断は、必ずバックエンドの API で行う必要があります。**

---

## 7. Group 機能の活用 (Keycloak)

ここまでの設計はロールだけでも十分に機能しますが、ユーザー数が多くなったり、組織的な運用が始まったりした場合には、**Group 機能**を活用するとユーザー管理が格段に効率化します。

### Group とは？

Group は、ユーザーを「組織」や「チーム」といった集団で管理するための機能です。

- **Role** が「何ができるか（権限）」を定義するのに対し、
- **Group** は「誰がどこに所属しているか（所属）」を表現します。

Group に特定の役割（Composite Roles）を紐付けておくことで、ユーザーを Group に追加・移動するだけで、自動的にロールが付与・変更される仕組みを構築できます。

### my-books における Group 設計例

以下に`my-books`アプリケーションのための Group 構造案を示します。

```
/
├── Users (ユーザー)
│   ├── General Users (一般ユーザー)
│   └── Premium Users (プレミアムユーザー)
└── Staff (運営チーム)
    ├── Admins (システム管理者)
    ├── Content Editors (コンテンツ編集チーム)
    └── Moderators (コミュニティ管理チーム)
```

### Group と Role の紐付け

| Group                    | 紐付ける役割 (Composite Roles)         |
| ------------------------ | -------------------------------------- |
| `/Users/General Users`   | `ui:general-user`                      |
| `/Users/Premium Users`   | `ui:premium-user`                      |
| `/Staff/Admins`          | `ui:admin`                             |
| `/Staff/Content Editors` | `ui:content-editor`, `ui:general-user` |
| `/Staff/Moderators`      | `ui:moderator`, `ui:general-user`      |

### Group 活用のメリット: 役割変更の簡素化

例えば、あるスタッフが「コンテンツ編集者」から「コミュニティ管理者」に異動になった場合、管理者が行う作業は以下の通りです。

1.  対象ユーザーの所属を `/Staff/Content Editors` Group から外す。
2.  対象ユーザーを `/Staff/Moderators` Group に追加する。

これだけで、Keycloak は自動的に古い `ui:content-editor` ロールを剥奪し、新しい `ui:moderator` ロールを付与します。個別のロールを付け替える人的ミスを防ぎ、管理を大幅に簡素化できます。

ユーザーの役割変更や昇格・降格が頻繁に発生する可能性がある場合は、この Group 設計の導入を強く推奨します。

## 8. ロール設計の注意点

1. 「権限 (Role)」と「役割 (Composite Roles)」の使い分け

   バックエンドはサービス層にて、**「権限 (Role)」**を元にアクセス管理を行う。フロントエンドは、**「役割 (Composite Roles)」**を元にアクセス管理を行う。これにより運用で破綻しにくい設計となる。

2. 「manage」ロールを戦略的に使う

   細かく分けすぎると管理が破綻します。「スタッフなら作成・編集・削除すべてできて当然」というリソースについては、個別に分けず `book:manage` １つで運用し、必要になったタイミングで分割するのが現実的です。

3. 否定形 (not) のロールは作らない

   「○○ できない」というロールを作成すると、複数のロールが合算されたときに論理破綻（Allow と Denay の競合）が起きやすくなります。権限は常に **「できることの積み上げ（ホワイトリスト方式）」**で設計してください。

## 9. 公開 API (permitAll)

以下の機能は認証を必要とせず、すべてのユーザー（未ログインユーザー含む）が利用できます。
バックエンドでは、これらの API エンドポイントを `permitAll()` もしくは同等の設定にする必要があります。

| 機能分類     | エンドポイント（例）          | HTTP メソッド | 説明                                           |
| :----------- | :---------------------------- | :------------ | :--------------------------------------------- |
| **書籍**     | `/api/books`                  | `GET`         | 書籍の一覧を検索・取得する                     |
|              | `/api/books/{bookId}`         | `GET`         | 特定の書籍の詳細（概要、目次）を取得する       |
| **レビュー** | `/api/books/{bookId}/reviews` | `GET`         | 特定の書籍に投稿されたレビューの一覧を取得する |
| **ジャンル** | `/api/genres`                 | `GET`         | ジャンルの一覧を取得する                       |
