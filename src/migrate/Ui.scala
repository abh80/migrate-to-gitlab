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
      footer
    )

  private def topbar: HtmlElement =
    div(
      cls := "topbar",
      div(cls := "brand", "migrate-to-gitlab")
    )

  private val stepDefs: Vector[(String, Set[Stage])] = Vector(
    "GitHub" -> Set[Stage](Stage.LinkGithub),
    "GitLab" -> Set[Stage](Stage.LinkGitlab),
    "Repos" -> Set[Stage](Stage.LoadingRepos, Stage.Selecting),
    "Import" -> Set[Stage](Stage.Importing, Stage.Done)
  )

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

  private def stageView(s: Stage): HtmlElement = s match
    case Stage.LinkGithub => linkGithubPage
    case Stage.LinkGitlab => linkGitlabPage
    case Stage.LoadingRepos => loadingPage
    case Stage.Selecting => selectorPage
    case other =>
      div(cls := "page", div(cls := "page-narrow", stepIndicator(other), p(cls := "muted", other.toString)))

  private def linkGithubPage: HtmlElement =
    div(
      cls := "page",
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
        )
      )
    )

  private def linkGitlabPage: HtmlElement =
    div(
      cls := "page",
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

  private def loadingPage: HtmlElement =
    div(
      cls := "page",
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

  private def selectorPage: HtmlElement =
    val filtered = repos.signal.combineWith(filter.signal).map(Logic.filteredRepos.tupled)
    val visible = filtered.combineWith(pageIndex.signal).map { (f, p) =>
      val total = f.size
      val maxPage = if total == 0 then 0 else (total - 1) / pageSize
      val safe = math.min(p, maxPage)
      (Logic.pageSlice(f, safe), safe, total, maxPage)
    }

    div(
      cls := "page",
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
            case true => span(cls := "pill err", div(cls := "dot"), s"capped at ${Api.MaxRepos}")
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
          case (vis, _, _, _, s) => vis.nonEmpty && vis.forall(r => s.contains(r.id))
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
      div(
        cls := "tiny tabular",
        child.text <-- selected.signal.combineWith(repos.signal).map { (s, rs) =>
          s"${s.size} selected • ${rs.size} total"
        }
      ),
      button(
        cls := "btn ghost sm",
        "Select all (filtered)",
        onClick --> { _ =>
          val q = filter.now()
          val ids = Logic.filteredRepos(repos.now(), q).map(_.id).toSet
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
    div(
      cls := "repo-row",
      cls("selected") <-- selected.signal.map(_.contains(repo.id)),
      onClick --> { ev =>
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
          else emptyNode
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

  private def footer: HtmlElement =
    div(
      cls := "footer",
      "made with ",
      span(cls := "heart", "❤"),
      " by ",
      a(href := "https://github.com/abh80", target := "_blank", rel := "noopener", "abh80")
    )
