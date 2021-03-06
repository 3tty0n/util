package com.twitter.util.security

import com.twitter.logging.Logger
import com.twitter.util.{Return, Throw, Try}
import com.twitter.util.security.Pkcs8KeyManagerFactory._
import java.io.File
import java.security.{KeyFactory, KeyStore, PrivateKey}
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.UUID
import javax.net.ssl.{KeyManager, KeyManagerFactory}

/**
 * A factory which can create a [[javax.net.ssl.KeyManager KeyManager]] which contains
 * an X.509 Certificate and a PKCS#8 private key.
 */
class Pkcs8KeyManagerFactory(certFile: File, keyFile: File) {

  private[this] def logException(ex: Throwable): Unit =
    log.warning(
      s"Pkcs8KeyManagerFactory (${certFile.getName()}, ${keyFile.getName()}) " +
        s"failed to create key manager: ${ex.getMessage()}."
    )

  private[this] def keySpecToPrivateKey(keySpec: PKCS8EncodedKeySpec): PrivateKey = {
    val kf: KeyFactory = KeyFactory.getInstance("RSA")
    kf.generatePrivate(keySpec)
  }

  private[this] def createKeyStore(cert: X509Certificate, privateKey: PrivateKey): KeyStore = {
    val alias: String = UUID.randomUUID().toString()
    val ks: KeyStore = KeyStore.getInstance("JKS")
    ks.load(null)
    ks.setKeyEntry(alias, privateKey, "".toCharArray(), Array(cert))
    ks
  }

  private[this] def keyStoreToKeyManagers(keyStore: KeyStore): Array[KeyManager] = {
    val kmf = KeyManagerFactory.getInstance("SunX509")
    kmf.init(keyStore, "".toCharArray())
    kmf.getKeyManagers()
  }

  /**
   * Attempts to read the contents of both the X.509 Certificate file and the PKCS#8
   * Private Key file and combine the contents into a [[javax.net.ssl.KeyManager KeyManager]].
   * The singular value is returned in an Array for ease of use with
   * [[javax.net.ssl.SSLContext SSLContext's]] init method.
   */
  def getKeyManagers(): Try[Array[KeyManager]] = {
    val tryCert: Try[X509Certificate] = new X509CertificateFile(certFile).readX509Certificate()
    val tryKeySpec: Try[PKCS8EncodedKeySpec] =
      new Pkcs8EncodedKeySpecFile(keyFile).readPkcs8EncodedKeySpec()
    val tryPrivateKey: Try[PrivateKey] = tryKeySpec.map(keySpecToPrivateKey)

    val tryCertKey: Try[(X509Certificate, PrivateKey)] = join(tryCert, tryPrivateKey)
    val tryKeyStore: Try[KeyStore] = tryCertKey.map((createKeyStore _).tupled)
    tryKeyStore.map(keyStoreToKeyManagers).onFailure(logException)
  }

}

private object Pkcs8KeyManagerFactory {
  private val log = Logger.get("com.twitter.util.security")

  private def join[A, B](tryA: Try[A], tryB: Try[B]): Try[(A, B)] = {
    (tryA, tryB) match {
      case (Return(aValue), Return(bValue)) => Return((aValue, bValue))
      case (Throw(_), _) => tryA.asInstanceOf[Try[(A, B)]]
      case (_, Throw(_)) => tryB.asInstanceOf[Try[(A, B)]]
    }
  }
}
