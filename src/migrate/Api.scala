package migrate

import org.scalajs.dom
import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.Thenable.Implicits.given

object Api:
  val MaxRepos = 4500
  val PerPage = 100
  val GhBase = "https://api.github.com"
  val GlBase = "https://gitlab.com/api/v4"

  given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue

  final class ApiError(val status: Int, msg: String) extends RuntimeException(msg)

  private def req(
      url: String,
      headers: Map[String, String],
      method: String = "GET",
      body: Option[String] = None
  ): Future[(ujson.Value, dom.Headers)] =
    val init = new dom.RequestInit {}
    init.method = method.asInstanceOf[dom.HttpMethod]
    val h = new dom.Headers()
    headers.foreach((k, v) => h.append(k, v))
    body.foreach { _ => h.append("Content-Type", "application/json") }
    init.headers = h
    body.foreach(b => init.body = b)
    dom.fetch(url, init).toFuture.flatMap { resp =>
      resp.text().toFuture.map { txt =>
        if !resp.ok then
          val msg =
            try ujson.read(txt).obj.get("message").map(_.str).getOrElse(txt)
            catch case _: Throwable => txt
          throw ApiError(resp.status, s"${resp.status}: $msg")
        val parsed = if txt.isEmpty then ujson.Null else ujson.read(txt)
        (parsed, resp.headers)
      }
    }

  // ---------- GitHub ----------

  def ghHeaders(token: String): Map[String, String] = Map(
    "Authorization" -> s"Bearer $token",
    "Accept" -> "application/vnd.github+json",
    "X-GitHub-Api-Version" -> "2022-11-28"
  )

  def ghVerify(token: String): Future[String] =
    req(s"$GhBase/user", ghHeaders(token)).map { case (j, _) => j("login").str }

  def ghReposPage(token: String, page: Int): Future[Vector[GhRepo]] =
    val url =
      s"$GhBase/user/repos?per_page=$PerPage&page=$page&sort=updated&affiliation=owner,collaborator,organization_member"
    req(url, ghHeaders(token)).map { case (j, _) =>
      j.arr.toVector.map(parseRepo)
    }

  private def parseRepo(r: ujson.Value): GhRepo =
    GhRepo(
      id = r("id").num.toLong,
      name = r("name").str,
      fullName = r("full_name").str,
      isPrivate = r("private").bool,
      description = r.obj
        .get("description")
        .flatMap(d => if d.isNull then None else Some(d.str)),
      stars = r.obj.get("stargazers_count").map(_.num.toInt).getOrElse(0),
      archived = r.obj.get("archived").exists(_.bool),
      updatedAt = r.obj.get("updated_at").map(_.str).getOrElse("")
    )

  // ---------- GitLab ----------

  def glHeaders(token: String): Map[String, String] =
    Map("PRIVATE-TOKEN" -> token, "Accept" -> "application/json")

  def glVerify(token: String): Future[String] =
    req(s"$GlBase/user", glHeaders(token)).map { case (j, _) => j("username").str }

  def glImport(
      glToken: String,
      ghToken: String,
      repoId: Long,
      targetNamespace: String,
      newName: String
  ): Future[ujson.Value] =
    val body = ujson.Obj(
      "personal_access_token" -> ghToken,
      "repo_id" -> repoId,
      "target_namespace" -> targetNamespace,
      "new_name" -> newName
    )
    req(
      s"$GlBase/import/github",
      glHeaders(glToken),
      method = "POST",
      body = Some(ujson.write(body))
    ).map { case (j, _) => j }

/** List all project paths under a namespace (user or group), paginated.
    * Tries `/users/:ns/projects` first; on 404 falls back to `/groups/:ns/projects`.
    * Returns the set of project `path` values (case-insensitive lower-cased).
    */
  def glListNamespaceProjects(token: String, namespace: String): Future[Set[String]] =
    val encoded = js.URIUtils.encodeURIComponent(namespace)
    def page(prefix: String, n: Int, acc: Set[String]): Future[Set[String]] =
      req(
        s"$GlBase/$prefix/$encoded/projects?per_page=100&page=$n&simple=true",
        glHeaders(token)
      ).flatMap { case (j, _) =>
        val batch = j.arr.toVector.flatMap(p => p.obj.get("path").map(_.str.toLowerCase))
        val merged = acc ++ batch
        if batch.size < 100 then Future.successful(merged)
        else page(prefix, n + 1, merged)
      }
    page("users", 1, Set.empty).recoverWith {
      case e: ApiError if e.status == 404 => page("groups", 1, Set.empty)
    }

  def glProjectStatus(glToken: String, projectId: Long): Future[(String, Option[String])] =
    req(s"$GlBase/projects/$projectId", glHeaders(glToken)).map { case (j, _) =>
      val st = j.obj.get("import_status").map(_.str).getOrElse("unknown")
      val err = j.obj.get("import_error").flatMap(v => if v.isNull then None else Some(v.str))
      (st, err)
    }
