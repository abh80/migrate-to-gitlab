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

  private def stageView(s: Stage): HtmlElement =
    div(
      cls := "page",
      div(cls := "page-narrow", stepIndicator(s), p(cls := "muted", s.toString))
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
