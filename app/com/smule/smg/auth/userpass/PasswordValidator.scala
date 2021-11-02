package com.smule.smg.auth.userpass
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class PasswordValidator() {

  private def byteArray2HexStr(hash: Array[Byte]): String = {
    val hexString = new StringBuilder(capacity = hash.length * 2)
    for(b <- hash) {
      val hex = Integer.toHexString(0xff & b)
      if (hex.length == 1) hexString.append('0')
      hexString.append(hex)
    }
    hexString.toString
  }

  private def hashString(stringToHash: String, algo: String): String = algo match {
    case "plain" => stringToHash
    case algoName => {
      val digest = MessageDigest.getInstance(algoName.toUpperCase)
      val hash: Array[Byte] = digest.digest(stringToHash.getBytes(StandardCharsets.UTF_8))
      byteArray2HexStr(hash)
    }
  }

  def validatePassword(handle: String, password: String, storedHash: String): Boolean = {
    val stringToHash = s"${handle}:${password}"
    val arr = storedHash.split(":", 2)
    val algo = if (arr.length == 2) arr(0) else "SHA-256"
    val hashVal = if (arr.length == 2) arr(1) else storedHash
    hashVal == hashString(stringToHash, algo)
  }

}
