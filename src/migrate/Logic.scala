package migrate

import com.raquo.laminar.api.L.*
import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.util.{Failure, Success}

object Logic:
  given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue
  import State.*

  def verifyGithub(): Unit =
    val t = ghToken.now().trim
    if t.isEmpty then
      ghError.set(Some("Token required."))
      return
    ghVerifying.set(true); ghError.set(None)
    Api.ghVerify(t).onComplete {
      case Success(login) =>
        ghVerifying.set(false)
        ghUser.set(Some(login))
        stage.set(Stage.LinkGitlab)
      case Failure(e) =>
        ghVerifying.set(false)
        ghError.set(Some(humanize(e)))
    }

  def verifyGitlab(): Unit =
    val t = glToken.now().trim
    if t.isEmpty then
      glError.set(Some("Token required."))
      return
    glVerifying.set(true); glError.set(None)
    Api.glVerify(t).onComplete {
      case Success(login) =>
        glVerifying.set(false)
        glUser.set(Some(login))
        if glNamespace.now().trim.isEmpty then glNamespace.set(login)
        stage.set(Stage.LoadingRepos)
      case Failure(e) =>
        glVerifying.set(false)
        glError.set(Some(humanize(e)))
    }

  private def humanize(t: Throwable): String =
    t match
      case e: Api.ApiError => e.getMessage
      case e: js.JavaScriptException => Option(e.getMessage).getOrElse("network error")
      case e => Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
