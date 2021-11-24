package com.smule.smgplugins.auth

import com.smule.smg.auth.{User, UsersProvider}
import com.smule.smg.config.SMGConfigService
import com.smule.smg.plugin.{SMGPlugin, SMGPluginLogger}
import com.smule.smgplugins.auth.trustedheader.TrustedHeaderUsersProvider

class SMGAuthPlugin(val pluginId: String,
                    val interval: Int,
                    val pluginConfFile: String,
                    val smgConfSvc: SMGConfigService) extends SMGPlugin {

  private val log = new SMGPluginLogger(pluginId)
  private val confParser = new SMGAuthPluginConfParser(pluginId, smgConfSvc, log)

  private val trustedHeaderUsersProvider = new TrustedHeaderUsersProvider(smgConfSvc, confParser)
  override def roleAccess: User.Role.Value = confParser.conf.pluginRoleAccess

  override def onConfigReloaded(): Unit = {
    confParser.reload()
  }

  override def usersProviders: Seq[UsersProvider] = if (confParser.conf.thConf.enabled)
    Seq(trustedHeaderUsersProvider)
  else
    Seq()

  override def htmlContent(httpParams: Map[String, String]): String = {
    <p>Auth plugin conf</p>
    <p>pluginRoleAccess={roleAccess}</p>
    <div>
      <p>Trusted header conf:</p>
      <ul>
        <li>enabled: {confParser.conf.thConf.enabled}</li>
        <li>handleHeaderName: {confParser.conf.thConf.handleHeaderName}</li>
        <li>rolesHeaderName: {confParser.conf.thConf.rolesHeaderName.getOrElse("None")}</li>
        <li>nameHeaderName: {confParser.conf.thConf.nameHeaderName.getOrElse("None")}</li>
        <li>defaultRole: {confParser.conf.thConf.defaultRole.toString}</li>
      </ul>
    </div>
  }.mkString
}
