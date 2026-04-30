package migrate

import com.raquo.laminar.api.L.*
import org.scalajs.dom

object Ui:
  import State.*

  def app: HtmlElement =
    div(
      cls := "app-shell",
      topbar,
      child <-- stage.signal.map(stageView),
      footer,
      child <-- unlockNeeded.signal.map(b => if b then unlockModal else emptyNode),
      child <-- saveDialogOpen.signal.map(b => if b then saveModal else emptyNode)
    )

  private def footer: HtmlElement =
    div(
      cls := "footer",
      "made with ",
      span(cls := "heart", "❤"),
      " by ",
      a(
        href := "https://github.com/abh80",
        target := "_blank",
        rel := "noopener",
        "abh80"
      )
    )

  // ---------- topbar ----------

  private def topbar: HtmlElement =
    div(
      cls := "topbar",
      div(cls := "brand", "Migrate to Gitlab"),
      div(
        cls := "topbar-right",
        saveTokenToggle,
        child <-- ghUser.signal.map {
          case Some(u) => div(cls := "tiny", s"gh: $u")
          case None => emptyNode
        }
      )
    )

  private def saveTokenToggle: HtmlElement =
    div(
      cls := "toggle",
      role := "switch",
      tabIndex := 0,
      title := "Encrypt and store tokens in this browser. Asks for a password.",
      aria.checked <-- saveTokens.signal.map(_.toString),
      onClick --> { _ => Logic.onSaveToggleChanged(!saveTokens.now()) },
      onKeyDown.filter(e => e.key == " " || e.key == "Enter") --> { e =>
        e.preventDefault()
        Logic.onSaveToggleChanged(!saveTokens.now())
      },
      span(
        cls := "toggle-track",
        cls("on") <-- saveTokens.signal,
        span(cls := "toggle-thumb")
      ),
      span(cls := "toggle-label", "Save tokens")
    )

  private def unlockModal: HtmlElement =
    div(
      cls := "modal-backdrop",
      div(
        cls := "modal",
        h2(cls := "h2", "Unlock saved tokens"),
        p(cls := "muted", "Encrypted tokens are stored in this browser. Enter your password to decrypt, or start fresh to delete them."),
        div(
          cls := "field",
          marginTop := "16px",
          label("Password"),
          input(
            tpe := "password",
            autoComplete := "current-password",
            spellCheck := false,
            controlled(
              value <-- unlockPassword,
              onInput.mapToValue --> unlockPassword
            ),
            onKeyDown.filter(_.key == "Enter") --> (_ => Logic.tryUnlock())
          )
        ),
        child <-- unlockError.signal.map {
          case Some(e) => div(cls := "error", e)
          case None => emptyNode
        },
        div(
          cls := "modal-actions",
          button(
            cls := "btn ghost",
            "Start fresh",
            onClick --> (_ => Logic.startFresh())
          ),
          button(
            cls := "btn",
            disabled <-- unlockBusy.signal,
            child <-- unlockBusy.signal.map(b =>
              if b then span(div(cls := "spinner"), "Unlocking…")
              else span("Unlock")
            ),
            onClick --> (_ => Logic.tryUnlock())
          )
        )
      )
    )

  private def saveModal: HtmlElement =
    div(
      cls := "modal-backdrop",
      div(
        cls := "modal",
        h2(cls := "h2", "Encrypt and save tokens"),
        p(cls := "muted", "Choose a password. Tokens are encrypted with AES-GCM and a PBKDF2-derived key (200k iterations). The password is never stored."),
        div(
          cls := "field",
          marginTop := "16px",
          label("Password (min 6 chars)"),
          input(
            tpe := "password",
            autoComplete := "new-password",
            spellCheck := false,
            controlled(
              value <-- savePassword,
              onInput.mapToValue --> savePassword
            )
          )
        ),
        div(
          cls := "field",
          label("Confirm password"),
          input(
            tpe := "password",
            autoComplete := "new-password",
            spellCheck := false,
            controlled(
              value <-- savePasswordConfirm,
              onInput.mapToValue --> savePasswordConfirm
            ),
            onKeyDown.filter(_.key == "Enter") --> (_ => Logic.confirmSave())
          )
        ),
        child <-- saveError.signal.map {
          case Some(e) => div(cls := "error", e)
          case None => emptyNode
        },
        div(
          cls := "modal-actions",
          button(
            cls := "btn ghost",
            "Skip",
            disabled <-- saveBusy.signal,
            onClick --> (_ => Logic.cancelSave())
          ),
          button(
            cls := "btn",
            disabled <-- saveBusy.signal,
            child <-- saveBusy.signal.map(b =>
              if b then span(div(cls := "spinner"), "Encrypting…")
              else span("Save and continue")
            ),
            onClick --> (_ => Logic.confirmSave())
          )
        )
      )
    )

  private val stepDefs: Vector[(String, Set[Stage])] = Vector(
    "GitHub" -> Set[Stage](Stage.LinkGithub),
    "GitLab" -> Set[Stage](Stage.LinkGitlab),
    "Repos" -> Set[Stage](Stage.LoadingRepos, Stage.Selecting),
    "Import" -> Set[Stage](Stage.Importing, Stage.Done)
  )

  // ---------- routing ----------

  private def stageView(s: Stage): HtmlElement = s match
    case Stage.LinkGithub => linkGithubPage
    case Stage.LinkGitlab => linkGitlabPage
    case Stage.LoadingRepos => loadingPage
    case Stage.Selecting => selectorPage
    case Stage.Importing => importingPage
    case Stage.Done => donePage

  // ---------- step indicator ----------

  private def stepIndicator(s: Stage): HtmlElement =
    val currentIdx = stepDefs.indexWhere(_._2.contains(s))
    div(
      cls := "step-block",
      div(
        cls := "step-labels",
        stepDefs.zipWithIndex.map { case ((label, _), i) =>
          span(
            cls := "step-label",
            cls("active") := i == currentIdx,
            cls("done") := i < currentIdx,
            label
          )
        }
      ),
      div(
        cls := "steps",
        stepDefs.zipWithIndex.map { case (_, i) =>
          div(
            cls := "step",
            cls("active") := i == currentIdx,
            cls("done") := i < currentIdx,
            div(cls := "fill")
          )
        }
      )
    )

  // ---------- link pages ----------

  private def linkGithubPage: HtmlElement =
    div(
      cls := "page stage-enter",
      div(
        cls := "page-narrow",
        stepIndicator(Stage.LinkGithub),
        div(
          cls := "card",
          h1(cls := "h1", "Connect GitHub"),
          p(
            cls := "muted",
            "Paste a GitHub Personal Access Token. ",
            a(
              href := "https://github.com/settings/tokens/new?scopes=repo,read:org&description=migrate-to-gitlab",
              target := "_blank",
              rel := "noopener",
              "Create one →"
            )
          ),
          div(cls := "tiny", "Required scopes: ", strong("repo"), ", ", strong("read:org"), "."),
          div(
            cls := "field",
            marginTop := "20px",
            label("GitHub token"),
            input(
              tpe := "password",
              placeholder := "ghp_…",
              autoComplete := "off",
              spellCheck := false,
              controlled(
                value <-- ghToken,
                onInput.mapToValue --> ghToken
              ),
              onKeyDown.filter(_.key == "Enter") --> (_ => Logic.verifyGithub())
            )
          ),
          div(
            display := "flex",
            justifyContent := "flex-end",
            gap := "8px",
            button(
              cls := "btn",
              disabled <-- ghVerifying.signal,
              child <-- ghVerifying.signal.map(b =>
                if b then span(div(cls := "spinner"), "Verifying…")
                else span("Continue")
              ),
              onClick --> (_ => Logic.verifyGithub())
            )
          ),
          child <-- ghError.signal.map {
            case Some(e) => div(cls := "error", e)
            case None => emptyNode
          }
        ),
        div(
          cls := "disclaimer",
          span(cls := "shield", "🔒"),
          span(
            "This app runs entirely in your browser. Tokens are never sent to any server; only directly to GitHub and GitLab APIs. Anything saved stays in this browser's localStorage, encrypted with your password. Feel free to check the network tab."
          )
        )
      )
    )

  private def linkGitlabPage: HtmlElement =
    div(
      cls := "page stage-enter",
      div(
        cls := "page-narrow",
        stepIndicator(Stage.LinkGitlab),
        div(
          cls := "card",
          h1(cls := "h1", "Connect GitLab"),
          p(
            cls := "muted",
            "Paste a GitLab Personal Access Token. ",
            a(
              href := "https://gitlab.com/-/user_settings/personal_access_tokens?name=migrate-to-gitlab&scopes=api",
              target := "_blank",
              rel := "noopener",
              "Create one →"
            )
          ),
          div(cls := "tiny", "Required scope: ", strong("api"), "."),
          div(
            cls := "field",
            marginTop := "20px",
            label("GitLab token"),
            input(
              tpe := "password",
              placeholder := "glpat-…",
              autoComplete := "off",
              spellCheck := false,
              controlled(
                value <-- glToken,
                onInput.mapToValue --> glToken
              )
            )
          ),
          div(
            cls := "field",
            label("Target namespace (user or group)"),
            input(
              tpe := "text",
              placeholder := "your-gitlab-username",
              controlled(
                value <-- glNamespace,
                onInput.mapToValue --> glNamespace
              ),
              onKeyDown.filter(_.key == "Enter") --> (_ => Logic.verifyGitlab())
            )
          ),
          div(
            display := "flex",
            justifyContent := "space-between",
            gap := "8px",
            button(
              cls := "btn ghost",
              "Back",
              onClick --> (_ => stage.set(Stage.LinkGithub))
            ),
            button(
              cls := "btn",
              disabled <-- glVerifying.signal,
              child <-- glVerifying.signal.map(b =>
                if b then span(div(cls := "spinner"), "Verifying…")
                else span("Continue")
              ),
              onClick --> (_ => Logic.verifyGitlab())
            )
          ),
          child <-- glError.signal.map {
            case Some(e) => div(cls := "error", e)
            case None => emptyNode
          }
        )
      )
    )

  // ---------- loading ----------

  private def loadingPage: HtmlElement =
    div(
      cls := "page stage-enter",
      div(
        cls := "page-narrow",
        stepIndicator(Stage.LoadingRepos),
        div(
          cls := "card",
          h1(cls := "h1", "Loading repositories"),
          p(
            cls := "muted",
            child.text <-- reposLoadProgress.signal.combineWith(repos.signal).map {
              case (p, rs) => s"Page $p loaded • ${rs.size} repos so far (cap ${Api.MaxRepos})"
            }
          ),
          div(
            display := "flex",
            alignItems := "center",
            gap := "10px",
            marginTop := "16px",
            div(cls := "spinner"),
            span(cls := "muted", "Fetching from GitHub…")
          ),
          child <-- reposLoadError.signal.map {
            case Some(e) => div(cls := "error", e)
            case None => emptyNode
          }
        )
      )
    )

  // ---------- selector ----------

  private def selectorPage: HtmlElement =
    val filtered = repos.signal.combineWith(filter.signal).map(Logic.filteredRepos.tupled)
    val visible = filtered.combineWith(pageIndex.signal).map { (f, p) =>
      val total = f.size
      val maxPage = if total == 0 then 0 else (total - 1) / pageSize
      val safe = math.min(p, maxPage)
      (Logic.pageSlice(f, safe), safe, total, maxPage)
    }

    div(
      cls := "page stage-enter",
      div(
        cls := "page-wide",
        stepIndicator(Stage.Selecting),
        div(
          display := "flex",
          alignItems := "baseline",
          justifyContent := "space-between",
          marginBottom := "12px",
          h1(cls := "h1", "Select repositories"),
          child <-- reposCapped.signal.map {
            case true =>
              span(cls := "pill err", div(cls := "dot"), s"capped at ${Api.MaxRepos}")
            case false => emptyNode
          }
        ),
        toolbar(visible),
        repoList(visible),
        pager(visible)
      )
    )

  private def toolbar(
      visible: Signal[(Vector[GhRepo], Int, Int, Int)]
  ): HtmlElement =
    div(
      cls := "toolbar",
      div(
        cls := "cbox",
        cls("checked") <-- visible.combineWith(selected.signal).map {
          case (vis, _, _, _, s) =>
            vis.nonEmpty && vis.forall(r => s.contains(r.id))
        },
        cls("indet") <-- visible.combineWith(selected.signal).map {
          case (vis, _, _, _, s) =>
            val anySel = vis.exists(r => s.contains(r.id))
            val allSel = vis.nonEmpty && vis.forall(r => s.contains(r.id))
            anySel && !allSel
        },
        onClick --> { _ =>
          visible.observe(unsafeWindowOwner).now() match
            case (vis, _, _, _) => Logic.toggleAllOnPage(vis)
        }
      ),
      input(
        tpe := "search",
        placeholder := "Filter by name or description…",
        controlled(
          value <-- filter,
          onInput.mapToValue --> { v =>
            filter.set(v); pageIndex.set(0); lastClickedIndex.set(None)
          }
        )
      ),
      div(cls := "grow"),
      child <-- collisionsChecked.signal.combineWith(collisionsTotal.signal, existing.signal).map {
        case (done, total, ex) =>
          if total > 0 && done < total then
            span(
              cls := "pill run",
              div(cls := "spinner"),
              s"checking gitlab… $done/$total"
            )
          else if ex.nonEmpty then
            span(cls := "pill err", div(cls := "dot"), s"${ex.size} already on gitlab")
          else emptyNode
      },
      div(
        cls := "tiny tabular",
        child.text <-- selected.signal.combineWith(repos.signal).map { (s, rs) =>
          val total = rs.size
          val sel = s.size
          s"$sel selected • $total total"
        }
      ),
      button(
        cls := "btn ghost sm",
        "Select all (filtered)",
        onClick --> { _ =>
          val f = repos.now()
          val q = filter.now()
          val ids = Logic.filteredRepos(f, q).map(_.id).toSet
          selected.update(_ ++ ids)
        }
      ),
      button(
        cls := "btn ghost sm",
        "Clear",
        disabled <-- selected.signal.map(_.isEmpty),
        onClick --> (_ => selected.set(Set.empty))
      ),
      button(
        cls := "btn",
        disabled <-- selected.signal.map(_.isEmpty),
        child.text <-- selected.signal.map(s => s"Import ${s.size}"),
        onClick --> (_ => Logic.beginImport())
      )
    )

  private def repoList(
      visible: Signal[(Vector[GhRepo], Int, Int, Int)]
  ): HtmlElement =
    div(
      cls := "repo-list",
      children <-- visible.map { case (vis, _, _, _) =>
        vis.zipWithIndex.map { (r, i) => repoRow(r, i, vis) }
      }
    )

  private def repoRow(repo: GhRepo, idx: Int, visible: Vector[GhRepo]): HtmlElement =
    val isExisting = existing.signal.map(_.contains(repo.id))
    div(
      cls := "repo-row",
      cls("selected") <-- selected.signal.map(_.contains(repo.id)),
      cls("collision") <-- isExisting,
      onClick --> { ev =>
        if existing.now().contains(repo.id) then ()
        else
          if ev.shiftKey then
            lastClickedIndex.now() match
              case Some(prev) =>
                val mark = !selected.now().contains(repo.id)
                Logic.shiftRange(visible, prev, idx, mark)
              case None =>
                Logic.toggle(repo)
          else
            Logic.toggle(repo)
          lastClickedIndex.set(Some(idx))
      },
      div(
        cls := "cbox",
        cls("checked") <-- selected.signal.map(_.contains(repo.id))
      ),
      div(
        div(
          cls := "name",
          repo.fullName,
          if repo.isPrivate then span(cls := "pill", marginLeft := "8px", "private")
          else emptyNode,
          if repo.archived then span(cls := "pill", marginLeft := "6px", "archived")
          else emptyNode,
          child <-- isExisting.combineWith(glNamespace.signal).map { (yes, ns) =>
            if yes then
              span(
                cls := "tip",
                marginLeft := "6px",
                span(cls := "pill err", div(cls := "dot"), "exists in gitlab"),
                span(
                  cls := "tip-content",
                  s"Already at gitlab.com/$ns/${repo.name}. Delete it on GitLab first to enable import."
                )
              )
            else emptyNode
          }
        ),
        repo.description match
          case Some(d) if d.nonEmpty => div(cls := "desc", d)
          case _ => emptyNode
      ),
      div(
        cls := "meta tiny tabular",
        if repo.stars > 0 then span("★ ", repo.stars.toString) else emptyNode
      ),
      div(cls := "tiny tabular", repo.updatedAt.take(10))
    )

  private def pager(
      visible: Signal[(Vector[GhRepo], Int, Int, Int)]
  ): HtmlElement =
    div(
      cls := "pager",
      div(
        cls := "tiny tabular",
        child.text <-- visible.map { case (vis, page, total, maxPage) =>
          if total == 0 then "0 results"
          else
            val from = page * pageSize + 1
            val to = page * pageSize + vis.size
            s"$from–$to of $total • page ${page + 1}/${maxPage + 1}"
        }
      ),
      div(
        cls := "ctrls",
        button(
          "‹ Prev",
          disabled <-- pageIndex.signal.map(_ <= 0),
          onClick --> (_ => pageIndex.update(p => math.max(0, p - 1)))
        ),
        button(
          "Next ›",
          disabled <-- visible.map { case (_, page, _, maxPage) => page >= maxPage },
          onClick --> { _ =>
            visible.observe(unsafeWindowOwner).now() match
              case (_, _, _, maxPage) =>
                pageIndex.update(p => math.min(maxPage, p + 1))
          }
        )
      )
    )

  // ---------- importing ----------

  private def importingPage: HtmlElement =
    div(
      cls := "page stage-enter",
      div(
        cls := "page-wide",
        stepIndicator(Stage.Importing),
        h1(cls := "h1", "Importing"),
        p(
          cls := "muted",
          child.text <-- jobs.signal.map { js =>
            val done = js.values.count {
              case ImportJob(_, ImportPhase.Finished, _) => true
              case _ => false
            }
            val failed = js.values.count {
              case ImportJob(_, ImportPhase.Failed(_), _) => true
              case _ => false
            }
            s"${js.size} total • $done finished • $failed failed"
          }
        ),
        div(
          cls := "progress-list",
          marginTop := "16px",
          children <-- jobs.signal.map { js =>
            js.values.toVector
              .sortBy(_.repo.fullName)
              .map(progressRow)
          }
        )
      )
    )

  private def progressRow(j: ImportJob): HtmlElement =
    div(
      cls := "progress-row",
      div(
        div(cls := "name", j.repo.fullName),
        j.gitlabPath match
          case Some(p) =>
            div(cls := "tiny", a(href := s"https://gitlab.com/$p", target := "_blank", s"gitlab.com/$p"))
          case None => emptyNode
      ),
      phaseBadge(j.phase),
      span(cls := "tiny tabular", j.repo.updatedAt.take(10))
    )

  private def phaseBadge(p: ImportPhase): HtmlElement = p match
    case ImportPhase.Pending => span(cls := "pill", div(cls := "dot"), "queued")
    case ImportPhase.Started => span(cls := "pill run", div(cls := "spinner"), "importing")
    case ImportPhase.Finished => span(cls := "pill ok", div(cls := "dot"), "finished")
    case ImportPhase.Failed(reason) =>
      span(cls := "pill err", title := reason, div(cls := "dot"), "failed")

  // ---------- done ----------

  private def donePage: HtmlElement =
    div(
      cls := "page stage-enter",
      div(
        cls := "page-narrow",
        stepIndicator(Stage.Done),
        div(
          cls := "card",
          h1(cls := "h1", "Done"),
          p(
            cls := "muted",
            child.text <-- jobs.signal.map { js =>
              val ok = js.values.count {
                case ImportJob(_, ImportPhase.Finished, _) => true; case _ => false
              }
              val bad = js.values.count {
                case ImportJob(_, ImportPhase.Failed(_), _) => true; case _ => false
              }
              s"$ok succeeded, $bad failed."
            }
          ),
          div(
            display := "flex",
            gap := "8px",
            marginTop := "16px",
            button(
              cls := "btn ghost",
              "Migrate more",
              onClick --> { _ =>
                selected.set(Set.empty)
                jobs.set(Map.empty)
                stage.set(Stage.Selecting)
              }
            ),
            button(
              cls := "btn",
              "Open GitLab",
              onClick --> (_ => dom.window.open(s"https://gitlab.com/${glNamespace.now()}", "_blank"))
            )
          )
        )
      )
    )
