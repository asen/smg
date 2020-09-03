package com.smule.smgplugins.kubeConf

import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.Configuration
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodList
import io.kubernetes.client.util.Config
import java.io.IOException

import scala.collection.JavaConversions._

class SMGKubeClient() {

  private val client: ApiClient = Config.fromConfig(sys.env("HOME") + "/.kube/config")

  def listPods(): Seq[String] = {

    Configuration.setDefaultApiClient(client)

    val api = new CoreV1Api
    val list: V1PodList = api.listPodForAllNamespaces(null, null, null,
      null, null, null, null, null, null)
    list.getItems.map(_.getMetadata.getName)
  }
}
