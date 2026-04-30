package migrate

import com.raquo.laminar.api.L.*

object State:
  val stage: Var[Stage] = Var(Stage.LinkGithub)

  val ghToken: Var[String] = Var("")
  val ghUser: Var[Option[String]] = Var(None)
  val ghError: Var[Option[String]] = Var(None)
  val ghVerifying: Var[Boolean] = Var(false)

  val glToken: Var[String] = Var("")
  val glUser: Var[Option[String]] = Var(None)
  val glNamespace: Var[String] = Var("")
  val glError: Var[Option[String]] = Var(None)
  val glVerifying: Var[Boolean] = Var(false)

  val repos: Var[Vector[GhRepo]] = Var(Vector.empty)
  val reposLoadProgress: Var[Int] = Var(0) // pages loaded
  val reposLoadError: Var[Option[String]] = Var(None)
  val reposCapped: Var[Boolean] = Var(false)

  val filter: Var[String] = Var("")
  val pageIndex: Var[Int] = Var(0) // 0-based, displayed page
  val pageSize: Int = 100

  val selected: Var[Set[Long]] = Var(Set.empty)
  val lastClickedIndex: Var[Option[Int]] = Var(None)

  val jobs: Var[Map[Long, ImportJob]] = Var(Map.empty)

  val existing: Var[Set[Long]] = Var(Set.empty)
  val collisionsChecked: Var[Int] = Var(0)
  val collisionsTotal: Var[Int] = Var(0)

  val storageKey: String = "user-key"

  val saveTokens: Var[Boolean] = Var(false)

  val unlockNeeded: Var[Boolean] = Var(false)
  val unlockPassword: Var[String] = Var("")
  val unlockError: Var[Option[String]] = Var(None)
  val unlockBusy: Var[Boolean] = Var(false)

  val saveDialogOpen: Var[Boolean] = Var(false)
  val savePassword: Var[String] = Var("")
  val savePasswordConfirm: Var[String] = Var("")
  val saveError: Var[Option[String]] = Var(None)
  val saveBusy: Var[Boolean] = Var(false)
