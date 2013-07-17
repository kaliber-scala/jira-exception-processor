package fly.play.jiraExceptionProcessor

import play.api.mvc.RequestHeader
import java.io.StringWriter
import java.io.PrintWriter
import play.api.mvc.Session
import play.api.Play.current
import java.security.MessageDigest
import javax.mail.Message
import play.api.PlayException
import play.Logger
import play.api.libs.concurrent.Promise
import play.modules.mailer.PlayConfiguration
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.Await
import scala.concurrent.duration._
import play.modules.mailer.Mailer
import play.modules.mailer.Email
import play.modules.mailer.EmailAddress
import play.modules.mailer.Recipient
import scala.language.postfixOps

object JiraExceptionProcessor {

  def getStackTraceString(ex: Throwable): String = {
    val s = new StringWriter
    val p = new PrintWriter(s)
    ex.printStackTrace(p)
    s.toString
  }

  def getRequestString(request: RequestHeader): String = {

    "uri: " + request.uri + "\n" +
      "path: " + request.path + "\n" +
      "method: " + request.method + "\n" +
      "headers: \n" +
      request.headers.toMap.toList.map((keyValueSeq _).tupled).mkString("\n") + "\n" +
      "session: \n" +
      request.session.data.toList.map((keyValue _).tupled).mkString("\n") + "\n" +
      "flash: \n" +
      request.flash.data.toList.map((keyValue _).tupled).mkString("\n")

  }

  def keyValue(key: String, value: String): String = "   " + key + ": " + value
  def keyValueSeq(key: String, value: Seq[String]): String = keyValue(key, value.mkString(", "))

  def reportError(request: RequestHeader, ex: Throwable): Unit = {
    if (!PlayConfiguration("jira.exceptionProcessor.enabled").toBoolean) return

    val summary = ex.getMessage
    val description = getStackTraceString(ex)

    val result: Either[Error, Success] =
      try {
        val hash = createHash(removePlayId(description))
        val comment = getRequestString(request)

        val result =
          Jira.findIssues(hash)
            .flatMap {
              //we found an issue, add the comment
              case Right(Some(issue)) => Jira.addComment(issue.key.get, comment)
              //no issue found, create the issue
              case Right(_) =>
                (Jira createIssue PlayProjectIssue(None, Some(summary), Some(description), Some(hash)))
                  .flatMap {
                    //add the comment
                    case Right(playProjectIssue) => Jira.addComment(playProjectIssue.key.get, comment)
                    case Left(error) => Promise pure Left(error)
                  }
              case Left(error) => Promise pure Left(error)

            }

        Await.result(result, 10 seconds)
      } catch {
        case e: PlayException => throw e
        case e: Throwable => Left(Error(0, Seq(
          "Exception while calling Jira:",
          e.getMessage,
          getStackTraceString(e),
          "Original error:",
          summary,
          description)))
      }

    result match {
      case Left(error) => sendEmail(error)
      case Right(success) => /* error reported */
    }
  }

  def removePlayId(message:String) = 
    message.replaceFirst("""@[^\s]*""", "")
  
  def sendEmail(error: Error) = {
    val message = "Status: " + error.status + "\n" +
      error.messages.mkString("\n\n")

    Logger error "Failed to report to Jira " + message

    val fromName = PlayConfiguration("jira.exceptionProcessor.mail.from.name")
    val fromAddress = PlayConfiguration("jira.exceptionProcessor.mail.from.address")
    val toName = PlayConfiguration("jira.exceptionProcessor.mail.to.name")
    val toAddress = PlayConfiguration("jira.exceptionProcessor.mail.to.address")

    Mailer.sendEmail(Email(
      subject = "Failed to report error for project %s and component %s" format (Jira.projectKey, Jira.componentName),
      from = EmailAddress(fromName, fromAddress),
      replyTo = None,
      recipients = List(
        Recipient(Message.RecipientType.TO, EmailAddress(toName, toAddress))),
      text = message,
      htmlText = message.replace("\n", "<br />"),
      attachments = Seq.empty))
  }

  def createHash(str: String): String =
    MessageDigest.getInstance("MD5").digest(str.getBytes).map("%02X" format _).mkString
}

