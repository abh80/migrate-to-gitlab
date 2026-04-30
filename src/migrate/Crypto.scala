package migrate

import org.scalajs.dom
import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.Thenable.Implicits.given
import scala.scalajs.js.typedarray.{ArrayBuffer, Uint8Array}

object Crypto:
  given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue

  private def subtle: js.Dynamic = js.Dynamic.global.crypto.subtle
  private def encode(s: String): Uint8Array =
    js.Dynamic.newInstance(js.Dynamic.global.TextEncoder)().encode(s).asInstanceOf[Uint8Array]
  private def decode(buf: ArrayBuffer): String =
    js.Dynamic.newInstance(js.Dynamic.global.TextDecoder)().decode(buf).asInstanceOf[String]

  private def randomBytes(n: Int): Uint8Array =
    val arr = new Uint8Array(n)
    js.Dynamic.global.crypto.getRandomValues(arr)
    arr

  private def b64encode(bytes: Uint8Array): String =
    val sb = new StringBuilder
    var i = 0
    while i < bytes.length do
      sb.append(bytes(i).toChar)
      i += 1
    js.Dynamic.global.btoa(sb.toString).asInstanceOf[String]

  private def b64decode(b64: String): Uint8Array =
    val raw = js.Dynamic.global.atob(b64).asInstanceOf[String]
    val out = new Uint8Array(raw.length)
    var i = 0
    while i < raw.length do
      out(i) = raw.charAt(i).toShort
      i += 1
    out

  private def deriveKey(password: String, salt: Uint8Array): Future[js.Any] =
    val passKeyP = subtle
      .importKey(
        "raw",
        encode(password),
        js.Dynamic.literal(name = "PBKDF2"),
        false,
        js.Array("deriveKey")
      )
      .asInstanceOf[js.Promise[js.Any]]
    passKeyP.toFuture.flatMap { pk =>
      val params = js.Dynamic.literal(
        name = "PBKDF2",
        salt = salt,
        iterations = 200000,
        hash = "SHA-256"
      )
      val alg = js.Dynamic.literal(name = "AES-GCM", length = 256)
      subtle
        .deriveKey(params, pk, alg, false, js.Array("encrypt", "decrypt"))
        .asInstanceOf[js.Promise[js.Any]]
        .toFuture
    }

  def encrypt(password: String, plaintext: String): Future[String] =
    val salt = randomBytes(16)
    val iv = randomBytes(12)
    deriveKey(password, salt).flatMap { aesKey =>
      val params = js.Dynamic.literal(name = "AES-GCM", iv = iv)
      subtle
        .encrypt(params, aesKey, encode(plaintext))
        .asInstanceOf[js.Promise[ArrayBuffer]]
        .toFuture
        .map { ctBuf =>
          val obj = js.Dynamic.literal(
            v = 1,
            salt = b64encode(salt),
            iv = b64encode(iv),
            ct = b64encode(new Uint8Array(ctBuf))
          )
          js.JSON.stringify(obj)
        }
    }

  def decrypt(password: String, blob: String): Future[String] =
    val parsed = js.JSON.parse(blob)
    val salt = b64decode(parsed.salt.asInstanceOf[String])
    val iv = b64decode(parsed.iv.asInstanceOf[String])
    val ct = b64decode(parsed.ct.asInstanceOf[String])
    deriveKey(password, salt).flatMap { aesKey =>
      val params = js.Dynamic.literal(name = "AES-GCM", iv = iv)
      subtle
        .decrypt(params, aesKey, ct)
        .asInstanceOf[js.Promise[ArrayBuffer]]
        .toFuture
        .map(decode)
    }
