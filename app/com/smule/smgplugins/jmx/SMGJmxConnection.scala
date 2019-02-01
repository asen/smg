package com.smule.smgplugins.jmx

import com.smule.smg.plugin.SMGPluginLogger
import javax.management.{MBeanServerConnection, ObjectName}
import javax.management.openmbean.CompositeData
import javax.management.remote.{JMXConnector, JMXConnectorFactory, JMXServiceURL}

import scala.util.Try
import scala.collection.JavaConversions._

/**
  * Created by asen on 4/12/17.
  */
class SMGJmxConnection(hostPort: String, log: SMGPluginLogger) {


  private var jmxConnection: Option[JMXConnector] = None

  private def getJmxUrlStr(hostPort: String) = "service:jmx:rmi:///jndi/rmi://%s/jmxrmi".format(hostPort)

  private val jmxUrlStr = getJmxUrlStr(hostPort)

  def getJmxMbeanConnection: MBeanServerConnection = {
    this.synchronized {
      if (jmxConnection.isEmpty) {
        val jmxUrl = new JMXServiceURL(jmxUrlStr)
        val jmxc = JMXConnectorFactory.connect(jmxUrl, null)
        jmxConnection = Some(jmxc)
        jmxc.getMBeanServerConnection
      } else jmxConnection.get.getMBeanServerConnection
    }
  }

  def closeJmxMbeanConnection(): Unit = {
    this.synchronized {
      if (jmxConnection.isDefined) {
        Try(jmxConnection.get.close())
      }
      jmxConnection = None
    }
  }

  /**
    * Return None if connection is good and Some(errorMsg) otherwise
    * @return
    */
  def checkJmxMbeanConnection: Option[String] = {
    try {
      val c = getJmxMbeanConnection
      c.getMBeanCount >= 0
      None
    } catch {
      case x: Throwable => {
        closeJmxMbeanConnection()
        log.ex(x, s"checkJmxMbeanConnection: error checking $hostPort")
        Some(s"checkJmxMbeanConnection: $hostPort: " + x.getMessage)
      }
    }
  }

  def fetchJmxValues(objName: String, attrNames: List[String]): List[Double] = {
    try {
      realFetchJmxValues(objName, attrNames)
    } catch {
      case ex: Throwable => {
        closeJmxMbeanConnection()
        throw ex
      }
    }
  }

  private def realFetchJmxValues(objName: String, attrNames: List[String]): List[Double] = {
    val connection = getJmxMbeanConnection
    val on = new ObjectName(objName)
    val attrs = connection.synchronized {
      connection.getAttributes(on, attrNames.map(_.split(":")(0)).toArray)
    }
    val ret = attrs.asList.zip(attrNames).map { t =>
      val at = t._1
      val an = t._2.split(":")
      if (an.size == 2) {
        at.getValue.asInstanceOf[CompositeData].get(an(1)).toString.toDouble
      } else
        at.getValue.toString.toDouble
    }
    // TODO check ret size vs attrNames.size ?
    ret.toList
  }
}

object SMGJmxConnection {

  import java.net.InetSocketAddress
  import java.net.ServerSocket
  import java.net.Socket
  import java.rmi.server.RMISocketFactory


  val DEFAULT_RMI_SOCKET_TIMEOUT_MS = 5000

  val log = new SMGPluginLogger("jmx")  // XXX don't have access to the plugin id here

  class SMGJmxRMISocketFactory extends RMISocketFactory {

    var timeoutMillis = DEFAULT_RMI_SOCKET_TIMEOUT_MS

    override def createSocket(host: String, port: Int): Socket = {
      log.debug(s"Creating RMI socket to $host:$port with timeout $timeoutMillis")
      val socket = new Socket
      socket.setSoTimeout(timeoutMillis)
      socket.setSoLinger(false, 0)
      socket.connect(new InetSocketAddress(host, port), timeoutMillis)
      socket
    }

    override def createServerSocket(port: Int) = new ServerSocket(port)
  }

  // For how long to keep unused RMI connections.

  // The default of 90 seconds is tuned for every-minute updates to
  // avoid establishing new connection every minute.
  private val CONNECTION_UNUSED_TIMEOUT_MS = 90000
  // Only do this if not already set in the environment
  private val envPropConnectionTimeout =
    System.getProperties.getProperty("sun.rmi.transport.connectionTimeout", "INVALID")
  if (envPropConnectionTimeout == "INVALID") {
    log.info(s"Setting sun.rmi.transport.connectionTimeout to $CONNECTION_UNUSED_TIMEOUT_MS")
    System.getProperties.setProperty("sun.rmi.transport.connectionTimeout", CONNECTION_UNUSED_TIMEOUT_MS.toString)
  } else {
    log.info(s"Not setting sun.rmi.transport.connectionTimeout because already set to $envPropConnectionTimeout")
  }

  RMISocketFactory.setSocketFactory(new SMGJmxRMISocketFactory)

  def setRMITimeout(timeoutMillis : Int): Unit = {
    RMISocketFactory.getSocketFactory.asInstanceOf[SMGJmxRMISocketFactory].timeoutMillis = timeoutMillis
  }
}
