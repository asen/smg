package com.smule.smg.auth

import com.smule.smg.auth.anonymous.AnonymousUsersProvider
import com.smule.smg.auth.system.SystemUsersProvider
import com.smule.smg.auth.userpass.PasswordUsersProvider
import com.smule.smg.config.{SMGConfigReloadListener, SMGConfigService, SMGUsersConfig}
import com.smule.smg.core.SMGLogger
import play.api.mvc.Request

import javax.inject.{Inject, Singleton}
import scala.collection.concurrent.TrieMap

@Singleton
class UsersService @Inject() (cfSvc: SMGConfigService) extends SMGConfigReloadListener{

  private val log = SMGLogger

  private var uconf: SMGUsersConfig = cfSvc.config.usersConfig

  private val anonymousUsersProvider = new AnonymousUsersProvider(cfSvc)
  private val systemUsersProvider = new SystemUsersProvider(cfSvc)
  private val passwordUsersProvider = new PasswordUsersProvider(cfSvc)
  private var pluginProviders = cfSvc.plugins.flatMap(_.usersProviders)

  private def reloadPluginProviders(): Unit = {
    pluginProviders = cfSvc.plugins.flatMap(_.usersProviders)
  }

  override def localOnly: Boolean = true
  override def reload(): Unit = {
    uconf = cfSvc.config.usersConfig
    reloadPluginProviders()
    anonymousUsersProvider.configReloaded()
    systemUsersProvider.configReloaded()
    passwordUsersProvider.configReloaded()
    pluginProviders.foreach(_.configReloaded())
  }
  cfSvc.registerReloadListener(this)


  private def myFromRequest[A](request: Request[A], includeSystem: Boolean): Option[User] = {
    val systemUsersSeq = if (includeSystem)
      Seq(systemUsersProvider.fromRequest(request)).flatten
    else
      Seq()
    val pluginUsers: Seq[User] = pluginProviders.flatMap(p => p.fromRequest(request))
    val passwordUserSeq = Seq(passwordUsersProvider.fromRequest(request)).flatten
    val anonymousUserSeq = Seq(anonymousUsersProvider.fromRequest(request)).flatten

    val allUsersByRole = (systemUsersSeq ++ pluginUsers ++ passwordUserSeq ++ anonymousUserSeq).groupBy(_.role)
    if (allUsersByRole.nonEmpty){
      val maxRole = allUsersByRole.keys.max
      val ret = allUsersByRole(maxRole).headOption
      log.debug(s"UsersService: authenticated request for user: ${ret.map(_.toString).getOrElse("None")}")
      ret
    } else None
  }

  def newPasswordUserSession(handle: String, password: String): Option[String] = {
    passwordUsersProvider.loginUserPass(handle, password)
  }

  def fromRequest[A](request: Request[A]): Option[User] = myFromRequest(request, includeSystem = false)

  def fromSystemRequest[A](request: Request[A]): Option[(User, Option[User])] = {
    val systemUserOpt = myFromRequest(request, includeSystem = true)
    systemUserOpt.map{ systemUser =>
      var onBehalfOfUser = request.headers.get(SMGUsersConfig.onBehalfOfHeader).flatMap { hdrVal =>
        RemoteUser.fromBase64(hdrVal)
      }
      if (onBehalfOfUser.isDefined && (onBehalfOfUser.get.role > systemUser.role)) {
        log.warn(s"Ignoring onBehalfOfUser (${onBehalfOfUser.get}) with role higher than system user ($systemUser)")
        onBehalfOfUser = None
      }
      (systemUser, onBehalfOfUser)
    }
  }

  def loginUrl: String = cfSvc.config.usersConfig.loginUrl
}
