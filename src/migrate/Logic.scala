package migrate

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import scala.concurrent.{ExecutionContext, Future}
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

  def beginImport(): Unit =
    val chosen = repos.now().filter(r => selected.now().contains(r.id))
    if chosen.isEmpty then return
    jobs.set(chosen.map(r => r.id -> ImportJob(r, ImportPhase.Pending, None)).toMap)
    stage.set(Stage.Importing)
    val batches = chosen.grouped(10).toVector
    runBatches(batches, 0)

  private def runBatches(batches: Vector[Vector[GhRepo]], idx: Int): Unit =
    if idx >= batches.size then
      checkAllDone()
      return
    val batch = batches(idx)
    val futs = batch.map(triggerOne)
    Future.sequence(futs).foreach { _ =>
      dom.window.setTimeout(() => runBatches(batches, idx + 1), 1500)
    }

  private def triggerOne(repo: GhRepo): Future[Unit] =
    updateJob(repo.id, _.copy(phase = ImportPhase.Started))
    Api
      .glImport(
        glToken.now(),
        ghToken.now(),
        repo.id,
        glNamespace.now().trim,
        repo.name
      )
      .map { resp =>
        val path = resp.obj.get("full_path").map(_.str)
        updateJob(repo.id, j => j.copy(gitlabPath = path))
        ()
      }
      .recover { case e: Throwable =>
        updateJob(repo.id, _.copy(phase = ImportPhase.Failed(humanize(e))))
        ()
      }

  private def updateJob(id: Long, f: ImportJob => ImportJob): Unit =
    jobs.update(m => m.get(id).fold(m)(j => m.updated(id, f(j))))

  private def checkAllDone(): Unit =
    val all = jobs.now().values
    val done = all.forall {
      case ImportJob(_, ImportPhase.Finished, _) => true
      case ImportJob(_, ImportPhase.Failed(_), _) => true
      case _ => false
    }
    if done && all.nonEmpty then stage.set(Stage.Done)

  private def humanize(t: Throwable): String =
    t match
      case e: Api.ApiError => e.getMessage
      case e: js.JavaScriptException => Option(e.getMessage).getOrElse("network error")
      case e => Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
