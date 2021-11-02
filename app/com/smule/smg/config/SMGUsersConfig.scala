package com.smule.smg.config

import com.smule.smg.auth.User
import com.smule.smg.auth.system.IpNetwork
import com.smule.smg.auth.userpass.PasswordUser
import com.smule.smg.core.SMGLogger
import com.smule.smg.rrd.SMGRrd

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.Try

case class SMGUsersConfig(
                           anonymousRoot: Boolean,
                           anonymousAdmin: Boolean,
                           anonymousViewer: Boolean,
                           passwordUsers: Seq[PasswordUser],
                           defaultSessionTtl: String,
                           systemAllowLocalhost: Boolean,
                           systemXffHeader: Option[String],
                           systemAuthHeader: Option[String],
                           systemAllowedNetworks: Seq[String],
                           systemSecretRootTokens: Set[String],
                           systemLocalhostAddresses: Seq[String],
                           loginUrl: String,
                           logoutUrl: String
                         ) {
//  val systemUsersConfig: SystemUsersConfig = SystemUsersConfig()
  val passwordUserCookieKey: String = "smg_password_user_session"

  val sessionTtlMs: Long = SMGRrd.parsePeriod(defaultSessionTtl).map(_.toLong * 1000L).
    getOrElse(SMGUsersConfig.defaultSessionTtl)

  val localhostAddresses: Seq[IpNetwork] = (SMGUsersConfig.defaultLocalhostAddresses ++
    systemLocalhostAddresses).flatMap { s =>
    Try(IpNetwork(s)).toOption
  }

  val systemAllowedNetworkRanges: Seq[IpNetwork] = systemAllowedNetworks.flatMap { cidr =>
    Try(IpNetwork(cidr)).toOption
  }
  private lazy val systemAllowedNetworkRangesHumanDesc =
    systemAllowedNetworkRanges.map(_.cidr).mkString(",")

  val passwordUsersMap: Map[String, PasswordUser] = passwordUsers.groupBy(_.handle).map(t => (t._1, t._2.head))

  lazy val humanDesc: String = {
    val anonLevel = if (anonymousRoot)
      "root"
    else if (anonymousAdmin)
      "admin"
    else if (anonymousViewer)
      "viewer"
    else
      "none"
    s"anonymous=$anonLevel passwordUsers=${passwordUsers.size} " +
      s"systemTokens=${systemSecretRootTokens.size} allowedNetworks=${systemAllowedNetworkRangesHumanDesc}"
  }
}


object SMGUsersConfig {
  private val log = SMGLogger

  val defaultSessionTtlStr: String = "24h"
  val defaultSessionTtl: Long = SMGRrd.parsePeriod(defaultSessionTtlStr).get.toLong * 1000L
  val defaultLoginUrl: String = "/login"
  val defaultLogoutUrl: String = "/logout"
  val defaultHffHeader: String = "X-Forwarded-For"
  val defaultAuthorizationHeader: String = "Authorization"
  val defaultLocalhostAddresses: Seq[String] = Seq("127.0.0.1", "::1")

  val onBehalfOfHeader: String = "X-SMG-On-Behalf-Of"

  // XXX backwaards compatible - alllow anonymous admin, may change in the future
  val default: SMGUsersConfig =
    SMGUsersConfig(
      anonymousRoot = false,
      anonymousAdmin = true,
      anonymousViewer = true,
      passwordUsers = Seq(),
      defaultSessionTtl = defaultSessionTtlStr,
      systemAllowLocalhost = true,
      systemXffHeader = None,
      systemAuthHeader = None,
      systemAllowedNetworks = Seq(),
      systemSecretRootTokens = Set(),
      systemLocalhostAddresses = Seq(),
      loginUrl = defaultLoginUrl,
      logoutUrl = defaultLogoutUrl
    )


  private def getPasswordUsers(authUsersPass: Seq[AuthUserConfig], errs: ListBuffer[String]): Seq[PasswordUser] = {
    val knownHandles = mutable.Set[String]()
    authUsersPass.flatMap { authU =>
      if (authU.props.contains("handle") || authU.props.contains("password_hash")) {
        val handle = authU.props("handle").toString
        if (knownHandles.contains(handle)) {
          val msg = s"SMGUsersConfig: duplicate user definition: $handle"
          log.error(msg)
          errs += msg
          None
        } else {
          knownHandles += handle
          Some(PasswordUser(
            handle = handle,
            name = authU.props.get("name").map(_.toString),
            role = authU.props.get("role").map { o =>
              val rn = o.toString.toUpperCase
              User.roleFromString(rn)
            }.getOrElse(User.Role.ADMIN),
            passwordHash = authU.props("password_hash").toString
            //, customSessionTtl
          ))
        }
      } else {
        val msg = s"SMGUsersConfig: Invalid auth-user-password spec - handle and password_hash are required: ${authU.props}"
        log.error(msg)
        errs += msg
        None
      }
    }
  }

  def fromConfigGlobalsAndAUthUsers(globals: Map[String,String],
                                    authUsersByType: Map[String, Seq[AuthUserConfig]]): (SMGUsersConfig, Seq[String]) = {

    try {
      def myGetOrDefault(key: String, v: Any): String = {
        globals.getOrElse(key, v.toString)
      }
      val errs = ListBuffer[String]()
      val passwordUsers = getPasswordUsers(authUsersByType.getOrElse("$auth-user-password", Seq()), errs)
      val systemRootTokens = authUsersByType.getOrElse("$auth-user-token", Seq()).flatMap { authU =>
        if (authU.props.contains("token")) {
          Some(authU.props("token").toString)
        } else {
          val msg = s"SMGUsersConfig: Invalid auth-user-token spec - missing token: ${authU.props}"
          log.error(msg)
          errs += msg
          None
        }
      }
      val ret = SMGUsersConfig(
        anonymousRoot = myGetOrDefault("$auth-anonymous-root", default.anonymousRoot) == "true",
        anonymousAdmin = myGetOrDefault("$auth-anonymous-admin", default.anonymousAdmin) == "true",
        anonymousViewer = myGetOrDefault("$auth-anonymous-viewer", default.anonymousViewer) == "true",
        passwordUsers = passwordUsers,
        defaultSessionTtl = myGetOrDefault("$auth-default-session-ttl", default.defaultSessionTtl) ,
        systemAllowLocalhost = myGetOrDefault("$auth-system-allow-localhost",
          default.systemAllowLocalhost) == "true",
        systemXffHeader = globals.get("$auth-system-xff-header"),
        systemAuthHeader = globals.get("$auth-system-authorization-header"),
        systemAllowedNetworks = globals.get("$auth-system-allowed-networks").map { s =>
          s.split("\\s+").filter(_.nonEmpty).toSeq
        }.getOrElse(Seq()),
        systemSecretRootTokens = systemRootTokens.toSet,
        systemLocalhostAddresses = globals.get("$auth-system-localhost-addresses").map { s =>
          s.split("\\s+").filter(_.nonEmpty).toSeq
        }.getOrElse(Seq()),
        loginUrl = myGetOrDefault("$auth-system-login-url", default.loginUrl),
        logoutUrl = myGetOrDefault("$auth-system-logout-url", default.logoutUrl)
      )
      (ret, errs.toList)
    } catch { case t: Throwable =>
      val msg = s"Unexpected error parsing users config - using defaults: ${t.getMessage}"
      log.ex(t, msg)
      (default, Seq(msg))
    }
  }
}
