package migrate

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.util.{Failure, Success}

object Logic:
  given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue
  import State.*

  // ---------- Init / persistence ----------

  def init(): Unit =
    val storage = dom.window.localStorage
    Option(storage.getItem(storageKey)) match
      case Some(_) =>
        saveTokens.set(true)
        unlockNeeded.set(true)
      case None => ()

  def tryUnlock(): Unit =
    val pw = unlockPassword.now()
    if pw.isEmpty then
      unlockError.set(Some("Password required."))
      return
    val storage = dom.window.localStorage
    Option(storage.getItem(storageKey)) match
      case None =>
        unlockNeeded.set(false)
      case Some(blob) =>
        unlockBusy.set(true); unlockError.set(None)
        Crypto.decrypt(pw, blob).onComplete {
          case Success(json) =>
            unlockBusy.set(false)
            try
              val parsed = js.JSON.parse(json)
              ghToken.set(parsed.gh.asInstanceOf[String])
              glToken.set(parsed.gl.asInstanceOf[String])
              glNamespace.set(parsed.ns.asInstanceOf[String])
              unlockPassword.set("")
              unlockNeeded.set(false)
              chainVerify()
            catch
              case _: Throwable =>
                unlockError.set(Some("Stored data unreadable. Use Start fresh."))
          case Failure(_) =>
            unlockBusy.set(false)
            unlockError.set(Some("Wrong password."))
        }

  def startFresh(): Unit =
    dom.window.localStorage.removeItem(storageKey)
    saveTokens.set(false)
    unlockPassword.set("")
    unlockError.set(None)
    unlockBusy.set(false)
    unlockNeeded.set(false)

  private def chainVerify(): Unit =
    ghVerifying.set(true)
    Api.ghVerify(ghToken.now()).onComplete {
      case Success(login) =>
        ghVerifying.set(false)
        ghUser.set(Some(login))
        glVerifying.set(true)
        Api.glVerify(glToken.now()).onComplete {
          case Success(glLogin) =>
            glVerifying.set(false)
            glUser.set(Some(glLogin))
            if glNamespace.now().trim.isEmpty then glNamespace.set(glLogin)
            beginRepoLoad()
          case Failure(e) =>
            glVerifying.set(false)
            glError.set(Some(humanize(e)))
            stage.set(Stage.LinkGitlab)
        }
      case Failure(e) =>
        ghVerifying.set(false)
        ghError.set(Some(humanize(e)))
        stage.set(Stage.LinkGithub)
    }

  def openSaveDialog(): Unit =
    savePassword.set("")
    savePasswordConfirm.set("")
    saveError.set(None)
    saveDialogOpen.set(true)

  def cancelSave(): Unit =
    saveDialogOpen.set(false)
    beginRepoLoad()

  def confirmSave(): Unit =
    val p = savePassword.now()
    val c = savePasswordConfirm.now()
    if p.length < 6 then
      saveError.set(Some("Password must be at least 6 characters."))
      return
    if p != c then
      saveError.set(Some("Passwords do not match."))
      return
    saveBusy.set(true); saveError.set(None)
    val payload = js.JSON.stringify(
      js.Dynamic.literal(
        gh = ghToken.now(),
        gl = glToken.now(),
        ns = glNamespace.now()
      )
    )
    Crypto.encrypt(p, payload).onComplete {
      case Success(blob) =>
        dom.window.localStorage.setItem(storageKey, blob)
        saveBusy.set(false)
        saveDialogOpen.set(false)
        savePassword.set("")
        savePasswordConfirm.set("")
        beginRepoLoad()
      case Failure(e) =>
        saveBusy.set(false)
        saveError.set(Some(humanize(e)))
    }

  def onSaveToggleChanged(enabled: Boolean): Unit =
    saveTokens.set(enabled)
    if !enabled then dom.window.localStorage.removeItem(storageKey)

  // ---------- Verification ----------

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
        if saveTokens.now() then openSaveDialog()
        else beginRepoLoad()
      case Failure(e) =>
        glVerifying.set(false)
        glError.set(Some(humanize(e)))
    }

  // ---------- Repo loading ----------

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
          checkCollisions()
        else if batch.size < Api.PerPage then
          stage.set(Stage.Selecting)
          checkCollisions()
        else
          loadNext(page + 1)
      case Failure(e) =>
        reposLoadError.set(Some(humanize(e)))
    }

  // ---------- Filter / paging ----------

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

  // ---------- Collision detection ----------

  def checkCollisions(): Unit =
    val ns = glNamespace.now().trim
    val all = repos.now()
    if ns.isEmpty || all.isEmpty then return
    existing.set(Set.empty)
    collisionsChecked.set(0)
    collisionsTotal.set(all.size)
    val concurrency = 6
    val queue = scala.collection.mutable.Queue.from(all)
    def worker(): Unit =
      if queue.isEmpty then return
      val r = queue.dequeue()
      Api
        .glProjectExists(glToken.now(), s"$ns/${r.name}")
        .recover { case _ => false }
        .foreach { exists =>
          if exists then existing.update(_ + r.id)
          collisionsChecked.update(_ + 1)
          worker()
        }
    (1 to math.min(concurrency, all.size)).foreach(_ => worker())

  // ---------- Selection ----------

  def toggle(repo: GhRepo): Unit =
    if existing.now().contains(repo.id) then return
    val s = selected.now()
    selected.set(if s.contains(repo.id) then s - repo.id else s + repo.id)

  def shiftRange(visible: Vector[GhRepo], from: Int, to: Int, mark: Boolean): Unit =
    val (lo, hi) = (math.min(from, to), math.max(from, to))
    val blocked = existing.now()
    val ids = visible.slice(lo, hi + 1).map(_.id).toSet -- blocked
    val s = selected.now()
    selected.set(if mark then s ++ ids else s -- ids)

  def toggleAllOnPage(visible: Vector[GhRepo]): Unit =
    val blocked = existing.now()
    val selectable = visible.filterNot(r => blocked.contains(r.id))
    val ids = selectable.map(_.id).toSet
    val s = selected.now()
    val allSelected = ids.nonEmpty && ids.subsetOf(s)
    selected.set(if allSelected then s -- ids else s ++ ids)

  // ---------- Import driver ----------

  def beginImport(): Unit =
    val chosen = repos.now().filter(r => selected.now().contains(r.id))
    if chosen.isEmpty then return
    jobs.set(chosen.map(r => r.id -> ImportJob(r, ImportPhase.Pending, None)).toMap)
    stage.set(Stage.Importing)
    // queue in batches of 10
    val batches = chosen.grouped(10).toVector
    runBatches(batches, 0)

  private def runBatches(batches: Vector[Vector[GhRepo]], idx: Int): Unit =
    if idx >= batches.size then
      checkAllDone()
      return
    val batch = batches(idx)
    val futs = batch.map(triggerOne)
    Future.sequence(futs).foreach { _ =>
      // small delay between batches via setTimeout
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
        val pid = resp.obj.get("id").map(_.num.toLong)
        val path = resp.obj.get("full_path").map(_.str)
        updateJob(repo.id, j => j.copy(gitlabPath = path))
        pid.foreach(pollProject(repo.id, _))
      }
      .recover { case e: Throwable =>
        updateJob(repo.id, _.copy(phase = ImportPhase.Failed(humanize(e))))
        ()
      }

  private def pollProject(repoId: Long, projectId: Long, attempt: Int = 0): Unit =
    val delay = math.min(60000, 5000 * math.pow(1.4, attempt.toDouble).toInt)
    dom.window.setTimeout(
      () =>
        Api.glProjectStatus(glToken.now(), projectId).onComplete {
          case Success(("finished", _)) =>
            updateJob(repoId, _.copy(phase = ImportPhase.Finished))
            checkAllDone()
          case Success(("failed", err)) =>
            updateJob(
              repoId,
              _.copy(phase = ImportPhase.Failed(err.getOrElse("import failed")))
            )
            checkAllDone()
          case Success((_, _)) =>
            pollProject(repoId, projectId, attempt + 1)
          case Failure(e) =>
            // transient — retry a few times
            if attempt < 8 then pollProject(repoId, projectId, attempt + 1)
            else
              updateJob(repoId, _.copy(phase = ImportPhase.Failed(humanize(e))))
              checkAllDone()
        },
      delay.toDouble
    )

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

  // ---------- Helpers ----------

  private def humanize(t: Throwable): String =
    t match
      case e: Api.ApiError => e.getMessage
      case e: js.JavaScriptException => Option(e.getMessage).getOrElse("network error")
      case e => Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
