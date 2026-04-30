# Migrate to Gitlab (from GitHub pages? yes!)
🚀 See https://abh80.is-a.dev/migrate-to-gitlab/

GitHub used to be the place. For a lot of us it still is: first commit, first job, the platform we grew up on. But it's been getting flakier, and the trust hasn't really survived it. Outages that used to be rare hit most weeks now. Merges get reverted with nobody saying anything. RCEs have shipped. And there's no CEO to call about it, which means in practice nobody really owns the failures.

Theo did a long version of this in [GitHub Is Dying by theo](https://www.youtube.com/@t3dotgg), and Mitchell Hashimoto's note on [Ghostty leaving GitHub](https://mitchellh.com/) is worth a read too. Or skip both and just open your repo and count what's broken today.

Anyway, this is for the people who are done with that and want to move some or all of their repos over to GitLab without writing the script themselves.

***Whats even better to migrate to a competitor from Github Pages? Gitlab awaits your first contribution!***

I present you this browser native tool (completely?...why would I lie to you?) to migrate to Gitlab in just a few clicks.

> AI has been used to make refactors such as my skill for scala code optimizer: https://github.com/abh80/skills and UI generation tasks, which was bad; so I fixed some of it.

## Why bother

Moving a 15-year codebase off the place you've been hosting it on is already a pain. Add "now go write a script that pages through the GitHub API and POSTs to GitLab and polls each import" and most people just don't. GitLab already does the most of hardwork, I just built the cherry on top.....Sweet.

If GitHub eventually fixes its problems, gets some accountability, stops the silent reverts and so on, then I will personally make a reverse tool of this. Until then good luck to the bros at Microsoft.

---
> For the nerds, you don't wanna read from now on. Everything from this point onwards is what we call as Slop. AI SLOP.

## What it does

GitLab ships an excellent [Import from GitHub API](https://docs.gitlab.com/api/import/#import-repository-from-github). You hand GitLab your GitHub PAT and a repo id, and **GitLab's servers do the pull themselves**: full git history, branches, tags, issues, PRs (as MRs), milestones, wikis, releases. You don't have to clone anything locally, which means no disk space and no proxy on your end.

This app is a small browser frontend on top of that API:

1. Paste a GitHub PAT, it gets verified
2. Paste a GitLab PAT and target namespace, verified
3. Up to **4500** of your GitHub repos get loaded (100 per page)
4. Each repo is checked against `gitlab.com/<namespace>/<name>` so collisions are flagged before you try
5. Pick the ones you want. Click, shift-click for ranges, page select-all, filter, "select all (filtered)"
6. Click Import. GitLab queues them in batches, and the UI polls each one and reports finished or failed

## Pure client

No server. Nothing leaves your browser except direct calls to `api.github.com` and `gitlab.com/api/v4`. There's no backend; nobody is collecting your tokens on a server somewhere. The whole thing is one Scala.js bundle plus a static `index.html` that you can serve from `python -m http.server` if you want to.

If you turn on **Save tokens** in the top bar, the GitHub PAT, GitLab PAT and namespace are encrypted with **AES-GCM** using a key derived from a password you choose (PBKDF2, SHA-256, 200k iterations, random per-blob salt and IV) and stored in this browser's `localStorage` under the key `user-key`. The password itself is never stored. On reload you're prompted to unlock, or to start fresh, which deletes the blob. Toggle off at any time and the blob is removed immediately.

## Tech

- **Scala 3.5 / Scala.js**, pure client app, no server runtime
- **Laminar 17**, reactive UI with fine-grained reactivity via `Var`/`Signal`
- **upickle** for JSON
- **WebCrypto SubtleCrypto** for the encrypt-tokens flow
- Direct `fetch` to GitHub REST and GitLab v4 (both expose CORS for the endpoints used)

The frontend is small enough to read in one sitting: `Models`, `State`, `Api`, `Logic`, `Ui`, `Crypto`, `Main`. Adding a feature usually means editing two files.

## Build

Requires [scala-cli](https://scala-cli.virtuslab.org/install).

```powershell
# one-shot bundle
scala-cli --power package . --js -o public/main.js -f

# watch mode
scala-cli --power package . --js -o public/main.js -f --watch
```

Output is a single self-contained JS file at `public/main.js`. No bundler, no module loader.

## Run

The browser has to serve over HTTP, since GitHub's API rejects `file://` origins.

```powershell
npx serve public
# or
python -m http.server -d public 8080
```

Open `http://localhost:8080`.

## PAT scopes

- **GitHub**: `repo` (full, required so GitLab can pull private repos), `read:org` (to list org-owned repos)
- **GitLab**: `api` (full)

## What gets migrated (by GitLab, free)

Full git history, all branches and tags, issues + labels, PRs → MRs, milestones, wiki pages, release notes.

## Known limits and gotchas

- **Cap**: 4500 repos. If you have more, do it again (run it twice, who cares?) Github had some sort of ratelimit like 5k requestions every hour.
- **`repo_id`** sent to GitLab has to be the numeric GitHub repo id, which the app handles for you.
- **Target namespace** has to already exist on GitLab. Your username always does; for groups, create them on GitLab first.
- **Same-name collisions** get caught up front. The row is tagged "exists in gitlab" with a hover tooltip and disabled. Delete the existing GitLab project first if you want to re-import.
- GitLab queues imports server-side. The UI fires them in batches of 10 with a 1.5s gap so it doesn't swamp the queue.
- Status polling uses exponential backoff starting at 5s, capped at 60s.

## Layout

```
project.scala            scala-cli config + deps
public/index.html        shell
public/styles.css        light theme, Inter, 4px grid, accent #817598
public/main.js           emitted by scala-cli (gitignored)
src/migrate/Main.scala   entrypoint
src/migrate/Models.scala domain types
src/migrate/State.scala  Laminar Vars
src/migrate/Api.scala    GitHub + GitLab REST clients
src/migrate/Logic.scala  verify, paginated load, collision check, import driver
src/migrate/Crypto.scala WebCrypto wrapper for save-tokens flow
src/migrate/Ui.scala     Laminar views
```



---
made with ❤ by [abh80](https://github.com/abh80)
