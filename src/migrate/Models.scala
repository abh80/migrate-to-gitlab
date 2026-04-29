package migrate

enum Stage:
  case LinkGithub
  case LinkGitlab
  case LoadingRepos
  case Selecting
  case Importing
  case Done

case class GhRepo(
    id: Long,
    name: String,
    fullName: String,
    isPrivate: Boolean,
    description: Option[String],
    stars: Int,
    archived: Boolean,
    updatedAt: String
)

enum ImportPhase:
  case Pending
  case Started
  case Finished
  case Failed(reason: String)

case class ImportJob(repo: GhRepo, phase: ImportPhase, gitlabPath: Option[String])
