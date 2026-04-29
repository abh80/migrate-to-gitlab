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

  private def footer: HtmlElement =
    div(
      cls := "footer",
      "made with ",
      span(cls := "heart", "❤"),
      " by ",
      a(href := "https://github.com/abh80", target := "_blank", rel := "noopener", "abh80")
    )
