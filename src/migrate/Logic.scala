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
        beginRepoLoad()
      case Failure(e) =>
        glVerifying.set(false)
        glError.set(Some(humanize(e)))
    }

  def beginRepoLoad(): Unit =
    repos.set(Vector.empty)
    reposLoadProgress.set(0)
    reposLoadError.set(None)
    reposCapped.set(false)
    stage.set(Stage.LoadingRepos)
    loadNext(1)

  private def loadNext(page: Int): Unit =
    Api.ghReposPage(ghToken.now(), page).onComplete {
      case Success(batch) =>
        val merged = repos.now() ++ batch
        val capped = merged.size >= Api.MaxRepos
        val truncated = if capped then merged.take(Api.MaxRepos) else merged
        repos.set(truncated)
        reposLoadProgress.set(page)
        if capped then
          reposCapped.set(true)
          stage.set(Stage.Selecting)
        else if batch.size < Api.PerPage then
          stage.set(Stage.Selecting)
        else
          loadNext(page + 1)
      case Failure(e) =>
        reposLoadError.set(Some(humanize(e)))
    }

  def filteredRepos(all: Vector[GhRepo], q: String): Vector[GhRepo] =
    if q.trim.isEmpty then all
    else
      val needle = q.trim.toLowerCase
      all.filter(r =>
        r.name.toLowerCase.contains(needle)
          || r.fullName.toLowerCase.contains(needle)
          || r.description.exists(_.toLowerCase.contains(needle))
      )

  def pageSlice(filtered: Vector[GhRepo], pageIdx: Int): Vector[GhRepo] =
    val start = pageIdx * State.pageSize
    filtered.slice(start, start + State.pageSize)

  def toggle(repo: GhRepo): Unit =
    val s = selected.now()
    selected.set(if s.contains(repo.id) then s - repo.id else s + repo.id)

  def shiftRange(visible: Vector[GhRepo], from: Int, to: Int, mark: Boolean): Unit =
    val (lo, hi) = (math.min(from, to), math.max(from, to))
    val ids = visible.slice(lo, hi + 1).map(_.id).toSet
    val s = selected.now()
    selected.set(if mark then s ++ ids else s -- ids)

  def toggleAllOnPage(visible: Vector[GhRepo]): Unit =
    val ids = visible.map(_.id).toSet
    val s = selected.now()
    val allSelected = ids.nonEmpty && ids.subsetOf(s)
    selected.set(if allSelected then s -- ids else s ++ ids)

  def beginImport(): Unit = stage.set(Stage.Importing)

  private def humanize(t: Throwable): String =
    t match
      case e: Api.ApiError => e.getMessage
      case e: js.JavaScriptException => Option(e.getMessage).getOrElse("network error")
      case e => Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
